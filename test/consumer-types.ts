import {
  type AcceptOfferOutcome,
  type AudioBinding,
  applyEvent,
  type BeatBinding,
  bindOfferToBasis,
  canonicalJson,
  compileConstraints,
  computeEntityValueDigest,
  computeGridFit,
  computeIntervalMusicalDigest,
  computeSnapshotDigest,
  createInitialState,
  createProviderView,
  type NormalizedIdPair,
  type Offer,
  type OfferPreviewResult,
  type Provider,
  projectTempoMap,
  rankInboxItems,
  SessionController,
  sampleToUs,
  secToUs,
  TRACE_SCHEMA_VERSION,
  type TransactionOp,
  toSnapshot,
  usToNearestSample,
  usToSec,
  validateOffer,
} from "../index.js";
import { generateSyntheticDraft, ScriptedMockProvider } from "../testing.js";

export async function verifyConsumerTypes(): Promise<string> {
  const audio: AudioBinding = {
    audioId: "song_ts",
    durationUs: secToUs(16),
    sampleRateHz: 48_000,
    sampleCount: 768_000,
  };
  const provider: Provider = new ScriptedMockProvider({
    matchScript: [
      {
        batchId: "b_match_res",
        offers: [],
      },
    ],
    segmentationScript: [
      {
        batchId: "b_seg_res",
        offers: [],
      },
    ],
  });
  const ctrl = new SessionController({ provider });
  await ctrl.openSong(audio);

  const sampleUs = sampleToUs(48_000, 48_000);
  const backSample = usToNearestSample(sampleUs, 48_000);
  const sec = usToSec(sampleUs);
  void backSample;
  void sec;

  const validSoundBinding: BeatBinding = {
    soundId: "snd_0",
    anchorRole: "transient_attack",
    anchorAtUs: 1_000_000,
  };
  const validCandidateBinding: BeatBinding = {
    candidateEventId: "cand_0",
    anchorRole: "p_center",
    anchorAtUs: 1_005_000,
  };
  void validSoundBinding;
  void validCandidateBinding;

  // @ts-expect-error BeatBinding requires at least one of soundId or candidateEventId
  const invalidEmptyBinding: BeatBinding = {
    anchorRole: "transient_attack",
  };
  void invalidEmptyBinding;

  const op: TransactionOp = {
    op: "upsert_beat",
    beat: {
      id: "b_ts_1",
      atUs: 1_200_000,
      structural: { downbeat: true },
    },
    via: "tap",
  };
  ctrl.applyEdit(op);

  const promoteTuple = [undefined, "bnd_ts_r"] as const;
  const promoteOp: TransactionOp = {
    op: "promote_range_to_segments",
    startUs: 2_000_000,
    endUs: 6_000_000,
    newBoundaryIds: promoteTuple,
    newSegmentIds: [null, "seg_ts_r"] as const,
  };
  ctrl.applyEdit(promoteOp);

  ctrl.applyEdit({
    op: "place_unbeat",
    id: "ub_ts_1",
    atUs: 2_500_000,
    pulseLayer: "tactus",
  });
  const moveUnbeatOp: TransactionOp = {
    op: "move_unbeat",
    id: "ub_ts_1",
    atUs: 2_510_000,
    tolUs: 30_000,
    pulseLayer: "subdivision",
    via: "nudge",
  };
  ctrl.applyEdit(moveUnbeatOp);

  const snap = ctrl.getSnapshot();
  const view = createProviderView(snap);
  const constraints = compileConstraints(view.snapshot);
  const grid = computeGridFit(snap, { anchorSelector: "primary", verifiedOnly: false });
  const tmap = projectTempoMap(snap);
  const firstIntervals: number | undefined = tmap.points[0]?.intervalCount;
  void firstIntervals;
  const inbox = rankInboxItems(snap);
  const ivDigest = computeIntervalMusicalDigest(snap, 0, 4_000_000);
  void constraints;
  void grid;
  void tmap;
  void inbox;
  void ivDigest;

  const draft = generateSyntheticDraft({ durationUs: 8_000_000 });
  let s = createInitialState();
  s = applyEvent(s, {
    schemaVersion: TRACE_SCHEMA_VERSION,
    seq: 0,
    id: "e0",
    type: "audio_bound",
    author: { kind: "host" },
    payload: audio,
  });
  void draft;

  const beat0 = snap.beats[0];
  const sound0 = snap.sounds[0];
  const boundary0 = snap.boundaries[0];
  const unbeat0 = snap.unbeats[0];
  const sc0 = snap.soundClasses[0];

  if (sound0) {
    void computeEntityValueDigest("sound", sound0, "occurrence");
  }
  if (boundary0) {
    void computeEntityValueDigest("boundary", boundary0);
  }
  if (unbeat0) {
    void computeEntityValueDigest("unbeat", unbeat0);
  }
  if (sc0) {
    void computeEntityValueDigest("sound_class", sc0);
  }
  void computeEntityValueDigest("beat", { id: "raw_b", atUs: 100_000 });

  if (beat0) {
    // Direct typed Beat without `as unknown` casts under `--strict`!
    const vd = computeEntityValueDigest("beat", beat0);
    const offer: Offer = bindOfferToBasis(snap, {
      id: "off_ts",
      batchId: "batch_ts",
      kind: "pulse",
      writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
      ops: [
        { op: "upsert_beat", beat: { id: "b_ts_offer", atUs: 2_000_000 } },
        { op: "move_unbeat", id: "ub_ts_1", atUs: 2_520_000 },
        {
          op: "promote_range_to_segments",
          startUs: 1_000_000,
          endUs: 3_000_000,
          newBoundaryIds: [undefined, "bnd_off_r"] as const,
        },
      ],
      notes: [{ code: "ok", message: "Test" }],
    });
    const normOp = offer.ops[2];
    if (normOp && normOp.op === "promote_range_to_segments" && normOp.newBoundaryIds) {
      const pair: NormalizedIdPair = normOp.newBoundaryIds;
      void pair;
    }
    const val = validateOffer(snap, offer);
    const prev: OfferPreviewResult = ctrl.previewOffer(offer);
    const movedUnbeatsCount: number = prev.consequences.unbeatsMoved;
    const movedUnbeatDelta: number | undefined = prev.diff.unbeats.moved[0]?.deltaUs;
    void movedUnbeatsCount;
    void movedUnbeatDelta;
    const acc: AcceptOfferOutcome = ctrl.acceptOffer({
      offerIds: [offer.id],
      authorizedChallenges: [],
    });
    void vd;
    void val;
    void prev;
    void acc;
  }

  return `${canonicalJson({ ok: true })}:${computeSnapshotDigest(toSnapshot(s))}`;
}
