import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  TRACE_SCHEMA_VERSION,
  applyEvent,
  compileConstraints,
  computeSemanticKey,
  createInitialState,
  isOfferSuppressed,
  validatePatchOffer,
} from "../index.js";

function ev(seq, type, payload, author = { kind: "user" }) {
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq,
    id: `${type}_${seq}`,
    type,
    author,
    payload,
  };
}

describe("Soft-first cue compilation and PatchOffer validation", () => {
  it("treats user beats & sounds directly as cues without ghost duplicates and scales unbeat repulsion by local IBI", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "song_cues", durationSec: 30 }, { kind: "host" }),
    );
    // Fast 240 BPM section (IBI = 0.250s -> 0.25 * 0.250 = 0.0625s) in [0, 5),
    // Steady 120 BPM section (IBI = 0.500s -> min(0.080, 0.125) = 0.080s) in [10, 15),
    // Sparse isolated region at 25.0s (< 2 beats -> fallback 0.050s)
    state = applyEvent(
      state,
      ev(
        1,
        "draft_loaded",
        {
          beats: [
            { id: "fast_0", timeSec: 1.0, source: "draft" },
            { id: "fast_1", timeSec: 1.25, source: "draft" },
            { id: "fast_2", timeSec: 1.5, source: "draft" },
            { id: "fast_3", timeSec: 1.75, source: "draft" },
            { id: "mid_0", timeSec: 11.0, source: "draft" },
            { id: "mid_1", timeSec: 11.5, source: "draft" },
            { id: "mid_2", timeSec: 12.0, source: "draft" },
          ],
        },
        { kind: "model" },
      ),
    );

    // User places a beat at 4.0, snaps it to 4.008, adds a user sound, and adds 3 unbeats
    state = applyEvent(
      state,
      ev(2, "edit", [
        { op: "upsert_beat", beat: { id: "u_beat", timeSec: 4.0, source: "user" } },
        {
          op: "snap_beat",
          id: "u_beat",
          timeSec: 4.008,
          snapEventId: "evt_kick",
          snapFamily: "percussion",
          snapRole: "transient_attack",
        },
        {
          op: "upsert_sound",
          sound: {
            id: "u_snd",
            timeSec: 8.25,
            family: "vocal",
            role: "p_center",
            source: "user",
          },
        },
        { op: "apply_unbeat_cue", id: "ub_fast", timeSec: 1.375 },
        { op: "apply_unbeat_cue", id: "ub_mid", timeSec: 11.25 },
        { op: "apply_unbeat_cue", id: "ub_sparse", timeSec: 25.0 },
        { op: "set_range_free", t0: 18, t1: 22, free: true },
        { op: "set_range_keep", t0: 0, t1: 0.8, kept: true },
      ]),
    );

    const compiled = compileConstraints(state);
    // Moving/snapping u_beat updated it in ONE place (zero ghost mistap cue at 4.0!)
    assert.equal(compiled.beatGuides.length, 1);
    assert.equal(compiled.beatGuides[0].timeSec, 4.008);
    assert.equal(compiled.beatGuides[0].snapEventId, "evt_kick");

    assert.equal(compiled.soundObservations.length, 1);
    assert.equal(compiled.soundObservations[0].timeSec, 8.25);

    const repById = new Map(compiled.unbeatRepulsions.map((r) => [r.unbeatId, r]));
    assert.equal(repById.get("ub_fast")?.repulsionRadiusSec, 0.0625);
    assert.equal(repById.get("ub_mid")?.repulsionRadiusSec, 0.08);
    assert.equal(repById.get("ub_sparse")?.repulsionRadiusSec, 0.05);

    assert.equal(compiled.freeIntervals.length, 1);
    assert.deepEqual([compiled.freeIntervals[0].t0, compiled.freeIntervals[0].t1], [18, 22]);
    assert.equal(compiled.keepLocks.length, 1);
    assert.deepEqual([compiled.keepLocks[0].t0, compiled.keepLocks[0].t1], [0, 0.8]);

    // Deleting u_beat removes the soft guide completely
    state = applyEvent(state, ev(3, "edit", { op: "remove_beat", id: "u_beat" }));
    assert.equal(compileConstraints(state).beatGuides.length, 0);
  });

  it("keeps soft-cue disagreements valid & acceptable with human-readable advisoryNotes while blocking hard violations", () => {
    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "song_soft", durationSec: 30 }, { kind: "host" }),
    );
    state = applyEvent(
      state,
      ev(
        1,
        "draft_loaded",
        {
          beats: [
            { id: "b_keep", timeSec: 2.0, source: "draft" },
            { id: "b_user", timeSec: 14.02, source: "user" },
          ],
        },
        { kind: "model" },
      ),
    );
    state = applyEvent(
      state,
      ev(2, "edit", [
        { op: "apply_unbeat_cue", id: "ub_1", timeSec: 22.41 },
        { op: "set_range_keep", t0: 1.0, t1: 3.0, kept: true },
      ]),
    );

    const userRev = state.entityRevisions.get("b_user");
    const keepRev = state.entityRevisions.get("b_keep");

    // 1. Soft disagreement: moves user beat by +28 ms (> 20 ms) AND places beat inside unbeat repulsion zone!
    const softOffer = {
      id: "off_soft",
      batchId: "batch_soft",
      semanticKey: "sk:soft",
      providerId: "ensomi.scripted",
      providerKind: "mock",
      generation: 1,
      basisSeq: state.lastSeq,
      expectedRevisions: { b_user: userRev },
      writeScopes: [{ segmentId: "seg_0", t0: 13.5, t1: 23.0 }],
      scopeKind: "local",
      title: "Refine phrase pulse",
      consequenceSummary: "Nudges 1 beat · adds 1 beat",
      advisoryNotes: [],
      ops: {
        upsertBeats: [
          { id: "b_user", timeSec: 14.048, source: "offer" },
          { id: "b_added_near_unbeat", timeSec: 22.43, source: "offer" },
        ],
      },
      consequences: { beatsAdded: 1, beatsMoved: 1, beatsRemoved: 0, maxShiftMs: 28, segmentChanges: 0 },
      conflicts: [],
      status: "valid",
    };

    const softValidation = validatePatchOffer(state, softOffer);
    assert.equal(softValidation.valid, true);
    assert.equal(softValidation.status, "valid");
    assert.equal(softValidation.conflicts.length, 0);
    assert.ok(
      softValidation.advisoryNotes.some((n) =>
        n.includes("Moves your beat near 0:14.020 by +28 ms"),
      ),
    );
    assert.ok(
      softValidation.advisoryNotes.some((n) =>
        n.includes("Places a beat near your muted tick at 0:22.410"),
      ),
    );

    // Confirm softOffer actually accepts cleanly in the reducer!
    let afterSoft = applyEvent(
      state,
      ev(3, "offer_batch", { batchId: "batch_soft", offers: [softOffer] }, { kind: "model" }),
    );
    afterSoft = applyEvent(afterSoft, ev(4, "offer_accept", { offerId: "off_soft" }));
    assert.equal(afterSoft.offers.get("off_soft")?.status, "accepted");
    assert.equal(afterSoft.beats.get("b_user")?.timeSec, 14.048);

    // 2. Hard block #1: `protected_keep`
    const keepBlockedOffer = {
      ...softOffer,
      id: "off_keep",
      expectedRevisions: { b_keep: keepRev },
      writeScopes: [{ segmentId: "seg_0", t0: 1.5, t1: 2.5 }],
      ops: {
        upsertBeats: [{ id: "b_keep", timeSec: 2.05, source: "offer" }],
      },
    };
    const keepCheck = validatePatchOffer(state, keepBlockedOffer);
    assert.equal(keepCheck.valid, false);
    assert.equal(keepCheck.status, "invalid");
    assert.ok(keepCheck.conflicts.some((c) => c.kind === "protected_keep"));

    // 3. Hard block #2: `out_of_scope`
    const oosOffer = {
      ...softOffer,
      id: "off_oos",
      expectedRevisions: {},
      writeScopes: [{ segmentId: "seg_0", t0: 5.0, t1: 6.0 }],
      ops: {
        upsertBeats: [{ id: "b_outside", timeSec: 9.0, source: "offer" }],
      },
    };
    const oosCheck = validatePatchOffer(state, oosOffer);
    assert.equal(oosCheck.valid, false);
    assert.equal(oosCheck.status, "invalid");
    assert.ok(oosCheck.conflicts.some((c) => c.kind === "out_of_scope"));

    // 4. Hard block #3: `range_changed` when segment split overlaps writeScopes after basisSeq
    const stateSplit = applyEvent(
      state,
      ev(3, "edit", { op: "split_segment", segmentId: "seg_0", newSegmentId: "seg_after", timeSec: 18.0 }),
    );
    const rangeCheck = validatePatchOffer(stateSplit, softOffer);
    assert.equal(rangeCheck.valid, false);
    assert.equal(rangeCheck.status, "stale");
    assert.ok(rangeCheck.conflicts.some((c) => c.kind === "range_changed"));
  });

  it("computes ephemeral-ID-independent semanticKeys and clears rejection suppression on overlapping edit", () => {
    const scopes = [{ segmentId: "seg_0", t0: 4.0, t1: 6.0 }];
    const k1 = computeSemanticKey(scopes, {
      upsertBeats: [{ id: "ephem_1", timeSec: 4.5, source: "offer" }],
    });
    const k2 = computeSemanticKey(scopes, {
      upsertBeats: [{ id: "ephem_999", timeSec: 4.5, source: "offer" }],
    });
    assert.equal(k1, k2);

    let state = createInitialState();
    state = applyEvent(
      state,
      ev(0, "audio_bound", { songId: "s_sup", durationSec: 12 }, { kind: "host" }),
    );
    const offer = {
      id: "off_sup_1",
      batchId: "b_sup_1",
      semanticKey: k1,
      providerId: "ensomi.scripted",
      providerKind: "mock",
      generation: 1,
      basisSeq: 0,
      expectedRevisions: {},
      writeScopes: scopes,
      scopeKind: "local",
      title: "Add pickup beat",
      consequenceSummary: "Adds 1 beat",
      advisoryNotes: [],
      ops: { upsertBeats: [{ id: "ephem_1", timeSec: 4.5, source: "offer" }] },
      consequences: { beatsAdded: 1, beatsMoved: 0, beatsRemoved: 0, maxShiftMs: 0, segmentChanges: 0 },
      conflicts: [],
      status: "valid",
    };

    state = applyEvent(state, ev(1, "offer_batch", { batchId: "b_sup_1", offers: [offer] }, { kind: "model" }));
    state = applyEvent(state, ev(2, "offer_reject", { offerId: "off_sup_1" }));
    assert.equal(isOfferSuppressed(state, { writeScopes: scopes, semanticKey: k2 }), true);

    // Non-overlapping edit keeps suppression
    state = applyEvent(state, ev(3, "edit", { op: "upsert_beat", beat: { id: "far", timeSec: 9.0, source: "user" } }));
    assert.equal(isOfferSuppressed(state, { writeScopes: scopes, semanticKey: k2 }), true);

    // Overlapping edit clears suppression
    state = applyEvent(state, ev(4, "edit", { op: "upsert_beat", beat: { id: "near", timeSec: 4.8, source: "user" } }));
    assert.equal(isOfferSuppressed(state, { writeScopes: scopes, semanticKey: k2 }), false);
  });
});
