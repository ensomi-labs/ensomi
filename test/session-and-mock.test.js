import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  ScriptedMockProvider,
  SessionController,
  foldTrace,
  parseTraceNdjson,
  serializeState,
} from "../index.js";

describe("Headless SessionController and ScriptedMockProvider", () => {
  it("keeps direct edits and statements decoupled from recompute (zero auto-recompute)", async () => {
    const provider = new ScriptedMockProvider();
    let now = 1_700_000_000_000;
    const controller = new SessionController({ provider, clock: () => (now += 100) });

    await controller.bindAudio({
      audioId: "synth_decoupled",
      durationSec: 16,
      clock: "source-audio-sec",
    });
    await controller.loadInitialDraft();

    controller.applyEdit({ op: "move_beat", id: "beat_0", t: 0.52, source: "user" });
    controller.upsertStatement({ id: "st_1", kind: "steady", span: { t0: 0, t1: 8 } });

    assert.equal(provider.recomputeCalls.length, 0);
    assert.equal(controller.getGeneration(), 0);
    assert.equal(controller.getState().beats.get("beat_0")?.t, 0.52);
    assert.equal(controller.getState().statements.get("st_1")?.active, true);
  });

  it("handles triplet-vs-reset mutually conflicting sibling offers cleanly", async () => {
    const provider = new ScriptedMockProvider();
    const controller = new SessionController({ provider });
    await controller.bindAudio({ audioId: "synth_sib", durationSec: 16, clock: "source-audio-sec" });
    await controller.loadInitialDraft();

    const res = await controller.triggerRecompute({
      scope: { t0: 4.0, t1: 6.0 },
      reason: "triplet_vs_reset",
    });
    assert.equal(res.status, "applied");
    assert.equal(res.offers.length, 2);
    assert.equal(res.offers[0].batchId, res.offers[1].batchId);
    assert.equal(res.offers[0].optionIndex, 0);
    assert.equal(res.offers[1].optionIndex, 1);

    // Accept option 0 -> option 1 is automatically superseded!
    const acc0 = controller.acceptOffer(res.offers[0].id);
    assert.equal(acc0.ok, true);
    assert.deepEqual(acc0.supersededOfferIds, [res.offers[1].id]);
    assert.equal(controller.getState().offers.get(res.offers[1].id)?.status, "superseded");

    // Trying to accept option 1 now returns structured sibling_conflict instead of overwriting
    const acc1 = controller.acceptOffer(res.offers[1].id);
    assert.equal(acc1.ok, false);
    assert.ok(acc1.conflicts.some((c) => c.kind === "sibling_conflict"));
  });

  it("supports forward_continuation, cross_region, and blocks hard_constraint_conflict", async () => {
    const provider = new ScriptedMockProvider();
    const controller = new SessionController({ provider });
    await controller.bindAudio({ audioId: "synth_scenarios", durationSec: 24, clock: "source-audio-sec" });
    await controller.loadInitialDraft();

    const fwd = await controller.triggerRecompute({
      scope: { t0: 2.0, t1: 4.0 },
      reason: "forward_continuation",
    });
    assert.equal(fwd.offers[0].scopeKind, "forward_continuation");
    assert.equal(fwd.offers[0].role, "extend");

    const cross = await controller.triggerRecompute({
      scope: { t0: 2.0, t1: 4.0 },
      reason: "cross_region",
    });
    assert.equal(cross.offers[0].scopeKind, "cross_region");
    assert.equal(cross.offers[0].role, "propagate");

    // Add a keep (freeze) constraint and trigger hard_constraint_conflict
    controller.upsertStatement({ id: "st_freeze", kind: "keep", span: { t0: 6.0, t1: 8.0 } });
    const conflictBatch = await controller.triggerRecompute({
      scope: { t0: 6.0, t1: 8.0 },
      reason: "hard_constraint_conflict",
    });
    assert.equal(conflictBatch.offers.length, 1);

    const attempt = controller.acceptOffer(conflictBatch.offers[0].id);
    assert.equal(attempt.ok, false);
    assert.ok(
      attempt.conflicts.some(
        (c) => c.kind === "constraint_violation" && c.statementIds?.includes("st_freeze"),
      ),
    );
  });

  it("suppresses rejected offer across recomputes and resurfaces after overlapping edit", async () => {
    const provider = new ScriptedMockProvider();
    const controller = new SessionController({ provider });
    await controller.bindAudio({ audioId: "synth_suppress", durationSec: 16, clock: "source-audio-sec" });
    controller.loadDraft({ beats: [], boundaries: [], sounds: [] });

    const scope = { t0: 2.0, t1: 4.0 };
    const first = await controller.triggerRecompute({ scope, reason: "local_rewrite" });
    assert.equal(first.offers.length, 1);
    const key1 = first.offers[0].semanticKey;

    controller.rejectOffer(first.offers[0].id, "not right");

    // Second recompute produces same semantic edit with fresh ephemeral IDs -> MUST be suppressed!
    const second = await controller.triggerRecompute({ scope, reason: "local_rewrite" });
    assert.equal(second.status, "applied");
    assert.equal(second.offers.length, 0);
    assert.equal(second.suppressedOffers.length, 1);
    assert.equal(second.suppressedOffers[0].semanticKey, key1);

    // Overlapping user edit clears suppression -> third recompute resurfaces!
    controller.applyEdit({ op: "upsert_beat", beat: { id: "u_b1", t: 3.0, source: "user" } });
    const third = await controller.triggerRecompute({ scope, reason: "local_rewrite" });
    assert.equal(third.status, "applied");
    assert.equal(third.offers.length, 1);
    assert.equal(third.suppressedOffers.length, 0);
  });

  it("enforces latest-wins preemptive cancellation via AbortSignal and generation guard", async () => {
    // Part A: Provider respects AbortSignal
    const abortableProvider = new ScriptedMockProvider({ delayMs: 60, ignoreAbort: false });
    const ctrlA = new SessionController({ provider: abortableProvider });
    await ctrlA.bindAudio({ audioId: "synth_cancel_a", durationSec: 16, clock: "source-audio-sec" });
    ctrlA.loadDraft({ beats: [] });

    const p1 = ctrlA.triggerRecompute({ scope: { t0: 0, t1: 4 }, reason: "slow_cancellable" });
    const p2 = ctrlA.triggerRecompute({ scope: { t0: 4, t1: 8 }, reason: "local_rewrite" });

    const [r1, r2] = await Promise.all([p1, p2]);
    assert.equal(r1.status, "cancelled");
    assert.equal(r1.offers.length, 0);
    assert.equal(abortableProvider.recomputeCalls[0].aborted, true);

    assert.equal(r2.status, "applied");
    assert.equal(r2.generation, 2);
    assert.equal(r2.offers.length, 1);
    assert.deepEqual(r2.offers[0].writeScope, { t0: 4, t1: 8 });

    // Part B: Provider IGNORES AbortSignal — generation guard MUST still discard the late promise!
    const stubbornProvider = new ScriptedMockProvider({
      script: [
        // Slow call 1 ignores signal and resolves after 50ms
        async ({ request }) => {
          await new Promise((r) => setTimeout(r, 50));
          return {
            batchId: "late_batch",
            offers: [
              {
                id: "late_offer",
                batchId: "late_batch",
                providerId: "stubborn",
                providerKind: "mock",
                generation: request.generation,
                basisSeq: request.basisSeq,
                role: "rewrite",
                scopeKind: "local",
                readScope: { t0: 0, t1: 2 },
                writeScope: { t0: 0, t1: 2 },
                ops: { upsertBeats: [{ id: "late_b", t: 0.5, source: "model" }] },
                summary: "Stale late offer",
                consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0 },
                conflicts: [],
                semanticKey: "sk:late",
                status: "pending",
              },
            ],
          };
        },
        // Fast call 2 resolves immediately
        async ({ request }) => ({
          batchId: "fast_batch",
          offers: [
            {
              id: "fast_offer",
              batchId: "fast_batch",
              providerId: "stubborn",
              providerKind: "mock",
              generation: request.generation,
              basisSeq: request.basisSeq,
              role: "rewrite",
              scopeKind: "local",
              readScope: { t0: 2, t1: 4 },
              writeScope: { t0: 2, t1: 4 },
              ops: { upsertBeats: [{ id: "fast_b", t: 2.5, source: "model" }] },
              summary: "Fresh fast offer",
              consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0 },
              conflicts: [],
              semanticKey: "sk:fast",
              status: "pending",
            },
          ],
        }),
      ],
    });

    const ctrlB = new SessionController({ provider: stubbornProvider });
    await ctrlB.bindAudio({ audioId: "synth_cancel_b", durationSec: 16, clock: "source-audio-sec" });
    ctrlB.loadDraft({ beats: [] });

    const lateP = ctrlB.triggerRecompute({ scope: { t0: 0, t1: 2 }, reason: "slow" });
    const fastP = ctrlB.triggerRecompute({ scope: { t0: 2, t1: 4 }, reason: "fast" });

    const [lateRes, fastRes] = await Promise.all([lateP, fastP]);
    assert.equal(fastRes.status, "applied");
    assert.equal(lateRes.status, "superseded");
    assert.equal(ctrlB.getState().offers.has("late_offer"), false);
    assert.equal(ctrlB.getState().offers.has("fast_offer"), true);
  });

  it("holds incoming offers during audition lock, tracks effort ledger, and round-trips NDJSON trace", async () => {
    const provider = new ScriptedMockProvider();
    let now = 10_000;
    const controller = new SessionController({
      provider,
      clock: () => {
        now += 250;
        return now;
      },
    });
    await controller.bindAudio({ audioId: "synth_audition", durationSec: 16, clock: "source-audio-sec" });
    await controller.loadInitialDraft();

    controller.setAuditionLock(true);
    assert.equal(controller.isAuditionLocked(), true);

    const buffered = await controller.triggerRecompute({
      scope: { t0: 2.0, t1: 4.0 },
      reason: "local_rewrite",
    });
    assert.equal(buffered.status, "buffered");
    assert.equal(controller.getPendingOffers().length, 0);
    assert.equal(controller.getBufferedOffers().length, 1);

    controller.recordListenEffort({ listenSec: 6.0, span: { t0: 2.0, t1: 4.0 } });

    const flushed = controller.setAuditionLock(false);
    assert.equal(flushed.flushed, true);
    assert.equal(flushed.offers.length, 1);
    assert.equal(controller.getPendingOffers().length, 1);
    assert.equal(controller.getBufferedOffers().length, 0);

    const ledger = controller.getEffortLedger();
    assert.equal(ledger.listenSec, 6.0);
    assert.ok(ledger.actionCount >= 1);
    assert.ok(ledger.waitSec > 0);

    // Export NDJSON, re-parse, re-fold, and verify exact equivalence
    const ndjson = controller.exportNdjson();
    const replayed = foldTrace(parseTraceNdjson(ndjson));
    assert.deepEqual(serializeState(replayed), serializeState(controller.getState()));
  });
});
