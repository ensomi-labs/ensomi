import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  applyEvent,
  assertOfferShape,
  bindOfferToBasis,
  compileConstraints,
  computeEntityValueDigest,
  createInitialState,
  EnsomiError,
  foldTrace,
  formatConsequenceSummary,
  OFFER_OP_KINDS,
  previewOffer,
  projectTempoMap,
  SessionController,
  TRANSACTION_OP_KINDS,
  TRACE_SCHEMA_VERSION,
  validateOffer,
} from "../index.js";
import { ScriptedMockProvider } from "../testing.js";

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

function createAudioState(durationUs = 10_000_000, draftPayload = undefined) {
  let state = createInitialState();
  state = applyEvent(
    state,
    makeEvent(0, "ev_0", "audio_bound", { audioId: "song_s2c", durationUs }, { kind: "host" }),
  );
  if (draftPayload) {
    state = applyEvent(
      state,
      makeEvent(1, "ev_1", "draft_loaded", draftPayload, { kind: "model" }),
    );
  }
  return state;
}

describe("Stage 2C Adversarial Audit Regressions (Astra P1 #8, #9, #10, #12 & P2 #16, #17)", () => {
  describe("Astra P1 #8: Explicit-only replacementId verdict transfer on confirm_replacement", () => {
    it("confers zero verdict onto unrelated added beats when replacementId is omitted on authorized deletion challenges", async () => {
      const mock = new ScriptedMockProvider();
      const ctrl = new SessionController({ provider: mock });
      await ctrl.bindAudio({ audioId: "song_p1_8", durationUs: 10_000_000 });

      // User places verified beats at 350 ms and 700 ms
      ctrl.applyEdit([
        { op: "upsert_beat", beat: { id: "b_350", atUs: 350_000 } },
        { op: "upsert_beat", beat: { id: "b_700", atUs: 700_000 } },
      ]);
      const s0 = ctrl.getState();
      const b350 = s0.beats.get("b_350");
      const b700 = s0.beats.get("b_700");
      const d350 = computeEntityValueDigest("beat", b350);
      const d700 = computeEntityValueDigest("beat", b700);

      // Provider offer removes verified beats at 350 ms and 700 ms and adds an unrelated AI beat at 3,900 ms
      // with NEITHER challenge naming `replacementId`.
      mock._script.push({
        batchId: "b_del_far",
        offers: [
          {
            id: "off_del_far",
            batchId: "b_del_far",
            kind: "pulse",
            writeRanges: [{ startUs: 0, endUs: 5_000_000 }],
            ops: [
              { op: "remove_beats", ids: ["b_350", "b_700"] },
              { op: "upsert_beat", beat: { id: "b_ai_3900", atUs: 3_900_000, confidence: 0.72 } },
            ],
            challenges: [
              {
                challengeId: "ch_350",
                targetKind: "beat",
                targetId: "b_350",
                expectedRevision: b350.revision,
                expectedValueDigest: d350,
                reasonCode: "false_intro_transient",
              },
              {
                challengeId: "ch_700",
                targetKind: "beat",
                targetId: "b_700",
                expectedRevision: b700.revision,
                expectedValueDigest: d700,
                reasonCode: "false_intro_transient",
              },
            ],
            notes: [],
          },
        ],
      });

      const rec = await ctrl.recompute({
        scope: { startUs: 0, endUs: 5_000_000 },
        reason: "clean_intro",
      });
      assert.equal(rec.offers[0].status, "requires_challenge_confirm");

      const acc = ctrl.acceptOffer({
        offerIds: ["off_del_far"],
        authorizedChallenges: [
          {
            offerId: "off_del_far",
            challengeId: "ch_350",
            targetId: "b_350",
            expectedValueDigest: d350,
            disposition: "confirm_replacement",
          },
          {
            offerId: "off_del_far",
            challengeId: "ch_700",
            targetId: "b_700",
            expectedValueDigest: d700,
            disposition: "confirm_replacement",
          },
        ],
      });
      assert.equal(acc.ok, true);

      const post = ctrl.getState();
      assert.equal(post.beats.has("b_350"), false);
      assert.equal(post.beats.has("b_700"), false);
      const aiBeat = post.beats.get("b_ai_3900");
      assert.ok(aiBeat);
      assert.equal(aiBeat.origin, "model");
      assert.equal(aiBeat.verdict, undefined);
      assert.equal(compileConstraints(post).beatGuides.length, 0);
    });

    it("transfers confirmed verdict when replacementId is explicitly valid, and rejects missing or duplicate replacementIds", async () => {
      const mock = new ScriptedMockProvider();
      const ctrl = new SessionController({ provider: mock });
      await ctrl.bindAudio({ audioId: "song_p1_8b", durationUs: 10_000_000 });

      ctrl.applyEdit([
        { op: "upsert_beat", beat: { id: "b_v1", atUs: 1_000_000 } },
        { op: "upsert_beat", beat: { id: "b_v2", atUs: 2_000_000 } },
      ]);
      const state = ctrl.getState();
      const b1 = state.beats.get("b_v1");
      const b2 = state.beats.get("b_v2");
      const d1 = computeEntityValueDigest("beat", b1);
      const d2 = computeEntityValueDigest("beat", b2);

      // 1. Duplicate (targetKind, replacementId) across two challenges throws INVALID_OFFER in bindOfferToBasis / assertOfferShape
      assert.throws(
        () =>
          bindOfferToBasis(state, {
            id: "off_dup_rep",
            batchId: "b_rep",
            kind: "pulse",
            writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
            ops: [
              { op: "remove_beats", ids: ["b_v1", "b_v2"] },
              { op: "upsert_beat", beat: { id: "b_shared_rep", atUs: 1_500_000 } },
            ],
            challenges: [
              {
                challengeId: "ch_1",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "merge_pulse",
                replacementId: "b_shared_rep",
              },
              {
                challengeId: "ch_2",
                targetKind: "beat",
                targetId: "b_v2",
                expectedRevision: b2.revision,
                expectedValueDigest: d2,
                reasonCode: "merge_pulse",
                replacementId: "b_shared_rep",
              },
            ],
            notes: [],
          }),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );

      // 2. Nonexistent replacementId in post-offer scratch state flags invalid_op
      const missingRepOffer = bindOfferToBasis(state, {
        id: "off_missing_rep",
        batchId: "b_rep",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
        ops: [{ op: "remove_beats", ids: ["b_v1"] }],
        challenges: [
          {
            challengeId: "ch_missing",
            targetKind: "beat",
            targetId: "b_v1",
            expectedRevision: b1.revision,
            expectedValueDigest: d1,
            reasonCode: "replace",
            replacementId: "no_such_beat",
          },
        ],
        notes: [],
      });
      assert.equal(missingRepOffer.status, "invalid");
      assert.ok(missingRepOffer.conflicts.some((c) => c.kind === "invalid_op"));

      // 3. Tampered raw offer with duplicate replacementId also caught by assertOfferShape & validateOffer
      const validExplicitOffer = bindOfferToBasis(state, {
        id: "off_explicit_rep",
        batchId: "b_rep_ok",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
        ops: [
          { op: "remove_beats", ids: ["b_v1"] },
          { op: "upsert_beat", beat: { id: "b_rep_1", atUs: 1_012_000, confidence: 0.96 } },
          { op: "upsert_beat", beat: { id: "b_unrel_3900", atUs: 3_900_000, confidence: 0.65 } },
        ],
        challenges: [
          {
            challengeId: "ch_rep_1",
            targetKind: "beat",
            targetId: "b_v1",
            expectedRevision: b1.revision,
            expectedValueDigest: d1,
            reasonCode: "transient_reanchor",
            replacementId: "b_rep_1",
          },
        ],
        notes: [],
      });
      assert.equal(validExplicitOffer.status, "requires_challenge_confirm");

      const tamperedDupRep = {
        ...validExplicitOffer,
        challenges: [
          validExplicitOffer.challenges[0],
          {
            challengeId: "ch_rep_2",
            targetKind: "beat",
            targetId: "b_v2",
            expectedRevision: b2.revision,
            expectedValueDigest: d2,
            reasonCode: "dup",
            replacementId: "b_rep_1",
          },
        ],
      };
      assert.throws(
        () => assertOfferShape(tamperedDupRep),
        (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
      );
      const tamperedVal = validateOffer(state, tamperedDupRep);
      assert.equal(tamperedVal.status, "invalid");
      assert.ok(tamperedVal.conflicts.some((c) => c.kind === "invalid_op"));

      // 4. Valid explicit replacementId transfers confirmed verdict ONLY to b_rep_1 (leaving b_unrel_3900 unverified)
      mock._script.push({
        batchId: "b_rep_live",
        offers: [
          {
            id: "off_rep_live",
            batchId: "b_rep_live",
            kind: "pulse",
            writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
            ops: [
              { op: "remove_beats", ids: ["b_v1"] },
              { op: "upsert_beat", beat: { id: "b_rep_1", atUs: 1_012_000, confidence: 0.96 } },
              { op: "upsert_beat", beat: { id: "b_unrel_3900", atUs: 3_900_000, confidence: 0.65 } },
            ],
            challenges: [
              {
                challengeId: "ch_rep_1",
                targetKind: "beat",
                targetId: "b_v1",
                expectedRevision: b1.revision,
                expectedValueDigest: d1,
                reasonCode: "transient_reanchor",
                replacementId: "b_rep_1",
              },
            ],
            notes: [],
          },
        ],
      });
      await ctrl.recompute({ scope: { startUs: 0, endUs: 4_000_000 }, reason: "rep" });
      const acc = ctrl.acceptOffer({
        offerIds: ["off_rep_live"],
        authorizedChallenges: [
          {
            offerId: "off_rep_live",
            challengeId: "ch_rep_1",
            targetId: "b_v1",
            expectedValueDigest: d1,
            disposition: "confirm_replacement",
          },
        ],
      });
      assert.equal(acc.ok, true);
      const after = ctrl.getState();
      assert.equal(after.beats.has("b_v1"), false);
      const repBeat = after.beats.get("b_rep_1");
      assert.equal(repBeat?.verdict?.kind, "confirmed");
      assert.equal(repBeat?.verdict?.via, "challenge_accept");
      assert.equal(repBeat?.verdict?.valueDigest, computeEntityValueDigest("beat", repBeat));
      assert.equal(after.beats.get("b_unrel_3900")?.verdict, undefined);
    });
  });

  describe("Astra P1 #9: Safe default includeVerified: false on shift_beats", () => {
    it("skips verified beats by default when includeVerified is omitted in both edits and offers", () => {
      let state = createAudioState(10_000_000, {
        beats: [
          { id: "m_0", atUs: 1_000_000 },
          { id: "m_1", atUs: 2_000_000 },
        ],
      });
      state = applyEvent(
        state,
        makeEvent(2, "ev_u", "edit", [
          { op: "upsert_beat", beat: { id: "u_ver", atUs: 1_500_000 } },
          { op: "confirm_marks", targets: [{ kind: "beat", id: "m_0", expectedRevision: 1 }] },
        ]),
      );

      // 1. User TransactionOp `shift_beats` with omitted `includeVerified` -> verified beats stay anchored!
      const shiftedUserDefault = applyEvent(
        state,
        makeEvent(3, "ev_shift_def", "edit", {
          op: "shift_beats",
          startUs: 0,
          endUs: 5_000_000,
          deltaUs: 25_000,
        }),
      );
      assert.equal(shiftedUserDefault.beats.get("m_0")?.atUs, 1_000_000);
      assert.equal(shiftedUserDefault.beats.get("m_0")?.revision, 2);
      assert.equal(shiftedUserDefault.beats.get("u_ver")?.atUs, 1_500_000);
      assert.equal(shiftedUserDefault.beats.get("u_ver")?.revision, 1);
      assert.equal(shiftedUserDefault.beats.get("m_1")?.atUs, 2_025_000);
      assert.equal(shiftedUserDefault.beats.get("m_1")?.verdict, undefined);

      // 2. OfferOp `shift_beats` with omitted `includeVerified` skips verified beats cleanly (`status: "valid"`)
      const safeOffer = bindOfferToBasis(state, {
        id: "off_shift_def",
        batchId: "b_s",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 6_000_000 }],
        ops: [{ op: "shift_beats", startUs: 500_000, endUs: 3_000_000, deltaUs: 30_000 }],
        notes: [],
      });
      assert.equal(safeOffer.status, "valid");
      const prev = previewOffer(state, safeOffer);
      assert.equal(prev.nextSnapshot.beats.find((b) => b.id === "m_0")?.atUs, 1_000_000);
      assert.equal(prev.nextSnapshot.beats.find((b) => b.id === "u_ver")?.atUs, 1_500_000);
      assert.equal(prev.nextSnapshot.beats.find((b) => b.id === "m_1")?.atUs, 2_030_000);

      // 3. Unchallenged OfferOp `shift_beats` with explicit `includeVerified: true` is blocked by `protected_verified`
      const unsafeOffer = bindOfferToBasis(state, {
        id: "off_shift_inc",
        batchId: "b_s",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 6_000_000 }],
        ops: [
          {
            op: "shift_beats",
            startUs: 500_000,
            endUs: 3_000_000,
            deltaUs: 30_000,
            includeVerified: true,
          },
        ],
        notes: [],
      });
      assert.equal(unsafeOffer.status, "invalid");
      assert.ok(unsafeOffer.conflicts.some((c) => c.kind === "protected_verified"));
    });
  });

  describe("Astra P1 #10: Clamped / unchanged user adjustments never fabricate adjusted verdicts", () => {
    it("treats clamped zero-change update_sound, move_beat, move_boundary, and move_unbeat as pure semantic no-ops", () => {
      let state = createAudioState(10_000_000, {
        segments: [
          { id: "seg_l", startUs: 0, endUs: 4_000_000, regime: "auto" },
          { id: "seg_r", startUs: 4_000_000, endUs: 10_000_000, regime: "auto" },
        ],
        boundaries: [{ id: "bnd_4s", atUs: 4_000_000 }],
        beats: [{ id: "b_zero", atUs: 0, confidence: 0.8 }],
        unbeats: [{ id: "ub_zero", atUs: 0, tolUs: 35_000, pulseLayer: "tactus" }],
        sounds: [
          {
            id: "snd_zero",
            startUs: 0,
            endUs: 120_000,
            endKind: "observed",
            anchors: [{ id: "anc_0", role: "transient_attack", atUs: 0, sigmaUs: 2_000 }],
            primaryAnchorId: "anc_0",
            salience: 0.9,
            characteristics: { family: "kick", register: "low", confidence: 0.92 },
          },
        ],
      });

      // Add a valid offer to verify zero-change user ops do NOT invalidate pending offers either
      const pendingOffer = bindOfferToBasis(state, {
        id: "off_watch",
        batchId: "b_watch",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 6_000_000 }],
        ops: [{ op: "upsert_beat", beat: { id: "b_cand", atUs: 2_000_000 } }],
        notes: [],
      });
      state = applyEvent(
        state,
        makeEvent(
          2,
          "ev_batch",
          "offer_batch",
          { batchId: "b_watch", offers: [pendingOffer] },
          { kind: "model" },
        ),
      );
      assert.equal(state.offers.get("off_watch")?.status, "valid");

      // Clamped Left nudge at 0 across all 4 adjustment ops!
      const afterClampedNudges = applyEvent(
        state,
        makeEvent(3, "ev_clamped_left", "edit", [
          {
            op: "update_sound",
            id: "snd_zero",
            patch: {
              startUs: 0,
              endUs: 120_000,
              anchors: [{ id: "anc_0", role: "transient_attack", atUs: 0, sigmaUs: 2_000 }],
            },
          },
          {
            op: "move_beat",
            id: "b_zero",
            atUs: 0,
            structural: {},
            confidence: 0.8,
          },
          {
            op: "move_boundary",
            boundaryId: "bnd_4s",
            atUs: 4_000_000,
          },
          {
            op: "move_unbeat",
            id: "ub_zero",
            atUs: 0,
            tolUs: 35_000,
            pulseLayer: "tactus",
          },
        ]),
      );

      assert.equal(afterClampedNudges.sounds.get("snd_zero")?.verdict, undefined);
      assert.equal(afterClampedNudges.sounds.get("snd_zero")?.revision, 1);
      assert.equal(afterClampedNudges.beats.get("b_zero")?.verdict, undefined);
      assert.equal(afterClampedNudges.beats.get("b_zero")?.revision, 1);
      assert.equal(afterClampedNudges.beats.get("b_zero")?.lineage, undefined);
      assert.equal(afterClampedNudges.boundaries.get("bnd_4s")?.verdict, undefined);
      assert.equal(afterClampedNudges.boundaries.get("bnd_4s")?.revision, 1);
      assert.equal(afterClampedNudges.unbeats.get("ub_zero")?.verdict, undefined);
      assert.equal(afterClampedNudges.unbeats.get("ub_zero")?.revision, 1);
      assert.equal(afterClampedNudges.offers.get("off_watch")?.status, "valid");

      // Also verify confirmed marks keep `kind: "confirmed"` and unchanged revision on clamped no-op nudges
      const confirmed = applyEvent(
        afterClampedNudges,
        makeEvent(4, "ev_confirm_snd", "edit", {
          op: "confirm_marks",
          targets: [{ kind: "sound", id: "snd_zero", expectedRevision: 1, facet: "occurrence" }],
        }),
      );
      assert.equal(confirmed.sounds.get("snd_zero")?.verdict?.kind, "confirmed");
      assert.equal(confirmed.sounds.get("snd_zero")?.revision, 2);

      const afterConfirmedNoop = applyEvent(
        confirmed,
        makeEvent(5, "ev_confirmed_noop", "edit", {
          op: "update_sound",
          id: "snd_zero",
          patch: { startUs: 0, endUs: 120_000 },
        }),
      );
      assert.equal(afterConfirmedNoop.sounds.get("snd_zero")?.verdict?.kind, "confirmed");
      assert.equal(afterConfirmedNoop.sounds.get("snd_zero")?.revision, 2);
    });
  });

  describe("Astra P1 #12: Explicit intervalCount on TempoPoint across all kinematics & bridges", () => {
    it("emits intervalCount: 4 on terminal 5-beat ramp [0, 300, 950, 1640, 2340 ms] and exact interval counts across all branches", () => {
      // 1. Exact Astra counterexample: 5 beats at [0, 300, 950, 1640, 2340 ms] with kinematics: "ramp"
      const rampState = createAudioState(5_000_000, {
        segments: [{ id: "seg_ramp", startUs: 0, endUs: 5_000_000, regime: "auto", kinematics: "ramp" }],
        beats: [
          { id: "b0", atUs: 0 },
          { id: "b1", atUs: 300_000 },
          { id: "b2", atUs: 950_000 },
          { id: "b3", atUs: 1_640_000 },
          { id: "b4", atUs: 2_340_000 },
        ],
      });
      const rampMap = projectTempoMap(rampState);
      assert.equal(rampMap.points.length, 1);
      assert.equal(rampMap.points[0].kind, "ramp");
      assert.equal(rampMap.points[0].atUs, 0);
      assert.equal(rampMap.points[0].endUs, 2_340_000);
      assert.equal(rampMap.points[0].beatCount, 5);
      assert.equal(rampMap.points[0].intervalCount, 4);

      // 2. Connected ramp -> steady -> fermata_hold -> isolated single beat after break
      const multiState = createAudioState(16_000_000, {
        segments: [
          { id: "seg_r", startUs: 0, endUs: 2_000_000, regime: "auto", kinematics: "ramp" },
          { id: "seg_s", startUs: 2_000_000, endUs: 8_000_000, regime: "auto", kinematics: "steady" },
          { id: "seg_brk", startUs: 8_000_000, endUs: 12_000_000, regime: "break" },
          { id: "seg_end", startUs: 12_000_000, endUs: 16_000_000, regime: "free" },
        ],
        boundaries: [
          { id: "bnd_1", atUs: 2_000_000 },
          { id: "bnd_2", atUs: 8_000_000 },
          { id: "bnd_3", atUs: 12_000_000 },
        ],
        beats: [
          // Connected ramp (3 beats reaching s0 at 2_000_000 -> beatCount: 3, intervalCount: 3)
          { id: "r0", atUs: 500_000 },
          { id: "r1", atUs: 1_100_000 },
          { id: "r2", atUs: 1_600_000 },
          // Steady run + fermata + post-fermata steady run (6 beats total in connected component #1 = 9 beats -> 8 intervals!)
          { id: "s0", atUs: 2_000_000 },
          { id: "s1", atUs: 2_500_000 },
          { id: "s2", atUs: 3_000_000, structural: { fermata: true } },
          { id: "s3", atUs: 4_200_000 },
          { id: "s4", atUs: 4_700_000 },
          { id: "s5", atUs: 5_200_000 },
          // Isolated single beat after break (endUs === null -> beatCount: 1, intervalCount: 0)
          { id: "iso_0", atUs: 13_000_000 },
        ],
      });

      const multiMap = projectTempoMap(multiState);
      for (const pt of multiMap.points) {
        assert.ok(Number.isInteger(pt.intervalCount) && pt.intervalCount >= 0);
      }

      // Connected component #1 has 9 beats [r0..s5] -> 8 total inter-beat intervals!
      const comp1 = multiMap.points.filter((p) => p.atUs < 8_000_000);
      assert.equal(comp1[0].kind, "ramp");
      assert.equal(comp1[0].beatCount, 3);
      assert.equal(comp1[0].intervalCount, 3);
      assert.ok(comp1.some((p) => p.kind === "fermata_hold" && p.beatCount === 1 && p.intervalCount === 1));
      const comp1Beats = comp1.reduce((acc, p) => acc + p.beatCount, 0);
      const comp1Intervals = comp1.reduce((acc, p) => acc + p.intervalCount, 0);
      assert.equal(comp1Beats, 9);
      assert.equal(comp1Intervals, 8);

      // Isolated single beat after break has endUs: null, beatCount: 1, intervalCount: 0
      const isoPt = multiMap.points.find((p) => p.startBeatId === "iso_0");
      assert.equal(isoPt?.endUs, null);
      assert.equal(isoPt?.beatCount, 1);
      assert.equal(isoPt?.intervalCount, 0);
    });
  });

  describe("Astra P2 #16: Identity-preserving move_unbeat across TransactionOp and OfferOp", () => {
    it("preserves Unbeat id, origin, createdByEventId, and replaces across user nudges, undo, and offer validation/preview", async () => {
      assert.ok(TRANSACTION_OP_KINDS.includes("move_unbeat"));
      assert.ok(OFFER_OP_KINDS.includes("move_unbeat"));

      const mock = new ScriptedMockProvider();
      const ctrl = new SessionController({ provider: mock });
      await ctrl.bindAudio({ audioId: "song_p2_16", durationUs: 16_000_000 });
      ctrl.loadDraft({
        segments: [{ id: "seg_0", startUs: 0, endUs: 16_000_000, regime: "auto" }],
        beats: [{ id: "b_false", atUs: 2_000_000, confidence: 0.42 }],
        unbeats: [{ id: "ub_model", atUs: 6_000_000, tolUs: 35_000, pulseLayer: "tactus" }],
      });

      // 1. Convert b_false -> ub_conv via beat_to_unbeat, then nudge via move_unbeat
      const { event: convEv } = ctrl.applyEdit({
        op: "beat_to_unbeat",
        conversions: [
          {
            beatId: "b_false",
            expectedRevision: 1,
            newUnbeatId: "ub_conv",
            pulseLayer: "tactus",
          },
        ],
      });

      // Also reject an offer at [12s, 15s) to prove move_unbeat at 2s does NOT wipe distant suppressions
      mock._script.push({
        batchId: "b_far_sup",
        offers: [
          {
            id: "off_far_sup",
            batchId: "b_far_sup",
            kind: "pulse",
            writeRanges: [{ startUs: 12_000_000, endUs: 15_000_000 }],
            ops: [{ op: "upsert_beat", beat: { id: "b_far_13", atUs: 13_000_000 } }],
            notes: [],
          },
        ],
      });
      await ctrl.recompute({ scope: { startUs: 12_000_000, endUs: 15_000_000 }, reason: "far" });
      ctrl.rejectOffer("off_far_sup", "dismiss");
      assert.equal(ctrl.getState().suppressions.length, 1);

      ctrl.applyEdit({
        op: "move_unbeat",
        id: "ub_conv",
        atUs: 2_025_000,
        tolUs: 28_000,
        pulseLayer: "subdivision",
        via: "nudge",
      });
      assert.equal(ctrl.getState().suppressions.length, 1);

      const movedUb = ctrl.getState().unbeats.get("ub_conv");
      assert.equal(movedUb?.id, "ub_conv");
      assert.equal(movedUb?.origin, "user");
      assert.equal(movedUb?.createdByEventId, convEv.id);
      assert.equal(movedUb?.revision, 2);
      assert.equal(movedUb?.atUs, 2_025_000);
      assert.equal(movedUb?.tolUs, 28_000);
      assert.equal(movedUb?.pulseLayer, "subdivision");
      assert.deepEqual(movedUb?.replaces, {
        beatId: "b_false",
        origin: "import",
        createdByEventId: ctrl.getTrace()[1].id,
        priorRevision: 1,
        atUs: 2_000_000,
        confidence: 0.42,
      });
      assert.equal(movedUb?.verdict?.kind, "placed");
      assert.equal(movedUb?.verdict?.via, "nudge");
      assert.equal(movedUb?.verdict?.tolUs, 28_000);
      assert.equal(movedUb?.verdict?.valueDigest, computeEntityValueDigest("unbeat", movedUb));

      const repulsions = compileConstraints(ctrl.getState()).unbeatRepulsions;
      assert.equal(repulsions.length, 1);
      assert.equal(repulsions[0].atUs, 2_025_000);
      assert.equal(repulsions[0].replaces?.beatId, "b_false");

      // Undo restores 2_000_000 and revision 1 while keeping `replaces`
      ctrl.undo();
      assert.equal(ctrl.getState().unbeats.get("ub_conv")?.atUs, 2_000_000);
      assert.equal(ctrl.getState().unbeats.get("ub_conv")?.replaces?.beatId, "b_false");
      ctrl.redo();
      assert.equal(ctrl.getState().unbeats.get("ub_conv")?.atUs, 2_025_000);

      // 2. Offer moving unverified model unbeat inside writeRanges -> valid + preview diff & consequences
      const validMoveUbOffer = bindOfferToBasis(ctrl.getState(), {
        id: "off_mv_ub_ok",
        batchId: "b_ub",
        kind: "pulse",
        writeRanges: [{ startUs: 5_500_000, endUs: 7_000_000 }],
        ops: [{ op: "move_unbeat", id: "ub_model", atUs: 6_040_000 }],
        notes: [],
      });
      assert.equal(validMoveUbOffer.status, "valid");
      const prev = previewOffer(ctrl.getState(), validMoveUbOffer);
      assert.equal(prev.diff.unbeats.moved.length, 1);
      assert.equal(prev.diff.unbeats.moved[0].deltaUs, 40_000);
      assert.equal(prev.consequences.unbeatsMoved, 1);
      assert.match(formatConsequenceSummary(prev.consequences), /unbeat/i);

      // 3. Offer moving unbeat outside writeRanges (old or new atUs) -> out_of_scope
      const outOfScopeUbOffer = bindOfferToBasis(ctrl.getState(), {
        id: "off_mv_ub_oos",
        batchId: "b_ub",
        kind: "pulse",
        writeRanges: [{ startUs: 5_500_000, endUs: 6_500_000 }],
        ops: [{ op: "move_unbeat", id: "ub_model", atUs: 7_000_000 }],
        notes: [],
      });
      assert.equal(outOfScopeUbOffer.status, "invalid");
      assert.ok(outOfScopeUbOffer.conflicts.some((c) => c.kind === "out_of_scope"));

      // 4. Offer moving verified unbeat without challenge -> protected_verified; with challenge -> requires_challenge_confirm
      const unchallengedVerUb = bindOfferToBasis(ctrl.getState(), {
        id: "off_mv_ub_ver_bad",
        batchId: "b_ub",
        kind: "pulse",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [{ op: "move_unbeat", id: "ub_conv", atUs: 2_060_000 }],
        notes: [],
      });
      assert.equal(unchallengedVerUb.status, "invalid");
      assert.ok(
        unchallengedVerUb.conflicts.some(
          (c) => c.kind === "protected_verified" && c.targetKind === "unbeat",
        ),
      );

      const curConvUb = ctrl.getState().unbeats.get("ub_conv");
      const challengedVerUb = bindOfferToBasis(ctrl.getState(), {
        id: "off_mv_ub_ver_ch",
        batchId: "b_ub",
        kind: "pulse",
        writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
        ops: [{ op: "move_unbeat", id: "ub_conv", atUs: 2_060_000, tolUs: 22_000 }],
        challenges: [
          {
            challengeId: "ch_ub_conv",
            targetKind: "unbeat",
            targetId: "ub_conv",
            expectedRevision: curConvUb.revision,
            expectedValueDigest: curConvUb.verdict.valueDigest,
            reasonCode: "syncopated_offbeat_shift",
          },
        ],
        notes: [],
      });
      assert.equal(challengedVerUb.status, "requires_challenge_confirm");

      mock._script.push({
        batchId: "b_ub_live",
        offers: [
          {
            id: "off_mv_ub_live",
            batchId: "b_ub_live",
            kind: "pulse",
            writeRanges: [{ startUs: 1_500_000, endUs: 3_000_000 }],
            ops: [{ op: "move_unbeat", id: "ub_conv", atUs: 2_060_000, tolUs: 22_000 }],
            challenges: [
              {
                challengeId: "ch_ub_conv",
                targetKind: "unbeat",
                targetId: "ub_conv",
                expectedRevision: curConvUb.revision,
                expectedValueDigest: curConvUb.verdict.valueDigest,
                reasonCode: "syncopated_offbeat_shift",
              },
            ],
            notes: [],
          },
        ],
      });
      await ctrl.recompute({ scope: { startUs: 1_500_000, endUs: 3_000_000 }, reason: "ub_ch" });
      const accUb = ctrl.acceptOffer({
        offerIds: ["off_mv_ub_live"],
        authorizedChallenges: [
          {
            offerId: "off_mv_ub_live",
            challengeId: "ch_ub_conv",
            targetId: "ub_conv",
            expectedValueDigest: curConvUb.verdict.valueDigest,
            disposition: "confirm_replacement",
          },
        ],
      });
      assert.equal(accUb.ok, true);
      const confirmedUb = ctrl.getState().unbeats.get("ub_conv");
      assert.equal(confirmedUb?.atUs, 2_060_000);
      assert.equal(confirmedUb?.tolUs, 22_000);
      assert.equal(confirmedUb?.replaces?.beatId, "b_false");
      assert.equal(confirmedUb?.verdict?.kind, "confirmed");
      assert.equal(confirmedUb?.verdict?.tolUs, 22_000);
      assert.equal(
        confirmedUb?.verdict?.valueDigest,
        computeEntityValueDigest("unbeat", confirmedUb),
      );
    });
  });

  describe("Astra P2 #17: BeatBinding.soundId validation & cumulative drift via anchorAtUs", () => {
    it("rejects nonexistent soundId (SOUND_NOT_FOUND) and nonexistent anchorId (INVALID_BEAT_BINDING), and orphans cleanly on retract", () => {
      const state = createAudioState(10_000_000, {
        beats: [{ id: "b_0", atUs: 1_000_000 }],
        sounds: [
          {
            id: "snd_valid",
            startUs: 1_000_000,
            endUs: 1_100_000,
            endKind: "observed",
            anchors: [{ id: "anc_real", role: "transient_attack", atUs: 1_002_000 }],
            primaryAnchorId: "anc_real",
          },
        ],
      });

      // 1. snap_beat / upsert_beat / move_beat with missing soundId -> SOUND_NOT_FOUND
      for (const badOp of [
        {
          op: "snap_beat",
          id: "b_0",
          atUs: 1_002_000,
          boundTo: { soundId: "ghost_snd", anchorRole: "transient_attack" },
        },
        {
          op: "upsert_beat",
          beat: {
            id: "b_new",
            atUs: 2_000_000,
            boundTo: { soundId: "ghost_snd", anchorRole: "transient_attack" },
          },
        },
        {
          op: "move_beat",
          id: "b_0",
          atUs: 1_005_000,
          boundTo: { soundId: "ghost_snd", anchorRole: "transient_attack" },
        },
      ]) {
        assert.throws(
          () => applyEvent(state, makeEvent(2, "ev_bad_snd", "edit", badOp)),
          (err) => err instanceof EnsomiError && err.code === "SOUND_NOT_FOUND",
        );
      }

      // 2. Existing soundId with nonexistent anchorId -> INVALID_BEAT_BINDING
      assert.throws(
        () =>
          applyEvent(
            state,
            makeEvent(2, "ev_bad_anc", "edit", {
              op: "snap_beat",
              id: "b_0",
              atUs: 1_002_000,
              boundTo: {
                soundId: "snd_valid",
                anchorId: "anc_missing",
                anchorRole: "transient_attack",
              },
            }),
          ),
        (err) => err instanceof EnsomiError && err.code === "INVALID_BEAT_BINDING",
      );

      // 3. draft_loaded with dangling BeatBinding.soundId -> SOUND_NOT_FOUND
      assert.throws(
        () =>
          createAudioState(10_000_000, {
            beats: [
              {
                id: "b_dangling",
                atUs: 1_000_000,
                boundTo: { soundId: "no_sound", anchorRole: "transient_attack" },
              },
            ],
          }),
        (err) => err instanceof EnsomiError && err.code === "SOUND_NOT_FOUND",
      );

      // 4. Selective retract of user-created sound cleanly orphans dependent snap_beat
      const e0 = makeEvent(
        0,
        "e0",
        "audio_bound",
        { audioId: "song_retract_snd", durationUs: 10_000_000 },
        { kind: "host" },
      );
      const e1 = makeEvent(
        1,
        "e1_draft",
        "draft_loaded",
        { beats: [{ id: "b_0", atUs: 1_000_000 }] },
        { kind: "model" },
      );
      const e2 = makeEvent(2, "e2_add_snd", "edit", {
        op: "upsert_sound",
        sound: {
          id: "snd_u",
          startUs: 1_000_000,
          endUs: 1_100_000,
          anchors: [{ id: "a_u", role: "transient_attack", atUs: 1_003_000 }],
        },
      });
      const e3 = makeEvent(3, "e3_snap", "edit", {
        op: "snap_beat",
        id: "b_0",
        atUs: 1_003_000,
        boundTo: { soundId: "snd_u", anchorId: "a_u", anchorRole: "transient_attack" },
      });
      const e4 = makeEvent(4, "e4_retract_snd", "retract", { targetEventId: "e2_add_snd" });

      const folded = foldTrace([e0, e1, e2, e3, e4]);
      assert.equal(folded.orphanedTransactions.length, 1);
      assert.equal(folded.orphanedTransactions[0].eventId, "e3_snap");
      assert.equal(folded.orphanedTransactions[0].causeCode, "SOUND_NOT_FOUND");
      assert.equal(folded.beats.get("b_0")?.atUs, 1_000_000);
      assert.equal(folded.beats.get("b_0")?.boundTo, undefined);
    });

    it("tracks cumulative drift via anchorAtUs so thirty 1 ms nudges past 2 ms tolerance set alignmentStale: true", () => {
      let state = createAudioState(10_000_000, {
        beats: [
          { id: "b_ephem", atUs: 1_000_000 },
          {
            id: "b_model_ephem",
            atUs: 3_000_000,
            boundTo: { candidateEventId: "cand_m", anchorRole: "p_center" },
          },
        ],
      });

      // Snap b_ephem to ephemeral SnapCandidate (`candidateEventId` without `soundId`, default snap tolUs = 2_000 us)
      state = applyEvent(
        state,
        makeEvent(2, "ev_snap_ephem", "edit", {
          op: "snap_beat",
          id: "b_ephem",
          atUs: 1_000_000,
          boundTo: {
            candidateEventId: "cand_ephem_1",
            anchorId: "anc_ephem_1",
            anchorRole: "transient_attack",
          },
        }),
      );
      assert.equal(state.beats.get("b_ephem")?.boundTo?.anchorAtUs, 1_000_000);
      assert.equal(state.beats.get("b_ephem")?.boundTo?.alignmentStale, undefined);
      assert.equal(state.beats.get("b_model_ephem")?.boundTo?.anchorAtUs, 3_000_000);

      // Thirty sequential +1 ms (`1_000 us`) nudges (`move_beat` on b_ephem + `shift_beats` on unverified b_model_ephem)
      let seq = 3;
      for (let step = 1; step <= 30; step += 1) {
        state = applyEvent(
          state,
          makeEvent(seq++, `ev_nudge_${step}`, "edit", [
            {
              op: "move_beat",
              id: "b_ephem",
              atUs: 1_000_000 + step * 1_000,
            },
            {
              op: "shift_beats",
              startUs: 2_900_000,
              endUs: 3_500_000,
              deltaUs: 1_000,
            },
          ]),
        );
        if (step === 2) {
          // +2 ms <= 2 ms snap tolUs -> NOT stale yet!
          assert.equal(state.beats.get("b_ephem")?.boundTo?.alignmentStale, undefined);
          assert.equal(state.beats.get("b_ephem")?.boundTo?.anchorAtUs, 1_000_000);
        }
        if (step === 3) {
          // +3 ms > 2 ms snap tolUs -> becomes stale on step 3 even though single-step deltaUs is only 1 ms!
          assert.equal(state.beats.get("b_ephem")?.boundTo?.alignmentStale, true);
          assert.equal(state.beats.get("b_ephem")?.boundTo?.anchorAtUs, 1_000_000);
        }
        if (step === 15) {
          // +15 ms <= 15 ms default sound tolUs on unverified beat -> NOT stale yet!
          assert.equal(state.beats.get("b_model_ephem")?.boundTo?.alignmentStale, undefined);
        }
      }

      // After thirty 1 ms nudges (+30 ms total drift), BOTH bindings have alignmentStale: true and preserved anchorAtUs!
      const finalEphem = state.beats.get("b_ephem");
      assert.equal(finalEphem?.atUs, 1_030_000);
      assert.equal(finalEphem?.boundTo?.anchorAtUs, 1_000_000);
      assert.equal(finalEphem?.boundTo?.alignmentStale, true);

      const finalModelEphem = state.beats.get("b_model_ephem");
      assert.equal(finalModelEphem?.atUs, 3_030_000);
      assert.equal(finalModelEphem?.boundTo?.anchorAtUs, 3_000_000);
      assert.equal(finalModelEphem?.boundTo?.alignmentStale, true);
    });
  });

  describe("[Stage 2F-1 Astra Stage 2E #8 & #17]: replacementId write-scope/mutation & immediate BeatBinding alignmentStale", () => {
    it("#8a: rejects OfferChallenge.replacementId outside offer.writeRanges (status: invalid, conflict: out_of_scope, throws OUT_OF_SCOPE on offer_accept)", () => {
      // Verified beat b1 at 1.0s, unverified beat b2 at 8.0s
      let state = createAudioState(10_000_000, {
        beats: [{ id: "b2", atUs: 8_000_000, confidence: 0.75 }],
      });
      state = applyEvent(
        state,
        makeEvent(2, "ev_place_b1", "edit", {
          op: "upsert_beat",
          beat: { id: "b1", atUs: 1_000_000 },
        }),
      );
      const b1 = state.beats.get("b1");
      const d1 = computeEntityValueDigest("beat", b1);

      const outOfScopeRepOffer = bindOfferToBasis(state, {
        id: "off_rep_oos_8s",
        batchId: "b_2f1_8a",
        kind: "pulse",
        writeRanges: [{ startUs: 500_000, endUs: 2_000_000 }],
        ops: [{ op: "remove_beats", ids: ["b1"] }],
        challenges: [
          {
            challengeId: "ch_b1_oos",
            targetKind: "beat",
            targetId: "b1",
            expectedRevision: b1.revision,
            expectedValueDigest: d1,
            reasonCode: "launder_distant_beat",
            replacementId: "b2",
          },
        ],
        notes: [],
      });

      assert.equal(outOfScopeRepOffer.status, "invalid");
      assert.ok(outOfScopeRepOffer.conflicts.some((c) => c.kind === "out_of_scope"));

      const withBatch = applyEvent(
        state,
        makeEvent(
          3,
          "ev_batch_8a",
          "offer_batch",
          { batchId: "b_2f1_8a", offers: [outOfScopeRepOffer] },
          { kind: "model" },
        ),
      );
      assert.equal(withBatch.offers.get("off_rep_oos_8s")?.status, "invalid");

      assert.throws(
        () =>
          applyEvent(
            withBatch,
            makeEvent(4, "ev_acc_8a", "offer_accept", {
              offerIds: ["off_rep_oos_8s"],
              authorizedChallenges: [
                {
                  offerId: "off_rep_oos_8s",
                  challengeId: "ch_b1_oos",
                  targetId: "b1",
                  expectedValueDigest: d1,
                  disposition: "confirm_replacement",
                },
              ],
            }),
          ),
        (err) => err instanceof EnsomiError && err.code === "OUT_OF_SCOPE",
      );
    });

    it("#8b: rejects OfferChallenge.replacementId naming an untouched pre-existing mark inside offer.writeRanges (status: invalid, conflict: invalid_op, throws INVALID_OFFER on offer_accept)", () => {
      // Verified beat b1 at 1.0s, unverified beat b2 at 8.0s, unverified sound s2 at [7.0s, 7.5s), unverified soundClass sc2
      let state = createAudioState(10_000_000, {
        beats: [{ id: "b2", atUs: 8_000_000, confidence: 0.75 }],
        sounds: [
          {
            id: "s2",
            startUs: 7_000_000,
            endUs: 7_500_000,
            endKind: "observed",
            anchors: [{ id: "a_s2", role: "transient_attack", atUs: 7_005_000 }],
            primaryAnchorId: "a_s2",
          },
        ],
        soundClasses: [{ id: "sc2", exemplarSoundIds: ["s2"], matchMode: "hit" }],
      });
      state = applyEvent(
        state,
        makeEvent(2, "ev_place_b1_sc1", "edit", [
          { op: "upsert_beat", beat: { id: "b1", atUs: 1_000_000 } },
          {
            op: "upsert_sound_class",
            soundClass: { id: "sc1", exemplarSoundIds: ["s2"], matchMode: "voice" },
          },
        ]),
      );
      const b1 = state.beats.get("b1");
      const d1 = computeEntityValueDigest("beat", b1);

      // Offer scoped [0.5s, 9.0s) removes b1 and names untouched pre-existing b2 at 8.0s (and even tries a zero-change no-op move_beat!)
      for (const ops of [
        [{ op: "remove_beats", ids: ["b1"] }],
        [
          { op: "remove_beats", ids: ["b1"] },
          { op: "move_beat", id: "b2", atUs: 8_000_000, confidence: 0.75 },
        ],
      ]) {
        const untouchedRepOffer = bindOfferToBasis(state, {
          id: "off_rep_untouched",
          batchId: "b_2f1_8b",
          kind: "pulse",
          writeRanges: [{ startUs: 500_000, endUs: 9_000_000 }],
          ops,
          challenges: [
            {
              challengeId: "ch_b1_untouched",
              targetKind: "beat",
              targetId: "b1",
              expectedRevision: b1.revision,
              expectedValueDigest: d1,
              reasonCode: "launder_untouched_beat",
              replacementId: "b2",
            },
          ],
          notes: [],
        });

        assert.equal(untouchedRepOffer.status, "invalid");
        assert.ok(untouchedRepOffer.conflicts.some((c) => c.kind === "invalid_op"));

        const withBatch = applyEvent(
          state,
          makeEvent(
            3,
            "ev_batch_8b",
            "offer_batch",
            { batchId: "b_2f1_8b", offers: [untouchedRepOffer] },
            { kind: "model" },
          ),
        );
        assert.throws(
          () =>
            applyEvent(
              withBatch,
              makeEvent(4, "ev_acc_8b", "offer_accept", {
                offerIds: ["off_rep_untouched"],
                authorizedChallenges: [
                  {
                    offerId: "off_rep_untouched",
                    challengeId: "ch_b1_untouched",
                    targetId: "b1",
                    expectedValueDigest: d1,
                    disposition: "confirm_replacement",
                  },
                ],
              }),
            ),
          (err) => err instanceof EnsomiError && err.code === "INVALID_OFFER",
        );
      }

      // Also verify untouched sound_class replacementId is rejected with invalid_op
      const sc1 = state.soundClasses.get("sc1");
      const dSc1 = computeEntityValueDigest("sound_class", sc1);
      const untouchedScOffer = bindOfferToBasis(state, {
        id: "off_sc_untouched",
        batchId: "b_2f1_8b_sc",
        kind: "sound_match",
        writeRanges: [{ startUs: 500_000, endUs: 9_000_000 }],
        ops: [{ op: "remove_sound_classes", ids: ["sc1"] }],
        challenges: [
          {
            challengeId: "ch_sc1",
            targetKind: "sound_class",
            targetId: "sc1",
            expectedRevision: sc1.revision,
            expectedValueDigest: dSc1,
            reasonCode: "merge_class",
            replacementId: "sc2",
          },
        ],
        notes: [],
      });
      assert.equal(untouchedScOffer.status, "invalid");
      assert.ok(untouchedScOffer.conflicts.some((c) => c.kind === "invalid_op"));
    });

    it("#17: immediately flags alignmentStale: true when binding Beat.boundTo to a displaced Sound anchor, and explicitly normalizes shift_beats.includeVerified in TraceEvent/Offer", () => {
      let state = createAudioState(10_000_000, {
        beats: [
          { id: "b_snap", atUs: 1_000_000 },
          { id: "b_move", atUs: 1_000_000 },
        ],
        sounds: [
          {
            id: "snd_disp",
            startUs: 950_000,
            endUs: 1_300_000,
            endKind: "observed",
            anchors: [{ id: "anc_disp", role: "transient_attack", atUs: 1_008_000 }],
            primaryAnchorId: "anc_disp",
          },
        ],
      });

      // Move Sound anchor to 1,200,000 us
      state = applyEvent(
        state,
        makeEvent(2, "ev_move_snd", "edit", {
          op: "update_sound",
          id: "snd_disp",
          patch: {
            anchors: [{ id: "anc_disp", role: "transient_attack", atUs: 1_200_000 }],
          },
        }),
      );

      // Now snap_beat at 1,008,000 us (tolUs: 2_000) referencing { soundId: "snd_disp", anchorId: "anc_disp" }
      state = applyEvent(
        state,
        makeEvent(3, "ev_snap_stale", "edit", [
          {
            op: "snap_beat",
            id: "b_snap",
            atUs: 1_008_000,
            tolUs: 2_000,
            boundTo: {
              soundId: "snd_disp",
              anchorId: "anc_disp",
              anchorRole: "transient_attack",
            },
          },
          {
            op: "upsert_beat",
            beat: {
              id: "b_upsert_stale",
              atUs: 1_008_000,
              boundTo: {
                soundId: "snd_disp",
                anchorId: "anc_disp",
                anchorRole: "transient_attack",
              },
            },
            tolUs: 20_000,
          },
          {
            op: "move_beat",
            id: "b_move",
            atUs: 1_008_000,
            tolUs: 20_000,
            boundTo: {
              soundId: "snd_disp",
              anchorId: "anc_disp",
              anchorRole: "transient_attack",
            },
          },
          {
            op: "shift_beats",
            startUs: 0,
            endUs: 5_000_000,
            deltaUs: 5_000,
          },
        ]),
      );

      for (const bid of ["b_snap", "b_upsert_stale", "b_move"]) {
        const beat = state.beats.get(bid);
        assert.equal(beat?.boundTo?.anchorAtUs, 1_200_000);
        assert.equal(beat?.boundTo?.alignmentStale, true);
        assert.equal(beat?.verdict?.valueDigest, computeEntityValueDigest("beat", beat));
      }

      // Verify `shift_beats.includeVerified` is explicitly normalized to boolean `false` in stored TraceEvent and Offer
      const recordedEditOps = /** @type {any[]} */ (state.events[state.events.length - 1].payload);
      assert.equal(recordedEditOps[3].op, "shift_beats");
      assert.strictEqual(recordedEditOps[3].includeVerified, false);

      const boundShiftOffer = bindOfferToBasis(state, {
        id: "off_norm_shift",
        batchId: "b_norm_shift",
        kind: "pulse",
        writeRanges: [{ startUs: 0, endUs: 5_000_000 }],
        ops: [{ op: "shift_beats", startUs: 0, endUs: 2_000_000, deltaUs: 10_000 }],
        notes: [],
      });
      assert.strictEqual(boundShiftOffer.ops[0].includeVerified, false);
    });
  });
});
