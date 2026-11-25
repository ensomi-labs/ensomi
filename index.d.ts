// Type definitions for ensomi@0.1.0 public contract
// Zero-dependency, DOM-free ESM package (Node 22/24+, browser, and Rust/Wasm JSON-safe seam)

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | readonly JsonValue[] | { readonly [key: string]: JsonValue };

export type Origin = "import" | "model" | "rule" | "user";
export type VerdictKind = "placed" | "adjusted" | "confirmed";
export type VerdictVia = "tap" | "snap" | "nudge" | "review" | "challenge_accept";

export type EntityKind =
  | "beat"
  | "unbeat"
  | "sound"
  | "sound_class"
  | "boundary"
  | "segment"
  | "span"
  | "approval";

export type VerdictTargetKind = "beat" | "unbeat" | "sound" | "sound_class" | "boundary";
export type SoundFacet = "occurrence" | "voice" | "extent" | `anchor:${string}`;

export type SegmentRegime = "auto" | "free" | "break";
export type SegmentKinematics = "steady" | "ramp" | "rubato" | "unknown";
export type SpanKind = "free" | "keep";
export type InboxPriority = "high" | "medium" | "low";

export type SnapRole =
  | "transient_attack"
  | "p_center"
  | "harmonic_change"
  | "release";

export type WellKnownSoundFamily =
  | "percussion"
  | "kick"
  | "snare"
  | "hihat_cymbal"
  | "piano_keys"
  | "plucked_guitar"
  | "bass_synth"
  | "strings_winds"
  | "vocal"
  | "fx_riser"
  | "other";

export type SoundRegister = "sub" | "low" | "mid" | "high";
export type SoundEndKind = "observed" | "estimated" | "right_censored";
export type SoundBlendStatus = "isolated" | "blended";
export type SoundMatchMode = "hit" | "voice" | "figure";
export type PulseLayer = "tactus" | "subdivision" | "any";
export type BeatAnchorKind = "anchored" | "interpolated";

export type TriggerLayer = "song_open" | "micro_assist" | "heavy_recompute";

export interface LayerTriggerRule {
  readonly mode: "auto" | "manual";
  readonly debounceMs: number;
  readonly cancellation: "latest_wins";
}

export interface TriggerPolicy {
  readonly song_open: LayerTriggerRule;
  readonly micro_assist: LayerTriggerRule;
  readonly heavy_recompute: LayerTriggerRule;
}

export type TriggerPolicyOverride = {
  [K in TriggerLayer]?: Partial<LayerTriggerRule>;
};

export type OfferKind = "pulse" | "segmentation" | "sound_match" | "propagation";

export type OfferStatus =
  | "valid"
  | "requires_challenge_confirm"
  | "stale"
  | "invalid"
  | "accepted"
  | "rejected"
  | "superseded";

export type ConflictKind =
  | "protected_keep"
  | "protected_verified"
  | "authority_violation"
  | "version_mismatch"
  | "range_changed"
  | "policy_changed"
  | "source_changed"
  | "out_of_scope"
  | "invalid_op"
  | "sibling_conflict";

export type ChallengeDisposition = "confirm_replacement" | "clear_verdict";

export type TraceEventType =
  | "audio_bound"
  | "reset_document"
  | "draft_loaded"
  | "edit"
  | "signal"
  | "offer_batch"
  | "offer_accept"
  | "offer_reject"
  | "retract"
  | "listen_effort"
  | "ready";

export type AuthorKind = "user" | "model" | "rule" | "host" | "import";
export type ProviderKind = "model" | "rule" | "mock";

/** Non-empty half-open interval `[startUs, endUs)` in integer microseconds (`0 <= startUs < endUs`). */
export interface RangeUs {
  startUs: number;
  endUs: number;
}

export interface StructuredNote {
  code: string;
  message: string;
  params?: Record<string, string | number | boolean | null>;
}

/** Per-mark human truth stamp (`verdict !== undefined` iff human truth). */
export interface Verdict {
  kind: VerdictKind;
  eventId: string;
  valueDigest: string;
  tolUs: number;
  facet?: string;
  via?: VerdictVia;
}

/** Full-footprint section/span `keep` lock. */
export interface Approval {
  id: string;
  eventId: string;
  contentDigest: string;
  modifiedSinceApproval?: boolean;
}

export interface AudioBinding {
  audioId: string;
  durationUs: number;
  sampleRateHz?: number;
  sampleCount?: number;
  audioSha256?: string;
  decodedPcmSha256?: string;
}

/** Interior cut boundary (`0 < atUs < durationUs`). */
export interface Boundary {
  id: string;
  atUs: number;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  verdict?: Verdict;
}

export interface Segment {
  id: string;
  startUs: number;
  endUs: number;
  startBoundaryId: string | null;
  endBoundaryId: string | null;
  regime: SegmentRegime;
  kinematics?: SegmentKinematics;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  approval?: Approval;
}

export interface Span {
  id: string;
  segmentId: string;
  startUs: number;
  endUs: number;
  kind: SpanKind;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  approval?: Approval;
}

export interface BeatBindingBase {
  anchorId?: string;
  anchorRole: SnapRole;
  anchorAtUs?: number;
  alignmentStale?: boolean;
}

export type BeatBinding = BeatBindingBase &
  (
    | { soundId: string; candidateEventId?: string }
    | { candidateEventId: string; soundId?: string }
  );

export interface BeatStructural {
  downbeat?: boolean;
  phraseReset?: boolean;
  fermata?: boolean;
  anchorKind?: BeatAnchorKind;
  groupId?: string;
}

export interface BeatLineage {
  origin: Origin;
  atUs: number;
  confidence?: number;
}

export interface Beat {
  id: string;
  atUs: number;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  confidence?: number;
  boundTo?: BeatBinding;
  structural?: BeatStructural;
  lineage?: BeatLineage;
  verdict?: Verdict;
}

export interface ReplacedBeatRef {
  beatId: string;
  origin: Origin;
  createdByEventId: string;
  priorRevision: number;
  atUs: number;
  confidence?: number;
}

export interface Unbeat {
  id: string;
  atUs: number;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  tolUs: number;
  pulseLayer: PulseLayer;
  replaces?: ReplacedBeatRef;
  verdict?: Verdict;
}

