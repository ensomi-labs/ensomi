import { describe, it } from "node:test";
import assert from "node:assert/strict";

import {
  canonicalJson,
  computeGridFit,
  createCheckpoint,
  parseTraceNdjson,
  projectTempoMap,
  rankInboxItems,
  SessionController,
  verifyCheckpoint,
} from "../index.js";
import { ScriptedMockProvider } from "../testing.js";

describe("Format-neutral Views (computeGridFit, projectTempoMap, rankInboxItems) & 3-Layer SessionController", () => {
  it("computes JCS-safe computeGridFit (null instead of Infinity), anchor role selection, and break isolation", async () => {
    const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
    await ctrl.bindAudio({ audioId: "views_song", durationUs: 12_000_000 });
    ctrl.loadDraft({
      segments: [
        { id: "seg_a", startUs: 0, endUs: 6_000_000, regime: "auto" },
        { id: "seg_brk", startUs: 6_000_000, endUs: 9_000_000, regime: "break" },
        { id: "seg_b", startUs: 9_000_000, endUs: 12_000_000, regime: "auto" },
      ],
      beats: [
        { id: "b0", atUs: 1_000_000 },
        { id: "b1", atUs: 1_500_000 },
      ],
      sounds: [
        {
          id: "s_grid",
          startUs: 1_000_000,
          endUs: 1_200_000,
          endKind: "observed",
          anchors: [
            { id: "a_att", role: "transient_attack", atUs: 1_004_000 },
            { id: "a_rel", role: "release", atUs: 1_190_000 },
          ],
          primaryAnchorId: "a_att",
          voiceGroupId: "vg_drums",
          classId: "cls_kick",
        },
        {
          id: "s_sub",
          startUs: 1_250_000,
          endUs: 1_350_000,
          endKind: "estimated",
          anchors: [{ id: "a0", role: "transient_attack", atUs: 1_250_000 }],
          primaryAnchorId: "a0",
          voiceGroupId: "vg_hat",
        },
        {
          id: "s_break",
          startUs: 7_000_000,
          endUs: 7_200_000,
          endKind: "estimated",
          anchors: [{ id: "a_brk", role: "p_center", atUs: 7_050_000 }],
          primaryAnchorId: "a_brk",
        },
      ],
    });

    const snap = ctrl.getSnapshot();
    const fit = computeGridFit(snap);
    assert.equal(fit.length, 3);
    // JCS serialization succeeds with zero Infinity!
    assert.ok(canonicalJson(fit).length > 0);

    const gridItem = fit.find((x) => x.soundId === "s_grid");
    assert.equal(gridItem?.status, "on_grid");
    assert.equal(gridItem?.residualUs, 4_000);

    const subItem = fit.find((x) => x.soundId === "s_sub");
    assert.equal(subItem?.status, "subdivision");
    assert.equal(subItem?.nearestDivisor, 2);

    const breakItem = fit.find((x) => x.soundId === "s_break");
    assert.equal(breakItem?.status, "unexplained");
    assert.equal(breakItem?.nearestBeatDistUs, null);
    assert.equal(breakItem?.residualUs, null);

    // Role selector `"release"` fits the release anchor at 1_190_000
    const releaseFit = computeGridFit(snap, { anchorSelector: "release" });
    assert.equal(releaseFit.length, 1);
    assert.equal(releaseFit[0].atUs, 1_190_000);

    // Filter by `classId`
    assert.equal(computeGridFit(snap, { classId: "cls_kick" }).length, 1);
  });

  it("projects format-neutral TempoMap (ramp, fermata_hold, phraseReset) and ranks InboxItems excluding Keep", async () => {
    const ctrl = new SessionController({ provider: new ScriptedMockProvider() });
    await ctrl.bindAudio({ audioId: "tempo_song", durationUs: 16_000_000 });
    ctrl.loadDraft({
      segments: [
        { id: "seg_steady", startUs: 0, endUs: 6_000_000, regime: "auto" },
        { id: "seg_ramp", startUs: 6_000_000, endUs: 12_000_000, regime: "auto", kinematics: "ramp" },
        { id: "seg_free", startUs: 12_000_000, endUs: 16_000_000, regime: "free" },
      ],
      beats: [
        { id: "b0", atUs: 1_000_000, structural: { downbeat: true } },
        { id: "b1", atUs: 1_500_000 },
        { id: "b2", atUs: 2_000_000, structural: { fermata: true } },
        { id: "b3", atUs: 3_200_000, structural: { phraseReset: true } },
        { id: "b4", atUs: 3_700_000 },
        { id: "r0", atUs: 6_500_000 },
        { id: "r1", atUs: 7_000_000 },
        { id: "r2", atUs: 7_400_000 },
        { id: "f0", atUs: 12_500_000, confidence: 0.45 },
        { id: "f1", atUs: 13_100_000, confidence: 0.5 },
      ],
    });

    const tmap = projectTempoMap(ctrl.getSnapshot());
    assert.equal(tmap.barPolicy, "explicit_only");
    assert.equal("osuTimingPoints" in tmap, false);
    assert.ok(tmap.points.some((p) => p.kind === "fermata_hold"));
    assert.ok(tmap.points.some((p) => p.kind === "ramp" && p.endPeriodUs !== null));
    assert.ok(tmap.points.some((p) => p.phraseReset === true));

    // Lock `seg_steady` as Keep and check `rankInboxItems` excludes [0, 6_000_000) completely!
    ctrl.applyEdit({ op: "set_segment_keep", segmentId: "seg_steady", keep: true });
    const inbox = rankInboxItems(ctrl.getSnapshot());
    assert.ok(inbox.length > 0);
    assert.ok(inbox.every((item) => item.startUs >= 6_000_000));
  });

  it("runs 3 independent lifecycles, suggestSoundOptions, matchSounds, suggestSegmentation, setHoldOffers, cancel(requestId), and checkpoints", async () => {
    const mock = new ScriptedMockProvider({ delayMs: 25, snapDelayMs: 5 });
    const ctrl = new SessionController({ provider: mock });
    await ctrl.openSong({
      audioId: "ctrl_song",
      durationUs: 20_000_000,
      sampleRateHz: 48_000,
      sampleCount: 960_000,
    });

    // Start slow heavy_recompute while firing micro_assist (`suggestSnaps` & `suggestSoundOptions`)
    ctrl.setHoldOffers(true);
    const recomputePromise = ctrl.recompute({
      scope: { startUs: 0, endUs: 8_000_000 },
      reason: "slow_cancellable",
    });

    const snapOpts = await ctrl.suggestSoundOptions({ atUs: 1_000_000, immediate: true });
    assert.equal(snapOpts.status, "ok");
    assert.equal(snapOpts.options.length, 2);

    const heldRes = await recomputePromise;
    assert.equal(heldRes.status, "held");
    assert.equal(ctrl.getHeldOffers().length, 1);

    const flushed = ctrl.setHoldOffers(false);
    assert.equal(flushed.flushed, true);
    assert.equal(flushed.offers.length, 1);

    // Preemption invokes `provider.cancel(requestId)`!
    const p1 = ctrl.recompute({ scope: "song", reason: "slow_cancellable" });
    const p2 = ctrl.recompute({ scope: "song", reason: "local_rewrite" });
    const [r1, r2] = await Promise.all([p1, p2]);
    assert.equal(r1.status, "superseded");
    assert.equal(r2.status, "applied");
    assert.ok(mock.cancelledRequestIds.size >= 1);

    // `matchSounds` and `suggestSegmentation`
    const firstSoundId = ctrl.getSnapshot().sounds[0].id;
    const matchRes = await ctrl.matchSounds({
      exemplarSoundIds: [firstSoundId],
      matchMode: "voice",
    });
    assert.equal(matchRes.status, "applied");
    assert.equal(matchRes.offers[0].kind, "sound_match");

    const segRes = await ctrl.suggestSegmentation();
    assert.equal(segRes.status, "applied");
    assert.equal(segRes.offers[0].kind, "segmentation");

    // NDJSON round-trip & JCS checkpoint verification
    const ndjson = ctrl.exportNdjson();
    const parsedEvents = parseTraceNdjson(ndjson);
    const cp = createCheckpoint(ctrl.getState());
    const ver = verifyCheckpoint(cp, parsedEvents);
    assert.equal(ver.valid, true);
  });

  it("enforces surgical RejectionSuppression scoping across unrelated edits, clear_verdict challenge disposition, and Boundary protected_verified", async () => {
    const mock = new ScriptedMockProvider();
    const ctrl = new SessionController({ provider: mock });
    await ctrl.openSong({ audioId: "supp_song", durationUs: 20_000_000 });

    // 1. User rejects an offer over [12_000_000, 16_000_000)
    const r1 = await ctrl.recompute({
      scope: { startUs: 12_000_000, endUs: 16_000_000 },
      reason: "local_rewrite",
    });
    const rejectedId = r1.offers[0].id;
    ctrl.rejectOffer(rejectedId, "not needed");

    // Unrelated user edit at 2_000_000 does NOT lift suppression at [12s, 16s)!
    ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b_far_away", atUs: 2_000_000 } });
    const r2 = await ctrl.recompute({
      scope: { startUs: 12_000_000, endUs: 16_000_000 },
      reason: "local_rewrite",
    });
    assert.equal(r2.offers.length, 0);
    assert.equal(r2.suppressedOffers.length, 1);

    // User edit INSIDE [12s, 16s) DOES lift suppression!
    ctrl.applyEdit({ op: "upsert_beat", beat: { id: "b_in_scope", atUs: 13_000_000 } });
    const r3 = await ctrl.recompute({
      scope: { startUs: 12_000_000, endUs: 16_000_000 },
      reason: "local_rewrite",
    });
    assert.equal(r3.offers.length, 1);

    // 2. Boundary `protected_verified` + `clear_verdict` disposition
    ctrl.applyEdit({
      op: "split_segment",
      segmentId: "seg_0",
      atUs: 6_000_000,
      newBoundaryId: "bnd_v",
      newSegmentId: "seg_v_right",
    });
    const bnd = ctrl.getState().boundaries.get("bnd_v");
    assert.equal(bnd?.verdict?.kind, "placed");

    mock._script.push({
      batchId: "b_bnd_ch",
      offers: [
        {
          id: "off_move_bnd",
          batchId: "b_bnd_ch",
          kind: "segmentation",
          writeRanges: [{ startUs: 4_000_000, endUs: 8_000_000 }],
          challenges: [
            {
              challengeId: "ch_bnd",
              targetKind: "boundary",
              targetId: "bnd_v",
              expectedRevision: bnd.revision,
              expectedValueDigest: bnd.verdict.valueDigest,
              reasonCode: "phrase_boundary_align",
            },
          ],
          ops: [{ op: "move_boundary", boundaryId: "bnd_v", atUs: 6_250_000 }],
          notes: [],
        },
      ],
    });

    const bndRes = await ctrl.recompute({
      scope: { startUs: 4_000_000, endUs: 8_000_000 },
      reason: "seg",
    });
    assert.equal(bndRes.offers[0].status, "requires_challenge_confirm");

    const accClear = ctrl.acceptOffer({
      offerIds: ["off_move_bnd"],
      authorizedChallenges: [
        {
          offerId: "off_move_bnd",
          challengeId: "ch_bnd",
          targetId: "bnd_v",
          expectedValueDigest: bnd.verdict.valueDigest,
          disposition: "clear_verdict",
        },
      ],
    });
    assert.equal(accClear.ok, true);
    assert.equal(ctrl.getState().boundaries.get("bnd_v")?.atUs, 6_250_000);
    assert.equal(ctrl.getState().boundaries.get("bnd_v")?.verdict, undefined);
  });
});
