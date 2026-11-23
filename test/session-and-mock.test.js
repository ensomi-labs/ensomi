import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  ScriptedMockProvider,
  SessionController,
  foldTrace,
  parseTraceNdjson,
  serializeState,
} from "../index.js";

describe("3-layer SessionController lifecycles, ephemeral micro_assist, and mid-flight offer validation", () => {
  it("isolates song_open, micro_assist, and heavy_recompute slots so suggestSnaps never aborts recompute or analyze and writes zero trace events", async () => {
    let resolveAnalyze;
    let resolveRecompute;

    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request, signal }) => {
          await new Promise((r) => {
            resolveRecompute = r;
          });
          if (signal?.aborted) throw new Error("recompute should NOT have been aborted");
          return new ScriptedMockProvider().recompute({}, state, request);
        },
      ],
    });
    const origAnalyze = provider.analyze.bind(provider);
    provider.analyze = async (evidence, signal) => {
      await new Promise((r) => {
        resolveAnalyze = r;
      });
      if (signal?.aborted) throw new Error("analyze should NOT have been aborted");
      return origAnalyze(evidence, signal);
    };

    const ctrl = new SessionController({
      provider,
      triggerPolicy: {
        song_open: { mode: "manual" },
        micro_assist: { debounceMs: 0 },
      },
    });
    await ctrl.bindAudio({ songId: "song_iso", durationSec: 24 });

    // Start analyze (song_open) in-flight
    const pAnalyze = ctrl.analyze();
    await new Promise((r) => setTimeout(r, 5));
    assert.equal(ctrl.getLayerState("song_open").inFlight, true);

    // Fire 2 rapid suggestSnaps (micro_assist) while analyze is in-flight
    const snapRes1 = await ctrl.suggestSnaps({ timeSec: 2.0, windowSec: 0.08 });
    assert.equal(snapRes1.status, "ok");
    assert.ok(snapRes1.candidates.length > 0);
    // analyze MUST still be running!
    assert.equal(ctrl.getLayerState("song_open").inFlight, true);

    resolveAnalyze();
    const analyzeRes = await pAnalyze;
    assert.equal(analyzeRes.status, "applied");

    const traceLenBeforeSnaps = ctrl.getTrace().length;
    const ndjsonBeforeSnaps = ctrl.exportNdjson();

    // Start heavy_recompute in-flight
    const pRecompute = ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "local_rewrite" });
    await new Promise((r) => setTimeout(r, 5));
    assert.equal(ctrl.getLayerState("heavy_recompute").inFlight, true);
    const traceLenDuringRecompute = ctrl.getTrace().length; // +1 for signal

    // Run multiple suggestSnaps calls while heavy_recompute is in-flight!
    const snapDuringA = await ctrl.suggestSnaps({ timeSec: 4.5, windowSec: 0.06 });
    const snapDuringB = await ctrl.suggestSnaps({ timeSec: 8.0, windowSec: 0.06 });
    assert.equal(snapDuringA.status, "ok");
    assert.equal(snapDuringB.status, "ok");

    // ZERO trace events written by suggestSnaps!
    assert.equal(ctrl.getTrace().length, traceLenDuringRecompute);
    assert.equal(ctrl.getLayerState("heavy_recompute").inFlight, true);

    resolveRecompute();
    const recRes = await pRecompute;
    assert.equal(recRes.status, "applied");
    assert.equal(recRes.offers.length, 1);

    // Extra suggestSnaps after completion still writes ZERO trace events
    const traceSnapshot = ctrl.exportNdjson();
    await ctrl.suggestSnaps({ timeSec: 1.0, windowSec: 0.05 });
    assert.equal(ctrl.exportNdjson(), traceSnapshot);
    assert.notEqual(ndjsonBeforeSnaps, traceSnapshot);
    assert.ok(ctrl.getTrace().length > traceLenBeforeSnaps);
  });

  it("preempts older calls on the SAME layer, coalesces debounced micro_assist bursts, and aborts all 3 layers on songId switch or close()", async () => {
    const provider = new ScriptedMockProvider({ snapDelayMs: 5, delayMs: 50 });
    const ctrl = new SessionController({
      provider,
      triggerPolicy: {
        song_open: { mode: "manual" },
        micro_assist: { debounceMs: 25 },
      },
    });
    await ctrl.bindAudio({ songId: "song_1", durationSec: 16 });
    ctrl.loadDraft({ beats: [], sounds: [] });

    // Burst of 3 suggestSnaps within 25ms debounce window -> first 2 cancel before hitting provider!
    const s1 = ctrl.suggestSnaps({ timeSec: 1.0 });
    const s2 = ctrl.suggestSnaps({ timeSec: 1.2 });
    const s3 = ctrl.suggestSnaps({ timeSec: 1.4 });
    const [r1, r2, r3] = await Promise.all([s1, s2, s3]);

    assert.equal(r1.status, "superseded");
    assert.equal(r2.status, "superseded");
    assert.equal(r3.status, "ok");
    assert.equal(provider.suggestSnapsCalls.length, 1);
    assert.equal(provider.suggestSnapsCalls[0].timeSec, 1.4);

    // Same-layer preemption on heavy_recompute
    const h1 = ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "slow_cancellable" });
    const h2 = ctrl.recompute({ scope: { t0: 4, t1: 8 }, reason: "local_rewrite" });
    const [hr1, hr2] = await Promise.all([h1, h2]);
    assert.equal(hr1.status, "superseded");
    assert.equal(hr2.status, "applied");
    assert.equal(provider.recomputeCalls[0].aborted, true);

    // Switching songId aborts all in-flight layers cleanly
    const hSong1 = ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "slow_cancellable" });
    const snapSong1 = ctrl.suggestSnaps({ timeSec: 2.0 });
    await ctrl.bindAudio({ songId: "song_2", durationSec: 20 });

    const [abortedH, abortedS] = await Promise.all([hSong1, snapSong1]);
    assert.equal(abortedH.status, "superseded");
    assert.equal(abortedS.status, "superseded");
    assert.equal(ctrl.getState().audio?.songId, "song_2");
    assert.equal(ctrl.getState().offers.size, 0);

    // close() aborts all layers and prevents further calls
    const pendingBeforeClose = ctrl.recompute({ scope: { t0: 0, t1: 4 }, reason: "slow_cancellable" });
    ctrl.close();
    assert.equal(ctrl.isClosed(), true);
    const closedRes = await pendingBeforeClose;
    assert.equal(closedRes.status, "cancelled");
  });

  it("validates arriving offers independently after mid-flight local edits and supports setHoldOffers(boolean)", async () => {
    let releaseMidFlight;
    const provider = new ScriptedMockProvider({
      script: [
        async ({ state, request }) => {
          const b0 = state.beats.get("b_local");
          const bCross = state.beats.get("b_cross");
          await new Promise((r) => {
            releaseMidFlight = r;
          });
          return {
            batchId: "batch_mixed",
            offers: [
              {
                id: "off_local",
                batchId: "batch_local_only",
                providerId: "ensomi.scripted",
                providerKind: "mock",
                generation: request.generation,
                basisSeq: request.basisSeq,
                expectedRevisions: { b_local: state.entityRevisions.get("b_local") },
                writeScopes: [{ segmentId: "seg_a", t0: 0, t1: 8 }],
                scopeKind: "local",
                title: "Align intro beat",
                consequenceSummary: "Nudges 1 beat (up to 15 ms)",
                advisoryNotes: [],
                ops: {
                  upsertBeats: [{ id: "b_local", timeSec: b0.timeSec + 0.015, source: "offer" }],
                },
                consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0, maxShiftMs: 15, segmentChanges: 0 },
                conflicts: [],
                semanticKey: "sk:local",
                status: "valid",
              },
              {
                id: "off_cross",
                batchId: "batch_cross_only",
                providerId: "ensomi.scripted",
                providerKind: "mock",
                generation: request.generation,
                basisSeq: request.basisSeq,
                expectedRevisions: { b_cross: state.entityRevisions.get("b_cross") },
                writeScopes: [{ segmentId: "seg_b", t0: 8, t1: 16 }],
                scopeKind: "cross_region",
                title: "Apply timing to chorus",
                consequenceSummary: "Nudges 1 beat (up to 15 ms)",
                advisoryNotes: [],
                ops: {
                  upsertBeats: [{ id: "b_cross", timeSec: bCross.timeSec + 0.015, source: "offer" }],
                },
                consequences: { beatsAdded: 0, beatsMoved: 1, beatsRemoved: 0, maxShiftMs: 15, segmentChanges: 0 },
                conflicts: [],
                semanticKey: "sk:cross",
                status: "valid",
              },
            ],
          };
        },
      ],
    });

    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ songId: "song_midflight", durationSec: 16 });
    ctrl.loadDraft({
      segments: [
        { id: "seg_a", t0: 0, t1: 8, regime: "auto", kept: false },
        { id: "seg_b", t0: 8, t1: 16, regime: "auto", kept: false },
      ],
      beats: [
        { id: "b_local", timeSec: 1.0, source: "draft" },
        { id: "b_cross", timeSec: 10.0, source: "draft" },
      ],
    });

    // Hold offers while Alt-auditioning AND nudge b_local mid-flight!
    ctrl.setHoldOffers(true);
    assert.equal(ctrl.isHoldingOffers(), true);

    const pendingRec = ctrl.recompute({ scope: "song", reason: "song_refine" });
    await new Promise((r) => setTimeout(r, 5));

    // User nudges b_local in seg_a while heavy_recompute is mid-flight!
    ctrl.applyEdit({ op: "move_beat", id: "b_local", timeSec: 1.04 });

    releaseMidFlight();
    const heldRes = await pendingRec;
    assert.equal(heldRes.status, "held");
    assert.equal(ctrl.getValidOffers().length, 0); // Still held!
    assert.equal(ctrl.getHeldOffers().length, 2);

    // Release Alt-audition hold -> commits batch and validates each offer independently!
    const flushed = ctrl.setHoldOffers(false);
    assert.equal(flushed.flushed, true);

    const byId = new Map(flushed.offers.map((o) => [o.id, o]));
    // Overlapping local offer became "stale", while non-overlapping cross_region offer stayed "valid"!
    assert.equal(byId.get("off_local")?.status, "stale");
    assert.equal(byId.get("off_cross")?.status, "valid");

    assert.equal(ctrl.acceptOffer("off_local").ok, false);
    const accCross = ctrl.acceptOffer("off_cross");
    assert.equal(accCross.ok, true);
    assert.equal(ctrl.getState().beats.get("b_cross")?.timeSec, 10.015);

    // Full NDJSON round-trip equivalence
    const replayed = foldTrace(parseTraceNdjson(ctrl.exportNdjson()));
    assert.deepEqual(serializeState(replayed), serializeState(ctrl.getState()));
  });

  it("handles mutually exclusive sibling batches with optionIndex/optionCount and clean supersession", async () => {
    const provider = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider });
    await ctrl.bindAudio({ songId: "song_sib", durationSec: 16 });
    await ctrl.analyze();

    const res = await ctrl.recompute({
      scope: { t0: 4.0, t1: 6.0 },
      reason: "triplet_vs_reset",
    });
    assert.equal(res.status, "applied");
    assert.equal(res.offers.length, 2);
    assert.equal(res.offers[0].optionIndex, 0);
    assert.equal(res.offers[0].optionCount, 2);
    assert.equal(res.offers[1].optionIndex, 1);
    assert.equal(res.offers[1].optionCount, 2);

    const acc = ctrl.acceptOffer(res.offers[0].id);
    assert.equal(acc.ok, true);
    assert.deepEqual(acc.supersededOfferIds, [res.offers[1].id]);

    const superseded = ctrl.getState().offers.get(res.offers[1].id);
    assert.equal(superseded?.status, "superseded");
    assert.equal(ctrl.acceptOffer(res.offers[1].id).ok, false);
  });
});