export interface SoundAnchorPoint {
  id: string;
  role: SnapRole;
  atUs: number;
  sigmaUs?: number;
}

export interface SoundCharacteristics {
  /** Open non-empty string (`WELL_KNOWN_SOUND_FAMILIES` lists standard identifiers). */
  family?: string;
  register?: SoundRegister;
  f0Hz?: number;
  harmonicity?: number;
  stereoPan?: number;
  stereoWidth?: number;
  confidence?: number;
}

export interface SoundDescriptor {
  schema: string;
  values: number[];
}

export interface Sound {
  id: string;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  startUs: number;
  endUs: number;
  endKind: SoundEndKind;
  anchors: SoundAnchorPoint[];
  primaryAnchorId: string;
  salience?: number;
  characteristics?: SoundCharacteristics;
  descriptor?: SoundDescriptor;
  voiceGroupId?: string;
  classId?: string;
  blendStatus?: SoundBlendStatus;
  verdict?: Verdict;
}

export interface SoundClass {
  id: string;
  origin: Origin;
  createdByEventId: string;
  revision: number;
  exemplarSoundIds: string[];
  matchMode: SoundMatchMode;
  label?: string;
  verdict?: Verdict;
}

export interface SnapCandidate {
  candidateId: string;
  atUs: number;
  deltaUs: number;
  anchorId: string;
  role: SnapRole;
  sigmaUs?: number;
  salience: number;
  labelCode: string;
  label: string;
  sound: {
    id?: string;
    startUs: number;
    endUs: number;
    endKind: SoundEndKind;
    anchors: SoundAnchorPoint[];
    primaryAnchorId: string;
    characteristics?: SoundCharacteristics;
    voiceGroupId?: string;
    classId?: string;
    blendStatus?: SoundBlendStatus;
  };
}

export interface SoundOptionCandidate {
  optionId: string;
  salience: number;
  labelCode: string;
  label: string;
  sound: SoundInput;
}

export interface BoundaryInput {
  id?: string;
  atUs: number;
}

export interface SegmentInput {
  id?: string;
  startUs: number;
  endUs: number;
  regime?: SegmentRegime;
  kinematics?: SegmentKinematics;
}

export interface SpanInput {
  id?: string;
  segmentId: string;
  startUs: number;
  endUs: number;
  kind: "free";
}

export interface BeatInput {
  id: string;
  atUs: number;
  confidence?: number;
  boundTo?: BeatBinding;
  structural?: BeatStructural;
}

export interface UnbeatInput {
  id: string;
  atUs: number;
  tolUs?: number;
  pulseLayer?: PulseLayer;
}

export interface SoundInput {
  id: string;
  startUs: number;
  endUs: number;
  endKind?: SoundEndKind;
  anchors: SoundAnchorPoint[];
  primaryAnchorId?: string;
  salience?: number;
  characteristics?: SoundCharacteristics;
  descriptor?: SoundDescriptor;
  voiceGroupId?: string;
  classId?: string;
  blendStatus?: SoundBlendStatus;
}

export type SoundPatch = Partial<Omit<SoundInput, "id">>;

export interface SoundClassInput {
  id: string;
  exemplarSoundIds: string[];
  matchMode: SoundMatchMode;
  label?: string;
}

export interface ConfirmTarget {
  kind: VerdictTargetKind;
  id: string;
  expectedRevision: number;
  facet?: string;
}

export interface ClearVerdictTarget {
  kind: VerdictTargetKind;
  id: string;
  expectedRevision: number;
}

export interface BeatToUnbeatConversion {
  beatId: string;
  expectedRevision: number;
  newUnbeatId: string;
  tolUs?: number;
  pulseLayer?: PulseLayer;
}

export type OptionalIdPairInput = readonly [
  (string | null | undefined)?,
  (string | null | undefined)?,
];
export type NormalizedIdPair = [string | null, string | null];

export type OfferOp =
  | { op: "upsert_beat"; beat: BeatInput }
  | {
      op: "move_beat";
      id: string;
      atUs: number;
      boundTo?: BeatBinding | null;
      structural?: BeatStructural | null;
      confidence?: number;
    }
  | { op: "snap_beat"; id: string; atUs: number; boundTo: BeatBinding; confidence?: number }
  | {
      op: "shift_beats";
      startUs: number;
      endUs: number;
      deltaUs: number;
      /** Defaults to `false` (verified beats stay anchored unless `true` is explicitly passed). */
      includeVerified?: boolean;
    }
  | { op: "remove_beats"; ids: string[] }
  | {
      op: "move_unbeat";
      id: string;
      atUs: number;
      tolUs?: number;
      pulseLayer?: PulseLayer;
    }
  | { op: "remove_unbeats"; ids: string[] }
  | { op: "upsert_sound"; sound: SoundInput }
  | { op: "update_sound"; id: string; patch: SoundPatch }
  | { op: "remove_sounds"; ids: string[] }
  | { op: "upsert_sound_class"; soundClass: SoundClassInput }
  | { op: "remove_sound_classes"; ids: string[] }
  | {
      op: "split_segment";
      segmentId: string;
      atUs: number;
      newBoundaryId?: string;
      newSegmentId?: string;
      rightRegime?: SegmentRegime;
      rightKinematics?: SegmentKinematics;
    }
  | {
      op: "merge_segments";
      leftSegmentId: string;
      rightSegmentId: string;
      mergedRegime?: SegmentRegime;
      mergedKinematics?: SegmentKinematics | null;
    }
  | { op: "move_boundary"; boundaryId: string; atUs: number }
  | {
      op: "promote_range_to_segments";
      startUs: number;
      endUs: number;
      newBoundaryIds?: NormalizedIdPair;
      newSegmentIds?: NormalizedIdPair;
      regime?: SegmentRegime;
      kinematics?: SegmentKinematics;
    }
  | { op: "set_segment_regime"; segmentId: string; regime: SegmentRegime }
  | {
      op: "set_segment_kinematics";
      segmentId: string;
      kinematics: SegmentKinematics | null;
    }
  | { op: "set_range_regime"; startUs: number; endUs: number; regime: "auto" | "free" };

