import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  applyEvent,
  bindOfferToBasis,
  canonicalJson,
  compileConstraints,
  computeEntityValueDigest,
  computeIntervalMusicalDigest,
  createInitialState,
  EnsomiError,
  foldTrace,
  previewOffer,
  projectTempoMap,
  SessionController,
  TRACE_SCHEMA_VERSION,
  toSnapshot,
  validateOffer,
} from "../index.js";
import { generateSyntheticDraft, ScriptedMockProvider } from "../testing.js";

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

function createSeededSession(durationUs = 20_000_000) {
  let state = createInitialState();
  state = applyEvent(
    state,
    makeEvent(0, "ev_audio", "audio_bound", { audioId: "song_1", durationUs }, { kind: "host" }),
  );
  const draft = generateSyntheticDraft({
    durationUs,
    bpm: 120,
    soundCount: 16,
    segmentCount: 2,
    seed: 99,
  });
  state = applyEvent(
    state,
    makeEvent(1, "ev_draft", "draft_loaded", draft, { kind: "model" }),
  );
  return state;
}

describe("All 12 Multi-Model Audit Counterexamples (A1–A7 + Astra #1–#5) & Epistemic Invariants", () => {
  it("A1: bulk shift_beats NEVER manufactures verdicts on unverified beats, respects includeVerified, and marks alignmentStale", () => {
    let state = createSeededSession(600_000_000); // 10 min -> ~1,199 beats
    const totalBeats = state.beats.size;
    assert.ok(totalBeats > 768);

    // Bind and snap one beat (`beat_2`) and place one user beat (`b_user`)
    const snd0 = Array.from(state.sounds.values())[0];
    const anc0 = snd0.anchors[0];
    state = applyEvent(
      state,
      makeEvent(2, "ev_snap", "edit", [
        {
          op: "snap_beat",
          id: "beat_2",
          atUs: anc0.atUs,
          boundTo: {
            soundId: snd0.id,
            anchorId: anc0.id,
            anchorRole: anc0.role,
          },
        },
        {
          op: "upsert_beat",
          beat: { id: "b_user", atUs: 1_250_000 },
        },
      ]),
    );

    assert.equal(compileConstraints(state).beatGuides.length, 2);

    // Bulk shift entire 10-min song by +30 ms (`30_000 us`) with explicit `includeVerified: true`
    const shiftedAll = applyEvent(
      state,
      makeEvent(3, "ev_shift_all", "edit", {
        op: "shift_beats",
        startUs: 0,
        endUs: 590_000_000,
        deltaUs: 30_000,
        includeVerified: true,
      }),
    );

    // Unverified beats shifted in time while keeping origin === "model" and verdict === undefined!
    const b10 = shiftedAll.beats.get("beat_10");
    assert.equal(b10.origin, "model");
    assert.equal(b10.verdict, undefined);
    // Compiled guides remain ONLY the 2 human-verified beats (not 768+ fake guides!)
    assert.equal(compileConstraints(shiftedAll).beatGuides.length, 2);

    // Snapped beat preserved `boundTo` and marked `alignmentStale: true` since shifted 30 ms away from anchor!
    const snappedAfter = shiftedAll.beats.get("beat_2");
    assert.equal(snappedAfter.verdict?.kind, "adjusted");
    assert.equal(snappedAfter.boundTo?.soundId, snd0.id);
    assert.equal(snappedAfter.boundTo?.alignmentStale, true);

    // Omitted `includeVerified` defaults to `false`: shifts ONLY unverified beats and leaves verified beats stationary!
    const shiftedDefaultOmitted = applyEvent(
      state,
      makeEvent(3, "ev_shift_default", "edit", {
        op: "shift_beats",
        startUs: 0,
        endUs: 590_000_000,
        deltaUs: 15_000,
      }),
    );
    assert.equal(shiftedDefaultOmitted.beats.get("beat_2").atUs, anc0.atUs);
    assert.equal(shiftedDefaultOmitted.beats.get("b_user").atUs, 1_250_000);
    assert.equal(
      shiftedDefaultOmitted.beats.get("beat_10").atUs,
      state.beats.get("beat_10").atUs + 15_000,
    );

    // Explicit `includeVerified: false` behaves identically!
    const shiftedUnverifiedOnly = applyEvent(
      state,
      makeEvent(3, "ev_shift_unver", "edit", {
        op: "shift_beats",
        startUs: 0,
        endUs: 590_000_000,
        deltaUs: 15_000,
        includeVerified: false,
      }),
    );
    assert.equal(shiftedUnverifiedOnly.beats.get("beat_2").atUs, anc0.atUs);
    assert.equal(shiftedUnverifiedOnly.beats.get("b_user").atUs, 1_250_000);
    assert.equal(
      shiftedUnverifiedOnly.beats.get("beat_10").atUs,
      state.beats.get("beat_10").atUs + 15_000,
    );
  });

  it("A2 & Challenges: 8 ms offer shift on snapped user beat is blocked by protected_verified; explicit challenges + authorization + retract work end-to-end", async () => {
    const mock = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider: mock });
    await ctrl.openSong({ audioId: "song_a2", durationUs: 16_000_000 });

    const snap0 = ctrl.getSnapshot();
    const snd = snap0.sounds[0];
    const anc = snd.anchors[0];

    // User snaps `beat_0` (`tolUs` defaults to 2_000 us = 2 ms)
    ctrl.applyEdit({
      op: "snap_beat",
      id: "beat_0",
      atUs: anc.atUs,
      boundTo: { soundId: snd.id, anchorId: anc.id, anchorRole: anc.role },
    });

    // 1. Unchallenged offer shifting snapped user beat by +8 ms (`8_000 us`) and stripping boundTo -> INVALID (`protected_verified`)!
    const badOffer = bindOfferToBasis(ctrl.getSnapshot(), {
      id: "off_bad_8ms",
      batchId: "b_a2",
      kind: "pulse",
      writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
      ops: [{ op: "upsert_beat", beat: { id: "beat_0", atUs: anc.atUs + 8_000 } }],
      notes: [],
    });
    assert.equal(badOffer.status, "invalid");
    assert.ok(badOffer.conflicts.some((c) => c.kind === "protected_verified"));

    // 2. Challenged offer with matching `OfferChallenge` -> `requires_challenge_confirm`
    const snappedBeat = ctrl.getState().beats.get("beat_0");
    const expectedDigest = computeEntityValueDigest("beat", snappedBeat);
    mock._script.push({
      batchId: "b_ch",
      offers: [
        {
          id: "off_ch_8ms",
          batchId: "b_ch",
          kind: "pulse",
          writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
          challenges: [
            {
              challengeId: "ch_1",
              targetKind: "beat",
              targetId: "beat_0",
              expectedRevision: snappedBeat.revision,
              expectedValueDigest: expectedDigest,
              reasonCode: "acoustic_reanchor",
            },
          ],
          ops: [{ op: "move_beat", id: "beat_0", atUs: anc.atUs + 8_000 }],
          notes: [{ code: "reanchor", message: "Shift +8 ms to stronger transient" }],
        },
      ],
    });

    const rec = await ctrl.recompute({ scope: { startUs: 0, endUs: 4_000_000 }, reason: "test" });
    assert.equal(rec.offers[0].status, "requires_challenge_confirm");

    // Accepting WITHOUT authorization fails cleanly without mutating state!
    const unauth = ctrl.acceptOffer("off_ch_8ms");
    assert.equal(unauth.ok, false);
    assert.equal(unauth.status, "requires_challenge_confirm");
    assert.equal(unauth.requiredChallenges.length, 1);
    assert.equal(ctrl.getState().beats.get("beat_0").atUs, anc.atUs);

    // Accepting WITH `confirm_replacement` authorization succeeds and preserves compiled BeatGuide!
    const authAccept = ctrl.acceptOffer({
      offerIds: ["off_ch_8ms"],
      authorizedChallenges: [
        {
          offerId: "off_ch_8ms",
          challengeId: "ch_1",
          targetId: "beat_0",
          expectedValueDigest: expectedDigest,
          disposition: "confirm_replacement",
        },
      ],
    });
    assert.equal(authAccept.ok, true);
    const afterAccept = ctrl.getState().beats.get("beat_0");
    assert.equal(afterAccept.atUs, anc.atUs + 8_000);
    assert.equal(afterAccept.verdict?.kind, "confirmed");
    assert.equal(afterAccept.verdict?.via, "challenge_accept");
    assert.equal(compileConstraints(ctrl.getState()).beatGuides.length, 1);

    // Undo (`retract` of `offer_accept`) restores exact prior `atUs`, `verdict`, `boundTo`, and offer status!
    const undone = ctrl.undo();
    assert.equal(undone.ok, true);
    const restoredBeat = ctrl.getState().beats.get("beat_0");
    assert.equal(restoredBeat.atUs, anc.atUs);
    assert.equal(restoredBeat.verdict?.kind, "adjusted");
    assert.equal(restoredBeat.verdict?.valueDigest, expectedDigest);
    assert.equal(restoredBeat.boundTo?.soundId, snd.id);
    assert.equal(ctrl.getState().offers.get("off_ch_8ms")?.status, "requires_challenge_confirm");
  });

  it("A3: blocks provider/draft authority injection (origin: user / verdict / approval) and blocks analyze() from wiping human truth", async () => {
    let state = createSeededSession(10_000_000);

    // Model draft attempting to inject `origin: "user"` or `verdict` throws AUTHORITY_VIOLATION
    assert.throws(
      () =>
        applyEvent(
          state,
          makeEvent(
            2,
            "ev_bad_draft",
            "draft_loaded",
            { beats: [{ id: "evil_b", atUs: 100_000, origin: "user" }], replace: false },
            { kind: "model" },
          ),
        ),
      (err) => err instanceof EnsomiError && err.code === "AUTHORITY_VIOLATION",
    );

    // Unbeat in a model draft gets `origin: "model"`, `verdict: undefined`, and ZERO compiled repulsions!
    const withDraftUnbeat = applyEvent(
      state,
      makeEvent(
        2,
        "ev_draft_ub",
        "draft_loaded",
        { unbeats: [{ id: "model_ub", atUs: 250_000 }], replace: false },
        { kind: "model" },
      ),
    );
    assert.equal(withDraftUnbeat.unbeats.get("model_ub")?.origin, "model");
    assert.equal(withDraftUnbeat.unbeats.get("model_ub")?.verdict, undefined);
    assert.equal(compileConstraints(withDraftUnbeat).unbeatRepulsions.length, 0);

    // Once human truth exists, `draft_loaded(replace: true)` throws PROTECTED_HUMAN_TRUTH unless `reset_document` runs!
    const mock = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider: mock });
    await ctrl.openSong({ audioId: "song_a3", durationUs: 10_000_000 });
    ctrl.applyEdit({ op: "upsert_beat", beat: { id: "user_b1", atUs: 1_100_000 } });

    assert.throws(
      () => ctrl.loadDraft({ beats: [], replace: true }, { kind: "model" }),
      (err) => err instanceof EnsomiError && err.code === "PROTECTED_HUMAN_TRUTH",
    );

    // Background `analyze()` returns `"superseded"` instead of wiping `user_b1`!
    const lateAnalyze = await ctrl.analyze();
    assert.equal(lateAnalyze.status, "superseded");
    assert.equal(ctrl.getState().beats.has("user_b1"), true);

    // Explicit `resetDocument()` cleanly resets and remains undoable!
    ctrl.resetDocument({ reason: "start fresh" });
    assert.equal(ctrl.getState().beats.has("user_b1"), false);
    ctrl.undo();
    assert.equal(ctrl.getState().beats.has("user_b1"), true);
  });

  it("A4 & Spanned Sound Facets: confirm_marks & clear_verdicts certify exact marks/facets without mutating coordinates", () => {
    let state = createSeededSession(12_000_000);
    const b0 = state.beats.get("beat_0");
    const snd0 = Array.from(state.sounds.values())[0];

    // Confirm AI beat AND Sound `"occurrence"` facet (leaving `"estimated"` endUs uncertified!)
    state = applyEvent(
      state,
      makeEvent(2, "ev_conf", "edit", {
        op: "confirm_marks",
        targets: [
          { kind: "beat", id: b0.id, expectedRevision: b0.revision },
          { kind: "sound", id: snd0.id, expectedRevision: snd0.revision, facet: "occurrence" },
        ],
      }),
    );

    const confBeat = state.beats.get(b0.id);
    assert.equal(confBeat.atUs, b0.atUs);
    assert.equal(confBeat.origin, "model");
    assert.equal(confBeat.verdict?.kind, "confirmed");

    const confSound = state.sounds.get(snd0.id);
    assert.equal(confSound.verdict?.kind, "confirmed");
    assert.equal(confSound.verdict?.facet, "occurrence");

    // Offer refining `endUs` from `estimated` to `observed` does NOT violate `"occurrence"` verdict!
    const refineTailOffer = bindOfferToBasis(state, {
      id: "off_tail_refine",
      batchId: "b_snd",
      kind: "sound_match",
      writeRanges: [{ startUs: 0, endUs: 12_000_000 }],
      ops: [
        {
          op: "update_sound",
          id: snd0.id,
          patch: { endUs: snd0.endUs + 40_000, endKind: "observed" },
        },
      ],
      notes: [],
    });
    assert.equal(refineTailOffer.status, "valid");

    // Offer changing `voiceGroupId` DOES violate `"occurrence"` verdict -> `protected_verified`!
    const overwriteVoiceOffer = bindOfferToBasis(state, {
      id: "off_voice_overwrite",
      batchId: "b_snd2",
      kind: "sound_match",
      writeRanges: [{ startUs: 0, endUs: 12_000_000 }],
      ops: [
        {
          op: "update_sound",
          id: snd0.id,
          patch: { voiceGroupId: "different_voice" },
        },
      ],
      notes: [],
    });
    assert.equal(overwriteVoiceOffer.status, "invalid");
    assert.ok(overwriteVoiceOffer.conflicts.some((c) => c.kind === "protected_verified"));

    // Clear verdict cleanly removes verdict without altering origin or coordinates
    const cleared = applyEvent(
      state,
      makeEvent(3, "ev_clear", "edit", {
        op: "clear_verdicts",
        targets: [{ kind: "beat", id: b0.id, expectedRevision: confBeat.revision }],
      }),
    );
    assert.equal(cleared.beats.get(b0.id)?.verdict, undefined);
    assert.equal(cleared.beats.get(b0.id)?.atUs, b0.atUs);
    assert.equal(cleared.beats.get(b0.id)?.origin, "model");
  });

  it("A5: Boundary & Segment provenance survive keep, regime, kinematics, split, and symmetric merge", () => {
    let state = createSeededSession(20_000_000); // has model `bnd_0` at 10_000_000 (`seg_0`, `seg_1`)
    assert.equal(state.boundaries.get("bnd_0")?.origin, "model");
    assert.equal(state.boundaries.get("bnd_0")?.verdict, undefined);

    // User splits `seg_0` at 5_000_000 -> `bnd_user` gets `origin: "user"` & `verdict: placed`
    state = applyEvent(
      state,
      makeEvent(2, "ev_split", "edit", {
        op: "split_segment",
        segmentId: "seg_0",
        atUs: 5_000_000,
        newBoundaryId: "bnd_user",
        newSegmentId: "seg_mid",
      }),
    );
    assert.equal(state.boundaries.get("bnd_user")?.origin, "user");
    assert.equal(state.boundaries.get("bnd_user")?.verdict?.kind, "placed");

    // Toggle keep, regime, and kinematics on `seg_mid` and `seg_1`
    state = applyEvent(
      state,
      makeEvent(3, "ev_policies", "edit", [
        { op: "set_segment_keep", segmentId: "seg_mid", keep: true },
        { op: "set_segment_regime", segmentId: "seg_1", regime: "free" },
        { op: "set_segment_kinematics", segmentId: "seg_1", kinematics: "rubato" },
      ]),
    );

    // Model boundary `bnd_0` stayed `origin: "model"`, `verdict: undefined`!
    assert.equal(state.boundaries.get("bnd_0")?.origin, "model");
    assert.equal(state.boundaries.get("bnd_0")?.verdict, undefined);
    // User boundary `bnd_user` kept `origin: "user"` & `verdict: placed`!
    assert.equal(state.boundaries.get("bnd_user")?.origin, "user");
    assert.equal(state.boundaries.get("bnd_user")?.verdict?.kind, "placed");
    // `seg_1` kept `origin: "model"`!
    assert.equal(state.segments.find((s) => s.id === "seg_1")?.origin, "model");
    // Beats inside kept `seg_mid` did NOT gain fake verdicts!
    for (const b of state.beats.values()) {
      if (b.atUs >= 5_000_000 && b.atUs < 10_000_000) {
        assert.equal(b.verdict, undefined);
      }
    }

    // Symmetric merge of kept `seg_mid` ([5s, 10s)) + free `seg_1` ([10s, 20s)) preserves BOTH
    // the [5s, 10s) `keep` approval AND the [10s, 20s) `free` sub-interval as child Spans!
    const merged = applyEvent(
      state,
      makeEvent(4, "ev_merge", "edit", {
        op: "merge_segments",
        leftSegmentId: "seg_mid",
        rightSegmentId: "seg_1",
        mergedRegime: "auto",
      }),
    );
    const keepSpans = merged.spans.filter((sp) => sp.kind === "keep");
    const freeSpans = merged.spans.filter((sp) => sp.kind === "free");
    assert.equal(keepSpans.length, 1);
    assert.equal(keepSpans[0].startUs, 5_000_000);
    assert.equal(keepSpans[0].endUs, 10_000_000);
    assert.ok(keepSpans[0].approval !== undefined);
    assert.equal(freeSpans.length, 1);
    assert.equal(freeSpans[0].startUs, 10_000_000);
    assert.equal(freeSpans[0].endUs, 20_000_000);
  });

  it("A6: beat_to_unbeat converts atomically with replaces provenance, and place_unbeat never deletes nearby human-verified beats", () => {
    let state = createSeededSession(10_000_000);
    const aiBeat = state.beats.get("beat_1"); // at 1_000_000

    // Place a human-verified beat 15 ms away from `beat_2` (at 1_500_000)
    state = applyEvent(
      state,
      makeEvent(2, "ev_user_b", "edit", {
        op: "upsert_beat",
        beat: { id: "human_near", atUs: 1_515_000 },
      }),
    );

    // 1. `beat_to_unbeat` on `beat_1`
    state = applyEvent(
      state,
      makeEvent(3, "ev_b2u", "edit", {
        op: "beat_to_unbeat",
        conversions: [
          {
            beatId: aiBeat.id,
            expectedRevision: aiBeat.revision,
            newUnbeatId: "ub_from_b1",
            pulseLayer: "tactus",
          },
        ],
      }),
    );
    assert.equal(state.beats.has(aiBeat.id), false);
    const ub1 = state.unbeats.get("ub_from_b1");
    assert.equal(ub1?.atUs, aiBeat.atUs);
    assert.equal(ub1?.verdict?.kind, "placed");
    assert.equal(ub1?.replaces?.beatId, aiBeat.id);
    assert.equal(ub1?.replaces?.origin, "model");

    // 2. `place_unbeat` at 1_505_000 with `captureUnverifiedRadiusUs: 30_000`
    //    Captures AI `beat_2` (5 ms away) while sparing `human_near` (10 ms away)!
    state = applyEvent(
      state,
      makeEvent(4, "ev_place_ub", "edit", {
        op: "place_unbeat",
        id: "ub_tap",
        atUs: 1_505_000,
        captureUnverifiedRadiusUs: 30_000,
      }),
    );
    assert.equal(state.beats.has("beat_2"), false);
    assert.equal(state.beats.has("human_near"), true);
    assert.equal(state.unbeats.get("ub_tap")?.replaces?.beatId, "beat_2");
  });

  it("A7: previewOffer computes full diff (beats + boundaries + segments + sounds) and previewTempoMap using the unified interpreter", () => {
    const state = createSeededSession(16_000_000);
    const offer = bindOfferToBasis(state, {
      id: "off_full_preview",
      batchId: "b_prev",
      kind: "segmentation",
      writeRanges: [{ startUs: 0, endUs: 8_000_000 }],
      ops: [
        {
          op: "split_segment",
          segmentId: "seg_0",
          atUs: 4_000_000,
          newBoundaryId: "bnd_prev",
          newSegmentId: "seg_prev_r",
          rightRegime: "free",
        },
        {
          op: "upsert_beat",
          beat: { id: "b_prev_new", atUs: 4_250_000, confidence: 0.95 },
        },
        {
          op: "upsert_sound",
          sound: {
            id: "snd_prev_new",
            startUs: 4_250_000,
            endUs: 4_400_000,
            endKind: "observed",
            anchors: [{ id: "a0", role: "transient_attack", atUs: 4_250_000 }],
            primaryAnchorId: "a0",
          },
        },
      ],
      notes: [],
    });

    const preview = previewOffer(state, offer);
    assert.equal(preview.validation.valid, true);
    assert.equal(preview.diff.boundaries.added.length, 1);
    assert.equal(preview.diff.segments.added.length, 1);
    assert.equal(preview.diff.beats.added.length, 1);
    assert.equal(preview.diff.sounds.added.length, 1);
    assert.ok(preview.previewTempoMap.points.length > 0);
  });

  it("Astra #1: zero 3-way validate / accept / replay divergence when reusing a deleted beat ID in split_segment", () => {
    const state = createSeededSession(16_000_000);
    // Offer tries `[remove_beats(["beat_0"]), split_segment({ newSegmentId: "beat_0" })]`
    const offer = bindOfferToBasis(state, {
      id: "off_astra1",
      batchId: "b_astra1",
      kind: "segmentation",
      writeRanges: [{ startUs: 0, endUs: 8_000_000 }],
      ops: [
        { op: "remove_beats", ids: ["beat_0"] },
        {
          op: "split_segment",
          segmentId: "seg_0",
          atUs: 4_000_000,
          newBoundaryId: "bnd_ok",
          newSegmentId: "beat_0", // retired beat ID!
        },
      ],
      notes: [],
    });

    // 1. `validateOffer` rejects as `invalid` (`invalid_op`)
    assert.equal(offer.status, "invalid");
    assert.equal(validateOffer(state, offer).valid, false);

    // 2. Direct `edit` throws `DUPLICATE_ENTITY_ID` and leaves `beat_0` untouched (atomic rollback!)
    assert.throws(
      () =>
        applyEvent(
          state,
          makeEvent(2, "ev_dup", "edit", [
            { op: "remove_beats", ids: ["beat_0"] },
            {
              op: "split_segment",
              segmentId: "seg_0",
              atUs: 4_000_000,
              newBoundaryId: "bnd_ok",
              newSegmentId: "beat_0",
            },
          ]),
        ),
      (err) => err instanceof EnsomiError && err.code === "DUPLICATE_ENTITY_ID",
    );
    assert.equal(state.beats.has("beat_0"), true);
  });

  it("Astra #2: selective retract aborts the ENTIRE dependent compound transaction atomically (zero half-applied ops!)", () => {
    let state = createSeededSession(16_000_000);
    // ev2: split seg_0 -> creates seg_child
    state = applyEvent(
      state,
      makeEvent(2, "ev_split", "edit", {
        op: "split_segment",
        segmentId: "seg_0",
        atUs: 4_000_000,
        newBoundaryId: "bnd_c",
        newSegmentId: "seg_child",
      }),
    );
    // ev3: compound transaction `[upsert_beat("b_compound"), set_segment_keep("seg_child", true)]`
    // Notice `upsert_beat` runs BEFORE `set_segment_keep` — rollback journal must undo `b_compound` too!
    state = applyEvent(
      state,
      makeEvent(3, "ev_compound", "edit", [
        { op: "upsert_beat", beat: { id: "b_compound", atUs: 2_200_000 } },
        { op: "set_segment_keep", segmentId: "seg_child", keep: true },
      ]),
    );
    assert.equal(state.beats.has("b_compound"), true);

    // ev4: selectively retract `ev_split`!
    const afterRetract = applyEvent(
      state,
      makeEvent(4, "ev_retract_split", "retract", { targetEventId: "ev_split" }),
    );
    assert.equal(afterRetract.beats.has("b_compound"), false);
    assert.equal(afterRetract.orphanedTransactions.length, 1);
    assert.equal(afterRetract.orphanedTransactions[0].eventId, "ev_compound");
    assert.equal(afterRetract.orphanedTransactions[0].code, "ORPHANED_BY_RETRACT");

    // Redo (`retract` of `ev_retract_split`) restores BOTH `seg_child` Keep AND `b_compound`!
    const afterRedo = applyEvent(
      afterRetract,
      makeEvent(5, "ev_redo_split", "retract", { targetEventId: "ev_retract_split" }),
    );
    assert.equal(afterRedo.orphanedTransactions.length, 0);
    assert.equal(afterRedo.beats.has("b_compound"), true);
    assert.ok(afterRedo.segments.find((s) => s.id === "seg_child")?.approval !== undefined);
  });

  it("Astra #3: pending offers go stale (`policy_changed`) on regime/free-span change, and semantic no-ops write nothing", () => {
    let state = createSeededSession(16_000_000);
    const pulseOffer = bindOfferToBasis(state, {
      id: "off_pulse",
      batchId: "b_p",
      kind: "pulse",
      writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
      ops: [{ op: "upsert_beat", beat: { id: "b_new", atUs: 1_100_000 } }],
      notes: [],
    });
    state = applyEvent(
      state,
      makeEvent(2, "ev_batch", "offer_batch", { batchId: "b_p", offers: [pulseOffer] }, { kind: "model" }),
    );
    assert.equal(state.offers.get("off_pulse")?.status, "valid");

    // Semantic no-op: setting `seg_0` to its already-active `"auto"` regime does NOT bump revision or stale offers!
    const afterNoop = applyEvent(
      state,
      makeEvent(3, "ev_noop", "edit", { op: "set_segment_regime", segmentId: "seg_0", regime: "auto" }),
    );
    assert.equal(afterNoop.segments[0].revision, state.segments[0].revision);
    assert.equal(afterNoop.offers.get("off_pulse")?.status, "valid");

    // Adding an overlapping `free` Span transitions `off_pulse` to `"stale"` (`policy_changed`)!
    const afterFreeSpan = applyEvent(
      afterNoop,
      makeEvent(4, "ev_free_span", "edit", {
        op: "set_range_regime",
        startUs: 1_000_000,
        endUs: 3_000_000,
        regime: "free",
      }),
    );
    const updatedOffer = afterFreeSpan.offers.get("off_pulse");
    assert.equal(updatedOffer?.status, "stale");
    assert.ok(updatedOffer?.conflicts.some((c) => c.kind === "policy_changed"));
  });

  it("Astra #4: blocks cross-kind Beat/Sound ID collisions and out-of-bounds Sound / shift_beats edits", () => {
    const state = createSeededSession(10_000_000);

    // Sound colliding with existing Beat ID `"beat_0"`
    assert.throws(
      () =>
        applyEvent(
          state,
          makeEvent(2, "ev_coll", "edit", {
            op: "upsert_sound",
            sound: {
              id: "beat_0",
              startUs: 1_000_000,
              endUs: 1_200_000,
              anchors: [{ id: "a0", role: "transient_attack", atUs: 1_000_000 }],
            },
          }),
        ),
      (err) => err instanceof EnsomiError && err.code === "DUPLICATE_ENTITY_ID",
    );

    // Sound exceeding track `durationUs` (`11_000_000 > 10_000_000`)
    assert.throws(
      () =>
        applyEvent(
          state,
          makeEvent(2, "ev_oob_snd", "edit", {
            op: "upsert_sound",
            sound: {
              id: "snd_oob",
              startUs: 9_900_000,
              endUs: 11_000_000,
              anchors: [{ id: "a0", role: "transient_attack", atUs: 9_900_000 }],
            },
          }),
        ),
      (err) => err instanceof EnsomiError && err.code === "OUT_OF_BOUNDS",
    );

    // `shift_beats` pushing last beat past `durationUs`
    assert.throws(
      () =>
        applyEvent(
          state,
          makeEvent(2, "ev_oob_shift", "edit", {
            op: "shift_beats",
            startUs: 0,
            endUs: 10_000_000,
            deltaUs: 2_000_000,
          }),
        ),
      (err) => err instanceof EnsomiError && err.code === "OUT_OF_BOUNDS",
    );
  });

  it("Astra #5: preserves co-timed (< 8 ms / 0 us) multi-role & polyphonic SnapCandidates without candidate suppression", async () => {
    const mock = new ScriptedMockProvider({
      snapScript: [
        [
          {
            candidateId: "c_kick_att",
            atUs: 2_000_000,
            deltaUs: 0,
            anchorId: "a_kick",
            role: "transient_attack",
            salience: 0.94,
            labelCode: "kick",
            label: "Kick attack",
            sound: {
              startUs: 2_000_000,
              endUs: 2_090_000,
              endKind: "observed",
              anchors: [{ id: "a_kick", role: "transient_attack", atUs: 2_000_000 }],
              primaryAnchorId: "a_kick",
              characteristics: { family: "kick", stereoWidth: 0.1 },
            },
          },
          {
            candidateId: "c_wide_pad",
            atUs: 2_003_000, // 3 ms apart (< 8 ms), wide stereo!
            deltaUs: 3_000,
            anchorId: "a_pad",
            role: "harmonic_change",
            salience: 0.89,
            labelCode: "synth_wide",
            label: "Wide stereo synth",
            sound: {
              startUs: 2_000_000,
              endUs: 2_500_000,
              endKind: "right_censored",
              anchors: [{ id: "a_pad", role: "harmonic_change", atUs: 2_003_000 }],
              primaryAnchorId: "a_pad",
              characteristics: { family: "bass_synth", stereoWidth: 1.0, stereoPan: -0.5 },
            },
          },
        ],
      ],
    });
    const ctrl = new SessionController({ provider: mock });
    await ctrl.bindAudio({ audioId: "song_a5", durationUs: 10_000_000 });
    const res = await ctrl.suggestSnaps({ atUs: 2_000_000, windowUs: 50_000, immediate: true });
    assert.equal(res.status, "ok");
    assert.equal(res.candidates.length, 2);
    assert.equal(ctrl.getTrace().length, 1); // 100% trace-free beyond audio_bound!
  });

  it("Full Keep Influence-Footprint, alternativeGroup vs batchId, and derivedFrom source-retract staleness", async () => {
    const mock = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider: mock });
    await ctrl.openSong({ audioId: "song_keep", durationUs: 24_000_000 });

    // Lock Keep on sub-span [4_000_000, 8_000_000) inside `seg_0` ([0, 24_000_000))
    ctrl.applyEdit({ op: "set_range_keep", startUs: 4_000_000, endUs: 8_000_000, keep: true });
    const preKeepDigest = computeIntervalMusicalDigest(ctrl.getState(), 4_000_000, 8_000_000);
    const preKeepTempo = projectTempoMap(ctrl.getState()).points.filter(
      (p) => p.atUs >= 4_000_000 && p.atUs < 8_000_000,
    );

    // 1. Neighboring edits BEFORE 4s and AFTER 8s do NOT bleed into Keep's TempoMap or digest!
    ctrl.applyEdit([
      { op: "upsert_beat", beat: { id: "b_before", atUs: 3_900_000 } },
      { op: "upsert_beat", beat: { id: "b_after", atUs: 8_100_000 } },
    ]);
    const postNeighborDigest = computeIntervalMusicalDigest(ctrl.getState(), 4_000_000, 8_000_000);
    const postKeepTempo = projectTempoMap(ctrl.getState()).points.filter(
      (p) => p.atUs >= 4_000_000 && p.atUs < 8_000_000,
    );
    assert.equal(postNeighborDigest, preKeepDigest);
    assert.equal(canonicalJson(postKeepTempo), canonicalJson(preKeepTempo));
    assert.equal(ctrl.getState().spans[0].approval?.modifiedSinceApproval, undefined);

    // 2. Offer whose Sound or regime bleeds into [4s, 8s) is rejected with `protected_keep`!
    const bleedOffer = bindOfferToBasis(ctrl.getSnapshot(), {
      id: "off_bleed",
      batchId: "b_bleed",
      kind: "sound_match",
      writeRanges: [{ startUs: 3_500_000, endUs: 4_500_000 }],
      ops: [
        {
          op: "upsert_sound",
          sound: {
            id: "snd_bleed",
            startUs: 3_800_000,
            endUs: 4_200_000, // overlaps Keep [4s, 8s)!
            anchors: [{ id: "a0", role: "transient_attack", atUs: 3_800_000 }],
          },
        },
      ],
      notes: [],
    });
    assert.equal(bleedOffer.status, "invalid");
    assert.ok(bleedOffer.conflicts.some((c) => c.kind === "protected_keep"));

    // 3. Direct user edit inside Keep IS allowed and flags `modifiedSinceApproval = true`!
    ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b_user_in_keep", atUs: 5_200_000 } });
    assert.equal(ctrl.getState().spans[0].approval?.modifiedSinceApproval, true);

    // 4. `propagation` batch with 2 independent destinations (same `batchId`, NO `alternativeGroup`)
    //    + `derivedFrom` supporting `b_before`:
    const propRes = await ctrl.recompute({
      scope: { startUs: 3_500_000, endUs: 4_000_000 },
      reason: "propagation",
    });
    assert.equal(propRes.offers.length, 2);
    const [prop0, prop1] = propRes.offers;
    assert.equal(prop0.batchId, prop1.batchId);
    assert.equal(prop0.alternativeGroup, undefined);

    // Accepting `prop0` leaves `prop1` VALID (not superseded!)
    const acc0 = ctrl.acceptOffer(prop0.id);
    assert.equal(acc0.ok, true);
    assert.equal(acc0.supersededOfferIds.length, 0);
    assert.equal(ctrl.getState().offers.get(prop1.id)?.status, "valid");

    // Editing `derivedFrom` source beat `b_before` marks `prop1` STALE (`source_changed`)!
    ctrl.applyEdit({ op: "move_beat", id: "b_before", atUs: 3_850_000 });
    const staleProp1 = ctrl.getState().offers.get(prop1.id);
    assert.equal(staleProp1?.status, "stale");
    assert.ok(staleProp1?.conflicts.some((c) => c.kind === "source_changed"));

    // 5. `triplet_vs_reset` with shared `alternativeGroup`: accepting option 0 supersedes option 1!
    const altRes = await ctrl.recompute({
      scope: { startUs: 12_000_000, endUs: 16_000_000 },
      reason: "triplet_vs_reset",
    });
    assert.equal(altRes.offers.length, 2);
    const accOpt0 = ctrl.acceptOffer(altRes.offers[0].id);
    assert.equal(accOpt0.ok, true);
    assert.deepEqual(accOpt0.supersededOfferIds, [altRes.offers[1].id]);
    assert.equal(ctrl.getState().offers.get(altRes.offers[1].id)?.status, "superseded");
    assert.equal(toSnapshot(ctrl.getState()).schemaVersion, "0.3");
  });
});
