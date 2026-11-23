import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  EnsomiError,
  ScriptedMockProvider,
  SessionController,
  TRACE_SCHEMA_VERSION,
  applyEvent,
  computeGridFit,
  computeUndoRedoTargets,
  createCheckpoint,
  createInitialState,
  detectSiblingConflicts,
  foldTrace,
  parseTraceNdjson,
  projectRedLines,
  rankInboxSpans,
  serializeState,
  validatePatchOffer,
  verifyCheckpoint,
} from "../index.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

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

const FORBIDDEN_USER_FACING_PATTERNS = [
  /\bmock\b/i,
  /\bWIP\b/,
  /\bexperimental\b/i,
  /reserved for N1/i,
  /\bsk:[0-9a-f]/i,
  /\bsha256:/i,
  /\bbatch_[A-Za-z0-9_]+/,
  /\boffer_[A-Za-z0-9_]+/,
  /\bseg_[A-Za-z0-9_]+/,
  /\bbeat_[A-Za-z0-9_]+/,
  /\bsnd_[A-Za-z0-9_]+/,
  /\bunbeat_[A-Za-z0-9_]+/,
];

function assertCleanHumanCopy(text, label) {
  assert.equal(typeof text, "string", `${label} must be a string`);
  for (const pat of FORBIDDEN_USER_FACING_PATTERNS) {
    assert.equal(
      pat.test(text),
      false,
      `Forbidden internal/dev token matched ${pat} in ${label}: "${text}"`,
    );
  }
}