export type OfferOpInput =
  | Exclude<OfferOp, { op: "promote_range_to_segments" }>
  | {
      op: "promote_range_to_segments";
      startUs: number;
      endUs: number;
      newBoundaryIds?: OptionalIdPairInput;
      newSegmentIds?: OptionalIdPairInput;
      regime?: SegmentRegime;
      kinematics?: SegmentKinematics;
    };

export type TransactionOp =
  | { op: "upsert_beat"; beat: BeatInput; tolUs?: number; via?: VerdictVia }
  | {
      op: "move_beat";
      id: string;
      atUs: number;
      tolUs?: number;
      via?: VerdictVia;
      boundTo?: BeatBinding | null;
      structural?: BeatStructural | null;
      confidence?: number;
    }
  | {
      op: "snap_beat";
      id: string;
      atUs: number;
      boundTo: BeatBinding;
      tolUs?: number;
      via?: VerdictVia;
      confidence?: number;
    }
  | {
      op: "shift_beats";
      startUs: number;
      endUs: number;
      deltaUs: number;
      /** Defaults to `false` (verified beats stay anchored unless `true` is explicitly passed). */
      includeVerified?: boolean;
    }
  | { op: "remove_beats"; ids: string[] }
  | {
      op: "place_unbeat";
      id: string;
      atUs: number;
      tolUs?: number;
      pulseLayer?: PulseLayer;
      captureUnverifiedRadiusUs?: number;
      removeVerifiedBeatIds?: string[];
      via?: VerdictVia;
    }
  | {
      op: "move_unbeat";
      id: string;
      atUs: number;
      tolUs?: number;
      pulseLayer?: PulseLayer;
      via?: VerdictVia;
    }
  | { op: "remove_unbeats"; ids: string[] }
  | { op: "beat_to_unbeat"; conversions: BeatToUnbeatConversion[] }
  | {
      op: "upsert_sound";
      sound: SoundInput;
      tolUs?: number;
      facet?: string;
      via?: VerdictVia;
    }
  | {
      op: "update_sound";
      id: string;
      patch: SoundPatch;
      tolUs?: number;
      facet?: string;
      via?: VerdictVia;
    }
  | { op: "remove_sounds"; ids: string[] }
  | {
      op: "upsert_sound_class";
      soundClass: SoundClassInput;
      tolUs?: number;
      via?: VerdictVia;
    }
  | { op: "remove_sound_classes"; ids: string[] }
  | {
      op: "confirm_marks";
      targets: ConfirmTarget[];
      tolUs?: number;
      via?: "review" | "tap";
    }
  | { op: "clear_verdicts"; targets: ClearVerdictTarget[] }
  | {
      op: "split_segment";
      segmentId: string;
      atUs: number;
      newBoundaryId?: string;
      newSegmentId?: string;
      rightRegime?: SegmentRegime;
      rightKinematics?: SegmentKinematics;
    }
  | {
      op: "merge_segments";
      leftSegmentId: string;
      rightSegmentId: string;
      mergedRegime?: SegmentRegime;
      mergedKinematics?: SegmentKinematics | null;
    }
  | {
      op: "move_boundary";
      boundaryId: string;
      atUs: number;
      tolUs?: number;
      via?: VerdictVia;
    }
  | {
      op: "promote_range_to_segments";
      startUs: number;
      endUs: number;
      newBoundaryIds?: OptionalIdPairInput;
      newSegmentIds?: OptionalIdPairInput;
      regime?: SegmentRegime;
      kinematics?: SegmentKinematics;
      keep?: boolean;
    }
  | { op: "set_segment_regime"; segmentId: string; regime: SegmentRegime }
  | {
      op: "set_segment_kinematics";
      segmentId: string;
      kinematics: SegmentKinematics | null;
    }
  | { op: "set_segment_keep"; segmentId: string; keep: boolean; approvalId?: string }
  | { op: "set_range_regime"; startUs: number; endUs: number; regime: "auto" | "free" }
  | { op: "set_range_keep"; startUs: number; endUs: number; keep: boolean };

export type TransactionOpKind = TransactionOp["op"];
export type OfferOpKind = OfferOp["op"];

export interface BeatGuideConstraint {
  beatId: string;
  atUs: number;
  tolUs: number;
  verdictKind: VerdictKind;
  boundTo?: BeatBinding;
  structural?: BeatStructural;
}

export interface UnbeatRepulsionConstraint {
  unbeatId: string;
  atUs: number;
  tolUs: number;
  localIbiUs: number | null;
  repulsionRadiusUs: number;
  pulseLayer: PulseLayer;
  replaces?: ReplacedBeatRef;
}

export interface SoundObservationConstraint {
  soundId: string;
  verified: boolean;
  verdictFacet?: string;
  tolUs?: number;
  startUs: number;
  endUs: number;
  endKind: SoundEndKind;
  primaryAnchorId: string;
  primaryAtUs: number;
  primaryRole: SnapRole;
  anchors: SoundAnchorPoint[];
  salience?: number;
  family?: string;
  voiceGroupId?: string;
  classId?: string;
}

export interface SoundClassConstraint {
  classId: string;
  verified: boolean;
  exemplarSoundIds: string[];
  matchMode: SoundMatchMode;
  label?: string;
}

export interface BoundaryConstraint {
  boundaryId: string;
  atUs: number;
  tolUs: number;
  verdictKind: VerdictKind;
}

export interface FreeIntervalConstraint {
  segmentId: string;
  spanId?: string;
  startUs: number;
  endUs: number;
  kinematics?: SegmentKinematics;
}

export interface KeepLockConstraint {
  approvalId: string;
  segmentId: string;
  spanId?: string;
  startUs: number;
  endUs: number;
  contentDigest: string;
  modifiedSinceApproval: boolean;
}

export interface CompiledConstraints {
  beatGuides: BeatGuideConstraint[];
  unbeatRepulsions: UnbeatRepulsionConstraint[];
  soundObservations: SoundObservationConstraint[];
  soundClasses: SoundClassConstraint[];
  boundaryConstraints: BoundaryConstraint[];
  freeIntervals: FreeIntervalConstraint[];
  keepLocks: KeepLockConstraint[];
}

export interface OfferConflict {
  kind: ConflictKind;
  message: string;
  startUs?: number;
  endUs?: number;
  targetKind?: VerdictTargetKind;
  targetId?: string;
}

