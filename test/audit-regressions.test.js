import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  EnsomiError,
  ScriptedMockProvider,
  SessionController,
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  computeSemanticKey,
  createCheckpoint,
  createInitialState,
  foldTrace,
  generateSyntheticDraft,
  getRangeEntityIds,
  isOfferSuppressed,
  parseTraceNdjson,
  projectRedLines,
  rankTroubleSpans,
  serializeState,
  validatePatchOffer,
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

/**
 * Reconstruct the beat grid (in seconds) implied by .osu timing points up to `maxSec`.
 * Each timing point generates beats from its offsetMs every beatLengthMs until the next timing point's offsetMs.
 */
function reconstructGridFromRedLines(redLines, maxSec) {
  const epsMs = 1e-6;
  const maxMs = maxSec * 1000;
  /** @type {number[]} */
  const beatsSec = [];
  for (let i = 0; i < redLines.length; i += 1) {
    const rl = redLines[i];
    const stopMs = i + 1 < redLines.length ? redLines[i + 1].offsetMs - epsMs : maxMs + epsMs;
    for (let k = 0; ; k += 1) {
      const tMs = rl.offsetMs + k * rl.beatLengthMs;
      if (tMs > stopMs) break;
      beatsSec.push(Math.round(tMs) / 1000);
    }
  }
  return beatsSec;
}