describe("Astra xhigh regressions and human-safe copy audit", () => {
  it("reopening session with fresh provider preserves accepted beats, never collides IDs, and rejects duplicate offer IDs", async () => {
    const provider1 = new ScriptedMockProvider();
    const ctrl1 = new SessionController({ provider: provider1 });
    await ctrl1.bindAudio({ songId: "reopen_song", durationSec: 12 });
    ctrl1.loadDraft({ beats: [], sounds: [] });

    const r1 = await ctrl1.recompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });
    assert.equal(r1.status, "applied");
    assert.equal(ctrl1.acceptOffer(r1.offers[0].id).ok, true);

    const savedTrace = parseTraceNdjson(ctrl1.exportNdjson());
    const provider2 = new ScriptedMockProvider();
    const ctrl2 = new SessionController({ provider: provider2, initialEvents: savedTrace });

    const r2 = await ctrl2.recompute({ scope: { t0: 4, t1: 6 }, reason: "local_rewrite" });
    assert.equal(r2.status, "applied");
    assert.notEqual(r2.offers[0].id, r1.offers[0].id);
    assert.equal(ctrl2.acceptOffer(r2.offers[0].id).ok, true);

    const beatTimes = Array.from(ctrl2.getState().beats.values())
      .map((b) => b.timeSec)
      .sort((a, b) => a - b);
    assert.deepEqual(beatTimes, [0.25, 0.75, 4.25, 4.75]);

    assert.throws(
      () =>
        applyEvent(
          ctrl2.getState(),
          ev(
            ctrl2.getState().lastSeq + 1,
            "offer_batch",
            { batchId: "dup_b", offers: [{ ...r1.offers[0], status: "valid" }] },
            { kind: "model" },
          ),
        ),
      (err) => err instanceof EnsomiError && err.code === "DUPLICATE_OFFER_ID",
    );
  });

  it("ABA undo-then-re-edit at the same integer revision cannot allow an obsolete offer to accept", async () => {
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request }) => ({
          batchId: "b_aba",
          offers: [
            {
              id: "off_aba",
              batchId: "b_aba",
              providerId: "ensomi.scripted",
              providerKind: "mock",
              generation: request.generation,
              basisSeq: request.basisSeq,
              expectedRevisions: { b: state.entityRevisions.get("b") },
              writeScopes: [{ segmentId: "seg_0", t0: 0, t1: 2 }],
              scopeKind: "local",
              title: "Refine beat timing",
              consequenceSummary: "Nudges 1 beat (up to 50 ms)",
              advisoryNotes: [],
              ops: { upsertBeats: [{ id: "b", timeSec: 0.25, source: "offer" }] },
              consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0, maxShiftMs: 50, segmentChanges: 0 },
              conflicts: [],
              semanticKey: "sk:aba",
              status: "valid",
            },
          ],
        }),
      ],
    });

    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ songId: "song_aba", durationSec: 4 });
    ctrl.loadDraft({ beats: [{ id: "b", timeSec: 0.1, source: "draft", revision: 1 }] });

    const move1 = ctrl.applyEdit({ op: "move_beat", id: "b", timeSec: 0.2 });
    assert.equal(ctrl.getState().beats.get("b")?.revision, 2);

    const rec = await ctrl.recompute({ scope: { t0: 0, t1: 2 }, reason: "local_rewrite" });
    const offerId = rec.offers[0].id;

    // Retract move1 (revision drops back to 1), then move b to 1.5 (revision becomes 2 again!)
    ctrl.retract(move1.event.id, "undo move1");
    ctrl.applyEdit({ op: "move_beat", id: "b", timeSec: 1.5 });
    assert.equal(ctrl.getState().beats.get("b")?.revision, 2);

    assert.equal(ctrl.getState().offers.get(offerId)?.status, "stale");
    const acc = ctrl.acceptOffer(offerId);
    assert.equal(acc.ok, false);
    assert.ok(acc.conflicts.some((c) => c.kind === "version_mismatch"));
    assert.equal(ctrl.getState().beats.get("b")?.timeSec, 1.5);
  });

  it("isolates caller-owned inputs, returned getState/getTrace copies, and rejects tampered checkpoints", () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider });
    ctrl.loadDraft({ durationSec: 10, beats: [], sounds: [] });

    const op = { op: "upsert_beat", beat: { id: "b1", timeSec: 1.0, source: "user" } };
    ctrl.applyEdit(op);
    op.beat.timeSec = 9.0;

    const stateCopy = ctrl.getState();
    stateCopy.beats.get("b1").timeSec = 77;
    stateCopy.segments[0].kept = true;

    const traceCopy = ctrl.getTrace();
    traceCopy[1].payload.beat.timeSec = 88;

    assert.equal(ctrl.getState().beats.get("b1")?.timeSec, 1.0);
    assert.equal(ctrl.getState().segments[0].kept, false);
    assert.equal(foldTrace(ctrl.getTrace()).beats.get("b1")?.timeSec, 1.0);

    const cp = createCheckpoint(ctrl.getState());
    assert.equal(verifyCheckpoint(cp, ctrl.getTrace()).valid, true);

    const tamperedSnap = structuredClone(cp);
    tamperedSnap.snapshot.ready = true;
    assert.equal(verifyCheckpoint(tamperedSnap, ctrl.getTrace()).valid, false);

    const tamperedVer = { ...structuredClone(cp), schemaVersion: "9.9" };
    assert.equal(verifyCheckpoint(tamperedVer, ctrl.getTrace()).valid, false);
  });

  it("lazily prepares restored sessions on first provider call and discards late prepare from superseded songId", async () => {
    let prepareCalls = 0;
    let resolveSlowSongA;
    const customProvider = {
      manifest: () => new ScriptedMockProvider().manifest(),
      prepare: async (audio) => {
        prepareCalls += 1;
        if (audio.songId === "slow_A") {
          await new Promise((r) => {
            resolveSlowSongA = r;
          });
        }
        return { songId: audio.songId, durationSec: audio.durationSec, prepared: true };
      },
      analyze: async (evidence) => ({
        beats: [{ id: "b0", timeSec: evidence.songId === "fast_B" ? 2.0 : 1.0, source: "draft" }],
      }),
      suggestSnaps: async () => [],
      recompute: async () => ({ offers: [] }),
    };

    // Part 1: Restored from initialEvents
    const restoredCtrl = new SessionController({
      provider: customProvider,
      initialEvents: [
        ev(0, "audio_bound", { songId: "restored_1", durationSec: 10 }, { kind: "host" }),
      ],
    });
    const resRestored = await restoredCtrl.analyze();
    assert.equal(resRestored.status, "applied");
    assert.equal(prepareCalls, 1);

    // Part 2: Song A slow prepare superseded by Song B fast prepare
    const raceCtrl = new SessionController({
      provider: customProvider,
      triggerPolicy: { song_open: { mode: "manual" } },
    });
    const pA = raceCtrl.bindAudio({ songId: "slow_A", durationSec: 10 });
    const pB = raceCtrl.bindAudio({ songId: "fast_B", durationSec: 10 });
    const evB = await pB;
    assert.equal(evB.songId, "fast_B");

    resolveSlowSongA();
    await assert.rejects(pA, (err) => err instanceof EnsomiError && err.code === "SUPERSEDED");

    await raceCtrl.analyze();
    assert.equal(raceCtrl.getState().beats.get("b0")?.timeSec, 2.0);
  });

  it("audits src/*.js and runtime user-facing strings for zero internal IDs, batchIds, hashes, or dev/mock leaks", async () => {
    // 1. Source scan for forbidden developer placeholders
    const srcFiles = readdirSync(join(rootDir, "src")).filter((f) => f.endsWith(".js"));
    for (const file of srcFiles) {
      const content = readFileSync(join(rootDir, "src", file), "utf8");
      assert.equal(/reserved for N1/i.test(content), false, `Found "reserved for N1" in src/${file}`);
      assert.equal(/\bWIP\b/.test(content), false, `Found "WIP" in src/${file}`);
      assert.equal(/\bexperimental\b/i.test(content), false, `Found "experimental" in src/${file}`);
    }

    // 2. Runtime user-facing copy scan across all scenarios, conflicts, advisories, views, and snaps
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({
      provider,
      triggerPolicy: { micro_assist: { debounceMs: 0 } },
    });
    await ctrl.openSong({ songId: "audit_track", durationSec: 32 });

    ctrl.applyEdit([
      { op: "upsert_beat", beat: { id: "beat_user_1", timeSec: 2.25, source: "user" } },
      { op: "apply_unbeat_cue", id: "unbeat_user_1", timeSec: 2.75 },
      { op: "set_range_keep", t0: 24, t1: 28, kept: true },
    ]);

    const scenarios = [
      "local_rewrite",
      "triplet_vs_reset",
      "forward",
      "cross_region",
      "protected_keep_conflict",
    ];
    for (const reason of scenarios) {
      const out = await ctrl.recompute({ scope: { t0: 2, t1: 4 }, reason });
      for (const offer of out.offers) {
        assertCleanHumanCopy(offer.title, `offer.title (${reason})`);
        assertCleanHumanCopy(offer.consequenceSummary, `offer.consequenceSummary (${reason})`);
        for (const note of offer.advisoryNotes) {
          assertCleanHumanCopy(note, `offer.advisoryNotes (${reason})`);
        }
        for (const conflict of offer.conflicts) {
          assertCleanHumanCopy(conflict.message, `conflict.message (${reason})`);
        }
        const check = validatePatchOffer(ctrl.getState(), offer);
        for (const note of check.advisoryNotes) {
          assertCleanHumanCopy(note, `validated advisory (${reason})`);
        }
        for (const conflict of check.conflicts) {
          assertCleanHumanCopy(conflict.message, `validated conflict (${reason})`);
        }
      }
    }

    // Check sibling supersession conflict message
    const sib = await ctrl.recompute({ scope: { t0: 6, t1: 8 }, reason: "triplet_vs_reset" });
    ctrl.acceptOffer(sib.offers[0].id);
    const supOffer = ctrl.getState().offers.get(sib.offers[1].id);
    for (const conflict of supOffer?.conflicts ?? []) {
      assertCleanHumanCopy(conflict.message, "superseded sibling conflict.message");
    }

    // Check snaps, inbox reasons, and red-line losses
    const snaps = await ctrl.suggestSnaps({ timeSec: 4.0, windowSec: 0.1 });
    for (const cand of snaps.candidates) {
      assertCleanHumanCopy(cand.label, "SnapCandidate.label");
    }

    const inbox = rankInboxSpans(ctrl.getState());
    for (const sp of inbox) {
      if (sp.reason) assertCleanHumanCopy(sp.reason, "Inbox Span.reason");
    }

    const proj = projectRedLines(ctrl.getState().beats, ctrl.getState().segments);
    for (const loss of proj.losses) {
      assertCleanHumanCopy(loss, "RedLineProjection.losses");
    }
  });

  it("[Astra #1] deep-freezes committed TraceEvents across getState(), applyEvent(), and provider snapshots", async () => {
    let providerSnapshotEvents = null;
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state }) => {
          providerSnapshotEvents = state.events;
          return { offers: [] };
        },
      ],
    });
    const ctrl = new SessionController({ provider, durationSec: 10 });
    ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b1", timeSec: 1.0, source: "user" } });
    ctrl.applyEdit({ op: "move_beat", id: "b1", timeSec: 2.0 });

    const snap = ctrl.getState();
    assert.throws(() => {
      snap.events[1].payload.timeSec = 9;
    }, TypeError);
    assert.throws(() => {
      snap.events[0].payload.beat.timeSec = 9;
    }, TypeError);
    assert.throws(() => {
      snap.events[1].author.kind = "host";
    }, TypeError);

    // Also test nested payload.op shape directly via applyEvent
    const customEvState = applyEvent(
      ctrl.getState(),
      ev(ctrl.getState().lastSeq + 1, "listen_effort", { listenSec: 1, range: { t0: 0, t1: 2 } }, { kind: "host" }),
    );
    assert.throws(() => {
      customEvState.events[2].payload.range.t1 = 9;
    }, TypeError);

    await ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "check_snapshot" });
    assert.ok(Array.isArray(providerSnapshotEvents));
    assert.throws(() => {
      providerSnapshotEvents[0].payload.beat.timeSec = 99;
    }, TypeError);

    const replayed = foldTrace(parseTraceNdjson(ctrl.exportNdjson()), { durationSec: 10 });
    assert.equal(replayed.beats.get("b1")?.timeSec, 2.0);
  });

  it("[Astra #2] preserves initialDurationSec across retract/foldTrace and cleanly skips orphaned dependent edits on selective undo", () => {
    // Part A: SessionController({ durationSec: 10 }) before bindAudio + split_segment + upsert_beat + retract
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider, durationSec: 10 });
    const splitRes = ctrl.applyEdit({
      op: "split_segment",
      segmentId: "seg_0",
      newSegmentId: "seg_1",
      timeSec: 4.0,
    });
    const beatRes = ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "b_temp", timeSec: 2.0, source: "user" },
    });
    const afterUndoBeat = ctrl.retract(beatRes.event.id);
    assert.equal(afterUndoBeat.state.beats.has("b_temp"), false);
    assert.deepEqual(
      afterUndoBeat.state.segments.map((s) => [s.id, s.t0, s.t1]),
      [
        ["seg_0", 0, 4],
        ["seg_1", 4, 10],
      ],
    );

    const directFold = foldTrace(ctrl.getTrace(), { durationSec: 10 });
    assert.deepEqual(serializeState(directFold), serializeState(ctrl.getState()));
    assert.equal(verifyCheckpoint(createCheckpoint(ctrl.getState()), ctrl.getTrace()).valid, true);

    // Part B: Selective retraction of split_segment when a later edit targets child segment seg_1
    const keepRes = ctrl.applyEdit({
      op: "set_segment_keep",
      segmentId: "seg_1",
      kept: true,
    });
    assert.equal(ctrl.getState().segments[1].kept, true);

    // Direct live invalid segment edit MUST still throw strictly!
    assert.throws(
      () => ctrl.applyEdit({ op: "set_segment_keep", segmentId: "seg_nonexistent", kept: true }),
      (err) => err instanceof EnsomiError && err.code === "INVALID_SEGMENT_EDIT",
    );

    // Selectively retracting the prerequisite split_segment MUST NOT crash!
    const selectiveUndo = ctrl.retract(splitRes.event.id);
    assert.deepEqual(
      selectiveUndo.state.segments.map((s) => [s.id, s.t0, s.t1, s.kept]),
      [["seg_0", 0, 10, false]],
    );
    assert.ok(
      selectiveUndo.state.foldWarnings.some(
        (w) => w.eventId === keepRes.event.id && w.code === "ORPHANED_DEPENDENT_EDIT",
      ),
    );

    // Redoing the retracted split_segment restores both seg_1 AND its dependent keep edit!
    const redoSplit = ctrl.retract(selectiveUndo.event.id);
    assert.deepEqual(
      redoSplit.state.segments.map((s) => [s.id, s.t0, s.t1, s.kept]),
      [
        ["seg_0", 0, 4, false],
        ["seg_1", 4, 10, true],
      ],
    );
    assert.equal(redoSplit.state.foldWarnings.length, 0);
  });

  it("[Astra #3] never overwrites user edits made while provider.prepare() is awaiting in openSong() or analyze()", async () => {
    let resumePrepare;
    let analyzeCalled = false;
    const provider = {
      manifest: () => new ScriptedMockProvider().manifest(),
      prepare: async (audio) => {
        await new Promise((r) => {
          resumePrepare = r;
        });
        return { songId: audio.songId, durationSec: audio.durationSec, prepared: true };
      },
      analyze: async () => {
        analyzeCalled = true;
        return {
          beats: [{ id: "b_draft_wipe", timeSec: 1.0, source: "draft" }],
        };
      },
      suggestSnaps: async () => [],
      recompute: async () => ({ offers: [] }),
    };

    const ctrl = new SessionController({ provider });
    const openPromise = ctrl.openSong({ songId: "song_prep_race", durationSec: 12 });
    await new Promise((r) => setTimeout(r, 5));

    // User places a beat while provider.prepare() is still in flight!
    ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "b_user_mid_prepare", timeSec: 3.25, source: "user" },
    });

    resumePrepare();
    const outcome = await openPromise;
    assert.equal(outcome.status, "superseded");
    assert.equal(outcome.event, null);
    assert.equal(analyzeCalled, false);
    assert.equal(ctrl.getState().beats.has("b_user_mid_prepare"), true);
    assert.equal(ctrl.getState().beats.has("b_draft_wipe"), false);
  });

  it("[Astra #4] rankInboxSpans never rounds 1-sample Break slivers to zero width or past non-round durationSec", () => {
    // 1. 1-sample (1/44100 s) unlocked sliver around a stray beat inside a Break segment
    const oneSample = 1 / 44100;
    let breakState = createInitialState();
    breakState = applyEvent(
      breakState,
      ev(0, "audio_bound", { songId: "s_sliver", durationSec: 4 }, { kind: "host" }),
    );
    breakState = applyEvent(
      breakState,
      ev(
        1,
        "draft_loaded",
        {
          segments: [{ id: "seg_brk", t0: 0, t1: 4, regime: "break", kept: false }],
          beats: [{ id: "b_stray", timeSec: 2.0 + oneSample * 0.5, source: "draft" }],
        },
        { kind: "model" },
      ),
    );
    breakState = applyEvent(
      breakState,
      ev(2, "edit", [
        { op: "set_range_keep", t0: 0, t1: 2.0, kept: true },
        { op: "set_range_keep", t0: 2.0 + oneSample, t1: 4.0, kept: true },
      ]),
    );

    const sliverInbox = rankInboxSpans(breakState);
    assert.equal(sliverInbox.length, 1);
    assert.ok(sliverInbox[0].t1 > sliverInbox[0].t0);
    assert.equal(sliverInbox[0].t0, 2.0);
    assert.equal(sliverInbox[0].t1, 2.0 + oneSample);

    // Locking the returned 1-sample Inbox span must succeed cleanly
    const lockedSliver = applyEvent(
      breakState,
      ev(3, "edit", {
        op: "set_range_keep",
        t0: sliverInbox[0].t0,
        t1: sliverInbox[0].t1,
        kept: true,
      }),
    );
    assert.equal(rankInboxSpans(lockedSliver).length, 0);

    // 2. Track duration 8.123456 s (where 4-decimal rounding 8.1235 would exceed durationSec!)
    const exactDur = 8.123456;
    let oddDurState = createInitialState();
    oddDurState = applyEvent(
      oddDurState,
      ev(0, "audio_bound", { songId: "s_odd_dur", durationSec: exactDur }, { kind: "host" }),
    );
    oddDurState = applyEvent(
      oddDurState,
      ev(
        1,
        "draft_loaded",
        {
          beats: [
            { id: "b0", timeSec: 1.0, confidence: 0.3, source: "draft" },
            { id: "b1", timeSec: 5.0, confidence: 0.3, source: "draft" },
            { id: "b2", timeSec: 8.1, confidence: 0.25, source: "draft" },
          ],
        },
        { kind: "model" },
      ),
    );

    const inboxSpans = rankInboxSpans(oddDurState, { windowSec: 4 });
    assert.ok(inboxSpans.length >= 2);
    for (let i = 0; i < inboxSpans.length; i += 1) {
      const sp = inboxSpans[i];
      assert.ok(sp.t0 >= 0 && sp.t1 > sp.t0 && sp.t1 <= exactDur);
      oddDurState = applyEvent(
        oddDurState,
        ev(10 + i, "edit", { op: "set_range_keep", t0: sp.t0, t1: sp.t1, kept: true }),
      );
    }
    assert.equal(oddDurState.segments[0].kept, true);
  });

  it("[Astra #5] aligns validatePatchOffer 1-to-1 with offer_accept for boundary adjacency/swept scopes, sequential splits, and entity kinds", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "s_val", durationSec: 12 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(
        1,
        "draft_loaded",
        {
          segments: [
            { id: "seg_0", t0: 0, t1: 4, regime: "auto", kept: false },
            { id: "seg_1", t0: 4, t1: 8, regime: "auto", kept: false },
            { id: "seg_2", t0: 8, t1: 12, regime: "auto", kept: false },
          ],
          beats: [{ id: "b_0", timeSec: 1.0, source: "draft" }],
        },
        { kind: "model" },
      ),
    );

    const baseOffer = {
      id: "off_base",
      batchId: "b_val",
      semanticKey: "sk:val",
      providerId: "ensomi.scripted",
      providerKind: "mock",
      generation: 1,
      basisSeq: state.lastSeq,
      expectedRevisions: {
        seg_0: state.entityRevisions.get("seg_0"),
        seg_1: state.entityRevisions.get("seg_1"),
        seg_2: state.entityRevisions.get("seg_2"),
      },
      writeScopes: [
        { segmentId: "seg_0", t0: 0, t1: 4 },
        { segmentId: "seg_1", t0: 4, t1: 8 },
        { segmentId: "seg_2", t0: 8, t1: 12 },
      ],
      scopeKind: "local",
      title: "Adjust section layout",
      consequenceSummary: "Updates 1 section boundary",
      advisoryNotes: [],
      ops: {},
      consequences: { beatsAdded: 0, beatsMoved: 0, beatsRemoved: 0, maxShiftMs: 0, segmentChanges: 1 },
      conflicts: [],
      status: "valid",
    };

    // 1. Non-adjacent moveSegmentBoundaries (seg_0 and seg_2) -> invalid / out_of_scope
    const nonAdj = validatePatchOffer(state, {
      ...baseOffer,
      ops: { moveSegmentBoundaries: [{ leftSegmentId: "seg_0", rightSegmentId: "seg_2", timeSec: 6 }] },
    });
    assert.equal(nonAdj.valid, false);
    assert.equal(nonAdj.status, "invalid");
    assert.ok(nonAdj.conflicts.some((c) => c.kind === "out_of_scope"));

    // 2. Disjoint writeScopes [0, 1) + [7, 8) trying to move boundary 4 -> 5 -> invalid / out_of_scope
    const disjointScopes = validatePatchOffer(state, {
      ...baseOffer,
      writeScopes: [
        { segmentId: "seg_0", t0: 0, t1: 1 },
        { segmentId: "seg_1", t0: 7, t1: 8 },
      ],
      ops: { moveSegmentBoundaries: [{ leftSegmentId: "seg_0", rightSegmentId: "seg_1", timeSec: 5 }] },
    });
    assert.equal(disjointScopes.valid, false);
    assert.equal(disjointScopes.status, "invalid");
    assert.ok(disjointScopes.conflicts.some((c) => c.kind === "out_of_scope"));

    // 3. removeBeatIds targeting a segment ID ("seg_0") -> invalid / out_of_scope
    const wrongKindRemove = validatePatchOffer(state, {
      ...baseOffer,
      ops: { removeBeatIds: ["seg_0"] },
    });
    assert.equal(wrongKindRemove.valid, false);
    assert.equal(wrongKindRemove.status, "invalid");
    assert.ok(wrongKindRemove.conflicts.some((c) => c.kind === "out_of_scope"));

    // 4. Duplicate / colliding newSegmentId in splitSegments -> invalid / out_of_scope
    const dupSplitId = validatePatchOffer(state, {
      ...baseOffer,
      ops: {
        splitSegments: [{ segmentId: "seg_0", newSegmentId: "b_0", timeSec: 2.0 }],
      },
    });
    assert.equal(dupSplitId.valid, false);
    assert.equal(dupSplitId.status, "invalid");

    // 5. Valid sequential splitSegments -> moveSegmentBoundaries -> setSegmentRegimes on newly created segment!
    const seqOffer = {
      ...baseOffer,
      id: "off_seq_valid",
      expectedRevisions: { seg_0: state.entityRevisions.get("seg_0") },
      writeScopes: [{ segmentId: "seg_0", t0: 0, t1: 4 }],
      ops: {
        splitSegments: [{ segmentId: "seg_0", newSegmentId: "seg_intro_b", timeSec: 2.0 }],
        moveSegmentBoundaries: [
          { leftSegmentId: "seg_0", rightSegmentId: "seg_intro_b", timeSec: 1.5 },
        ],
        setSegmentRegimes: [{ segmentId: "seg_intro_b", regime: "free" }],
      },
    };
    const seqCheck = validatePatchOffer(state, seqOffer);
    assert.equal(seqCheck.valid, true);
    assert.equal(seqCheck.status, "valid");

    let afterSeq = applyEvent(
      state,
      ev(2, "offer_batch", { batchId: "b_seq", offers: [seqOffer] }, { kind: "model" }),
    );
    afterSeq = applyEvent(afterSeq, ev(3, "offer_accept", { offerId: "off_seq_valid" }));
    assert.equal(afterSeq.offers.get("off_seq_valid")?.status, "accepted");
    assert.deepEqual(
      afterSeq.segments.map((s) => [s.id, s.t0, s.t1, s.regime]),
      [
        ["seg_0", 0, 1.5, "auto"],
        ["seg_intro_b", 1.5, 4, "free"],
        ["seg_1", 4, 8, "auto"],
        ["seg_2", 8, 12, "auto"],
      ],
    );
  });

  it("[Astra #6] enforces Keep-lock protection and soft-cue advisories for SoundAnchors", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "s_snd_parity", durationSec: 20 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(
        1,
        "draft_loaded",
        {
          sounds: [
            { id: "snd_locked", timeSec: 2.0, source: "draft" },
            { id: "snd_user", timeSec: 14.02, source: "user" },
          ],
        },
        { kind: "model" },
      ),
    );
    state = applyEvent(
      state,
      ev(2, "edit", { op: "set_range_keep", t0: 1.0, t1: 3.0, kept: true }),
    );

    const keepSoundOffer = {
      id: "off_snd_keep",
      batchId: "b_snd_1",
      semanticKey: "sk:snd_keep",
      providerId: "ensomi.scripted",
      providerKind: "mock",
      generation: 1,
      basisSeq: state.lastSeq,
      expectedRevisions: { snd_locked: state.entityRevisions.get("snd_locked") },
      writeScopes: [{ segmentId: "seg_0", t0: 1.5, t1: 2.5 }],
      scopeKind: "local",
      title: "Adjust onset placement",
      consequenceSummary: "No beat timing changes",
      advisoryNotes: [],
      ops: {
        upsertSounds: [{ id: "snd_locked", timeSec: 2.04, source: "offer" }],
      },
      consequences: { beatsAdded: 0, beatsMoved: 0, beatsRemoved: 0, maxShiftMs: 0, segmentChanges: 0 },
      conflicts: [],
      status: "valid",
    };
    const keepRes = validatePatchOffer(state, keepSoundOffer);
    assert.equal(keepRes.valid, false);
    assert.equal(keepRes.status, "invalid");
    assert.ok(keepRes.conflicts.some((c) => c.kind === "protected_keep"));

    const userSoundOffer = {
      ...keepSoundOffer,
      id: "off_snd_user",
      expectedRevisions: { snd_user: state.entityRevisions.get("snd_user") },
      writeScopes: [{ segmentId: "seg_0", t0: 13.0, t1: 15.0 }],
      ops: {
        upsertSounds: [{ id: "snd_user", timeSec: 14.055, source: "offer" }],
      },
    };
    const userRes = validatePatchOffer(state, userSoundOffer);
    assert.equal(userRes.valid, true);
    assert.equal(userRes.status, "valid");
    assert.ok(
      userRes.advisoryNotes.some((n) => n.includes("Adjusts your sound marker near 0:14.020")),
    );
    for (const note of userRes.advisoryNotes) {
      assertCleanHumanCopy(note, "SoundAnchor soft-cue advisory");
    }
  });

  it("[Astra #7] reconciles orphaned spans on additive draft_loaded and prunes out-of-range beats when durationSec shrinks", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "s_rec", durationSec: 10 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(1, "edit", [
        { op: "split_segment", segmentId: "seg_0", newSegmentId: "seg_old_b", timeSec: 5 },
        { op: "set_range_keep", t0: 6, t1: 8, kept: true },
        { op: "upsert_beat", beat: { id: "b_early", timeSec: 2.0, source: "user" } },
        { op: "upsert_beat", beat: { id: "b_late_9s", timeSec: 9.0, source: "user" } },
      ]),
    );
    assert.equal(state.spans.length, 1);
    assert.equal(state.spans[0].segmentId, "seg_old_b");

    // Additive draft_loaded replaces segments with new IDs without replace:true -> span reparents cleanly!
    state = applyEvent(
      state,
      ev(
        2,
        "draft_loaded",
        {
          replace: false,
          segments: [{ id: "seg_fresh", t0: 0, t1: 10, regime: "auto", kept: false }],
          beats: [{ id: "b_mid", timeSec: 3.0, source: "draft" }],
        },
        { kind: "model" },
      ),
    );
    assert.equal(state.spans.length, 1);
    assert.equal(state.spans[0].segmentId, "seg_fresh");
    assert.ok(state.entityRevisions.has(state.spans[0].id));
    assert.equal(state.entityRevisions.has("seg_old_b"), false);

    // Rebinding audio_bound with shorter durationSec (4 s) prunes b_late_9s and span [6, 8)
    state = applyEvent(
      state,
      ev(3, "audio_bound", { songId: "s_rec", durationSec: 4 }, { kind: "host" }),
    );
    assert.equal(state.beats.has("b_late_9s"), false);
    assert.equal(state.entityRevisions.has("b_late_9s"), false);
    assert.equal(state.beats.has("b_early"), true);
    assert.equal(state.spans.length, 0);

    const red = projectRedLines(state.beats, state.segments);
    assert.ok(red.redLines.every((rl) => rl.offsetMs <= 4000));
  });

  it("[Astra #8] invalidates evidence on any AudioDescriptor change, resets ready on song switch, keeps held offers live-validated, and reconstructs Undo/Redo deterministically", async () => {
    let prepareCount = 0;
    const provider = new ScriptedMockProvider();
    const origPrepare = provider.prepare.bind(provider);
    provider.prepare = async (audio, signal) => {
      prepareCount += 1;
      return origPrepare(audio, signal);
    };

    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ songId: "s_rebind", durationSec: 12, sampleRate: 44100 });
    assert.equal(prepareCount, 1);

    // Identical descriptor reuses cached evidence
    await ctrl.bindAudio({ songId: "s_rebind", durationSec: 12, sampleRate: 44100 });
    assert.equal(prepareCount, 1);

    // Changing sampleRate or leadOffsetSec on the SAME songId invalidates evidence & re-prepares!
    await ctrl.bindAudio({
      songId: "s_rebind",
      durationSec: 12,
      sampleRate: 48000,
      leadOffsetSec: 0.012,
    });
    assert.equal(prepareCount, 2);

    // Held offers dynamic validation parity
    ctrl.loadDraft({ beats: [{ id: "b_h", timeSec: 1.0, source: "draft" }] });
    ctrl.setHoldOffers(true);
    const heldOut = await ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "local_rewrite" });
    assert.equal(heldOut.status, "held");
    assert.equal(heldOut.offers[0].status, "valid");
    assert.equal(ctrl.getHeldOffers()[0].status, "valid");

    // Editing overlapping range while still holding dynamically updates getHeldOffers() to "stale"!
    ctrl.applyEdit({ op: "move_beat", id: "b_h", timeSec: 1.2 });
    assert.equal(ctrl.getHeldOffers()[0].status, "stale");
    const flushed = ctrl.setHoldOffers(false);
    assert.equal(flushed.offers[0].status, "stale");

    // Mark ready + record visited range, then switch songId -> ready & visitedRanges reset cleanly!
    ctrl.recordListenEffort({ listenSec: 3, range: { t0: 0, t1: 4 } });
    ctrl.markReady("done song 1");
    assert.equal(ctrl.getState().ready, true);
    assert.equal(ctrl.getState().visitedRanges.length, 1);

    await ctrl.bindAudio({ songId: "s_second_song", durationSec: 16 });
    assert.equal(ctrl.getState().ready, false);
    assert.equal(ctrl.getState().readyAtSeq, null);
    assert.equal(ctrl.getState().visitedRanges.length, 0);
    assert.equal(ctrl.canUndo(), false);
    assert.equal(ctrl.canRedo(), false);

    // Deterministic Undo/Redo across edits, ready, persistence, and branch clearing
    const e1 = ctrl.applyEdit({ op: "upsert_beat", beat: { id: "u1", timeSec: 1.0, source: "user" } });
    const e2 = ctrl.applyEdit({ op: "upsert_beat", beat: { id: "u2", timeSec: 2.0, source: "user" } });
    assert.deepEqual(computeUndoRedoTargets(ctrl.getState()).undoStack, [e1.event.id, e2.event.id]);

    const uRes1 = ctrl.undo();
    assert.equal(uRes1.ok, true);
    assert.equal(ctrl.getState().beats.has("u2"), false);
    assert.equal(ctrl.canUndo(), true);
    assert.equal(ctrl.canRedo(), true);

    // Persist to NDJSON and restore in a brand-new controller -> Undo/Redo stack survives 100%!
    const restored = new SessionController({
      provider: new ScriptedMockProvider(),
      initialEvents: parseTraceNdjson(ctrl.exportNdjson()),
    });
    assert.equal(restored.canUndo(), true);
    assert.equal(restored.canRedo(), true);

    const rRes1 = restored.redo();
    assert.equal(rRes1.ok, true);
    assert.equal(restored.getState().beats.has("u2"), true);
    assert.equal(restored.canRedo(), false);

    // Undo once, then make a new edit -> clears redoStack (branch cleared!)
    restored.undo();
    assert.equal(restored.canRedo(), true);
    restored.applyEdit({ op: "upsert_beat", beat: { id: "u3", timeSec: 3.0, source: "user" } });
    assert.equal(restored.canRedo(), false);
    assert.equal(restored.redo().ok, false);
  });

  it("[Astra #9] handles coincident beats, sub-segment free Spans, same-segment GridFit preference, exact beatCount sum, draft unbeats, and batch sibling detection", async () => {
    // 1. Coincident beats + sub-segment free Span + exact sum(beatCount) === uniqueBeats.length
    const segments = [
      { id: "seg_a", t0: 0, t1: 5.0, regime: "auto", kept: false, source: "draft", revision: 1 },
      { id: "seg_b", t0: 5.0, t1: 10.0, regime: "auto", kept: false, source: "draft", revision: 1 },
    ];
    const spans = [
      { id: "sp_free", segmentId: "seg_a", t0: 2.0, t1: 4.5, kind: "free", source: "user", revision: 1 },
    ];
    const beats = [
      { id: "b0", timeSec: 0.5, source: "draft", revision: 1 },
      { id: "b0_dup", timeSec: 0.5 + 1e-7, source: "user", revision: 1 }, // coincident!
      { id: "b1", timeSec: 1.0, source: "draft", revision: 1 },
      { id: "b2", timeSec: 1.5, source: "draft", revision: 1 },
      // Inside sp_free [2.0, 4.5): rubato 2.0 -> 2.65 -> 3.2 -> 3.9 -> 4.996
      { id: "f0", timeSec: 2.0, source: "user", revision: 1 },
      { id: "f1", timeSec: 2.65, source: "user", revision: 1 },
      { id: "f2", timeSec: 3.2, source: "user", revision: 1 },
      { id: "f3", timeSec: 3.9, source: "user", revision: 1 },
      // Near end of seg_a (4.996), followed by b_seg1 (5.011) in seg_b
      { id: "b_edge_a", timeSec: 4.996, source: "draft", revision: 1 },
      { id: "b_edge_b", timeSec: 5.011, source: "draft", revision: 1 },
      { id: "b_b2", timeSec: 5.511, source: "draft", revision: 1 },
    ];

    const proj = projectRedLines(beats, segments, { spans });
    for (const rl of proj.redLines) {
      assert.ok(Number.isFinite(rl.beatLengthMs) && rl.beatLengthMs > 0);
      assert.ok(Number.isFinite(rl.bpm) && rl.bpm > 0);
      assert.ok(rl.beatCount >= 1);
    }
    assert.ok(proj.redLines.some((rl) => rl.offsetMs === 2000 && rl.kind === "free_pulse"));
    const totalBeatCount = proj.redLines.reduce((acc, rl) => acc + rl.beatCount, 0);
    assert.equal(totalBeatCount, beats.length - 1); // 11 beats minus 1 coalesced coincident duplicate = 10!

    // 2. Same-segment beat preference in computeGridFit:
    // Sound at 5.002 is inside seg_b [5.0, 10.0). Beat b_edge_a at 4.996 (in seg_a) is 6ms away,
    // while beat b_edge_b at 5.011 (in seg_b) is 9ms away (<= 12ms tolMs). Must match b_edge_b ("on_grid")!
    const fit = computeGridFit(
      [{ id: "s_boundary", timeSec: 5.002, source: "draft", revision: 1 }],
      beats,
      segments,
    );
    assert.equal(fit[0].status, "on_grid");
    assert.equal(fit[0].nearestDivisor, 1);
    assert.equal(fit[0].slotTimeSec, 5.011);
    assert.equal(fit[0].residualMs, -9);

    // 3. Sub-segment free Span suppresses false IBI jump penalty in rankInboxSpans
    let freeSpanState = createInitialState();
    freeSpanState = applyEvent(
      freeSpanState,
      ev(0, "audio_bound", { songId: "s_freespan_inbox", durationSec: 8 }, { kind: "host" }),
    );
    freeSpanState = applyEvent(
      freeSpanState,
      ev(
        1,
        "draft_loaded",
        {
          beats: [
            { id: "r0", timeSec: 1.0, confidence: 0.95, source: "user" },
            { id: "r1", timeSec: 1.5, confidence: 0.95, source: "user" },
            { id: "r2", timeSec: 2.3, confidence: 0.95, source: "user" },
          ],
        },
        { kind: "model" },
      ),
    );
    freeSpanState = applyEvent(
      freeSpanState,
      ev(2, "edit", { op: "set_range_free", t0: 0.5, t1: 3.5, free: true }),
    );
    const inboxInFree = rankInboxSpans(freeSpanState, { windowSec: 4 });
    for (const sp of inboxInFree) {
      assert.equal(sp.reason?.includes("sudden tempo shift"), false);
    }

    // 4. AnalysisDraft.unbeats preserved in SessionController.analyze() & detectSiblingConflicts batch parity
    const draftProvider = new ScriptedMockProvider({
      draft: {
        beats: [{ id: "db0", timeSec: 1.0, source: "draft" }],
        unbeats: [{ id: "du0", timeSec: 1.5, source: "user" }],
      },
    });
    const ctrl = new SessionController({ provider: draftProvider });
    const openOut = await ctrl.openSong({ songId: "s_draft_ub", durationSec: 8 });
    assert.equal(openOut.status, "applied");
    assert.equal(ctrl.getState().unbeats.length, 1);
    assert.equal(ctrl.getState().unbeats[0].id, "du0");

    const sibMap = detectSiblingConflicts([
      /** @type {any} */ ({
        id: "opt_a",
        batchId: "shared_batch",
        optionIndex: 0,
        writeScopes: [{ segmentId: "seg_0", t0: 0, t1: 2 }],
      }),
      /** @type {any} */ ({
        id: "opt_b",
        batchId: "shared_batch",
        optionIndex: 1,
        writeScopes: [{ segmentId: "seg_0", t0: 4, t1: 6 }],
      }),
    ]);
    assert.deepEqual(sibMap.get("opt_a"), [1]);
    assert.deepEqual(sibMap.get("opt_b"), [0]);
  });

  it("[Stage 7 #1] derives Undo/Redo from bottom-up effective-retract graph across arbitrary-depth selective retract-of-redo", async () => {
    const ctrl = new SessionController({ provider: new ScriptedMockProvider(), durationSec: 12 });

    // Add A (e0) -> Undo (r1 retracts e0) -> Redo (r2 retracts r1) -> selective retract(r2) (r3 retracts r2)
    const e0 = ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "beat_a", timeSec: 2.0, source: "user" },
    });
    assert.equal(ctrl.getState().beats.has("beat_a"), true);
    assert.equal(ctrl.canUndo(), true);
    assert.equal(ctrl.canRedo(), false);

    const r1 = ctrl.undo();
    assert.equal(r1.ok, true);
    assert.equal(ctrl.getState().beats.has("beat_a"), false);
    assert.equal(ctrl.canUndo(), false);
    assert.equal(ctrl.canRedo(), true);

    const r2 = ctrl.redo();
    assert.equal(r2.ok, true);
    assert.equal(ctrl.getState().beats.has("beat_a"), true);
    assert.equal(ctrl.canUndo(), true);
    assert.equal(ctrl.canRedo(), false);

    // Selectively retract the Redo event r2!
    const r3 = ctrl.retract(r2.event.id, "retract redo");
    assert.equal(r3.state.beats.has("beat_a"), false);
    assert.equal(ctrl.canUndo(), false);
    assert.equal(ctrl.canRedo(), true);
    assert.equal(computeUndoRedoTargets(ctrl.getState()).redoTargetEventId, r1.event.id);

    // Verify NDJSON round-trip preserves the exact effective redo target (r1)
    const restored = new SessionController({
      provider: new ScriptedMockProvider(),
      initialEvents: parseTraceNdjson(ctrl.exportNdjson()),
    });
    assert.equal(restored.canUndo(), false);
    assert.equal(restored.canRedo(), true);

    // Calling redo() targets r1 and restores beat_a!
    const r4 = restored.redo();
    assert.equal(r4.ok, true);
    assert.equal(r4.targetEventId, r1.event.id);
    assert.equal(restored.getState().beats.has("beat_a"), true);
    assert.equal(restored.canUndo(), true);
    assert.equal(restored.canRedo(), false);
    assert.equal(computeUndoRedoTargets(restored.getState()).undoTargetEventId, e0.event.id);

    // Deeper selective retract chain: retract r4 -> r1 becomes effective again -> beat_a absent, canRedo=true!
    const r5 = restored.retract(r4.event.id, "retract second redo");
    assert.equal(r5.state.beats.has("beat_a"), false);
    assert.equal(restored.canUndo(), false);
    assert.equal(restored.canRedo(), true);
  });

  it("[Stage 7 #2] replays standalone .jsonl from SessionController({ durationSec }) without re-passing durationSec and preserves pre-bindAudio beats", async () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider, durationSec: 10 });
    // Zero synthetic startup trace events!
    assert.equal(ctrl.getTrace().length, 0);

    const b1 = ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "b_keep_early", timeSec: 2.5, source: "user" },
    });
    ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "b_prune_late", timeSec: 9.5, source: "user" },
    });
    ctrl.applyEdit({
      op: "split_segment",
      segmentId: "seg_0",
      newSegmentId: "seg_1",
      timeSec: 4.0,
    });
    // Even if the very first event (carrying seedDurationSec) is retracted, standalone .jsonl still seeds 10s!
    ctrl.retract(b1.event.id, "undo b1");
    ctrl.applyEdit({
      op: "upsert_beat",
      beat: { id: "b_keep_2", timeSec: 3.0, source: "user" },
    });

    const parsed = parseTraceNdjson(ctrl.exportNdjson());
    const standaloneCtrl = new SessionController({
      provider: new ScriptedMockProvider(),
      initialEvents: parsed,
    });
    assert.equal(standaloneCtrl.getState().foldWarnings.length, 0);
    assert.deepEqual(
      standaloneCtrl.getState().segments.map((s) => [s.id, s.t0, s.t1]),
      [
        ["seg_0", 0, 4],
        ["seg_1", 4, 10],
      ],
    );
    assert.deepEqual(serializeState(standaloneCtrl.getState()), serializeState(ctrl.getState()));

    // Also test binding audio for the first time (prevAudio === null) when beats already exist in state:
    // preserves beats <= nextAudio.durationSec (3.0 <= 6.0) and prunes beats > nextAudio.durationSec (9.5 > 6.0)!
    await standaloneCtrl.bindAudio({ songId: "bound_first_time", durationSec: 6.0 });
    assert.equal(standaloneCtrl.getState().beats.has("b_keep_2"), true);
    assert.equal(standaloneCtrl.getState().beats.has("b_prune_late"), false);
    assert.deepEqual(
      standaloneCtrl.getState().segments.map((s) => [s.id, s.t0, s.t1]),
      [
        ["seg_0", 0, 4],
        ["seg_1", 4, 6],
      ],
    );

    // Also test unbound controller (durationSec: 0) with pre-placed beats before first audio_bound:
    const unbound = new SessionController({ provider: new ScriptedMockProvider() });
    unbound.applyEdit([
      { op: "upsert_beat", beat: { id: "ub_in", timeSec: 2.0, source: "user" } },
      { op: "upsert_beat", beat: { id: "ub_out", timeSec: 12.0, source: "user" } },
    ]);
    await unbound.bindAudio({ songId: "unbound_healed", durationSec: 8.0 });
    assert.equal(unbound.getState().beats.has("ub_in"), true);
    assert.equal(unbound.getState().beats.has("ub_out"), false);
    assert.deepEqual(
      unbound.getState().segments.map((s) => [s.id, s.t0, s.t1]),
      [["seg_0", 0, 8]],
    );
  });

  it("[Stage 7 #3] enforces validatePatchOffer stored-stale parity, held-offer _audioEpoch check, and zero-duration shrink cleanup", async () => {
    // 1. Overshadowed stored-stale offer: validatePatchOffer and acceptOffer agree 100%!
    const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
    await ctrl.bindAudio({ songId: "s_stale_parity", durationSec: 12, sampleRate: 44100 });
    ctrl.loadDraft({ beats: [{ id: "b0", timeSec: 0.5, source: "draft" }] });

    const r1 = await ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "local_rewrite" });
    const oldOfferId = r1.offers[0].id;
    assert.equal(ctrl.getState().offers.get(oldOfferId)?.status, "valid");

    // Newer generation overlapping [0, 4) marks oldOfferId as "stale" even with zero beat edits!
    const r2 = await ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "local_rewrite" });
    assert.equal(r2.status, "applied");
    const staleStored = ctrl.getState().offers.get(oldOfferId);
    assert.equal(staleStored?.status, "stale");

    const checkStale = validatePatchOffer(ctrl.getState(), staleStored);
    assert.equal(checkStale.valid, false);
    assert.equal(checkStale.status, "stale");
    assert.ok(checkStale.conflicts.length > 0);
    for (const c of checkStale.conflicts) {
      assertCleanHumanCopy(c.message, "stored-stale conflict.message");
    }
    assert.equal(ctrl.acceptOffer(oldOfferId).ok, false);

    // 2. Held-offer _audioEpoch invalidation on same-songId descriptor change (sampleRate / leadOffsetSec)!
    ctrl.setHoldOffers(true);
    const heldBatch = await ctrl.recompute({ scope: { t0: 4, t1: 8 }, reason: "local_rewrite" });
    assert.equal(heldBatch.status, "held");
    assert.equal(ctrl.getHeldOffers().length, 1);

    // Rebind SAME songId with updated sampleRate -> _audioEpoch increments -> held batch cleared!
    await ctrl.bindAudio({
      songId: "s_stale_parity",
      durationSec: 12,
      sampleRate: 48000,
      leadOffsetSec: 0.005,
    });
    assert.deepEqual(ctrl.getHeldOffers(), []);
    const flushedAfterRebind = ctrl.setHoldOffers(false);
    assert.equal(flushedAfterRebind.flushed, false);
    assert.deepEqual(flushedAfterRebind.offers, []);

    // 3. Zero-duration shrink clears beats, sounds, unbeats, and entityRevisions on both audio_bound and additive draft_loaded!
    let stateA = createInitialState();
    stateA = applyEvent(
      stateA,
      ev(0, "audio_bound", { songId: "s_zero", durationSec: 10 }, { kind: "host" }),
    );
    stateA = applyEvent(
      stateA,
      ev(1, "edit", [
        { op: "upsert_beat", beat: { id: "bz_1", timeSec: 2.0, source: "user" } },
        { op: "upsert_sound", sound: { id: "sz_1", timeSec: 3.0, source: "user" } },
        { op: "apply_unbeat_cue", id: "uz_1", timeSec: 2.5 },
      ]),
    );
    const shrunkAudio = applyEvent(
      stateA,
      ev(2, "audio_bound", { songId: "s_zero", durationSec: 0 }, { kind: "host" }),
    );
    assert.equal(shrunkAudio.beats.size, 0);
    assert.equal(shrunkAudio.sounds.size, 0);
    assert.equal(shrunkAudio.unbeats.length, 0);
    assert.equal(shrunkAudio.entityRevisions.size, 0);
    assert.equal(projectRedLines(shrunkAudio.beats, shrunkAudio.segments).redLines.length, 0);

    const shrunkDraft = applyEvent(
      stateA,
      ev(2, "draft_loaded", { replace: false, durationSec: 0, segments: [] }, { kind: "model" }),
    );
    assert.equal(shrunkDraft.beats.size, 0);
    assert.equal(shrunkDraft.sounds.size, 0);
    assert.equal(shrunkDraft.unbeats.length, 0);
    assert.equal(shrunkDraft.entityRevisions.size, 0);
  });
});