export interface OfferChallenge {
  challengeId: string;
  targetKind: VerdictTargetKind;
  targetId: string;
  expectedRevision: number;
  expectedValueDigest: string;
  reasonCode: string;
  replacementId?: string;
  message?: string;
}

export interface ChallengeAuthorization {
  offerId: string;
  challengeId: string;
  targetId: string;
  expectedValueDigest: string;
  disposition: ChallengeDisposition;
}

export interface OfferDerivedFrom {
  range: RangeUs;
  sourceDigest: string;
  supportingIds: string[];
}

export interface OfferDependencies {
  audioId: string | null;
  durationUs: number;
  writeRanges: RangeUs[];
  segments: Array<{
    id: string;
    startUs: number;
    endUs: number;
    regime: SegmentRegime;
    kinematics: SegmentKinematics | null;
    revision: number;
    approved: boolean;
  }>;
  boundaries: Array<{ id: string; atUs: number; revision: number }>;
  spans: Array<{
    id: string;
    segmentId: string;
    startUs: number;
    endUs: number;
    kind: SpanKind;
    revision: number;
    approved: boolean;
  }>;
  beats: Array<{ id: string; atUs: number; revision: number }>;
  unbeats: Array<{ id: string; atUs: number; revision: number }>;
  sounds: Array<{ id: string; startUs: number; endUs: number; revision: number }>;
  soundClasses: Array<{ id: string; revision: number }>;
  derivedFrom?: {
    range: RangeUs;
    sourceDigest: string;
    supportingRevisions: Array<{
      id: string;
      kind: EntityKind;
      revision: number;
      valueDigest: string;
    }>;
  };
}

export interface Offer {
  id: string;
  batchId: string;
  alternativeGroup?: string;
  kind: OfferKind;
  providerId: string;
  modelRevision?: string;
  audioId: string;
  basisSeq: number;
  writeRanges: RangeUs[];
  dependencies: OfferDependencies;
  basisDigest: string;
  ops: OfferOp[];
  challenges?: OfferChallenge[];
  derivedFrom?: OfferDerivedFrom;
  notes: StructuredNote[];
  title?: string;
  optionIndex?: number;
  optionCount?: number;
  score?: number;
  semanticKey?: string;
  status: OfferStatus;
  conflicts: OfferConflict[];
}

export interface OfferInput {
  id?: string;
  batchId?: string;
  alternativeGroup?: string;
  kind?: OfferKind;
  providerId?: string;
  modelRevision?: string;
  audioId?: string;
  basisSeq?: number;
  writeRanges: RangeUs[];
  ops: OfferOpInput[];
  challenges?: OfferChallenge[];
  derivedFrom?: {
    range: RangeUs;
    supportingIds?: string[];
  };
  notes?: StructuredNote[];
  title?: string;
  optionIndex?: number;
  optionCount?: number;
  score?: number;
  semanticKey?: string;
}

export interface OfferValidationResult {
  valid: boolean;
  status: OfferStatus;
  conflicts: OfferConflict[];
  requiredChallenges: OfferChallenge[];
  notes: StructuredNote[];
}

export interface OfferPreviewDiff {
  beats: {
    added: Beat[];
    moved: Array<{ before: Beat; after: Beat; deltaUs: number }>;
    removed: Beat[];
  };
  unbeats: {
    added: Unbeat[];
    moved: Array<{ before: Unbeat; after: Unbeat; deltaUs: number }>;
    removed: Unbeat[];
  };
  sounds: {
    added: Sound[];
    updated: Array<{ before: Sound; after: Sound }>;
    removed: Sound[];
  };
  boundaries: {
    added: Boundary[];
    moved: Array<{ before: Boundary; after: Boundary; deltaUs: number }>;
    removed: Boundary[];
  };
  segments: {
    added: Segment[];
    updated: Array<{ before: Segment; after: Segment }>;
    removed: Segment[];
  };
}

export interface OfferConsequences {
  beatsAdded: number;
  beatsMoved: number;
  beatsRemoved: number;
  unbeatsMoved: number;
  maxShiftUs: number;
  soundsAdded: number;
  soundsUpdated: number;
  soundsRemoved: number;
  boundaryChanges: number;
  segmentChanges: number;
}

export interface OfferPreviewResult {
  validation: OfferValidationResult;
  nextSnapshot: Snapshot;
  diff: OfferPreviewDiff;
  previewTempoMap: TempoMap;
  consequences: OfferConsequences;
}

export interface SignalPayload {
  layer: "heavy_recompute";
  scope: RangeUs | "song";
  reason: string;
  generation: number;
}

export interface TraceAuthor {
  kind: AuthorKind;
  id?: string;
}

export interface TraceEvent<TPayload = unknown> {
  schemaVersion: "0.3";
  seq: number;
  id: string;
  type: TraceEventType;
  tsMs?: number;
  author: TraceAuthor;
  payload: TPayload;
}

export interface RejectionSuppression {
  semanticKey: string;
  writeRanges: RangeUs[];
  rejectedAtSeq: number;
  offerId: string;
}

export interface EffortLedger {
  listenUs: number;
  waitUs: number;
  actionCount: number;
}

export interface EntityIdRecord {
  id: string;
  kind: EntityKind;
  active: boolean;
  createdByEventId: string;
  retiredByEventId?: string;
}

export interface RetiredEntityIdRecord {
  id: string;
  kind: EntityKind;
  createdByEventId: string;
  retiredByEventId: string;
}

export interface OrphanedTransaction {
  seq: number;
  eventId: string;
  type: TraceEventType;
  code: "ORPHANED_BY_RETRACT";
  causeCode: string;
  message: string;
}

