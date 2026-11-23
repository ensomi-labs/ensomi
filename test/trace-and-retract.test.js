import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeSemanticKey,
  createCheckpoint,
  createInitialState,
  foldTrace,
  parseTraceNdjson,
  resolveActiveRetractions,
  serializeState,
  serializeTraceNdjson,
  verifyCheckpoint,
} from "../index.js";

function ev(seq, type, payload, author = { kind: "user" }, id = `${type}_${seq}`) {
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq,
    id,
    type,
    tsMs: 1_700_000_000_000 + seq * 100,
    author,
    payload,
  };
}

describe("Trace fold, retraction, NDJSON, and checkpoints", () => {
  it("enforces selective undo without inverse-delta clobbering (A: 1->2, B: 2->3, retract A => 3) and redo", () => {
    const events = [
      ev(0, "draft_loaded", {
        beats: [{ id: "b1", t: 1.0, source: "model", version: 1 }],
      }, { kind: "model" }),
      ev(1, "edit", { op: "move_beat", id: "b1", t: 2.0, source: "user" }), // Edit A: 1 -> 2
      ev(2, "edit", { op: "move_beat", id: "b1", t: 3.0, source: "user" }), // Edit B: 2 -> 3
      ev(3, "retract", { targetEventId: "edit_1", reason: "undo A" }),       // Retract A
    ];

    const stateAfterUndoA = foldTrace(events);
    // B (2->3) must survive cleanly without restoring stale 1.0!
    assert.equal(stateAfterUndoA.beats.get("b1")?.t, 3.0);
    assert.equal(stateAfterUndoA.beats.get("b1")?.version, 2); // draft v1 + B v2
    assert.ok(stateAfterUndoA.retractedEventIds.has("edit_1"));

    // Incremental reduction must produce identical state to full fold
    const incremental = events.reduce(applyEvent, createInitialState());
    assert.deepEqual(serializeState(incremental), serializeState(stateAfterUndoA));

    // Redo A by retracting the retraction (retract edit_3)
    const redoEvent = ev(4, "retract", { targetEventId: "retract_3", reason: "redo A" });
    const stateAfterRedo = applyEvent(incremental, redoEvent);
    const fullAfterRedo = foldTrace([...events, redoEvent]);

    assert.equal(stateAfterRedo.beats.get("b1")?.t, 3.0);
    assert.equal(stateAfterRedo.beats.get("b1")?.version, 3); // draft v1 + A v2 + B v3
    assert.ok(!stateAfterRedo.retractedEventIds.has("edit_1"));
    assert.ok(stateAfterRedo.retractedEventIds.has("retract_3"));
    assert.deepEqual(serializeState(stateAfterRedo), serializeState(fullAfterRedo));

    // Now retract B instead -> b1 should fall back to A's 2.0!
    const undoB = ev(5, "retract", { targetEventId: "edit_2", reason: "undo B" });
    const stateAfterUndoB = applyEvent(stateAfterRedo, undoB);
    assert.equal(stateAfterUndoB.beats.get("b1")?.t, 2.0);
    assert.equal(stateAfterUndoB.beats.get("b1")?.version, 2);

    // Undo A again (retracting redoEvent at seq 4) -> b1 should fall back to draft 1.0!
    const undoAAgain = ev(6, "retract", { targetSeq: 4, reason: "undo redo of A" });
    const stateBackToDraft = applyEvent(stateAfterUndoB, undoAAgain);
    assert.equal(stateBackToDraft.beats.get("b1")?.t, 1.0);
    assert.equal(stateBackToDraft.beats.get("b1")?.version, 1);

    const activeRetractions = resolveActiveRetractions([...events, redoEvent, undoB, undoAAgain]);
    assert.ok(activeRetractions.has("edit_1"));
    assert.ok(activeRetractions.has("edit_2"));
    assert.ok(activeRetractions.has("retract_4"));
    assert.ok(!activeRetractions.has("retract_3"));
  });

  it("rejects forward-referencing and self-referencing retractions", () => {
    const base = [
      ev(0, "draft_loaded", { beats: [{ id: "b1", t: 1.0, source: "model" }] }, { kind: "model" }),
    ];

    assert.throws(
      () => foldTrace([...base, ev(1, "retract", { targetEventId: "retract_1" })]),
      /strictly earlier/,
    );
    assert.throws(
      () =>
        foldTrace([
          ...base,
          ev(1, "retract", { targetSeq: 2 }),
          ev(2, "edit", { op: "move_beat", id: "b1", t: 2.0 }),
        ]),
      /strictly earlier/,
    );
  });

  it("maintains strict incremental vs. full-replay equivalence across complex traces", () => {
    const writeScope = { t0: 4.0, t1: 6.0 };
    const offerOps = {
      upsertBeats: [
        { id: "offer_b1", t: 4.5, source: "model", conf: 0.9 },
        { id: "offer_b2", t: 5.5, source: "model", conf: 0.9 },
      ],
      expectedRangeEntityIds: [],
    };
    const offer = {
      id: "off_1",
      batchId: "batch_1",
      optionIndex: 0,
      providerId: "mock.v0",
      providerKind: "mock",
      generation: 1,
      basisSeq: 4,
      role: "extend",
      scopeKind: "local",
      readScope: writeScope,
      writeScope,
      ops: offerOps,
      summary: "Add beats in [4, 6)",
      consequences: { beatsAdded: 2, beatsMoved: 0, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: computeSemanticKey(writeScope, offerOps),
      status: "pending",
    };

    const trace = [
      ev(0, "audio_bound", { audioId: "synth_01", durationSec: 30, sampleRate: 44100, clock: "source-audio-sec" }, { kind: "host" }),
      ev(1, "draft_loaded", {
        beats: [
          { id: "b0", t: 0.5, source: "model", conf: 0.95 },
          { id: "b1", t: 1.0, source: "model", conf: 0.95 },
          { id: "b2", t: 1.5, source: "model", conf: 0.5 },
        ],
        boundaries: [{ id: "bnd0", t: 0.5, kind: "steady_start", source: "model", bpm: 120 }],
        sounds: [{ id: "s0", t: 0.5, emphasis: 1.4, source: "model" }], // clamped to 1.0
      }, { kind: "model" }),
      ev(2, "edit", {
        ops: [
          { op: "shift_run_offset", span: { t0: 0.4, t1: 1.6 }, deltaSec: 0.05, source: "user" },
          { op: "upsert_sound", sound: { id: "s1", t: 1.25, emphasis: -0.2, source: "user" } }, // clamped to 0
        ],
      }),
      ev(3, "statement", {
        action: "upsert",
        statement: { id: "st_keep", kind: "keep", span: { t0: 0, t1: 2.0 } },
      }),
      ev(4, "signal", { scope: writeScope, reason: "local_extend", generation: 1 }),
      ev(5, "offer_batch", { batchId: "batch_1", offers: [offer], waitSec: 0.12 }, { kind: "model" }),
      ev(6, "offer_accept", { offerId: "off_1" }),
      ev(7, "listen_effort", { listenSec: 3.5, span: { t0: 0, t1: 4 } }, { kind: "host" }),
      ev(8, "edit", { op: "remove_beat", id: "b2" }),
      ev(9, "retract", { targetEventId: "edit_8", reason: "restore b2" }),
      ev(10, "ready", { ready: true, notes: "checked" }),
    ];

    let incremental = createInitialState();
    for (let i = 0; i < trace.length; i += 1) {
      incremental = applyEvent(incremental, trace[i]);
      const fullPrefix = foldTrace(trace.slice(0, i + 1));
      assert.deepEqual(
        serializeState(incremental),
        serializeState(fullPrefix),
        `Mismatch at prefix index ${i} (${trace[i].type})`,
      );
    }

    // Verify emphasis clamping and sound independence
    assert.equal(incremental.sounds.get("s0")?.emphasis, 1);
    assert.equal(incremental.sounds.get("s1")?.emphasis, 0);
    assert.ok(incremental.beats.has("b2"));
    assert.ok(incremental.beats.has("offer_b1"));
    assert.equal(incremental.ready, true);
    assert.equal(incremental.effort.listenSec, 3.5);
    assert.equal(incremental.effort.waitSec, 0.12);

    // Checkpoint round-trip & tampering detection
    const cp = createCheckpoint(incremental);
    const verified = verifyCheckpoint(cp, trace);
    assert.equal(verified.valid, true);

    const tampered = { ...cp, stateHash: "sha256:0000" };
    const failed = verifyCheckpoint(tampered, trace);
    assert.equal(failed.valid, false);
  });

  it("handles NDJSON serialization, crash-safe truncated tail recovery, and throws on interior corruption", () => {
    const events = [
      ev(0, "audio_bound", { audioId: "a1", durationSec: 10, clock: "source-audio-sec" }, { kind: "host" }),
      ev(1, "edit", { op: "upsert_beat", beat: { id: "b1", t: 0.5, source: "user" } }),
      ev(2, "edit", { op: "move_beat", id: "b1", t: 0.75 }),
    ];

    const ndjson = serializeTraceNdjson(events);
    const roundTripped = parseTraceNdjson(ndjson);
    assert.deepEqual(roundTripped, events);

    // Simulate crash leaving an incomplete trailing line
    const truncated = `${ndjson}{"schemaVersion":"0.1","seq":3,"id":"edit_3","ty`;

    // Strict mode must throw on truncated tail
    assert.throws(() => parseTraceNdjson(truncated), /Corrupt NDJSON at line 4/);

    // Recovery mode discards ONLY the incomplete trailing line
    const recovered = parseTraceNdjson(truncated, { discardTruncatedTail: true });
    assert.deepEqual(recovered, events);

    // Interior corruption MUST throw even when discardTruncatedTail is enabled
    const lines = ndjson.trimEnd().split("\n");
    const interiorCorrupt = [lines[0], '{"broken": json', lines[2]].join("\n") + "\n";
    assert.throws(
      () => parseTraceNdjson(interiorCorrupt, { discardTruncatedTail: true }),
      /Corrupt NDJSON at line 2/,
    );
  });
});
