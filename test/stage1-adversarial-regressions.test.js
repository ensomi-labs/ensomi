import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  applyEvent,
  assertOfferShape,
  bindOfferToBasis,
  canonicalJson,
  compileConstraints,
  computeEntityValueDigest,
  computeIntervalMusicalDigest,
  computeSnapshotDigest,
  computeUndoRedoTargets,
  createInitialState,
  EnsomiError,
  foldTrace,
  OFFER_OP_KINDS,
  previewOffer,
  projectTempoMap,
  SessionController,
  TRANSACTION_OP_KINDS,
  TRACE_SCHEMA_VERSION,
  toSnapshot,
  validateOffer,
} from "../index.js";
import { generateSyntheticDraft, ScriptedMockProvider } from "../testing.js";

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));

function makeEvent(seq, id, type, payload, author = { kind: "user" }) {
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq,
    id,
    type,
    author,
    payload,
  };
}

function createBaseState(durationUs = 20_000_000, draftPayload = undefined) {
  let state = createInitialState();
  state = applyEvent(
    state,
    makeEvent(0, "ev_0", "audio_bound", { audioId: "song_reg", durationUs }, { kind: "host" }),
  );
  if (draftPayload) {
    state = applyEvent(
      state,
      makeEvent(1, "ev_1", "draft_loaded", draftPayload, { kind: "model" }),
    );
  }
  return state;
}