export interface SessionState {
  audio: AudioBinding | null;
  durationUs: number;
  segments: Segment[];
  boundaries: Map<string, Boundary>;
  spans: Span[];
  beats: Map<string, Beat>;
  unbeats: Map<string, Unbeat>;
  sounds: Map<string, Sound>;
  soundClasses: Map<string, SoundClass>;
  approvals: Map<string, Approval>;
  idRegistry: Map<string, EntityIdRecord>;
  offers: Map<string, Offer>;
  suppressedOffers: Offer[];
  suppressions: RejectionSuppression[];
  orphanedTransactions: OrphanedTransaction[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedRanges: RangeUs[];
  ready: boolean;
  readyAtSeq: number | null;
  events: TraceEvent[];
  eventIds: Set<string>;
  retractedEventIds: Set<string>;
  lastSeq: number;
}

export interface Snapshot {
  schemaVersion: "0.3";
  audio: AudioBinding | null;
  durationUs: number;
  segments: Segment[];
  boundaries: Boundary[];
  spans: Span[];
  beats: Beat[];
  unbeats: Unbeat[];
  sounds: Sound[];
  soundClasses: SoundClass[];
  approvals: Approval[];
  retiredEntityIds: RetiredEntityIdRecord[];
  offers: Offer[];
  suppressions: RejectionSuppression[];
  orphanedTransactions: OrphanedTransaction[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedRanges: RangeUs[];
  ready: boolean;
  readyAtSeq: number | null;
  retractedEventIds: string[];
  lastSeq: number;
}

export interface ProviderView {
  snapshot: Snapshot;
  constraints: CompiledConstraints;
}

export interface TraceCheckpoint {
  schemaVersion: "0.3";
  lastSeq: number;
  eventCount: number;
  stateHash: string;
  snapshot: Snapshot;
}

export interface CheckpointVerification {
  valid: boolean;
  reason?: string;
  expectedHash: string;
  actualHash: string;
}

export type GridFitStatus = "on_grid" | "subdivision" | "unexplained";

export interface GridFitItem {
  soundId: string;
  anchorId: string;
  anchorRole: SnapRole;
  atUs: number;
  nearestBeatId: string | null;
  nearestBeatDistUs: number | null;
  nearestDivisor: number | null;
  slotAtUs: number | null;
  residualUs: number | null;
  status: GridFitStatus;
}

export interface GridFitOptions {
  anchorSelector?: "primary" | SnapRole;
  classId?: string;
  voiceGroupId?: string;
  verifiedOnly?: boolean;
  divisors?: number[];
  tolUs?: number;
  maxInterpolatedIbiUs?: number;
}

export type TempoPointKind = "steady" | "ramp" | "free_pulse" | "fermata_hold";

export interface TempoPoint {
  atUs: number;
  endUs: number | null;
  periodUs: number;
  endPeriodUs: number | null;
  bpm: number;
  meter: number;
  startBeatId: string;
  beatCount: number;
  intervalCount: number;
  segmentId: string | null;
  kind: TempoPointKind;
  downbeat?: boolean;
  phraseReset?: boolean;
}

export interface TempoMap {
  barPolicy: "explicit_only";
  points: TempoPoint[];
  notes: StructuredNote[];
}

export interface TempoMapOptions {
  defaultMeter?: number;
  steadyJitterTolUs?: number;
  freeJitterTolUs?: number;
  maxInterpolatedIbiUs?: number;
}

export type InboxReasonCode =
  | "stray_beats_in_break"
  | "unexplained_sounds"
  | "sudden_tempo_jump"
  | "low_confidence_beats"
  | "unbeat_repulsion_tension"
  | "conflicted_offers"
  | "unreviewed_passage"
  | "reviewed_passage";

export interface InboxItem {
  startUs: number;
  endUs: number;
  segmentId: string;
  priority: InboxPriority;
  score: number;
  reasonCode: InboxReasonCode;
  reasonCodes: InboxReasonCode[];
  params: Record<string, number | string | boolean | null>;
}

export interface InboxOptions {
  windowUs?: number;
  lowConfThreshold?: number;
  jumpRatioThreshold?: number;
  includeReviewedClean?: boolean;
  gridFitOptions?: GridFitOptions;
}

export interface ProviderCapabilities {
  draft: boolean;
  sounds: boolean;
  snapSuggestions: boolean;
  soundOptions?: boolean;
  soundMatching?: boolean;
  segmentation?: boolean;
  constrainedRecompute: boolean;
  supportedSnapFamilies: string[];
  supportedSnapRoles: SnapRole[];
  alternatives: boolean;
  repeatPropagation: boolean;
}

export interface ProviderManifest {
  id: string;
  version: string;
  kind: ProviderKind;
  modelRevision?: string;
  capabilities: ProviderCapabilities;
}

export interface EvidenceRef {
  evidenceId: string;
  audioId: string;
  [key: string]: JsonValue | undefined;
}

export interface AnalysisDraft {
  segments?: SegmentInput[];
  boundaries?: BoundaryInput[];
  spans?: SpanInput[];
  beats?: BeatInput[];
  sounds?: SoundInput[];
  soundClasses?: SoundClassInput[];
  unbeats?: UnbeatInput[];
}

export interface AnalyzeRequest {
  requestId: string;
  generation: number;
  audioId: string;
  audio: AudioBinding;
  evidenceRef: EvidenceRef;
  view: ProviderView;
}

export interface SuggestSnapsRequest {
  requestId: string;
  generation: number;
  audioId: string;
  evidenceRef: EvidenceRef;
  atUs: number;
  windowUs: number;
  view: ProviderView;
}

export interface SuggestSoundOptionsRequest {
  requestId: string;
  generation: number;
  audioId: string;
  evidenceRef: EvidenceRef;
  atUs?: number;
  rangeUs?: RangeUs;
  windowUs: number;
  view: ProviderView;
}

export interface MatchSoundsRequest {
  requestId: string;
  generation: number;
  basisSeq: number;
  audioId: string;
  evidenceRef: EvidenceRef;
  exemplarSoundIds: string[];
  matchMode: SoundMatchMode;
  classId?: string;
  scope: RangeUs | "song";
  view: ProviderView;
}

export interface SuggestSegmentationRequest {
  requestId: string;
  generation: number;
  basisSeq: number;
  audioId: string;
  evidenceRef: EvidenceRef;
  scope: RangeUs | "song";
  reason: string;
  view: ProviderView;
}

export interface RecomputeRequest {
  requestId: string;
  generation: number;
  basisSeq: number;
  audioId: string;
  evidenceRef: EvidenceRef;
  scope: RangeUs | "song";
  reason: string;
  view: ProviderView;
}

export interface RecomputeResult {
  batchId?: string;
  offers: OfferInput[];
}

export interface Provider {
  manifest(): ProviderManifest;
  prepare(audio: AudioBinding, cancelToken?: AbortSignal): Promise<EvidenceRef>;
  analyze(request: AnalyzeRequest, cancelToken?: AbortSignal): Promise<AnalysisDraft>;
  suggestSnaps(
    request: SuggestSnapsRequest,
    cancelToken?: AbortSignal,
  ): Promise<SnapCandidate[]>;
  suggestSoundOptions?(
    request: SuggestSoundOptionsRequest,
    cancelToken?: AbortSignal,
  ): Promise<SoundOptionCandidate[]>;
  matchSounds?(
    request: MatchSoundsRequest,
    cancelToken?: AbortSignal,
  ): Promise<OfferInput[] | RecomputeResult>;
  suggestSegmentation?(
    request: SuggestSegmentationRequest,
    cancelToken?: AbortSignal,
  ): Promise<OfferInput[] | RecomputeResult>;
  recompute(
    request: RecomputeRequest,
    cancelToken?: AbortSignal,
  ): Promise<RecomputeResult>;
  cancel?(requestId: string): void | Promise<void>;
}

export interface SessionControllerOptions {
  provider: Provider;
  durationUs?: number;
  triggerPolicy?: TriggerPolicyOverride;
  clock?: () => number;
  idGenerator?: (prefix: string, seq: number) => string;
  initialEvents?: TraceEvent[];
}

export interface LayerLifecycleState {
  audioId: string | null;
  layer: TriggerLayer;
  generation: number;
  requestId: string | null;
  inFlight: boolean;
}

export interface AnalyzeOutcome {
  status: "applied" | "manual" | "cancelled" | "superseded";
  audioId: string | null;
  generation: number;
  event: TraceEvent | null;
  state: SessionState;
}

export interface SuggestSnapsOutcome {
  status: "ok" | "cancelled" | "superseded";
  audioId: string | null;
  generation: number;
  candidates: SnapCandidate[];
}

export interface SuggestSoundOptionsOutcome {
  status: "ok" | "cancelled" | "superseded";
  audioId: string | null;
  generation: number;
  options: SoundOptionCandidate[];
}

export interface RecomputeOutcome {
  status: "applied" | "held" | "cancelled" | "superseded";
  audioId: string | null;
  generation: number;
  offers: Offer[];
  suppressedOffers: Offer[];
}

export type AcceptOfferOutcome =
  | {
      ok: true;
      status: "accepted";
      conflicts: [];
      requiredChallenges: [];
      acceptedOffers: Offer[];
      supersededOfferIds: string[];
    }
  | {
      ok: false;
      status: OfferStatus;
      conflicts: OfferConflict[];
      requiredChallenges: OfferChallenge[];
    };

export interface RejectOfferOutcome {
  ok: true;
  offer: Offer | undefined;
}

export interface UndoRedoTargets {
  canUndo: boolean;
  canRedo: boolean;
  undoTargetEventId: string | null;
  redoTargetEventId: string | null;
  undoStack: string[];
  redoStack: string[];
}

export interface UndoRedoOutcome {
  ok: boolean;
  targetEventId: string | null;
  event: TraceEvent | null;
  state: SessionState;
}

// Runtime constants (1-to-1 with index.js)
export declare const US_PER_SEC: 1000000;
export declare const US_PER_MS: 1000;
export declare const TRACE_SCHEMA_VERSION: "0.3";

export declare const DEFAULT_BEAT_TOL_US: number;
export declare const DEFAULT_SNAP_BEAT_TOL_US: number;
export declare const DEFAULT_UNBEAT_TOL_US: number;
export declare const DEFAULT_SOUND_TOL_US: number;
export declare const DEFAULT_BOUNDARY_TOL_US: number;
export declare const DEFAULT_UNBEAT_FALLBACK_RADIUS_US: number;
export declare const MAX_UNBEAT_REPULSION_RADIUS_US: number;
export declare const DEFAULT_TRIGGER_POLICY: TriggerPolicy;

export declare const ORIGINS: readonly Origin[];
export declare const VERDICT_KINDS: readonly VerdictKind[];
export declare const VERDICT_VIAS: readonly VerdictVia[];
export declare const ENTITY_KINDS: readonly EntityKind[];
export declare const VERDICT_TARGET_KINDS: readonly VerdictTargetKind[];
export declare const SEGMENT_REGIMES: readonly SegmentRegime[];
export declare const SEGMENT_KINEMATICS: readonly SegmentKinematics[];
export declare const SPAN_KINDS: readonly SpanKind[];
export declare const INBOX_PRIORITIES: readonly InboxPriority[];
export declare const SNAP_ROLES: readonly SnapRole[];
export declare const WELL_KNOWN_SOUND_FAMILIES: readonly WellKnownSoundFamily[];
export declare const SOUND_REGISTERS: readonly SoundRegister[];
export declare const SOUND_END_KINDS: readonly SoundEndKind[];
export declare const SOUND_BLEND_STATUSES: readonly SoundBlendStatus[];
export declare const SOUND_MATCH_MODES: readonly SoundMatchMode[];
export declare const PULSE_LAYERS: readonly PulseLayer[];
export declare const BEAT_ANCHOR_KINDS: readonly BeatAnchorKind[];
export declare const TRIGGER_LAYERS: readonly TriggerLayer[];
export declare const OFFER_KINDS: readonly OfferKind[];
export declare const OFFER_STATUSES: readonly OfferStatus[];
export declare const CONFLICT_KINDS: readonly ConflictKind[];
export declare const CHALLENGE_DISPOSITIONS: readonly ChallengeDisposition[];
export declare const TRACE_EVENT_TYPES: readonly TraceEventType[];
export declare const AUTHOR_KINDS: readonly AuthorKind[];
export declare const PROVIDER_KINDS: readonly ProviderKind[];
export declare const TRANSACTION_OP_KINDS: readonly TransactionOpKind[];
export declare const OFFER_OP_KINDS: readonly OfferOpKind[];

// JCS & Error
export declare class EnsomiError extends Error {
  code: string;
  details?: Record<string, unknown>;
  constructor(code: string, message: string, details?: Record<string, unknown>);
}

export declare function compareCodeUnits(a: string, b: string): number;
export declare function canonicalJson(value: unknown): string;
export declare function sha256Hex(input: string): string;
export declare function jcsSha256Hex(value: unknown): string;

// Timebase & Range geometry
export declare function isNonNegSafeInt(value: unknown): value is number;
export declare function assertNonNegSafeIntUs(
  value: unknown,
  label?: string,
  errorCode?: string,
): number;
export declare function assertSafeIntUs(
  value: unknown,
  label?: string,
  errorCode?: string,
): number;
export declare function assertPointUs(
  atUs: unknown,
  durationUs?: number,
  label?: string,
): number;
export declare function assertInteriorPointUs(
  atUs: unknown,
  durationUs?: number,
  label?: string,
): number;
export declare function assertIntervalUs(
  startUs: unknown,
  endUs: unknown,
  durationUs?: number,
  label?: string,
  errorCode?: string,
): RangeUs;
export declare function assertRangeUs(range: unknown, durationUs?: number): RangeUs;
export declare function secToUs(sec: number): number;
export declare function usToSec(us: number): number;
export declare function msToUs(ms: number): number;
export declare function usToMs(us: number): number;
export declare function sampleToUs(sampleIndex: number, sampleRateHz: number): number;
export declare function usToNearestSample(us: number, sampleRateHz: number): number;
export declare function formatTimeUs(atUs: number): string;
export declare function rangeContainsUs(range: RangeUs, atUs: number): boolean;
export declare function rangesOverlapUs(a: RangeUs | "song", b: RangeUs | "song"): boolean;
export declare function mergeRangesUs(ranges: readonly RangeUs[]): RangeUs[];
export declare function subtractRangeUs(
  ranges: readonly RangeUs[],
  cutStartUs: number,
  cutEndUs: number,
): RangeUs[];

// Normalizers & Structural validators
export declare function deepFreeze<T>(value: T): T;
export declare function assertNonEmptyId(
  value: unknown,
  label: string,
  errorCode?: string,
): string;
export declare function clampUnit(value: number): number;
export declare function assertPositiveRevision(revision: unknown, entityName: string): number;
export declare function authorKindToOrigin(authorKind: string): Origin;
export declare function validateSoundFacet(
  facet: unknown,
  anchors?: readonly { id: string }[],
): string;
export type VerdictTargetEntity =
  | Beat
  | BeatInput
  | Unbeat
  | UnbeatInput
  | Sound
  | SoundInput
  | SoundClass
  | SoundClassInput
  | Boundary
  | BoundaryInput
  | Record<string, unknown>;

export declare function computeEntityValueDigest(
  kind: VerdictTargetKind,
  entity: VerdictTargetEntity,
  facet?: string,
): string;
export declare function normalizeVerdict(raw: unknown): Verdict;
export declare function normalizeApproval(raw: unknown): Approval;
export declare function normalizeAudioBinding(raw: unknown): AudioBinding;
export declare function normalizeBoundary(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Boundary;
export declare function normalizeSegment(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Segment;
export declare function normalizeSpan(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Span;
export declare function normalizeBeatBinding(raw: unknown): BeatBinding;
export declare function normalizeBeatStructural(raw: unknown): BeatStructural;
export declare function normalizeBeat(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Beat;
export declare function normalizeUnbeat(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Unbeat;
export declare function normalizeSoundCharacteristics(raw: unknown): SoundCharacteristics;
export declare function normalizeSound(
  raw: unknown,
  ctx?: {
    durationUs?: number;
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): Sound;
export declare function normalizeSoundClass(
  raw: unknown,
  ctx?: {
    defaultOrigin?: Origin;
    defaultEventId?: string;
    defaultRevision?: number;
  },
): SoundClass;
export declare function normalizeStructuredNote(raw: unknown): StructuredNote;
export declare function normalizeSnapCandidate(
  raw: unknown,
  queryAtUs?: number,
  durationUs?: number,
): SnapCandidate;
export declare function normalizeSoundOptionCandidate(
  raw: unknown,
  durationUs?: number,
): SoundOptionCandidate;
export declare function decomposeRangeToSegments(
  segments: readonly Segment[],
  range: RangeUs,
): Array<{ segmentId: string; startUs: number; endUs: number }>;
export declare function getInteriorBoundaryTimesUs(segments: readonly Segment[]): number[];
export declare function assertTraceEventEnvelope(raw: unknown): TraceEvent;
export declare function isOfferOpKind(opKind: string): boolean;
export declare function isTransactionOpKind(opKind: string): boolean;
export declare function assertOfferShape(raw: unknown): void;

// Constraints & Views
export declare function compileConstraints(
  stateOrSnapshot: Snapshot | SessionState,
): CompiledConstraints;
export declare function collectApprovedKeepIntervals(
  segments: readonly Segment[],
  spans: readonly Span[],
): RangeUs[];
export declare function computeGridFit(
  snapshot: Snapshot | SessionState,
  options?: GridFitOptions,
): GridFitItem[];
export declare function projectTempoMap(
  snapshot: Snapshot | SessionState,
  options?: TempoMapOptions,
): TempoMap;
export declare function computeIntervalMusicalDigest(
  snapshot: Snapshot | SessionState,
  startUs: number,
  endUs: number,
): string;
export declare function rankInboxItems(
  snapshot: Snapshot | SessionState,
  options?: InboxOptions,
): InboxItem[];

// Pure Transaction Interpreter & Offer Engine
export declare function deriveChildEntityId(
  txId: string,
  opIndex: number,
  childSlot: string,
): string;
export declare function getVerdictEntity(
  state: SessionState,
  kind: VerdictTargetKind,
  id: string,
): Beat | Unbeat | Sound | SoundClass | Boundary | undefined;
export declare function executeTransaction(
  state: SessionState,
  ops: readonly TransactionOp[],
  ctx: {
    txId: string;
    eventId: string;
    actorMode: "user" | "offer";
    actorOrigin: Origin;
  },
): { writeCount: number };

export declare function computeOfferDependencies(
  snapshotOrState: Snapshot | SessionState,
  writeRanges: readonly RangeUs[],
  derivedFrom?: { range: RangeUs; supportingIds?: readonly string[] },
  ops?: readonly OfferOpInput[],
): OfferDependencies;
export declare function computeOfferBasisDigest(dependencies: OfferDependencies): string;
export declare function computeOfferSemanticKey(offer: {
  kind?: string;
  writeRanges: readonly RangeUs[];
  ops: readonly OfferOpInput[];
}): string;
export declare function isOfferSuppressed(
  stateOrSnapshot: Snapshot | SessionState,
  offer: {
    semanticKey?: string;
    kind?: string;
    writeRanges: readonly RangeUs[];
    ops: readonly OfferOpInput[];
  },
): boolean;
export declare function computeOfferDiffAndConsequences(
  beforeState: SessionState,
  afterState: SessionState,
): { diff: OfferPreviewDiff; consequences: OfferConsequences };
export declare function formatConsequenceSummary(consequences: OfferConsequences): string;
export declare function detectSiblingConflicts(offers: readonly Offer[]): Map<string, number[]>;
export declare function validateOffer(
  snapshotOrState: Snapshot | SessionState,
  offer: Offer,
  options?: { ignoreTerminalStatus?: boolean },
): OfferValidationResult;
export declare function previewOffer(
  snapshotOrState: Snapshot | SessionState,
  offer: Offer,
  tempoOptions?: TempoMapOptions,
): OfferPreviewResult;
export declare function bindOfferToBasis(
  capturedBasis: Snapshot | SessionState,
  rawOffer: OfferInput,
  defaults?: {
    defaultBatchId?: string;
    defaultProviderId?: string;
    defaultModelRevision?: string;
    defaultAudioId?: string;
    defaultBasisSeq?: number;
  },
): Offer;

// Trace Reducer & Checkpoints
export declare function createInitialState(options?: { durationUs?: number }): SessionState;
export declare function toSnapshot(stateOrSnapshot: SessionState | Snapshot): Snapshot;
export declare function serializeState(stateOrSnapshot: SessionState | Snapshot): Snapshot;
export declare function computeSnapshotDigest(stateOrSnapshot: SessionState | Snapshot): string;
export declare function createProviderView(stateOrSnapshot: SessionState | Snapshot): ProviderView;
export declare function hasActiveHumanTruth(state: SessionState): boolean;
export declare function resolveActiveRetractions(events: readonly TraceEvent[]): Set<string>;
export declare function computeUndoRedoTargets(
  stateOrEvents: SessionState | readonly TraceEvent[],
): UndoRedoTargets;
export declare function applyEvent(state: SessionState, event: TraceEvent): SessionState;
export declare function foldTrace(
  events: readonly TraceEvent[],
  options?: { durationUs?: number },
): SessionState;
export declare function serializeTraceNdjson(events: readonly TraceEvent[]): string;
export declare function parseTraceNdjson(
  text: string,
  options?: { discardTruncatedTail?: boolean },
): TraceEvent[];
export declare function createCheckpoint(state: SessionState): TraceCheckpoint;
export declare function verifyCheckpoint(
  checkpoint: TraceCheckpoint,
  prefixEvents: readonly TraceEvent[],
): CheckpointVerification;

// SessionController
export declare function assertProvider(provider: Provider): void;

export declare class SessionController {
  constructor(options: SessionControllerOptions);
  getManifest(): ProviderManifest;
  getTriggerPolicy(): TriggerPolicy;
  isClosed(): boolean;
  getState(): SessionState;
  getSnapshot(): Snapshot;
  getTrace(): readonly TraceEvent[];
  getLayerState(layer?: TriggerLayer): LayerLifecycleState;
  getGeneration(): number;
  getEffortLedger(): EffortLedger;
  getValidOffers(): Offer[];
  getHeldOffers(): Offer[];
  isHoldingOffers(): boolean;
  setHoldOffers(hold: boolean): { flushed: boolean; offers: Offer[] };
  exportNdjson(): string;
  abortLayer(layer: TriggerLayer, reason?: string): void;
  abortAll(reason?: string, errorCode?: "ABORTED" | "SUPERSEDED"): void;
  close(): void;
  bindAudio(audio: AudioBinding, signal?: AbortSignal): Promise<EvidenceRef>;
  openSong(audio: AudioBinding, signal?: AbortSignal): Promise<AnalyzeOutcome>;
  resetDocument(
    options?: { reason?: string; audio?: AudioBinding },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  analyze(signal?: AbortSignal, options?: { basisSeq?: number }): Promise<AnalyzeOutcome>;
  suggestSnaps(
    request: { atUs: number; windowUs?: number; immediate?: boolean },
    signal?: AbortSignal,
  ): Promise<SuggestSnapsOutcome>;
  suggestSoundOptions(
    request: { atUs?: number; rangeUs?: RangeUs; windowUs?: number; immediate?: boolean },
    signal?: AbortSignal,
  ): Promise<SuggestSoundOptionsOutcome>;
  loadDraft(
    draft: AnalysisDraft & { replace?: boolean },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  applyEdit(
    opOrOps: TransactionOp | readonly TransactionOp[],
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  retract(
    targetEventIdOrSeq: string | number,
    reason?: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  canUndo(): boolean;
  canRedo(): boolean;
  undo(reason?: string, author?: TraceAuthor): UndoRedoOutcome;
  redo(reason?: string, author?: TraceAuthor): UndoRedoOutcome;
  recordListenEffort(
    effort: { listenUs?: number; waitUs?: number; range?: RangeUs },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  markReady(
    notes?: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  recompute(
    request: { scope: RangeUs | "song"; reason: string },
    author?: TraceAuthor,
  ): Promise<RecomputeOutcome>;
  matchSounds(
    request: {
      exemplarSoundIds: string[];
      matchMode: SoundMatchMode;
      classId?: string;
      scope?: RangeUs | "song";
      reason?: string;
    },
    author?: TraceAuthor,
  ): Promise<RecomputeOutcome>;
  suggestSegmentation(
    request?: { scope?: RangeUs | "song"; reason?: string },
    author?: TraceAuthor,
  ): Promise<RecomputeOutcome>;
  previewOffer(offerIdOrOffer: string | Offer, tempoOptions?: TempoMapOptions): OfferPreviewResult;
  acceptOffer(
    offerIdOrInput:
      | string
      | { offerIds: string[]; authorizedChallenges?: ChallengeAuthorization[] },
    author?: TraceAuthor,
  ): AcceptOfferOutcome;
  rejectOffer(offerId: string, reason?: string, author?: TraceAuthor): RejectOfferOutcome;
}