describe("Audit regression suite (findings #4–#13, #22–#25)", () => {
  it("#4: reopening session with fresh provider preserves earlier accepted beats and rejects duplicate offer IDs & unguarded upserts", async () => {
    const provider1 = new ScriptedMockProvider();
    const ctrl1 = new SessionController({ provider: provider1 });
    await ctrl1.bindAudio({ audioId: "a4", durationSec: 10, clock: "source-audio-sec" });
    ctrl1.loadDraft({ beats: [], boundaries: [], sounds: [] });

    const r1 = await ctrl1.triggerRecompute({ scope: { t0: 0, t1: 1 }, reason: "local_rewrite" });
    assert.equal(r1.status, "applied");
    const acc1 = ctrl1.acceptOffer(r1.offers[0].id);
    assert.equal(acc1.ok, true);

    const beatsAfterFirst = Array.from(ctrl1.getState().beats.values())
      .map((b) => b.t)
      .sort((a, b) => a - b);
    assert.deepEqual(beatsAfterFirst, [0.25, 0.75]);

    // Reopen session from trace with a brand-new provider instance
    const savedTrace = parseTraceNdjson(ctrl1.exportNdjson());
    const provider2 = new ScriptedMockProvider();
    const ctrl2 = new SessionController({ provider: provider2, initialEvents: savedTrace });

    const r2 = await ctrl2.triggerRecompute({ scope: { t0: 4, t1: 5 }, reason: "local_rewrite" });
    assert.equal(r2.status, "applied");
    assert.notEqual(r2.offers[0].id, r1.offers[0].id);
    const acc2 = ctrl2.acceptOffer(r2.offers[0].id);
    assert.equal(acc2.ok, true);

    const beatsAfterSecond = Array.from(ctrl2.getState().beats.values())
      .map((b) => b.t)
      .sort((a, b) => a - b);
    assert.deepEqual(beatsAfterSecond, [0.25, 0.75, 4.25, 4.75]);

    // Duplicate offer IDs in offer_batch MUST throw DUPLICATE_OFFER_ID
    const dupOffer = {
      ...r1.offers[0],
      status: "pending",
    };
    assert.throws(
      () =>
        applyEvent(
          ctrl2.getState(),
          ev(ctrl2.getState().lastSeq + 1, "offer_batch", {
            batchId: "dup_batch",
            offers: [dupOffer],
          }, { kind: "model" }),
        ),
      (err) => err instanceof EnsomiError && err.code === "DUPLICATE_OFFER_ID",
    );

    // Upsert targeting an existing entity ID without expectedVersions / expectedRevisions MUST fail validation
    const existingBeatId = Array.from(ctrl2.getState().beats.keys())[0];
    const existingBeat = ctrl2.getState().beats.get(existingBeatId);
    const unguardedOffer = {
      id: "off_unguarded",
      batchId: "b_unguarded",
      providerId: "mock",
      providerKind: "mock",
      generation: 99,
      basisSeq: ctrl2.getState().lastSeq,
      role: "rewrite",
      scopeKind: "local",
      readScope: { t0: 0, t1: 1 },
      writeScope: { t0: 0, t1: 1 },
      ops: {
        upsertBeats: [{ id: existingBeatId, t: existingBeat.t + 0.05, source: "model" }],
      },
      summary: "Unguarded overwrite",
      consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: "sk:unguarded",
      status: "pending",
    };
    const conflicts = validatePatchOffer(ctrl2.getState(), unguardedOffer);
    assert.ok(conflicts.some((c) => c.kind === "version_mismatch" && c.entityIds?.includes(existingBeatId)));
  });

  it("#5: retracting an edit and making a new edit at the same version number cannot make an obsolete offer accept", async () => {
    // Part A: Full SessionController flow
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request }) => {
          const b = state.beats.get("b");
          return {
            batchId: "b_rev_test",
            offers: [
              {
                id: "off_rev_test",
                batchId: "b_rev_test",
                providerId: "mock.scripted.v0",
                providerKind: "mock",
                generation: request.generation,
                basisSeq: request.basisSeq,
                role: "rewrite",
                scopeKind: "local",
                readScope: { t0: 0, t1: 1 },
                writeScope: { t0: 0, t1: 1 },
                ops: {
                  upsertBeats: [{ id: "b", t: 0.25, source: "model" }],
                  expectedVersions: { b: b.version },
                  expectedRangeEntityIds: getRangeEntityIds(state, { t0: 0, t1: 1 }),
                },
                summary: "Nudge b from 0.2 to 0.25",
                consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
                conflicts: [],
                semanticKey: "sk:rev_test",
                status: "pending",
              },
            ],
          };
        },
      ],
    });
    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ audioId: "a5", durationSec: 4, clock: "source-audio-sec" });
    ctrl.loadDraft({ beats: [{ id: "b", t: 0.1, source: "model", version: 1 }] });

    // Move b 0.1 -> 0.2 (version 2)
    const move1 = ctrl.applyEdit({ op: "move_beat", id: "b", t: 0.2 });
    assert.equal(ctrl.getState().beats.get("b")?.version, 2);

    // Generate rewrite against version 2
    const rec = await ctrl.triggerRecompute({ scope: { t0: 0, t1: 1 }, reason: "local_rewrite" });
    assert.equal(rec.offers.length, 1);
    const offerId = rec.offers[0].id;
    assert.ok(rec.offers[0].ops.expectedRevisions?.b);

    // Retract the move, then move b to 0.99 (version 2 again, same range [0, 1)!)
    ctrl.retract(move1.event.id, "undo move1");
    ctrl.applyEdit({ op: "move_beat", id: "b", t: 0.99 });
    assert.equal(ctrl.getState().beats.get("b")?.version, 2);
    assert.equal(ctrl.getState().beats.get("b")?.t, 0.99);

    // Original offer MUST be stale and MUST NOT accept!
    assert.equal(ctrl.getState().offers.get(offerId)?.status, "stale");
    const acc = ctrl.acceptOffer(offerId);
    assert.equal(acc.ok, false);
    assert.ok(acc.conflicts.some((c) => c.kind === "version_mismatch"));
    assert.equal(ctrl.getState().beats.get("b")?.t, 0.99);

    // Part B: Raw trace reducer even WITHOUT expectedRevisions on the offer
    const rawTrace = [
      ev(0, "draft_loaded", { beats: [{ id: "b", t: 0.1, source: "model", version: 1 }] }, { kind: "model" }),
      ev(1, "edit", { op: "move_beat", id: "b", t: 0.2 }),
      ev(
        2,
        "offer_batch",
        {
          batchId: "raw_b",
          offers: [
            {
              id: "raw_off",
              batchId: "raw_b",
              providerId: "mock",
              providerKind: "mock",
              generation: 1,
              basisSeq: 1,
              role: "rewrite",
              scopeKind: "local",
              readScope: { t0: 0, t1: 1 },
              writeScope: { t0: 0, t1: 1 },
              ops: {
                upsertBeats: [{ id: "b", t: 0.25, source: "model" }],
                expectedVersions: { b: 2 },
                expectedRangeEntityIds: ["b"],
              },
              summary: "Raw offer without expectedRevisions",
              consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
              conflicts: [],
              semanticKey: "sk:raw",
              status: "pending",
            },
          ],
        },
        { kind: "model" },
      ),
      ev(3, "retract", { targetEventId: "edit_1" }),
      ev(4, "edit", { op: "move_beat", id: "b", t: 0.99 }),
      ev(5, "offer_accept", { offerId: "raw_off" }),
    ];

    const folded = foldTrace(rawTrace);
    const incremental = rawTrace.reduce(applyEvent, createInitialState());
    assert.deepEqual(serializeState(incremental), serializeState(folded));
    assert.equal(folded.beats.get("b")?.t, 0.99);
    assert.equal(folded.offers.get("raw_off")?.status, "stale");
    // Direct validatePatchOffer even if caller forces status:"pending" STILL fails via basisSeq > entity revSeq!
    const directConflicts = validatePatchOffer(folded, {
      ...folded.offers.get("raw_off"),
      status: "pending",
    });
    assert.ok(directConflicts.some((c) => c.kind === "version_mismatch" && c.entityIds?.includes("b")));
  });

  it("#6: mutating caller-owned payloads or returned state/trace cannot corrupt internal history", () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider });
    ctrl.loadDraft({ beats: [], boundaries: [], sounds: [] });

    const op = { op: "upsert_beat", beat: { id: "b1", t: 1.0, source: "user", meta: { tag: "orig" } } };
    ctrl.applyEdit(op);
    // Mutate caller's op AFTER applyEdit
    op.beat.t = 8.0;
    op.beat.meta.tag = "corrupted";

    const otherEdit = ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b2", t: 2.0, source: "user" } });

    assert.equal(ctrl.getState().beats.get("b1")?.t, 1.0);
    assert.equal(ctrl.getState().beats.get("b1")?.meta?.tag, "orig");

    // Mutate returned getTrace() and getState()
    const traceCopy = ctrl.getTrace();
    traceCopy[1].payload.beat.t = 99;
    const stateCopy = ctrl.getState();
    stateCopy.beats.get("b1").t = 77;

    // Re-folding controller.getTrace() MUST still say 1.0
    const refolded = foldTrace(ctrl.getTrace());
    assert.equal(refolded.beats.get("b1")?.t, 1.0);
    assert.equal(refolded.beats.get("b1")?.meta?.tag, "orig");

    // Retracting the unrelated edit MUST NOT import 8.0 or 99!
    ctrl.retract(otherEdit.event.id);
    assert.equal(ctrl.getState().beats.get("b1")?.t, 1.0);
    assert.equal(ctrl.getState().beats.get("b1")?.meta?.tag, "orig");
    assert.equal(ctrl.getState().beats.has("b2"), false);

    // Direct applyEvent & foldTrace ingestion isolation
    const rawEvent = ev(0, "edit", { op: "upsert_beat", beat: { id: "raw_b", t: 3.0, source: "user" } });
    const s1 = applyEvent(createInitialState(), rawEvent);
    rawEvent.payload.beat.t = 999;
    assert.equal(s1.events[0].payload.beat.t, 3.0);
    assert.equal(foldTrace(s1.events).beats.get("raw_b")?.t, 3.0);
  });

  it("#7: projectRedLines anchors tempo transitions at the pivot beat with zero phantom beats", () => {
    const beats = [0, 0.5, 1.0, 1.75, 2.5].map((t, i) => ({
      id: `b${i}`,
      t,
      source: "model",
      version: 1,
    }));

    const projection = projectRedLines(beats, [], { defaultMeter: 4, jitterTolMs: 15 });
    assert.equal(projection.redLines.length, 2);
    assert.equal(projection.redLines[0].offsetMs, 0);
    assert.equal(projection.redLines[0].beatLengthMs, 500);
    assert.equal(projection.redLines[1].offsetMs, 1000);
    assert.equal(projection.redLines[1].beatLengthMs, 750);

    // Reconstructed beat grid MUST match [0, 0.5, 1.0, 1.75, 2.5] with ZERO phantom beat at 1.5!
    const reconstructed = reconstructGridFromRedLines(projection.redLines, 2.5);
    assert.deepEqual(reconstructed, [0, 0.5, 1.0, 1.75, 2.5]);

    // Also verify mid-song single anomalous interval [0, 0.5, 1.0, 1.75, 2.25, 2.75]
    const beatsAnom = [0, 0.5, 1.0, 1.75, 2.25, 2.75].map((t, i) => ({
      id: `a${i}`,
      t,
      source: "model",
      version: 1,
    }));
    const projAnom = projectRedLines(beatsAnom, [], { defaultMeter: 4, jitterTolMs: 15 });
    assert.deepEqual(reconstructGridFromRedLines(projAnom.redLines, 2.75), [
      0, 0.5, 1.0, 1.75, 2.25, 2.75,
    ]);
  });

  it("#8: verifyCheckpoint rejects altered snapshot contents & schemaVersion, and checkpoint snapshots are deeply cloned", () => {
    const emptyState = createInitialState();
    const emptyCp = createCheckpoint(emptyState);
    assert.equal(verifyCheckpoint(emptyCp, []).valid, true);

    // Tamper snapshot.ready = true -> MUST fail
    const tamperedReady = structuredClone(emptyCp);
    tamperedReady.snapshot.ready = true;
    const resReady = verifyCheckpoint(tamperedReady, []);
    assert.equal(resReady.valid, false);
    assert.match(resReady.reason ?? "", /snapshot/i);

    // Tamper schemaVersion -> MUST fail
    const tamperedSchema = { ...structuredClone(emptyCp), schemaVersion: "9.9" };
    const resSchema = verifyCheckpoint(tamperedSchema, []);
    assert.equal(resSchema.valid, false);
    assert.match(resSchema.reason ?? "", /schemaVersion/i);

    // Snapshot beat objects MUST NOT share live state references
    const trace = [
      ev(0, "draft_loaded", { beats: [{ id: "b0", t: 1.0, source: "model", meta: { k: 1 } }] }, { kind: "model" }),
    ];
    const state = foldTrace(trace);
    const cp = createCheckpoint(state);
    cp.snapshot.beats[0].t = 999;
    cp.snapshot.beats[0].meta.k = 42;
    assert.equal(state.beats.get("b0")?.t, 1.0);
    assert.equal(state.beats.get("b0")?.meta?.k, 1);
    assert.equal(verifyCheckpoint(cp, trace).valid, false);
  });

  it("#9: late audio bind or analyze cannot install stale recording's evidence or overwrite newer drafts/edits", async () => {
    // Part A: bindAudio(A) slow (ignores AbortSignal) + bindAudio(B) fast -> B must win!
    let resolveA;
    const raceProvider = {
      manifest: () => ({
        id: "race.mock",
        version: "0.1.0",
        kind: "mock",
        capabilities: {
          draft: true,
          sounds: false,
          constrainedRecompute: false,
          honoredStatements: [],
          diagnostics: false,
          alternatives: false,
          repeatPropagation: false,
        },
      }),
      prepare: async (audio) => {
        if (audio.audioId === "audio_A") {
          await new Promise((r) => {
            resolveA = r;
          });
          return { audioId: "audio_A", tag: "from_A" };
        }
        return { audioId: "audio_B", tag: "from_B" };
      },
      analyze: async (evidence) => ({
        beats: [{ id: `beat_${evidence.audioId}`, t: evidence.audioId === "audio_B" ? 2.0 : 1.0, source: "model" }],
      }),
      recompute: async () => ({ offers: [] }),
    };

    const ctrl = new SessionController({ provider: raceProvider });
    const pA = ctrl.bindAudio({ audioId: "audio_A", durationSec: 10, clock: "source-audio-sec" });
    const pB = ctrl.bindAudio({ audioId: "audio_B", durationSec: 10, clock: "source-audio-sec" });

    const evB = await pB;
    assert.equal(evB.audioId, "audio_B");

    // Now A resolves LATE
    resolveA();
    await assert.rejects(pA, (err) => err instanceof EnsomiError && err.code === "SUPERSEDED");

    // Loading initial draft MUST use B's evidence, NOT A's!
    await ctrl.loadInitialDraft();
    assert.equal(ctrl.getState().audio?.audioId, "audio_B");
    assert.ok(ctrl.getState().beats.has("beat_audio_B"));
    assert.equal(ctrl.getState().beats.has("beat_audio_A"), false);

    // Part B: Pending analyze() cannot overwrite a newer loadDraft() or intervening edit!
    let resolveAnalyze;
    const slowAnalyzeProvider = {
      ...raceProvider,
      prepare: async (audio) => ({ audioId: audio.audioId }),
      analyze: async () => {
        await new Promise((r) => {
          resolveAnalyze = r;
        });
        return { beats: [{ id: "stale_analyze_beat", t: 0.5, source: "model" }] };
      },
    };

    const ctrl2 = new SessionController({ provider: slowAnalyzeProvider });
    await ctrl2.bindAudio({ audioId: "audio_C", durationSec: 10, clock: "source-audio-sec" });

    const pendingAnalyze = ctrl2.loadInitialDraft();
    // Intervening user edit arrives before analyze finishes
    ctrl2.applyEdit({ op: "upsert_beat", beat: { id: "user_edit_beat", t: 3.0, source: "user" } });
    resolveAnalyze();
    await assert.rejects(pendingAnalyze, (err) => err instanceof EnsomiError && err.code === "STALE_DRAFT");
    assert.ok(ctrl2.getState().beats.has("user_edit_beat"));
    assert.equal(ctrl2.getState().beats.has("stale_analyze_beat"), false);

    // Intervening loadDraft() also supersedes pending loadInitialDraft()
    const pendingAnalyze2 = ctrl2.loadInitialDraft();
    ctrl2.loadDraft({ beats: [{ id: "imported_beat", t: 4.0, source: "import" }] });
    resolveAnalyze();
    await assert.rejects(pendingAnalyze2, (err) => err instanceof EnsomiError && err.code === "SUPERSEDED");
    assert.ok(ctrl2.getState().beats.has("imported_beat"));
    assert.equal(ctrl2.getState().beats.has("stale_analyze_beat"), false);
  });

  it("#10: enforces pending-only accept/reject, writeScope containment, tiny-scope mock rewrites, and complete preconditions", async () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ audioId: "a10", durationSec: 10, clock: "source-audio-sec" });
    ctrl.loadDraft({ beats: [{ id: "b0", t: 0.05, source: "model", version: 1 }] });

    // Tiny scope [0, 0.1): mock rewrite MUST place all beats strictly inside [0, 0.1)!
    const tiny = await ctrl.triggerRecompute({ scope: { t0: 0, t1: 0.1 }, reason: "local_rewrite" });
    assert.equal(tiny.offers.length, 1);
    for (const b of tiny.offers[0].ops.upsertBeats ?? []) {
      assert.ok(b.t >= 0 && b.t < 0.1, `Expected beat inside [0, 0.1), got ${b.t}`);
    }
    const accTiny = ctrl.acceptOffer(tiny.offers[0].id);
    assert.equal(accTiny.ok, true);

    // Re-accepting or rejecting an already-accepted offer MUST fail cleanly
    const reAccept = ctrl.acceptOffer(tiny.offers[0].id);
    assert.equal(reAccept.ok, false);
    assert.throws(
      () => ctrl.rejectOffer(tiny.offers[0].id),
      (err) => err instanceof EnsomiError && err.code === "OFFER_NOT_PENDING",
    );

    // Rejecting a pending offer, then attempting to accept or re-reject it MUST fail cleanly
    const r2 = await ctrl.triggerRecompute({ scope: { t0: 2, t1: 3 }, reason: "local_rewrite" });
    ctrl.rejectOffer(r2.offers[0].id, "nope");
    assert.equal(ctrl.acceptOffer(r2.offers[0].id).ok, false);
    assert.throws(
      () => ctrl.rejectOffer(r2.offers[0].id),
      (err) => err instanceof EnsomiError && err.code === "OFFER_NOT_PENDING",
    );

    // Reducer-level offer_accept on rejected/accepted offer MUST NOT mutate state or status
    const beforeState = ctrl.getState();
    const afterRedAccept = applyEvent(
      beforeState,
      ev(beforeState.lastSeq + 1, "offer_accept", { offerId: r2.offers[0].id }),
    );
    assert.equal(afterRedAccept.offers.get(r2.offers[0].id)?.status, "rejected");
    assert.ok(afterRedAccept.foldWarnings.some((w) => w.code === "OFFER_NOT_PENDING"));

    // Write-scope check: old or new position outside writeScope MUST fail validation
    const outOfScopeNew = {
      id: "off_oos_new",
      batchId: "b_oos",
      providerId: "mock",
      providerKind: "mock",
      generation: 10,
      basisSeq: ctrl.getState().lastSeq,
      role: "rewrite",
      scopeKind: "local",
      readScope: { t0: 0, t1: 0.1 },
      writeScope: { t0: 0, t1: 0.1 },
      ops: {
        upsertBeats: [{ id: "bad_new", t: 0.25, source: "model" }],
      },
      summary: "Writes outside scope",
      consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: "sk:oos_new",
      status: "pending",
    };
    const oosNewConflicts = validatePatchOffer(ctrl.getState(), outOfScopeNew);
    assert.ok(oosNewConflicts.some((c) => c.kind === "constraint_violation" && c.entityIds?.includes("bad_new")));

    // Existing beat inside [0, 0.1) moved by offer with writeScope [4, 5) -> old position outside writeScope!
    const existingInsideTiny = Array.from(ctrl.getState().beats.values())[0];
    const outOfScopeOld = {
      ...outOfScopeNew,
      id: "off_oos_old",
      writeScope: { t0: 4, t1: 5 },
      readScope: { t0: 4, t1: 5 },
      ops: {
        upsertBeats: [{ id: existingInsideTiny.id, t: 4.5, source: "model" }],
        expectedVersions: { [existingInsideTiny.id]: existingInsideTiny.version },
      },
    };
    const oosOldConflicts = validatePatchOffer(ctrl.getState(), outOfScopeOld);
    assert.ok(
      oosOldConflicts.some(
        (c) => c.kind === "constraint_violation" && c.entityIds?.includes(existingInsideTiny.id),
      ),
    );
  });

  it("#11: keep respects entity-kind scope & full entity content, and duplicate upsert IDs cannot bypass beat_at", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "draft_loaded", {
        beats: [{ id: "b1", t: 1.0, source: "model", version: 1, conf: 0.9 }],
        sounds: [{ id: "s1", t: 1.0, emphasis: 0.8, source: "model", version: 1 }],
      }, { kind: "model" }),
    );

    // 1. Sounds-only keep MUST NOT block beat edits
    const stateKeepSounds = applyEvent(
      state,
      ev(1, "statement", {
        action: "upsert",
        statement: { id: "keep_sounds", kind: "keep", span: { t0: 0, t1: 2 }, value: { scope: "sounds" } },
      }),
    );
    const beatMoveOffer = {
      id: "off_move_b1",
      batchId: "b_k1",
      providerId: "mock",
      providerKind: "mock",
      generation: 1,
      basisSeq: 1,
      role: "rewrite",
      scopeKind: "local",
      readScope: { t0: 0, t1: 2 },
      writeScope: { t0: 0, t1: 2 },
      ops: {
        upsertBeats: [{ id: "b1", t: 1.2, source: "model", conf: 0.9 }],
        expectedVersions: { b1: 1 },
      },
      summary: "Move b1",
      consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: "sk:move_b1",
      status: "pending",
    };
    assert.deepEqual(validatePatchOffer(stateKeepSounds, beatMoveOffer), []);

    // 2. Changing frozen beat's conf at the SAME t & source MUST be caught
    const stateKeepBeats = applyEvent(
      state,
      ev(1, "statement", {
        action: "upsert",
        statement: { id: "keep_beats", kind: "keep", span: { t0: 0, t1: 2 }, value: { scope: "beats" } },
      }),
    );
    const confChangeOffer = {
      ...beatMoveOffer,
      id: "off_conf",
      ops: {
        upsertBeats: [{ id: "b1", t: 1.0, source: "model", conf: 0.2 }],
        expectedVersions: { b1: 1 },
      },
    };
    const confConflicts = validatePatchOffer(stateKeepBeats, confChangeOffer);
    assert.ok(
      confConflicts.some(
        (c) => c.kind === "constraint_violation" && c.statementIds?.includes("keep_beats"),
      ),
    );
    // Exact identical beat upsert is allowed (no-op)
    const noopOffer = {
      ...beatMoveOffer,
      id: "off_noop",
      ops: {
        upsertBeats: [{ id: "b1", t: 1.0, source: "model", conf: 0.9 }],
        expectedVersions: { b1: 1 },
      },
    };
    assert.deepEqual(validatePatchOffer(stateKeepBeats, noopOffer), []);

    // Beats-only keep MUST NOT block sound edits
    const soundMoveOffer = {
      ...beatMoveOffer,
      id: "off_sound",
      ops: {
        upsertSounds: [{ id: "s1", t: 1.1, emphasis: 0.7, source: "model" }],
        expectedVersions: { s1: 1 },
      },
    };
    assert.deepEqual(validatePatchOffer(stateKeepBeats, soundMoveOffer), []);

    // 3. Duplicate IDs in upsertBeats MUST be rejected AND MUST NOT satisfy beat_at!
    const stateBeatAt = applyEvent(
      state,
      ev(1, "statement", {
        action: "upsert",
        statement: { id: "st_beat_at", kind: "beat_at", t: 1.0 },
      }),
    );
    const dupTrickOffer = {
      ...beatMoveOffer,
      id: "off_dup_trick",
      ops: {
        upsertBeats: [
          { id: "b1", t: 1.0, source: "model" },
          { id: "b1", t: 1.8, source: "model" }, // last-write-wins moves b1 away from 1.0!
        ],
        expectedVersions: { b1: 1 },
      },
    };
    const dupConflicts = validatePatchOffer(stateBeatAt, dupTrickOffer);
    assert.ok(dupConflicts.some((c) => c.kind === "constraint_violation" && c.id.includes("duplicate_upsert")));
    assert.ok(
      dupConflicts.some(
        (c) => c.kind === "constraint_violation" && c.statementIds?.includes("st_beat_at"),
      ),
    );
  });

  it("#12: semanticKey distinguishes different existing beat repairs and range check detects newly added sounds when includeSounds is explicit", () => {
    const scope = { t0: 0, t1: 2 };
    const keyMoveA = computeSemanticKey(scope, {
      upsertBeats: [{ id: "beat_A", t: 0.9, source: "model" }],
      expectedVersions: { beat_A: 1 },
    });
    const keyMoveB = computeSemanticKey(scope, {
      upsertBeats: [{ id: "beat_B", t: 0.9, source: "model" }],
      expectedVersions: { beat_B: 1 },
    });
    assert.notEqual(keyMoveA, keyMoveB);

    // Rejecting move-A MUST NOT suppress move-B
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "draft_loaded", {
        beats: [
          { id: "beat_A", t: 0.5, source: "model", version: 1 },
          { id: "beat_B", t: 1.5, source: "model", version: 1 },
        ],
      }, { kind: "model" }),
    );
    const offA = {
      id: "off_move_A",
      batchId: "b_12",
      providerId: "mock",
      providerKind: "mock",
      generation: 1,
      basisSeq: 0,
      role: "rewrite",
      scopeKind: "local",
      readScope: scope,
      writeScope: scope,
      ops: { upsertBeats: [{ id: "beat_A", t: 0.9, source: "model" }], expectedVersions: { beat_A: 1 } },
      summary: "Move A to 0.9",
      consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: keyMoveA,
      status: "pending",
    };
    state = applyEvent(state, ev(1, "offer_batch", { batchId: "b_12", offers: [offA] }, { kind: "model" }));
    state = applyEvent(state, ev(2, "offer_reject", { offerId: "off_move_A" }));

    assert.equal(isOfferSuppressed(state, { writeScope: scope, semanticKey: keyMoveA }), true);
    assert.equal(isOfferSuppressed(state, { writeScope: scope, semanticKey: keyMoveB }), false);

    // Empty sound range snapshotted with includeSounds: true on a beat-only patch MUST catch newly added sound
    const emptySoundScope = { t0: 4, t1: 6 };
    const snapIds = getRangeEntityIds(state, emptySoundScope, { includeSounds: true });
    assert.deepEqual(snapIds, []);

    const beatOnlyOfferWithSounds = {
      id: "off_beat_only",
      batchId: "b_12b",
      providerId: "mock",
      providerKind: "mock",
      generation: 2,
      basisSeq: 2,
      role: "extend",
      scopeKind: "local",
      readScope: emptySoundScope,
      writeScope: emptySoundScope,
      ops: {
        upsertBeats: [{ id: "new_b", t: 5.0, source: "model" }],
        expectedRangeEntityIds: snapIds,
        includeSounds: true,
      },
      summary: "Add beat contingent on empty sound range",
      consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0 },
      conflicts: [],
      semanticKey: "sk:beat_only_sound_snap",
      status: "pending",
    };
    assert.deepEqual(validatePatchOffer(state, beatOnlyOfferWithSounds), []);

    const stateWithSound = applyEvent(
      state,
      ev(3, "edit", { op: "upsert_sound", sound: { id: "s_new", t: 4.5, emphasis: 0.9, source: "user" } }),
    );
    const rangeConflicts = validatePatchOffer(stateWithSound, beatOnlyOfferWithSounds);
    assert.ok(rangeConflicts.some((c) => c.kind === "range_changed" && c.entityIds?.includes("s_new")));

    // Also verify expectedRangeKinds: ["sound"] explicitly
    const kindOffer = {
      ...beatOnlyOfferWithSounds,
      id: "off_kind_sound",
      ops: {
        upsertBeats: [{ id: "new_b", t: 5.0, source: "model" }],
        expectedRangeEntityIds: [],
        expectedRangeKinds: ["sound"],
      },
    };
    assert.ok(
      validatePatchOffer(stateWithSound, kindOffer).some(
        (c) => c.kind === "range_changed" && c.entityIds?.includes("s_new"),
      ),
    );
  });

  it("#13: computeGridFit returns signed residualMs (negative = early, positive = late)", () => {
    const beats = [
      { id: "b0", t: 1.0, source: "model", version: 1 },
      { id: "b1", t: 1.5, source: "model", version: 1 },
    ];
    const sounds = [
      { id: "early_beat", t: 0.992, emphasis: 0.8, source: "model", version: 1 }, // -8ms from 1.0
      { id: "late_beat", t: 1.007, emphasis: 0.8, source: "model", version: 1 },  // +7ms from 1.0
      { id: "early_sub", t: 1.244, emphasis: 0.7, source: "model", version: 1 },  // -6ms from 1.25 (1/2)
      { id: "late_sub", t: 1.256, emphasis: 0.7, source: "model", version: 1 },   // +6ms from 1.25 (1/2)
      { id: "early_unexp", t: 1.19, emphasis: 0.6, source: "model", version: 1 }, // closest to 1/3 (~1.1667) or 1/4 (1.125) or 1/2 (1.25)
    ];

    const fit = computeGridFit(sounds, beats, [], { divisors: [1, 2], tolMs: 15 });
    const byId = new Map(fit.map((x) => [x.soundId, x]));

    assert.equal(byId.get("early_beat")?.status, "on_grid");
    assert.equal(byId.get("early_beat")?.nearestBeatDistMs, 8);
    assert.equal(byId.get("early_beat")?.residualMs, -8);

    assert.equal(byId.get("late_beat")?.status, "on_grid");
    assert.equal(byId.get("late_beat")?.nearestBeatDistMs, 7);
    assert.equal(byId.get("late_beat")?.residualMs, 7);

    assert.equal(byId.get("early_sub")?.status, "subdivision");
    assert.equal(byId.get("early_sub")?.nearestDivisor, 2);
    assert.equal(byId.get("early_sub")?.residualMs, -6);

    assert.equal(byId.get("late_sub")?.status, "subdivision");
    assert.equal(byId.get("late_sub")?.nearestDivisor, 2);
    assert.equal(byId.get("late_sub")?.residualMs, 6);

    // 1.19 is 60ms early relative to 1.25 (closer than 190ms to 1.0)
    assert.equal(byId.get("early_unexp")?.status, "unexplained");
    assert.equal(byId.get("early_unexp")?.residualMs, -60);

    // Zero beats cleanly returns Infinity
    const noBeatsFit = computeGridFit([sounds[0]], [], []);
    assert.equal(noBeatsFit[0].residualMs, Infinity);
    assert.equal(noBeatsFit[0].status, "unexplained");
  });

  it("#22: rejects nonpositive/non-finite windowSec in rankTroubleSpans and invalid bpm/durationSec in generateSyntheticDraft", () => {
    const state = applyEvent(
      createInitialState(),
      ev(0, "audio_bound", { audioId: "a22", durationSec: 10, clock: "source-audio-sec" }, { kind: "host" }),
    );
    for (const badWindow of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(
        () => rankTroubleSpans(state, { windowSec: badWindow }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OPTIONS",
      );
    }
    for (const badBpm of [0, -120, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(
        () => generateSyntheticDraft({ bpm: badBpm }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OPTIONS",
      );
    }
    for (const badDur of [-1, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(
        () => generateSyntheticDraft({ durationSec: badDur }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OPTIONS",
      );
    }
    // durationSec: 0 is valid and terminates cleanly
    const zeroDraft = generateSyntheticDraft({ durationSec: 0, bpm: 120 });
    assert.equal(zeroDraft.beats.length, 0);
  });

  it("#23: automatically prepares restored audio from initialEvents instead of passing null evidence", async () => {
    /** @type {any[]} */
    const seenEvidence = [];
    let prepareCalls = 0;

    const inspectingProvider = {
      manifest: () => new ScriptedMockProvider().manifest(),
      prepare: async (audio) => {
        prepareCalls += 1;
        return { audioId: audio.audioId, durationSec: audio.durationSec, prepared: true };
      },
      analyze: async (evidence) => {
        seenEvidence.push(evidence);
        return { beats: [{ id: "b0", t: 0.5, source: "model" }] };
      },
      recompute: async (evidence, state, request) => {
        seenEvidence.push(evidence);
        return new ScriptedMockProvider().recompute(evidence, state, request);
      },
    };

    const initialEvents = [
      ev(0, "audio_bound", { audioId: "restored_audio", durationSec: 12, clock: "source-audio-sec" }, { kind: "host" }),
    ];

    const ctrl = new SessionController({ provider: inspectingProvider, initialEvents });
    await ctrl.loadInitialDraft();
    await ctrl.triggerRecompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });

    assert.equal(prepareCalls, 1); // prepared once, cached thereafter
    assert.equal(seenEvidence.length, 2);
    for (const evObj of seenEvidence) {
      assert.ok(evObj !== null && typeof evObj === "object");
      assert.equal(evObj.audioId, "restored_audio");
      assert.equal(evObj.prepared, true);
    }
  });

  it("#24: gap_start at a beat timestamp disables subdivision interpolation across [t, gap_end)", () => {
    const beats = [
      { id: "b0", t: 0.0, source: "model", version: 1 },
      { id: "b1", t: 1.0, source: "model", version: 1 },
      { id: "b2", t: 2.0, source: "model", version: 1 },
    ];
    const sounds = [
      { id: "s_in_gap", t: 0.5, emphasis: 0.8, source: "model", version: 1 },
      { id: "s_after_gap", t: 1.5, emphasis: 0.8, source: "model", version: 1 },
    ];

    // Counterexample: gap_start exactly at beat 0 -> sound at 0.5 MUST be unexplained (NOT subdivision)
    const gapOpenOnly = [{ id: "g0", t: 0.0, kind: "gap_start", source: "user", version: 1 }];
    const fitOpen = computeGridFit(sounds, beats, gapOpenOnly);
    assert.equal(fitOpen[0].status, "unexplained");
    assert.equal(fitOpen[1].status, "unexplained");

    // Closed gap [0.0, 1.0): sound at 0.5 is unexplained, sound at 1.5 IS a subdivision!
    const gapClosed = [
      { id: "g0", t: 0.0, kind: "gap_start", source: "user", version: 1 },
      { id: "g1", t: 1.0, kind: "gap_end", source: "user", version: 1 },
    ];
    const fitClosed = computeGridFit(sounds, beats, gapClosed);
    assert.equal(fitClosed[0].status, "unexplained");
    assert.equal(fitClosed[1].status, "subdivision");
    assert.equal(fitClosed[1].nearestDivisor, 2);
  });

  it("#25: preserves effort.waitSec across superseded buffered batches under audition lock", async () => {
    let now = 1_000;
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request }) => {
          now += 500; // 0.5s wait
          return new ScriptedMockProvider().recompute({}, state, request);
        },
        async ({ state, request }) => {
          now += 750; // 0.75s wait
          return new ScriptedMockProvider().recompute({}, state, request);
        },
      ],
    });
    const ctrl = new SessionController({ provider, clock: () => now });
    await ctrl.bindAudio({ audioId: "a25", durationSec: 10, clock: "source-audio-sec" });
    ctrl.loadDraft({ beats: [], boundaries: [], sounds: [] });

    ctrl.setAuditionLock(true);
    const b1 = await ctrl.triggerRecompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });
    const b2 = await ctrl.triggerRecompute({ scope: { t0: 2, t1: 4 }, reason: "local_rewrite" });
    assert.equal(b1.status, "buffered");
    assert.equal(b2.status, "buffered");

    const flushed = ctrl.setAuditionLock(false);
    assert.equal(flushed.flushed, true);
    assert.equal(ctrl.getEffortLedger().waitSec, 1.25);

    // Must survive full NDJSON round-trip replay
    const replayed = foldTrace(parseTraceNdjson(ctrl.exportNdjson()));
    assert.equal(replayed.effort.waitSec, 1.25);
    assert.deepEqual(serializeState(replayed), serializeState(ctrl.getState()));
  });
});