describe("Stage 1 Adversarial Audit Regressions (P0-1..P0-4, P1-1..P1-6, P2-1..P2-2)", () => {
  describe("P0-1: Offer writeRanges scope escape & split/merge policy tails", () => {
    it("blocks intra-transaction created boundary/segment escape and boundary gap sweeps", () => {
      const state = createBaseState(20_000_000);

      // 1. Split at 2s inside [1s, 3s), move newly created boundary to 18s, flip [18s, 20s) to "break"
      const escapeOffer = bindOfferToBasis(state, {
        id: "off_intra_escape",
        batchId: "b_p01",
        kind: "segmentation",
        writeRanges: [{ startUs: 1_000_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: state.segments[0].id,
            atUs: 2_000_000,
            newBoundaryId: "bnd_esc",
            newSegmentId: "seg_esc",
          },
          {
            op: "move_boundary",
            boundaryId: "bnd_esc",
            atUs: 18_000_000,
          },
          {
            op: "set_segment_regime",
            segmentId: "seg_esc",
            regime: "break",
          },
        ],
        notes: [],
      });
      assert.equal(escapeOffer.status, "invalid");
      assert.ok(escapeOffer.conflicts.some((c) => c.kind === "out_of_scope"));

      // 2. Transient split -> move outside writeRanges -> merge back is still caught by live per-step check!
      const transientSweep = bindOfferToBasis(state, {
        id: "off_transient_sweep",
        batchId: "b_p01",
        kind: "segmentation",
        writeRanges: [{ startUs: 1_000_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: state.segments[0].id,
            atUs: 2_000_000,
            newBoundaryId: "bnd_tmp",
            newSegmentId: "seg_tmp",
          },
          {
            op: "move_boundary",
            boundaryId: "bnd_tmp",
            atUs: 18_000_000,
          },
          {
            op: "merge_segments",
            leftSegmentId: state.segments[0].id,
            rightSegmentId: "seg_tmp",
          },
        ],
        notes: [],
      });
      assert.equal(transientSweep.status, "invalid");
      assert.ok(transientSweep.conflicts.some((c) => c.kind === "out_of_scope"));

      // 3. Disjoint writeRanges [[1s, 3s), [17s, 19s)] sweeping boundary across uncovered [3s, 17s)
      const splitState = applyEvent(
        state,
        makeEvent(1, "ev_split", "edit", {
          op: "split_segment",
          segmentId: state.segments[0].id,
          atUs: 2_000_000,
          newBoundaryId: "bnd_2s",
          newSegmentId: "seg_right",
        }),
      );
      const gapSweepOffer = bindOfferToBasis(splitState, {
        id: "off_gap_sweep",
        batchId: "b_p01",
        kind: "segmentation",
        writeRanges: [
          { startUs: 1_000_000, endUs: 3_000_000 },
          { startUs: 17_000_000, endUs: 19_000_000 },
        ],
        ops: [{ op: "move_boundary", boundaryId: "bnd_2s", atUs: 18_000_000 }],
        notes: [],
      });
      assert.equal(gapSweepOffer.status, "invalid");
      assert.ok(gapSweepOffer.conflicts.some((c) => c.kind === "out_of_scope"));
    });

    it("allows pure structural split_segment inside writeRanges while blocking rightRegime/mergedRegime tail escapes", () => {
      const state = createBaseState(20_000_000);
      const rootSegId = state.segments[0].id;

      // Pure structural split at 2s inside [1s, 3s) without changing effective policy on [2s, 20s) -> VALID
      const pureSplit = bindOfferToBasis(state, {
        id: "off_pure_split",
        batchId: "b_p01b",
        kind: "segmentation",
        writeRanges: [{ startUs: 1_000_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: rootSegId,
            atUs: 2_000_000,
            newBoundaryId: "bnd_ok",
            newSegmentId: "seg_ok",
          },
        ],
        notes: [],
      });
      assert.equal(pureSplit.status, "valid");

      // 1-op split_segment with rightRegime: "break" mutating [2s, 20s) outside [1s, 3s) -> out_of_scope
      const tailRegimeSplit = bindOfferToBasis(state, {
        id: "off_tail_regime",
        batchId: "b_p01b",
        kind: "segmentation",
        writeRanges: [{ startUs: 1_000_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: rootSegId,
            atUs: 2_000_000,
            newBoundaryId: "bnd_bad1",
            newSegmentId: "seg_bad1",
            rightRegime: "break",
          },
        ],
        notes: [],
      });
      assert.equal(tailRegimeSplit.status, "invalid");
      assert.ok(tailRegimeSplit.conflicts.some((c) => c.kind === "out_of_scope"));

      // 1-op split_segment with rightKinematics: "ramp" mutating [2s, 20s) outside [1s, 3s) -> out_of_scope
      const tailKinSplit = bindOfferToBasis(state, {
        id: "off_tail_kin",
        batchId: "b_p01b",
        kind: "segmentation",
        writeRanges: [{ startUs: 1_000_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: rootSegId,
            atUs: 2_000_000,
            newBoundaryId: "bnd_bad2",
            newSegmentId: "seg_bad2",
            rightKinematics: "ramp",
          },
        ],
        notes: [],
      });
      assert.equal(tailKinSplit.status, "invalid");
      assert.ok(tailKinSplit.conflicts.some((c) => c.kind === "out_of_scope"));

      // 1-op merge_segments across 6s inside [5s, 7s) with mergedRegime: "break" mutating [0, 20s) -> out_of_scope
      const twoSegState = createBaseState(20_000_000, {
        segments: [
          { id: "seg_0_6", startUs: 0, endUs: 6_000_000, regime: "auto" },
          { id: "seg_6_20", startUs: 6_000_000, endUs: 20_000_000, regime: "auto" },
        ],
        boundaries: [{ id: "bnd_6", atUs: 6_000_000 }],
      });
      const pureMerge = bindOfferToBasis(twoSegState, {
        id: "off_pure_merge",
        batchId: "b_p01b",
        kind: "segmentation",
        writeRanges: [{ startUs: 5_000_000, endUs: 7_000_000 }],
        ops: [
          {
            op: "merge_segments",
            leftSegmentId: "seg_0_6",
            rightSegmentId: "seg_6_20",
          },
        ],
        notes: [],
      });
      assert.equal(pureMerge.status, "valid");

      const tailMerge = bindOfferToBasis(twoSegState, {
        id: "off_tail_merge",
        batchId: "b_p01b",
        kind: "segmentation",
        writeRanges: [{ startUs: 5_000_000, endUs: 7_000_000 }],
        ops: [
          {
            op: "merge_segments",
            leftSegmentId: "seg_0_6",
            rightSegmentId: "seg_6_20",
            mergedRegime: "break",
          },
        ],
        notes: [],
      });
      assert.equal(tailMerge.status, "invalid");
      assert.ok(tailMerge.conflicts.some((c) => c.kind === "out_of_scope"));
    });
  });

  describe("P0-2: protected_verified & OfferChallenge authority invariants (all 5 sub-cases)", () => {
    it("(1-3) blocks unchallenged sub-tolUs drift, 0-us metadata injection, and verified Sound/Unbeat/SoundClass edits without mutating verdict", () => {
      let state = createBaseState(20_000_000, {
        segments: [
          { id: "seg_a", startUs: 0, endUs: 10_000_000, regime: "auto" },
          { id: "seg_b", startUs: 10_000_000, endUs: 20_000_000, regime: "auto" },
        ],
        boundaries: [{ id: "bnd_10", atUs: 10_000_000 }],
        beats: [{ id: "b_m", atUs: 2_000_000 }],
        sounds: [
          {
            id: "snd_1",
            startUs: 2_000_000,
            endUs: 2_100_000,
            endKind: "observed",
            anchors: [{ id: "anc_1", role: "transient_attack", atUs: 2_000_000 }],
            primaryAnchorId: "anc_1",
            salience: 0.9,
          },
        ],
      });

      state = applyEvent(
        state,
        makeEvent(2, "ev_user_cert", "edit", [
          { op: "upsert_beat", beat: { id: "b_u", atUs: 4_000_000 } }, // tolUs = 20_000
          { op: "place_unbeat", id: "ub_u", atUs: 5_000_000 },
          {
            op: "confirm_marks",
            targets: [
              { kind: "boundary", id: "bnd_10", expectedRevision: 1 }, // tolUs = 50_000
              { kind: "sound", id: "snd_1", expectedRevision: 1, facet: "occurrence" },
            ],
          },
          {
            op: "upsert_sound_class",
            soundClass: {
              id: "cls_u",
              exemplarSoundIds: ["snd_1"],
              matchMode: "hit",
            },
          },
        ]),
      );

      // Solver constraints still export human uncertainty tolUs (20ms beat, 50ms boundary)
      const constraints = compileConstraints(state);
      assert.equal(constraints.beatGuides.find((g) => g.beatId === "b_u")?.tolUs, 20_000);
      assert.equal(
        constraints.boundaryConstraints.find((g) => g.boundaryId === "bnd_10")?.tolUs,
        50_000,
      );

      const origBeatVerdict = state.beats.get("b_u").verdict;

      // Sub-case 1a: +10 ms shift (< 20 ms tolUs) on verified Beat -> blocked by protected_verified!
      const driftBeatOffer = bindOfferToBasis(state, {
        id: "off_drift_beat",
        batchId: "b_p02",
        kind: "pulse",
        writeRanges: [{ startUs: 3_000_000, endUs: 6_000_000 }],
        ops: [{ op: "move_beat", id: "b_u", atUs: 4_010_000 }],
        notes: [],
      });
      assert.equal(driftBeatOffer.status, "invalid");
      assert.ok(
        driftBeatOffer.conflicts.some(
          (c) => c.kind === "protected_verified" && c.targetKind === "beat" && c.targetId === "b_u",
        ),
      );
      // Offer scratch execution NEVER forged a new valueDigest on the human verdict!
      const prev = previewOffer(state, driftBeatOffer);
      const previewBeat = prev.nextSnapshot.beats.find((b) => b.id === "b_u");
      assert.deepEqual(previewBeat.verdict, origBeatVerdict);

      // Sub-case 1b: +20 ms shift (< 50 ms tolUs) on verified Boundary -> blocked by protected_verified!
      const driftBndOffer = bindOfferToBasis(state, {
        id: "off_drift_bnd",
        batchId: "b_p02",
        kind: "segmentation",
        writeRanges: [{ startUs: 9_000_000, endUs: 11_000_000 }],
        ops: [{ op: "move_boundary", boundaryId: "bnd_10", atUs: 10_020_000 }],
        notes: [],
      });
      assert.equal(driftBndOffer.status, "invalid");
      assert.ok(
        driftBndOffer.conflicts.some(
          (c) =>
            c.kind === "protected_verified" &&
            c.targetKind === "boundary" &&
            c.targetId === "bnd_10",
        ),
      );

      // Sub-case 2a: 0-us shift injecting `undefined -> boundTo` on verified Beat -> protected_verified!
      const injectBoundOffer = bindOfferToBasis(state, {
        id: "off_inject_bound",
        batchId: "b_p02",
        kind: "pulse",
        writeRanges: [{ startUs: 3_000_000, endUs: 6_000_000 }],
        ops: [
          {
            op: "snap_beat",
            id: "b_u",
            atUs: 4_000_000,
            boundTo: { soundId: "snd_1", anchorId: "anc_1", anchorRole: "transient_attack" },
          },
        ],
        notes: [],
      });
      assert.equal(injectBoundOffer.status, "invalid");
      assert.ok(injectBoundOffer.conflicts.some((c) => c.kind === "protected_verified"));

      // Sub-case 2b: 0-us shift injecting `undefined -> { fermata: true }` on verified Beat -> protected_verified!
      const injectStructOffer = bindOfferToBasis(state, {
        id: "off_inject_struct",
        batchId: "b_p02",
        kind: "pulse",
        writeRanges: [{ startUs: 3_000_000, endUs: 6_000_000 }],
        ops: [
          {
            op: "upsert_beat",
            beat: { id: "b_u", atUs: 4_000_000, structural: { fermata: true } },
          },
        ],
        notes: [],
      });
      assert.equal(injectStructOffer.status, "invalid");
      assert.ok(injectStructOffer.conflicts.some((c) => c.kind === "protected_verified"));

      // Sub-case 3: Unchallenged mutations of verified Sound (respecting certified facet!), Unbeat, and SoundClass -> protected_verified!
      // For `facet: "occurrence"`, refining `endUs` alone is permitted while altering `startUs` or `family` is blocked!
      const refineEndOnlyOffer = bindOfferToBasis(state, {
        id: "off_refine_end",
        batchId: "b_p02",
        kind: "sound_match",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [{ op: "update_sound", id: "snd_1", patch: { endUs: 2_180_000 } }],
        notes: [],
      });
      assert.equal(refineEndOnlyOffer.status, "valid");

      const mutSoundOffer = bindOfferToBasis(state, {
        id: "off_mut_snd",
        batchId: "b_p02",
        kind: "sound_match",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "update_sound",
            id: "snd_1",
            patch: { startUs: 1_990_000, characteristics: { family: "snare" } },
          },
        ],
        notes: [],
      });
      assert.equal(mutSoundOffer.status, "invalid");
      assert.ok(
        mutSoundOffer.conflicts.some(
          (c) => c.kind === "protected_verified" && c.targetKind === "sound",
        ),
      );

      const remUnbeatOffer = bindOfferToBasis(state, {
        id: "off_rem_ub",
        batchId: "b_p02",
        kind: "pulse",
        writeRanges: [{ startUs: 4_500_000, endUs: 5_500_000 }],
        ops: [{ op: "remove_unbeats", ids: ["ub_u"] }],
        notes: [],
      });
      assert.equal(remUnbeatOffer.status, "invalid");
      assert.ok(
        remUnbeatOffer.conflicts.some(
          (c) => c.kind === "protected_verified" && c.targetKind === "unbeat",
        ),
      );

      const mutClassOffer = bindOfferToBasis(state, {
        id: "off_mut_cls",
        batchId: "b_p02",
        kind: "sound_match",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [
          {
            op: "upsert_sound_class",
            soundClass: { id: "cls_u", exemplarSoundIds: ["snd_1"], matchMode: "voice" },
          },
        ],
        notes: [],
      });
      assert.equal(mutClassOffer.status, "invalid");
      assert.ok(
        mutClassOffer.conflicts.some(
          (c) => c.kind === "protected_verified" && c.targetKind === "sound_class",
        ),
      );
    });

    it("(4-5) enforces unique challengeId & (targetKind,targetId), validates authorizedChallenges strictly, and stamps confirm_replacement", async () => {
      const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
      await ctrl.openSong({ audioId: "song_p02_45", durationUs: 12_000_000 });

      ctrl.applyEdit([
        { op: "upsert_beat", beat: { id: "b_v1", atUs: 2_000_000 } },
        { op: "upsert_beat", beat: { id: "b_v2", atUs: 3_000_000 } },
      ]);
      const state = ctrl.getState();
      const b1 = state.beats.get("b_v1");
      const b2 = state.beats.get("b_v2");
      const d1 = computeEntityValueDigest("beat", b1);
      const d2 = computeEntityValueDigest("beat", b2);

      // Sub-case 4a: Duplicate challengeId rejected by bindOfferToBasis / assertOfferShape AND validateOffer
      assert.throws(
        () =>
          bindOfferToBasis(state, {
            id: "off_dup_cid",
            batchId: "b_dup",
            kind: "pulse",
            writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
            ops: [
              { op: "move_beat", id: "b_v1", atUs: 2_030_000 },
              { op: "move_beat", id: "b_v2", atUs: 3_030_000 },
            ],
            challenges: [
              {
                challengeId: "ch_same",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "r1",
              },
              {
                challengeId: "ch_same",
                targetKind: "beat",
                targetId: "b_v2",
                expectedRevision: b2.revision,
                expectedValueDigest: d2,
                reasonCode: "r2",
              },
            ],
            notes: [],
          }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );

      // Sub-case 4b: Duplicate (targetKind, targetId) rejected
      assert.throws(
        () =>
          bindOfferToBasis(state, {
            id: "off_dup_tgt",
            batchId: "b_dup",
            kind: "pulse",
            writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
            ops: [{ op: "move_beat", id: "b_v1", atUs: 2_030_000 }],
            challenges: [
              {
                challengeId: "ch_a",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "r1",
              },
              {
                challengeId: "ch_b",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "r2",
              },
            ],
            notes: [],
          }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );

      // Even if a raw un-normalized offer bypasses assertOfferShape into validateOffer, it returns invalid_op!
      const validOffer = bindOfferToBasis(state, {
        id: "off_ch_valid",
        batchId: "b_ch",
        kind: "pulse",
        writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
        ops: [{ op: "move_beat", id: "b_v1", atUs: 2_035_000 }],
        challenges: [
          {
            challengeId: "ch_only",
            targetKind: "beat",
            targetId: "b_v1",
            expectedRevision: b1.revision,
            expectedValueDigest: d1,
            reasonCode: "reanchor",
          },
        ],
        notes: [],
      });
      const tampered = {
        ...validOffer,
        challenges: [
          validOffer.challenges[0],
          { ...validOffer.challenges[0], targetId: "b_v2", expectedValueDigest: d2 },
        ],
      };
      assert.throws(
        () => assertOfferShape(tampered),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );
      const tamperedVal = validateOffer(state, tampered);
      assert.equal(tamperedVal.status, "invalid");
      assert.ok(tamperedVal.conflicts.some((c) => c.kind === "invalid_op"));

      // Surface validOffer via recompute so we can test Sub-case 5 (`authorizedChallenges`)
      ctrl._provider._script.push({
        batchId: "b_ch_live",
        offers: [
          {
            id: "off_ch_live",
            batchId: "b_ch_live",
            kind: "pulse",
            writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
            ops: [{ op: "move_beat", id: "b_v1", atUs: 2_035_000 }],
            challenges: [
              {
                challengeId: "ch_live",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "reanchor",
              },
            ],
            notes: [],
          },
        ],
      });
      await ctrl.recompute({ scope: { startUs: 1_000_000, endUs: 4_000_000 }, reason: "ch" });

      // Sub-case 5a: `[null]` in authorizedChallenges throws EnsomiError("INVALID_OFFER"), NEVER TypeError!
      assert.throws(
        () =>
          ctrl.acceptOffer({
            offerIds: ["off_ch_live"],
            authorizedChallenges: /** @type {any} */ ([null]),
          }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );

      // Sub-case 5b: Duplicate (offerId, challengeId) (e.g. bogus extra entry alongside valid one) throws INVALID_OFFER!
      assert.throws(
        () =>
          ctrl.acceptOffer({
            offerIds: ["off_ch_live"],
            authorizedChallenges: [
              {
                offerId: "off_ch_live",
                challengeId: "ch_live",
                targetId: "b_v1",
                expectedValueDigest: d1,
                disposition: "confirm_replacement",
              },
              {
                offerId: "off_ch_live",
                challengeId: "ch_live",
                targetId: "b_v1",
                expectedValueDigest: d1,
                disposition: "clear_verdict",
              },
            ],
          }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );

      // Sub-case 5c: Valid `confirm_replacement` stamps eventId, via: "challenge_accept", and exact post-offer valueDigest!
      const acc = ctrl.acceptOffer({
        offerIds: ["off_ch_live"],
        authorizedChallenges: [
          {
            offerId: "off_ch_live",
            challengeId: "ch_live",
            targetId: "b_v1",
            expectedValueDigest: d1,
            disposition: "confirm_replacement",
          },
        ],
      });
      assert.equal(acc.ok, true);
      const acceptEvent = ctrl.getTrace()[ctrl.getTrace().length - 1];
      const updatedBeat = ctrl.getState().beats.get("b_v1");
      assert.equal(updatedBeat.atUs, 2_035_000);
      assert.equal(updatedBeat.verdict?.eventId, acceptEvent.id);
      assert.equal(updatedBeat.verdict?.kind, "confirmed");
      assert.equal(updatedBeat.verdict?.via, "challenge_accept");
      assert.equal(
        updatedBeat.verdict?.valueDigest,
        computeEntityValueDigest("beat", updatedBeat),
      );
    });
  });

  describe("P0-3: Exact retraction timeline theorem in foldTrace vs applyEvent", () => {
    it("throws on corrupt post-retract events (k > r) in BOTH applyEvent and foldTrace instead of swallowing into orphans", () => {
      const e0 = makeEvent(
        0,
        "e0",
        "audio_bound",
        { audioId: "p03_song", durationUs: 10_000_000 },
        { kind: "host" },
      );
      const e1 = makeEvent(1, "e1", "edit", {
        op: "upsert_beat",
        beat: { id: "b1", atUs: 2_000_000 },
      });
      const e2 = makeEvent(2, "e2", "edit", {
        op: "move_beat",
        id: "b1",
        atUs: 2_050_000,
      });
      const e3 = makeEvent(3, "e3", "retract", { targetEventId: "e1" });

      let live = createInitialState();
      for (const ev of [e0, e1, e2, e3]) {
        live = applyEvent(live, ev);
      }
      assert.equal(live.orphanedTransactions.length, 1);
      assert.equal(live.orphanedTransactions[0].eventId, "e2");

      // Corrupt events committed AFTER e3 (`seq = 4 > 3`) MUST throw identically in applyEvent and foldTrace!
      const badAfterRetractCases = [
        {
          ev: makeEvent(4, "e4_oob", "edit", {
            op: "upsert_beat",
            beat: { id: "b_oob", atUs: 99_000_000 },
          }),
          code: "OUT_OF_BOUNDS",
        },
        {
          ev: makeEvent(4, "e4_missing", "edit", {
            op: "move_beat",
            id: "b1",
            atUs: 3_000_000,
          }),
          code: "MISSING_ENTITY",
        },
        {
          ev: makeEvent(4, "e4_dup", "edit", [
            { op: "upsert_beat", beat: { id: "b_dup", atUs: 3_000_000 } },
            { op: "place_unbeat", id: "b_dup", atUs: 4_000_000 },
          ]),
          code: "DUPLICATE_ENTITY_ID",
        },
      ];

      for (const { ev, code } of badAfterRetractCases) {
        assert.throws(
          () => applyEvent(live, ev),
          (err) => err instanceof EnsomiError && err.code === code,
        );
        assert.throws(
          () => foldTrace([e0, e1, e2, e3, ev]),
          (err) => err instanceof EnsomiError && err.code === code,
        );
      }
    });

    it("handles redo-causes-conflict (t < k < r) identically across applyEvent and foldTrace", () => {
      const e0 = makeEvent(
        0,
        "r0",
        "audio_bound",
        { audioId: "p03_redo", durationUs: 12_000_000 },
        { kind: "host" },
      );
      const initState = applyEvent(createInitialState(), e0);
      const rootSegId = initState.segments[0].id;

      // e1 (seq 1): split rootSegId [0, 12s) at 6s -> rootSegId becomes [0, 6s), seg_r1 is [6s, 12s)
      const e1 = makeEvent(1, "e1_split6", "edit", {
        op: "split_segment",
        segmentId: rootSegId,
        atUs: 6_000_000,
        newBoundaryId: "bnd_6",
        newSegmentId: "seg_r1",
      });
      // e2 (seq 2): retract e1 -> rootSegId is [0, 12s) again!
      const e2 = makeEvent(2, "e2_undo_e1", "retract", { targetEventId: "e1_split6" });
      // e3 (seq 3): committed while e1 is retracted -> splits rootSegId [0, 12s) at 8s (VALID at seq 3!)
      const e3 = makeEvent(3, "e3_split8", "edit", {
        op: "split_segment",
        segmentId: rootSegId,
        atUs: 8_000_000,
        newBoundaryId: "bnd_8",
        newSegmentId: "seg_r2",
      });
      // e4 (seq 4): retract e2 (redo of e1!) -> active retraction (targetSeq=2, retractSeq=4) wraps k=3!
      // With e1 active again, rootSegId is [0, 6s), so e3's split of rootSegId at 8s is orphaned!
      const e4 = makeEvent(4, "e4_redo_e1", "retract", { targetEventId: "e2_undo_e1" });

      let inc = createInitialState();
      for (const ev of [e0, e1, e2, e3, e4]) {
        inc = applyEvent(inc, ev);
      }
      const folded = foldTrace([e0, e1, e2, e3, e4]);

      assert.equal(computeSnapshotDigest(inc), computeSnapshotDigest(folded));
      assert.equal(folded.orphanedTransactions.length, 1);
      assert.equal(folded.orphanedTransactions[0].eventId, "e3_split8");
      assert.equal(folded.orphanedTransactions[0].code, "ORPHANED_BY_RETRACT");
      assert.equal(folded.orphanedTransactions[0].causeCode, "INVALID_SEGMENT_EDIT");
      assert.deepEqual(
        folded.segments.map((s) => ({ id: s.id, startUs: s.startUs, endUs: s.endUs })),
        [
          { id: rootSegId, startUs: 0, endUs: 6_000_000 },
          { id: "seg_r1", startUs: 6_000_000, endUs: 12_000_000 },
        ],
      );
    });
  });

  describe("P0-4: Strict [0, 1] unit-interval validation & tuple/event JCS safety", () => {
    it("(1) rejects NaN, ±Infinity, < 0, > 1, and non-numbers across all confidence, salience, and score fields", async () => {
      const state = createBaseState(10_000_000, {
        beats: [{ id: "b_0", atUs: 2_000_000, confidence: 0.8 }],
      });

      for (const bad of [NaN, Infinity, -Infinity, -0.01, 1.01, /** @type {any} */ ("0.5")]) {
        // move_beat (even when atUs === existing.atUs!)
        assert.throws(
          () =>
            applyEvent(
              state,
              makeEvent(2, "ev_mb", "edit", {
                op: "move_beat",
                id: "b_0",
                atUs: 2_000_000,
                confidence: bad,
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );

        // snap_beat
        assert.throws(
          () =>
            applyEvent(
              state,
              makeEvent(2, "ev_sb", "edit", {
                op: "snap_beat",
                id: "b_0",
                atUs: 2_000_000,
                confidence: bad,
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );

        // upsert_beat confidence
        assert.throws(
          () =>
            applyEvent(
              state,
              makeEvent(2, "ev_ub", "edit", {
                op: "upsert_beat",
                beat: { id: "b_bad", atUs: 3_000_000, confidence: bad },
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );

        // Sound.salience & Sound.characteristics.confidence
        assert.throws(
          () =>
            applyEvent(
              state,
              makeEvent(2, "ev_snd_sal", "edit", {
                op: "upsert_sound",
                sound: {
                  id: "s_bad",
                  startUs: 1_000_000,
                  endUs: 1_100_000,
                  endKind: "observed",
                  anchors: [{ id: "a1", role: "transient_attack", atUs: 1_000_000 }],
                  primaryAnchorId: "a1",
                  salience: bad,
                },
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );

        assert.throws(
          () =>
            applyEvent(
              state,
              makeEvent(2, "ev_snd_char", "edit", {
                op: "upsert_sound",
                sound: {
                  id: "s_bad_c",
                  startUs: 1_000_000,
                  endUs: 1_100_000,
                  endKind: "observed",
                  anchors: [{ id: "a1", role: "transient_attack", atUs: 1_000_000 }],
                  primaryAnchorId: "a1",
                  salience: 0.5,
                  characteristics: { family: "kick", confidence: bad },
                },
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );

        // Offer.score
        assert.throws(
          () =>
            bindOfferToBasis(state, {
              id: "off_bad_score",
              batchId: "b_sc",
              kind: "pulse",
              writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
              ops: [{ op: "move_beat", id: "b_0", atUs: 2_010_000 }],
              score: bad,
              notes: [],
            }),
          (err) => err instanceof EnsomiError && err.code === "INVALID_FIELD",
        );
      }
    });

    it("(2) normalizes [undefined, 'id'] tuples in promote_range_to_segments to [null, 'id'] and enforces JCS on all TraceEvents", async () => {
      const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
      await ctrl.openSong({ audioId: "song_p04_2", durationUs: 12_000_000 });

      // Promote prefix [0, 5s) where left boundary/segment slot is `undefined`
      ctrl.applyEdit({
        op: "promote_range_to_segments",
        startUs: 0,
        endUs: 5_000_000,
        regime: "free",
        newBoundaryIds: [undefined, "bnd_5s"],
        newSegmentIds: [undefined, "seg_tail"],
      });

      const lastEv = ctrl.getTrace()[ctrl.getTrace().length - 1];
      assert.deepEqual(/** @type {any} */ (lastEv.payload).newBoundaryIds, [null, "bnd_5s"]);
      assert.deepEqual(/** @type {any} */ (lastEv.payload).newSegmentIds, [null, "seg_tail"]);

      // NDJSON export, JCS canonicalization, and Snapshot digest all succeed cleanly!
      const ndjson = ctrl.exportNdjson();
      assert.ok(ndjson.includes('"newBoundaryIds":[null,"bnd_5s"]'));
      assert.doesNotThrow(() => canonicalJson(ctrl.getTrace()));
      assert.equal(typeof computeSnapshotDigest(ctrl.getState()), "string");

      // Offer binding also normalizes [undefined, "id"] -> [null, "id"] cleanly!
      const offer = bindOfferToBasis(ctrl.getState(), {
        id: "off_prom_tuple",
        batchId: "b_tup",
        kind: "segmentation",
        writeRanges: [{ startUs: 5_000_000, endUs: 12_000_000 }],
        ops: [
          {
            op: "promote_range_to_segments",
            startUs: 8_000_000,
            endUs: 12_000_000,
            regime: "break",
            newBoundaryIds: ["bnd_8s", undefined],
            newSegmentIds: ["seg_8_12", undefined],
          },
        ],
        notes: [],
      });
      assert.equal(offer.status, "valid");
      assert.deepEqual(/** @type {any} */ (offer.ops[0]).newBoundaryIds, ["bnd_8s", null]);
      assert.deepEqual(/** @type {any} */ (offer.ops[0]).newSegmentIds, ["seg_8_12", null]);
      assert.doesNotThrow(() => canonicalJson(offer));

      // Arbitrary non-JCS values inside TraceEvent payloads are rejected by applyEvent!
      const s = ctrl.getState();
      assert.throws(
        () =>
          applyEvent(
            s,
            makeEvent(s.lastSeq + 1, "ev_bad_arr", "ready", {
              ready: true,
              extra: [1, undefined, 3],
            }),
          ),
        (err) => err instanceof EnsomiError && err.code === "INVALID_CANONICAL_VALUE",
      );
      assert.throws(
        () =>
          applyEvent(
            s,
            makeEvent(s.lastSeq + 1, "ev_bad_surr", "ready", {
              ready: true,
              note: "bad\ud800surrogate",
            }),
          ),
        (err) => err instanceof EnsomiError && err.code === "INVALID_CANONICAL_VALUE",
      );
    });
  });

  describe("P1-1: Approval (keep) footprint clamping, update_sound protection & left-split symmetry", () => {
    it("(1) clamps steady linear run t0Us >= sliceStartUs so beats in [8s, 12s) never bleed into keep [4s, 8s)", () => {
      let state = createBaseState(16_000_000, {
        segments: [
          { id: "seg_intro", startUs: 0, endUs: 4_000_000, regime: "auto", kinematics: "steady" },
          { id: "seg_keep", startUs: 4_000_000, endUs: 8_000_000, regime: "auto", kinematics: "steady" },
          { id: "seg_work", startUs: 8_000_000, endUs: 16_000_000, regime: "auto", kinematics: "steady" },
        ],
        boundaries: [
          { id: "bnd_4", atUs: 4_000_000 },
          { id: "bnd_8", atUs: 8_000_000 },
        ],
        beats: [
          { id: "bk_0", atUs: 4_000_000 },
          { id: "bk_1", atUs: 4_500_000 },
          { id: "bk_2", atUs: 5_000_000 },
          { id: "bw_0", atUs: 8_000_000 },
          { id: "bw_1", atUs: 8_500_000 },
          { id: "bw_2", atUs: 9_000_000 },
        ],
      });

      state = applyEvent(
        state,
        makeEvent(2, "ev_keep", "edit", {
          op: "set_segment_keep",
          segmentId: "seg_keep",
          keep: true,
          approvalId: "app_4_8",
        }),
      );

      const preKeepDigest = computeIntervalMusicalDigest(state, 4_000_000, 8_000_000);

      // Jitter middle beat bw_1 to 8.495s -> unconstrained OLS intercept `8_000_000 - 1_667 = 7_998_333`
      // would fall inside [4s, 8s) if not clamped to >= 8_000_000!
      const offer = bindOfferToBasis(state, {
        id: "off_steady_clamp",
        batchId: "b_p11",
        kind: "pulse",
        writeRanges: [{ startUs: 8_000_000, endUs: 12_000_000 }],
        ops: [{ op: "move_beat", id: "bw_1", atUs: 8_495_000 }],
        notes: [],
      });
      assert.equal(offer.status, "valid");

      const afterEdit = applyEvent(
        state,
        makeEvent(3, "ev_jitter", "edit", { op: "move_beat", id: "bw_1", atUs: 8_495_000 }),
      );
      const tm = projectTempoMap(afterEdit);
      assert.ok(tm.points.filter((p) => p.atUs > 4_000_000).every((p) => p.atUs >= 8_000_000));
      assert.equal(computeIntervalMusicalDigest(afterEdit, 4_000_000, 8_000_000), preKeepDigest);
    });

    it("(2) blocks update_sound overlapping keep and hashes full Sound musical fields in computeIntervalMusicalDigest", () => {
      let state = createBaseState(12_000_000, {
        sounds: [
          {
            id: "snd_k",
            startUs: 2_000_000,
            endUs: 2_200_000,
            endKind: "observed",
            anchors: [{ id: "a_k", role: "transient_attack", atUs: 2_002_000, sigmaUs: 2_000 }],
            primaryAnchorId: "a_k",
            salience: 0.75,
            blendStatus: "isolated",
            characteristics: { family: "snare", confidence: 0.8 },
          },
        ],
      });
      state = applyEvent(
        state,
        makeEvent(2, "ev_keep_span", "edit", {
          op: "set_range_keep",
          startUs: 1_000_000,
          endUs: 4_000_000,
          keep: true,
        }),
      );

      const d0 = computeIntervalMusicalDigest(state, 1_000_000, 4_000_000);

      // Offer attempting update_sound (salience / blendStatus / sigmaUs) inside keep -> protected_keep!
      const badUpdate = bindOfferToBasis(state, {
        id: "off_upd_keep",
        batchId: "b_p11_2",
        kind: "sound_match",
        writeRanges: [{ startUs: 1_000_000, endUs: 4_000_000 }],
        ops: [
          {
            op: "update_sound",
            id: "snd_k",
            patch: { salience: 0.25, blendStatus: "blended", descriptor: { tag: "muted" } },
          },
        ],
        notes: [],
      });
      assert.equal(badUpdate.status, "invalid");
      assert.ok(badUpdate.conflicts.some((c) => c.kind === "protected_keep"));

      // User edit changing salience/sigmaUs/descriptor updates computeIntervalMusicalDigest & marks modifiedSinceApproval!
      const userMut = applyEvent(
        state,
        makeEvent(3, "ev_u_snd", "edit", {
          op: "update_sound",
          id: "snd_k",
          patch: { salience: 0.25 },
        }),
      );
      const d1 = computeIntervalMusicalDigest(userMut, 1_000_000, 4_000_000);
      assert.notEqual(d1, d0);
      assert.equal(userMut.spans[0].approval?.modifiedSinceApproval, true);
    });

    it("(3) treats splitting a parent segment to the left of a keep Span symmetrically with splitting to the right", () => {
      let state = createBaseState(20_000_000);
      const rootSegId = state.segments[0].id;
      state = applyEvent(
        state,
        makeEvent(1, "ev_keep_mid", "edit", {
          op: "set_range_keep",
          startUs: 8_000_000,
          endUs: 12_000_000,
          keep: true,
        }),
      );

      const leftSplitOffer = bindOfferToBasis(state, {
        id: "off_split_left",
        batchId: "b_sym",
        kind: "segmentation",
        writeRanges: [{ startUs: 3_000_000, endUs: 5_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: rootSegId,
            atUs: 4_000_000,
            newBoundaryId: "bnd_left",
            newSegmentId: "seg_after_4",
          },
        ],
        notes: [],
      });
      const rightSplitOffer = bindOfferToBasis(state, {
        id: "off_split_right",
        batchId: "b_sym",
        kind: "segmentation",
        writeRanges: [{ startUs: 15_000_000, endUs: 17_000_000 }],
        ops: [
          {
            op: "split_segment",
            segmentId: rootSegId,
            atUs: 16_000_000,
            newBoundaryId: "bnd_right",
            newSegmentId: "seg_after_16",
          },
        ],
        notes: [],
      });

      assert.equal(leftSplitOffer.status, "valid");
      assert.equal(rightSplitOffer.status, "valid");
    });
  });

  describe("P1-2: Challenged offer replacing anchor IDs on 'anchor:<id>'-verified Sound", () => {
    it("evaluates, previews, accepts (clear_verdict & confirm_replacement), and retracts cleanly without INVALID_VERDICT", async () => {
      const mock = new ScriptedMockProvider();
      const ctrl = new SessionController({ provider: mock });
      await ctrl.openSong({ audioId: "song_p12", durationUs: 12_000_000 });

      const snd = ctrl.getSnapshot().sounds[0];
      const oldAnc = snd.anchors[0];

      ctrl.applyEdit({
        op: "confirm_marks",
        targets: [
          {
            kind: "sound",
            id: snd.id,
            expectedRevision: snd.revision,
            facet: `anchor:${oldAnc.id}`,
          },
        ],
      });

      const verifiedSnd = ctrl.getState().sounds.get(snd.id);
      const oldFacetDigest = verifiedSnd.verdict.valueDigest;
      const writeRange = {
        startUs: Math.max(0, snd.startUs - 100_000),
        endUs: Math.min(12_000_000, snd.endUs + 100_000),
      };

      // 1. Unchallenged replacement of anchor IDs -> status: "invalid" (protected_verified), NEVER throws INVALID_VERDICT!
      const unchallenged = bindOfferToBasis(ctrl.getState(), {
        id: "off_anc_unch",
        batchId: "b_p12",
        kind: "sound_match",
        writeRanges: [writeRange],
        ops: [
          {
            op: "update_sound",
            id: snd.id,
            patch: {
              anchors: [{ id: "anc_new", role: "p_center", atUs: oldAnc.atUs + 4_000 }],
              primaryAnchorId: "anc_new",
            },
          },
        ],
        notes: [],
      });
      assert.equal(unchallenged.status, "invalid");
      assert.ok(unchallenged.conflicts.some((c) => c.kind === "protected_verified"));

      // 2. Challenged replacement -> status: "requires_challenge_confirm" + previewOffer works!
      mock._script.push({
        batchId: "b_p12_ch",
        offers: [
          {
            id: "off_anc_ch",
            batchId: "b_p12_ch",
            kind: "sound_match",
            writeRanges: [writeRange],
            ops: [
              {
                op: "update_sound",
                id: snd.id,
                patch: {
                  anchors: [{ id: "anc_new", role: "p_center", atUs: oldAnc.atUs + 4_000 }],
                  primaryAnchorId: "anc_new",
                },
              },
            ],
            challenges: [
              {
                challengeId: "ch_anc",
                targetKind: "sound",
                targetId: snd.id,
                expectedRevision: verifiedSnd.revision,
                expectedValueDigest: oldFacetDigest,
                reasonCode: "p_center_reanchor",
              },
            ],
            notes: [],
          },
        ],
      });

      const rec = await ctrl.recompute({ scope: writeRange, reason: "reanchor" });
      assert.equal(rec.offers[0].status, "requires_challenge_confirm");

      const prev = ctrl.previewOffer("off_anc_ch");
      assert.equal(prev.validation.status, "requires_challenge_confirm");
      assert.equal(
        prev.nextSnapshot.sounds.find((s) => s.id === snd.id)?.primaryAnchorId,
        "anc_new",
      );

      // 3. Accept with `confirm_replacement` -> re-targets facet to "anchor:anc_new" with matching digest!
      const acc = ctrl.acceptOffer({
        offerIds: ["off_anc_ch"],
        authorizedChallenges: [
          {
            offerId: "off_anc_ch",
            challengeId: "ch_anc",
            targetId: snd.id,
            expectedValueDigest: oldFacetDigest,
            disposition: "confirm_replacement",
          },
        ],
      });
      assert.equal(acc.ok, true);
      const afterConfirm = ctrl.getState().sounds.get(snd.id);
      assert.equal(afterConfirm.verdict?.facet, "anchor:anc_new");
      assert.equal(
        afterConfirm.verdict?.valueDigest,
        computeEntityValueDigest("sound", afterConfirm, "anchor:anc_new"),
      );

      // 4. Undo restores `anchor:${oldAnc.id}`, then accept with `clear_verdict` clears verdict cleanly!
      ctrl.undo();
      assert.equal(ctrl.getState().sounds.get(snd.id)?.verdict?.facet, `anchor:${oldAnc.id}`);

      const accClear = ctrl.acceptOffer({
        offerIds: ["off_anc_ch"],
        authorizedChallenges: [
          {
            offerId: "off_anc_ch",
            challengeId: "ch_anc",
            targetId: snd.id,
            expectedValueDigest: oldFacetDigest,
            disposition: "clear_verdict",
          },
        ],
      });
      assert.equal(accClear.ok, true);
      assert.equal(ctrl.getState().sounds.get(snd.id)?.verdict, undefined);
    });
  });

  describe("P1-3: Scoped soundClasses in OfferDependencies & sequential scratch pre-check in acceptOffer", () => {
    it("(1) keeps distant pulse offers valid when unrelated SoundClasses change, while marking referencing offers stale", () => {
      const state = createBaseState(20_000_000, {
        beats: [{ id: "b_16", atUs: 16_000_000 }],
        sounds: [
          {
            id: "snd_1s",
            startUs: 1_000_000,
            endUs: 1_100_000,
            endKind: "observed",
            anchors: [{ id: "a_1", role: "transient_attack", atUs: 1_000_000 }],
            primaryAnchorId: "a_1",
            salience: 0.9,
          },
        ],
        soundClasses: [
          {
            id: "cls_1s",
            exemplarSoundIds: ["snd_1s"],
            matchMode: "hit",
            label: "Intro Kick",
          },
        ],
      });

      const pulseAt16s = bindOfferToBasis(state, {
        id: "off_pulse_16s",
        batchId: "b_p13",
        kind: "pulse",
        writeRanges: [{ startUs: 15_000_000, endUs: 19_000_000 }],
        ops: [{ op: "move_beat", id: "b_16", atUs: 16_020_000 }],
        notes: [],
      });
      assert.deepEqual(pulseAt16s.dependencies.soundClasses, []);

      const classMatchAt16s = bindOfferToBasis(state, {
        id: "off_match_16s",
        batchId: "b_p13",
        kind: "sound_match",
        writeRanges: [{ startUs: 15_000_000, endUs: 19_000_000 }],
        ops: [
          {
            op: "upsert_sound",
            sound: {
              id: "snd_16s",
              startUs: 16_000_000,
              endUs: 16_090_000,
              endKind: "observed",
              anchors: [{ id: "a_16", role: "transient_attack", atUs: 16_000_000 }],
              primaryAnchorId: "a_16",
              salience: 0.85,
              classId: "cls_1s",
            },
          },
        ],
        notes: [],
      });
      assert.equal(classMatchAt16s.dependencies.soundClasses.length, 1);

      // Edit cls_1s AND create a brand-new cls_new at 1s
      const afterClassEdit = applyEvent(
        state,
        makeEvent(2, "ev_cls_edit", "edit", [
          {
            op: "upsert_sound_class",
            soundClass: { id: "cls_1s", exemplarSoundIds: ["snd_1s"], matchMode: "voice" },
          },
          {
            op: "upsert_sound_class",
            soundClass: { id: "cls_new", exemplarSoundIds: ["snd_1s"], matchMode: "hit" },
          },
        ]),
      );

      // Distant pulse offer at 16s stays 100% VALID!
      assert.equal(validateOffer(afterClassEdit, pulseAt16s).status, "valid");
      // Offer referencing cls_1s goes STALE!
      const matchVal = validateOffer(afterClassEdit, classMatchAt16s);
      assert.equal(matchVal.status, "stale");
      assert.ok(matchVal.conflicts.some((c) => c.kind === "version_mismatch"));
    });

    it("(2) pre-checks multi-offer acceptOffer sequentially on scratch and returns structured { ok: false }", async () => {
      const mock = new ScriptedMockProvider();
      const ctrl = new SessionController({ provider: mock });
      await ctrl.openSong({ audioId: "song_p13_2", durationUs: 20_000_000 });

      ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b_ver", atUs: 15_000_000 } });
      const verBeat = ctrl.getState().beats.get("b_ver");
      const verDigest = computeEntityValueDigest("beat", verBeat);

      mock._script.push({
        batchId: "b_multi",
        offers: [
          {
            id: "off_alt_a",
            batchId: "b_multi",
            alternativeGroup: "grp_alt",
            optionIndex: 0,
            kind: "pulse",
            writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
            ops: [{ op: "upsert_beat", beat: { id: "b_alt_a", atUs: 1_250_000 } }],
            notes: [],
          },
          {
            id: "off_alt_b",
            batchId: "b_multi",
            alternativeGroup: "grp_alt",
            optionIndex: 1,
            kind: "pulse",
            writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
            ops: [{ op: "upsert_beat", beat: { id: "b_alt_b", atUs: 1_300_000 } }],
            notes: [],
          },
          {
            id: "off_ov_1",
            batchId: "b_multi",
            kind: "pulse",
            writeRanges: [{ startUs: 6_000_000, endUs: 10_000_000 }],
            ops: [{ op: "upsert_beat", beat: { id: "b_ov_1", atUs: 7_250_000 } }],
            notes: [],
          },
          {
            id: "off_ov_2",
            batchId: "b_multi",
            kind: "pulse",
            writeRanges: [{ startUs: 6_000_000, endUs: 10_000_000 }],
            ops: [{ op: "upsert_beat", beat: { id: "b_ov_2", atUs: 7_750_000 } }],
            notes: [],
          },
          {
            id: "off_need_ch",
            batchId: "b_multi",
            kind: "pulse",
            writeRanges: [{ startUs: 14_000_000, endUs: 18_000_000 }],
            ops: [{ op: "move_beat", id: "b_ver", atUs: 15_040_000 }],
            challenges: [
              {
                challengeId: "ch_bver",
                targetKind: "beat",
                targetId: "b_ver",
                expectedRevision: verBeat.revision,
                expectedValueDigest: verDigest,
                reasonCode: "reanchor",
              },
            ],
            notes: [],
          },
        ],
      });
      await ctrl.recompute({ scope: "song", reason: "multi" });
      const seqBefore = ctrl.getState().lastSeq;

      // 1. Duplicate offerIds: [o1, o1] -> structured { ok: false }, zero state mutation
      const dupRes = ctrl.acceptOffer({ offerIds: ["off_ov_1", "off_ov_1"] });
      assert.equal(dupRes.ok, false);
      assert.equal(ctrl.getState().lastSeq, seqBefore);

      // 2. Shared alternativeGroup: [off_alt_a, off_alt_b] -> structured { ok: false, status: "superseded" }
      const altRes = ctrl.acceptOffer({ offerIds: ["off_alt_a", "off_alt_b"] });
      assert.equal(altRes.ok, false);
      assert.equal(altRes.status, "superseded");
      assert.ok(altRes.conflicts.some((c) => c.kind === "sibling_conflict"));
      assert.equal(ctrl.getState().lastSeq, seqBefore);

      // 3. Overlapping writeRanges where off_ov_1 mutates off_ov_2's basis -> structured { ok: false, status: "stale" }
      const ovRes = ctrl.acceptOffer({ offerIds: ["off_ov_1", "off_ov_2"] });
      assert.equal(ovRes.ok, false);
      assert.equal(ovRes.status, "stale");
      assert.equal(ctrl.getState().lastSeq, seqBefore);

      // 4. Multi-offer batch missing challenge confirmation on second offer -> { ok: false, status: "requires_challenge_confirm" }
      const chRes = ctrl.acceptOffer({ offerIds: ["off_ov_1", "off_need_ch"] });
      assert.equal(chRes.ok, false);
      assert.equal(chRes.status, "requires_challenge_confirm");
      assert.equal(chRes.requiredChallenges.length, 1);
      assert.equal(ctrl.getState().lastSeq, seqBefore);
    });
  });

  describe("P1-4: Exclude ORPHANED_BY_RETRACT events from computeUndoRedoTargets undoStack", () => {
    it("skips orphaned transactions during undo() while preserving full restoration via redo()", async () => {
      const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
      await ctrl.openSong({ audioId: "song_p14", durationUs: 12_000_000 });
      const rootSegId = ctrl.getState().segments[0].id;

      // e_keep_ok: independent user edit
      const { event: ev0 } = ctrl.applyEdit({
        op: "upsert_beat",
        beat: { id: "b_indep", atUs: 1_250_000 },
      });
      // e_split: split root segment
      const { event: evSplit } = ctrl.applyEdit({
        op: "split_segment",
        segmentId: rootSegId,
        atUs: 6_000_000,
        newBoundaryId: "bnd_6",
        newSegmentId: "seg_r",
      });
      // e_dep: dependent edit on seg_r
      const { event: evDep } = ctrl.applyEdit({
        op: "set_segment_regime",
        segmentId: "seg_r",
        regime: "break",
      });

      // Selective retract of evSplit -> evDep becomes ORPHANED_BY_RETRACT!
      ctrl.retract(evSplit.id);
      assert.equal(ctrl.getState().orphanedTransactions.length, 1);
      assert.equal(ctrl.getState().orphanedTransactions[0].eventId, evDep.id);

      const targetsFromState = computeUndoRedoTargets(ctrl.getState());
      const targetsFromTrace = computeUndoRedoTargets(ctrl.getTrace());
      assert.deepEqual(targetsFromState, targetsFromTrace);
      assert.ok(!targetsFromState.undoStack.includes(evDep.id));
      assert.equal(targetsFromState.undoTargetEventId, ev0.id);

      // Redo undoes the retraction of evSplit and restores BOTH evSplit and evDep!
      const redone = ctrl.redo();
      assert.equal(redone.ok, true);
      assert.equal(ctrl.getState().orphanedTransactions.length, 0);
      assert.equal(
        ctrl.getState().segments.find((s) => s.id === "seg_r")?.regime,
        "break",
      );

      // Re-retract evSplit again, then verify a single ctrl.undo() immediately undoes ev0 (zero dead click!)
      ctrl.retract(evSplit.id);
      const undone = ctrl.undo();
      assert.equal(undone.ok, true);
      assert.equal(ctrl.getState().beats.has("b_indep"), false);
    });
  });

  describe("P1-5: resetDocument atomicity, _audioEpoch 3-layer cancellation & derivedFrom verdict staleness", () => {
    it("(1) validates resetDocument inputs before mutating _slots, _audioEpoch, _evidenceRef, or trace", async () => {
      const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
      await ctrl.openSong({ audioId: "song_p15", durationUs: 10_000_000 });

      const epochBefore = ctrl._audioEpoch;
      const evidenceBefore = ctrl._evidenceRef;
      const seqBefore = ctrl.getState().lastSeq;
      const gensBefore = {
        song_open: ctrl._slots.song_open.generation,
        micro_assist: ctrl._slots.micro_assist.generation,
        heavy_recompute: ctrl._slots.heavy_recompute.generation,
      };

      assert.throws(
        () => ctrl.resetDocument(/** @type {any} */ (null)),
        (err) => err instanceof EnsomiError,
      );
      assert.throws(
        () => ctrl.resetDocument({}, { kind: "model" }),
        (err) => err instanceof EnsomiError && err.code === "AUTHORITY_VIOLATION",
      );
      assert.throws(
        () => ctrl.resetDocument({ audio: { audioId: "", durationUs: -10 } }),
        (err) => err instanceof EnsomiError,
      );

      assert.equal(ctrl._audioEpoch, epochBefore);
      assert.equal(ctrl._evidenceRef, evidenceBefore);
      assert.equal(ctrl.getState().lastSeq, seqBefore);
      assert.deepEqual(
        {
          song_open: ctrl._slots.song_open.generation,
          micro_assist: ctrl._slots.micro_assist.generation,
          heavy_recompute: ctrl._slots.heavy_recompute.generation,
        },
        gensBefore,
      );
    });

    it("(2) aborts all 3 layer slots and invokes provider.cancel(requestId) on same-audioId rebind, resetDocument, and retract", async () => {
      /** @type {string[]} */
      const cancelledIds = [];
      /** @type {Array<() => void>} */
      const gates = [];

      const makeHangingPromise = (signal) =>
        new Promise((_resolve, reject) => {
          const onAbort = () => reject(signal.reason ?? new EnsomiError("ABORTED", "aborted"));
          if (signal.aborted) {
            onAbort();
            return;
          }
          signal.addEventListener("abort", onAbort, { once: true });
          gates.push(onAbort);
        });

      const provider = {
        manifest() {
          return {
            id: "hang-provider",
            version: "0.1.0",
            capabilities: {
              draftAnalysis: true,
              microSnap: true,
              constrainedRecompute: true,
            },
          };
        },
        async prepare(audio) {
          return { providerId: "hang-provider", audioId: audio.audioId, cacheKey: `${audio.sampleRateHz ?? 48000}` };
        },
        async analyze(req, signal) {
          return await makeHangingPromise(signal);
        },
        async suggestSnaps(req, signal) {
          return await makeHangingPromise(signal);
        },
        async recompute(req, signal) {
          return await makeHangingPromise(signal);
        },
        cancel(requestId) {
          cancelledIds.push(requestId);
        },
      };

      const ctrl = new SessionController({
        provider,
        triggerPolicy: {
          song_open: { mode: "manual" },
          micro_assist: { debounceMs: 0 },
          heavy_recompute: { debounceMs: 0 },
        },
      });
      await ctrl.bindAudio({ audioId: "same_song", durationUs: 10_000_000, sampleRateHz: 44100 });

      // Start all 3 layers concurrently, then re-bind SAME audioId with updated sampleRateHz
      const pAnalyze1 = ctrl.analyze();
      const pSnaps1 = ctrl.suggestSnaps({ atUs: 2_000_000, immediate: true });
      const pRecomp1 = ctrl.recompute({ scope: "song", reason: "r1" });
      await new Promise((r) => setTimeout(r, 10));

      await ctrl.bindAudio({ audioId: "same_song", durationUs: 10_000_000, sampleRateHz: 48000 });
      const [rA1, rS1, rR1] = await Promise.all([pAnalyze1, pSnaps1, pRecomp1]);
      assert.equal(rA1.status, "superseded");
      assert.equal(rS1.status, "superseded");
      assert.equal(rR1.status, "superseded");
      assert.equal(cancelledIds.length, 3);

      // Start all 3 layers again, then call resetDocument()
      cancelledIds.length = 0;
      const pAnalyze2 = ctrl.analyze();
      const pSnaps2 = ctrl.suggestSnaps({ atUs: 3_000_000, immediate: true });
      const pRecomp2 = ctrl.recompute({ scope: "song", reason: "r2" });
      await new Promise((r) => setTimeout(r, 10));

      const resetRes = ctrl.resetDocument({ reason: "wipe" });
      const [rA2, rS2, rR2] = await Promise.all([pAnalyze2, pSnaps2, pRecomp2]);
      assert.equal(rA2.status, "superseded");
      assert.equal(rS2.status, "superseded");
      assert.equal(rR2.status, "superseded");
      assert.equal(cancelledIds.length, 3);

      // Start all 3 layers again, then retract the reset_document event!
      cancelledIds.length = 0;
      const pAnalyze3 = ctrl.analyze();
      const pSnaps3 = ctrl.suggestSnaps({ atUs: 4_000_000, immediate: true });
      const pRecomp3 = ctrl.recompute({ scope: "song", reason: "r3" });
      await new Promise((r) => setTimeout(r, 10));

      ctrl.retract(resetRes.event.id);
      const [rA3, rS3, rR3] = await Promise.all([pAnalyze3, pSnaps3, pRecomp3]);
      assert.equal(rA3.status, "superseded");
      assert.equal(rS3.status, "superseded");
      assert.equal(rR3.status, "superseded");
      assert.equal(cancelledIds.length, 3);
    });

    it("(3) marks derivedFrom offers stale on clear_verdicts or retract of confirm_marks, and supports span/approval supportingIds", () => {
      let state = createBaseState(20_000_000, {
        beats: [{ id: "b_src", atUs: 3_000_000 }],
      });

      const eConfirm = makeEvent(2, "ev_conf_src", "edit", [
        {
          op: "confirm_marks",
          targets: [{ kind: "beat", id: "b_src", expectedRevision: 1 }],
        },
        {
          op: "set_range_keep",
          startUs: 2_000_000,
          endUs: 4_000_000,
          keep: true,
        },
      ]);
      state = applyEvent(state, eConfirm);

      const keepSpan = state.spans[0];
      const keepApproval = keepSpan.approval;

      const propOffer = bindOfferToBasis(state, {
        id: "off_prop_verdict",
        batchId: "b_p15_3",
        kind: "propagation",
        writeRanges: [{ startUs: 12_000_000, endUs: 18_000_000 }],
        ops: [{ op: "upsert_beat", beat: { id: "b_dst", atUs: 13_000_000 } }],
        derivedFrom: {
          range: { startUs: 2_000_000, endUs: 5_000_000 },
          supportingIds: ["b_src", keepSpan.id, keepApproval.id],
        },
        notes: [],
      });
      assert.equal(propOffer.status, "valid");
      assert.equal(propOffer.dependencies.derivedFrom?.supportingRevisions.length, 3);

      // 1. `clear_verdicts` on b_src (coordinates unchanged!) -> offer becomes stale (`source_changed`)
      const afterClear = applyEvent(
        state,
        makeEvent(3, "ev_clear_src", "edit", {
          op: "clear_verdicts",
          targets: [{ kind: "beat", id: "b_src", expectedRevision: 2 }],
        }),
      );
      const valAfterClear = validateOffer(afterClear, propOffer);
      assert.equal(valAfterClear.status, "stale");
      assert.ok(valAfterClear.conflicts.some((c) => c.kind === "source_changed"));

      // 2. Retracting `ev_conf_src` also transitions `propOffer` to stale (`source_changed`)!
      const withBatch = applyEvent(
        state,
        makeEvent(
          3,
          "ev_prop_batch",
          "offer_batch",
          { batchId: "b_p15_3", offers: [propOffer] },
          { kind: "model" },
        ),
      );
      const afterRetract = applyEvent(
        withBatch,
        makeEvent(4, "ev_retract_conf", "retract", { targetEventId: "ev_conf_src" }),
      );
      const updatedOffer = afterRetract.offers.get("off_prop_verdict");
      assert.equal(updatedOffer?.status, "stale");
      assert.ok(updatedOffer?.conflicts.some((c) => c.kind === "source_changed"));
    });
  });

  describe("P1-6: SoundClass/Beat referential integrity on remove_sounds/draft_loaded & move_boundary Span canonicalization", () => {
    it("(1) enforces SoundClass.exemplarSoundIds referential integrity and marks bound Beat alignmentStale / protected_verified", () => {
      // 1. draft_loaded with missing exemplarSoundIds throws REFERENTIAL_INTEGRITY
      assert.throws(
        () =>
          createBaseState(10_000_000, {
            soundClasses: [
              {
                id: "cls_dangling",
                exemplarSoundIds: ["no_such_sound"],
                matchMode: "hit",
              },
            ],
          }),
        (err) => err instanceof EnsomiError && err.code === "REFERENTIAL_INTEGRITY",
      );

      // 2. Active state with snd_1, snd_2, SoundClass cls_1 -> ["snd_1"], unverified beat bound to snd_2, verified beat bound to snd_2
      let state = createBaseState(12_000_000, {
        beats: [
          {
            id: "b_model_bound",
            atUs: 2_000_000,
            boundTo: { soundId: "snd_2", anchorId: "a2", anchorRole: "transient_attack" },
          },
          { id: "b_user_bound", atUs: 2_500_000 },
        ],
        sounds: [
          {
            id: "snd_1",
            startUs: 1_000_000,
            endUs: 1_100_000,
            endKind: "observed",
            anchors: [{ id: "a1", role: "transient_attack", atUs: 1_000_000 }],
            primaryAnchorId: "a1",
            salience: 0.9,
          },
          {
            id: "snd_2",
            startUs: 2_000_000,
            endUs: 2_600_000,
            endKind: "observed",
            anchors: [{ id: "a2", role: "transient_attack", atUs: 2_500_000 }],
            primaryAnchorId: "a2",
            salience: 0.9,
          },
        ],
        soundClasses: [
          { id: "cls_1", exemplarSoundIds: ["snd_1"], matchMode: "hit" },
        ],
      });

      state = applyEvent(
        state,
        makeEvent(2, "ev_snap_u", "edit", {
          op: "snap_beat",
          id: "b_user_bound",
          atUs: 2_500_000,
          boundTo: { soundId: "snd_2", anchorId: "a2", anchorRole: "transient_attack" },
        }),
      );

      // Deleting snd_1 while cls_1 still references it throws REFERENTIAL_INTEGRITY!
      assert.throws(
        () =>
          applyEvent(
            state,
            makeEvent(3, "ev_bad_rem", "edit", { op: "remove_sounds", ids: ["snd_1"] }),
          ),
        (err) => err instanceof EnsomiError && err.code === "REFERENTIAL_INTEGRITY",
      );

      // Removing cls_1 earlier in the SAME transaction allows removing snd_1 cleanly!
      const removedClassAndSound = applyEvent(
        state,
        makeEvent(3, "ev_ok_rem_cls", "edit", [
          { op: "remove_sound_classes", ids: ["cls_1"] },
          { op: "remove_sounds", ids: ["snd_1"] },
        ]),
      );
      assert.equal(removedClassAndSound.sounds.has("snd_1"), false);

      // Unchallenged Offer deleting snd_2 (referenced by verified Beat b_user_bound) -> blocked by protected_verified!
      const badRemOffer = bindOfferToBasis(state, {
        id: "off_rem_snd2",
        batchId: "b_p16",
        kind: "sound_match",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [{ op: "remove_sounds", ids: ["snd_2"] }],
        notes: [],
      });
      assert.equal(badRemOffer.status, "invalid");
      assert.ok(
        badRemOffer.conflicts.some(
          (c) =>
            c.kind === "protected_verified" &&
            c.targetKind === "beat" &&
            c.targetId === "b_user_bound",
        ),
      );

      // User deleting snd_2 marks alignmentStale: true on BOTH unverified & user-verified bound beats!
      const userRemovedSnd2 = applyEvent(
        state,
        makeEvent(3, "ev_u_rem_snd2", "edit", { op: "remove_sounds", ids: ["snd_2"] }),
      );
      assert.equal(userRemovedSnd2.beats.get("b_model_bound")?.boundTo?.alignmentStale, true);
      assert.equal(userRemovedSnd2.beats.get("b_model_bound")?.boundTo?.soundId, "snd_2");
      assert.equal(userRemovedSnd2.beats.get("b_model_bound")?.verdict, undefined);
      const uBeatAfter = userRemovedSnd2.beats.get("b_user_bound");
      assert.equal(uBeatAfter?.boundTo?.alignmentStale, true);
      assert.equal(uBeatAfter?.verdict?.valueDigest, computeEntityValueDigest("beat", uBeatAfter));
    });

    it("(2) canonicalizes free & keep Spans during move_boundary identically to split_segment/merge_segments", () => {
      // Case A: "free" Span [0, 6s) on "auto" Segment [0, 10s) -> move_boundary from 10s to 6s promotes Segment to "free" and retires Span!
      let stateFree = createBaseState(20_000_000, {
        segments: [
          { id: "seg_l", startUs: 0, endUs: 10_000_000, regime: "auto" },
          { id: "seg_r", startUs: 10_000_000, endUs: 20_000_000, regime: "free" },
        ],
        boundaries: [{ id: "bnd_10", atUs: 10_000_000 }],
      });
      stateFree = applyEvent(
        stateFree,
        makeEvent(2, "ev_free_span", "edit", {
          op: "set_range_regime",
          startUs: 0,
          endUs: 8_000_000,
          regime: "free",
        }),
      );
      assert.equal(stateFree.spans.length, 1);

      // Moving bnd_10 from 10s -> 6s:
      //  - left piece [0, 6s) covers 100% of shrunk seg_l [0, 6s) -> promotes seg_l.regime to "free" & retires left span!
      //  - right piece [6s, 8s) lands on seg_r (already regime: "free") -> retires right piece too!
      const movedFree = applyEvent(
        stateFree,
        makeEvent(3, "ev_mv_6", "edit", {
          op: "move_boundary",
          boundaryId: "bnd_10",
          atUs: 6_000_000,
        }),
      );
      assert.equal(movedFree.segments[0].regime, "free");
      assert.equal(movedFree.segments[1].regime, "free");
      assert.equal(movedFree.spans.length, 0);

      // Case B: untouched [0, 6s) "keep" Span on seg_l [0, 10s) -> move_boundary 10s -> 6s promotes Approval onto seg_l!
      let stateKeep = createBaseState(20_000_000, {
        segments: [
          { id: "seg_kl", startUs: 0, endUs: 10_000_000, regime: "auto" },
          { id: "seg_kr", startUs: 10_000_000, endUs: 20_000_000, regime: "auto" },
        ],
        boundaries: [{ id: "bnd_k10", atUs: 10_000_000 }],
      });
      stateKeep = applyEvent(
        stateKeep,
        makeEvent(2, "ev_keep_0_6", "edit", {
          op: "set_range_keep",
          startUs: 0,
          endUs: 6_000_000,
          keep: true,
        }),
      );
      assert.equal(stateKeep.spans.length, 1);
      const keepAppId = stateKeep.spans[0].approval.id;

      const movedKeep = applyEvent(
        stateKeep,
        makeEvent(3, "ev_mv_k6", "edit", {
          op: "move_boundary",
          boundaryId: "bnd_k10",
          atUs: 6_000_000,
        }),
      );
      assert.equal(movedKeep.spans.length, 0);
      assert.equal(movedKeep.segments[0].approval?.id, keepAppId);
      assert.equal(movedKeep.segments[0].approval?.modifiedSinceApproval, true);
    });
  });

  describe("P2-1 & P2-2: Strict JSON Schemas & TypeScript Consumer Declarations", () => {
    it("wires OfferOp, TransactionOp, OfferDependencies, RejectionSuppression, BeatBinding anyOf, and Segment boundary minLength in schemas/*.json", () => {
      const entitiesSchema = JSON.parse(
        readFileSync(join(rootDir, "schemas", "entities.schema.json"), "utf8"),
      );
      const offerSchema = JSON.parse(
        readFileSync(join(rootDir, "schemas", "offer.schema.json"), "utf8"),
      );
      const traceSchema = JSON.parse(
        readFileSync(join(rootDir, "schemas", "trace-event.schema.json"), "utf8"),
      );

      // Entities schema checks
      assert.deepEqual(entitiesSchema.$defs.BeatBinding.anyOf, [
        { required: ["soundId"] },
        { required: ["candidateEventId"] },
      ]);
      assert.equal(entitiesSchema.$defs.Segment.properties.startBoundaryId.oneOf[1].minLength, 1);
      assert.equal(entitiesSchema.$defs.Segment.properties.endBoundaryId.oneOf[1].minLength, 1);
      assert.equal(
        traceSchema.$defs.Snapshot.properties.suppressions.items.$ref,
        "#/$defs/RejectionSuppression",
      );

      // Offer schema checks (`OfferOp` restricts ops to OFFER_OP_KINDS and forbids promote_range_to_segments.keep)
      assert.equal(offerSchema.$defs.Offer.properties.dependencies.$ref, "#/$defs/OfferDependencies");
      assert.equal(offerSchema.$defs.Offer.properties.ops.items.$ref, "#/$defs/OfferOp");
      const offerOpConsts = offerSchema.$defs.OfferOp.oneOf.map(
        (branch) => branch.properties.op.const,
      );
      assert.deepEqual([...offerOpConsts].sort(), [...OFFER_OP_KINDS].sort());
      assert.ok(!offerOpConsts.includes("confirm_marks"));
      assert.ok(!offerOpConsts.includes("clear_verdicts"));
      assert.ok(!offerOpConsts.includes("set_segment_keep"));
      assert.ok(!offerOpConsts.includes("set_range_keep"));
      const offerPromoteBranch = offerSchema.$defs.OfferOp.oneOf.find(
        (b) => b.properties.op.const === "promote_range_to_segments",
      );
      assert.equal("keep" in offerPromoteBranch.properties, false);

      // TraceEvent schema checks (`TransactionOp` covers all 20 TRANSACTION_OP_KINDS)
      const txOpConsts = traceSchema.$defs.TransactionOp.oneOf.map(
        (branch) => branch.properties.op.const,
      );
      assert.deepEqual([...txOpConsts].sort(), [...TRANSACTION_OP_KINDS].sort());
      const editBranch = traceSchema.$defs.TraceEvent.allOf.find(
        (rule) => rule.if?.properties?.type?.const === "edit",
      );
      assert.ok(editBranch);

      // Consumer TypeScript smoke file exercises P2-2 fixes
      const consumerTs = readFileSync(join(rootDir, "test", "consumer-types.ts"), "utf8");
      assert.ok(consumerTs.includes('computeEntityValueDigest("beat", beat0)'));
      assert.ok(consumerTs.includes('[undefined, "bnd_ts_r"]'));
      assert.ok(consumerTs.includes("matchScript:"));
      assert.ok(consumerTs.includes("segmentationScript:"));
    });
  });
});
