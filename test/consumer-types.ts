import {
  type AcceptOfferOutcome,
  type AudioBinding,
  applyEvent,
  bindOfferToBasis,
  canonicalJson,
  compileConstraints,
  computeEntityValueDigest,
  computeGridFit,
  computeIntervalMusicalDigest,
  computeSnapshotDigest,
  createInitialState,
  createProviderView,
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
  const provider: Provider = new ScriptedMockProvider();
  const ctrl = new SessionController({ provider });
  await ctrl.openSong(audio);

  const sampleUs = sampleToUs(48_000, 48_000);
  const backSample = usToNearestSample(sampleUs, 48_000);
  const sec = usToSec(sampleUs);
  void backSample;
  void sec;

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

  const snap = ctrl.getSnapshot();
  const view = createProviderView(snap);
  const constraints = compileConstraints(view.snapshot);
  const grid = computeGridFit(snap, { anchorSelector: "primary", verifiedOnly: false });
  const tmap = projectTempoMap(snap);
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
  if (beat0) {
    const vd = computeEntityValueDigest("beat", beat0 as unknown as Record<string, unknown>);
    const offer: Offer = bindOfferToBasis(snap, {
      id: "off_ts",
      batchId: "batch_ts",
      kind: "pulse",
      writeRanges: [{ startUs: 0, endUs: 4_000_000 }],
      ops: [{ op: "upsert_beat", beat: { id: "b_ts_offer", atUs: 2_000_000 } }],
      notes: [{ code: "ok", message: "Test" }],
    });
    const val = validateOffer(snap, offer);
    const prev: OfferPreviewResult = ctrl.previewOffer(offer);
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
