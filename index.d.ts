// Type definitions for ensomi tempo contract
// Zero-dependency ESM package (Node 22/24+ and browser-compatible sans-IO core)

export type EntitySource = "draft" | "user" | "offer";
export type SegmentRegime = "auto" | "free" | "break";
export type SpanKind = "keep" | "free" | "inbox";
export type SpanPriority = "high" | "medium" | "low";

export type SnapRole =
  | "transient_attack"
  | "p_center"
  | "harmonic_change"
  | "release";

export type SnapFamily =
  | "percussion"
  | "piano_keys"
  | "strings_winds"
  | "vocal"
  | "bass_synth"
  | "other";

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

export type OfferScopeKind = "local" | "forward" | "cross_region";

export type OfferStatus =
  | "valid"
  | "stale"
  | "invalid"
  | "accepted"
  | "rejected"
  | "superseded";

export type ConflictKind =
  | "protected_keep"
  | "version_mismatch"
  | "range_changed"
  | "out_of_scope"
  | "sibling_conflict";

export type TraceEventType =
  | "audio_bound"
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

export type EditOpKind =
  | "upsert_beat"
  | "move_beat"
  | "snap_beat"
  | "remove_beat"
  | "shift_beats"
  | "upsert_sound"
  | "move_sound"
  | "remove_sound"
  | "apply_unbeat_cue"
  | "move_unbeat"
  | "remove_unbeat"
  | "move_segment_boundary"
  | "split_segment"
  | "merge_segments"
  | "set_segment_keep"
  | "set_segment_regime"
  | "set_range_keep"
  | "set_range_free"
  | "promote_range_to_segments"
  | "upsert_span"
  | "remove_span";

/** Transient half-open interval [t0, t1) in canonical source-audio seconds (`0 <= t0 < t1`). */
export interface Range {
  t0: number;
  t1: number;
}

/** Contiguous macro-partition segment covering [t0, t1). */
export interface Segment {
  id: string;
  t0: number;
  t1: number;
  regime: SegmentRegime;
  kept: boolean;
  source: EntitySource;
  revision: number;
}

/** Child interval strictly inside a single parent `Segment` (`seg.t0 <= span.t0 < span.t1 <= seg.t1`). */
export interface Span {
  id: string;
  segmentId: string;
  t0: number;
  t1: number;
  kind: SpanKind;
  priority?: SpanPriority;
  reason?: string;
  score?: number;
  source: EntitySource;
  revision: number;
}

/** Pulse beat (`source === "user"` acts directly as a soft beat guide cue). */
export interface Beat {
  id: string;
  timeSec: number;
  source: EntitySource;
  snapEventId?: string;
  snapFamily?: SnapFamily;
  snapRole?: SnapRole;
  confidence?: number;
  revision: number;
}

/** Acoustic onset anchor (`source === "user"` acts directly as a soft sound observation cue). */
export interface SoundAnchor {
  id: string;
  timeSec: number;
  strength?: number;
  family?: SnapFamily;
  role?: SnapRole;
  sigmaMs?: number;
  source: EntitySource;
  revision: number;
}

/** Standalone soft repulsion cue marking where no beat should fall. */
export interface UnbeatCue {
  id: string;
  timeSec: number;
  source: "user";
  revision: number;
}

/** Verifiable `micro_assist` acoustic snap target. */
export interface SnapCandidate {
  eventId: string;
  targetSec: number;
  deltaMs: number;
  family: SnapFamily;
  role: SnapRole;
  sigmaMs?: number;
  label: string;
  salience: number;
}

export interface AudioDescriptor {
  songId: string;
  durationSec: number;
  sampleRate?: number;
  clock: string;
  decoder?: string;
  leadOffsetSec?: number;
}

export type AudioDescriptorInput = Omit<AudioDescriptor, "clock" | "songId"> & {
  songId?: string;
  audioId?: string;
  clock?: string;
};

export type SegmentInput = Omit<Segment, "regime" | "kept" | "source" | "revision"> & {
  regime?: SegmentRegime;
  kept?: boolean;
  source?: EntitySource;
  revision?: number;
};

export type SpanInput = Omit<Span, "source" | "revision"> & {
  source?: EntitySource;
  revision?: number;
};

export type BeatInput = Omit<Beat, "source" | "revision"> & {
  source?: EntitySource;
  revision?: number;
};

export type SoundAnchorInput = Omit<SoundAnchor, "source" | "revision"> & {
  source?: EntitySource;
  revision?: number;
};

export type UnbeatCueInput = Omit<UnbeatCue, "source" | "revision"> & {
  source?: "user";
  revision?: number;
};

export type EditOp =
  | { op: "upsert_beat"; beat: BeatInput }
  | {
      op: "move_beat";
      id: string;
      timeSec: number;
      source?: EntitySource;
      snapEventId?: string | null;
      snapFamily?: SnapFamily | null;
      snapRole?: SnapRole | null;
      confidence?: number;
    }
  | {
      op: "snap_beat";
      id: string;
      timeSec: number;
      snapEventId: string;
      snapFamily: SnapFamily;
      snapRole: SnapRole;
      source?: EntitySource;
    }
  | { op: "remove_beat"; id: string }
  | {
      op: "shift_beats";
      range?: Range;
      beatIds?: string[];
      deltaSec: number;
      source?: EntitySource;
    }
  | { op: "upsert_sound"; sound: SoundAnchorInput }
  | {
      op: "move_sound";
      id: string;
      timeSec: number;
      strength?: number;
      family?: SnapFamily;
      role?: SnapRole;
      sigmaMs?: number;
      source?: EntitySource;
    }
  | { op: "remove_sound"; id: string }
  | {
      op: "apply_unbeat_cue";
      id: string;
      timeSec: number;
      captureRadiusSec?: number;
      removeUserBeats?: boolean;
    }
  | { op: "move_unbeat"; id: string; timeSec: number }
  | { op: "remove_unbeat"; id: string }
  | {
      op: "move_segment_boundary";
      leftSegmentId: string;
      rightSegmentId: string;
      timeSec: number;
      source?: EntitySource;
    }
  | {
      op: "split_segment";
      segmentId: string;
      newSegmentId: string;
      timeSec: number;
      source?: EntitySource;
    }
  | {
      op: "merge_segments";
      leftSegmentId: string;
      rightSegmentId: string;
      source?: EntitySource;
    }
  | {
      op: "set_segment_keep";
      segmentId: string;
      kept: boolean;
      source?: EntitySource;
    }
  | {
      op: "set_segment_regime";
      segmentId: string;
      regime: SegmentRegime;
      source?: EntitySource;
    }
  | {
      op: "set_range_keep";
      t0: number;
      t1: number;
      kept: boolean;
      source?: EntitySource;
    }
  | {
      op: "set_range_free";
      t0: number;
      t1: number;
      free: boolean;
      source?: EntitySource;
    }
  | {
      op: "promote_range_to_segments";
      t0: number;
      t1: number;
      newSegmentIds?: [string?, string?];
      regime?: SegmentRegime;
      kept?: boolean;
      source?: EntitySource;
    }
  | { op: "upsert_span"; span: SpanInput }
  | { op: "remove_span"; id: string };

export interface BeatGuideConstraint {
  beatId: string;
  timeSec: number;
  toleranceSec: number;
  snapEventId?: string;
  snapFamily?: SnapFamily;
  snapRole?: SnapRole;
}

export interface UnbeatRepulsionConstraint {
  unbeatId: string;
  timeSec: number;
  localIbiSec: number | null;
  repulsionRadiusSec: number;
}

export interface SoundObservationConstraint {
  soundId: string;
  timeSec: number;
  strength?: number;
  family?: SnapFamily;
  role?: SnapRole;
  sigmaMs?: number;
}

export interface FreeIntervalConstraint {
  segmentId: string;
  spanId?: string;
  t0: number;
  t1: number;
}

export interface KeepLockConstraint {
  segmentId: string;
  spanId?: string;
  t0: number;
  t1: number;
}

export interface CompiledConstraints {
  beatGuides: BeatGuideConstraint[];
  unbeatRepulsions: UnbeatRepulsionConstraint[];
  soundObservations: SoundObservationConstraint[];
  freeIntervals: FreeIntervalConstraint[];
  keepLocks: KeepLockConstraint[];
}

export interface WriteScope {
  segmentId: string;
  t0: number;
  t1: number;
}

export interface PatchOp {
  upsertBeats?: BeatInput[];
  removeBeatIds?: string[];
  upsertSounds?: SoundAnchorInput[];
  removeSoundIds?: string[];
  splitSegments?: Array<{
    segmentId: string;
    newSegmentId: string;
    timeSec: number;
  }>;
  moveSegmentBoundaries?: Array<{
    leftSegmentId: string;
    rightSegmentId: string;
    timeSec: number;
  }>;
  setSegmentRegimes?: Array<{
    segmentId: string;
    regime: SegmentRegime;
  }>;
}

export interface OfferConflict {
  kind: ConflictKind;
  message: string;
  t0?: number;
  t1?: number;
}

export interface OfferConsequences {
  beatsAdded: number;
  beatsMoved: number;
  beatsRemoved: number;
  maxShiftMs: number;
  segmentChanges: number;
}

export interface PatchOffer {
  id: string;
  batchId: string;
  semanticKey: string;
  providerId: string;
  providerKind: ProviderKind;
  generation: number;
  basisSeq: number;
  expectedRevisions: Record<string, string>;
  expectedScopeBeatIds?: string[];
  writeScopes: WriteScope[];
  readScope?: Range | "song";
  scopeKind: OfferScopeKind;
  optionIndex?: number;
  optionCount?: number;
  title: string;
  consequenceSummary: string;
  advisoryNotes: string[];
  providerAdvisories?: string[];
  ops: PatchOp;
  consequences: OfferConsequences;
  conflicts: OfferConflict[];
  status: OfferStatus;
}

export interface OfferValidationResult {
  valid: boolean;
  status: OfferStatus;
  conflicts: OfferConflict[];
  advisoryNotes: string[];
}

export interface SignalPayload {
  layer: "heavy_recompute";
  scope: Range | "song";
  reason: string;
  generation: number;
}

export interface TraceAuthor {
  kind: AuthorKind;
  id?: string;
}

export interface TraceEvent<TPayload = unknown> {
  schemaVersion: "0.2";
  seq: number;
  id: string;
  type: TraceEventType;
  tsMs?: number;
  author: TraceAuthor;
  payload: TPayload;
}

export interface RejectionSuppression {
  semanticKey: string;
  writeScopes: WriteScope[];
  rejectedAtSeq: number;
  offerId: string;
}

export interface EffortLedger {
  listenSec: number;
  actionCount: number;
  waitSec: number;
}

export interface FoldWarning {
  seq: number;
  eventId: string;
  code: string;
  message: string;
}

export interface SegmentMutationRecord {
  seq: number;
  segmentIds: string[];
  t0: number;
  t1: number;
}

export interface SessionState {
  audio: AudioDescriptor | null;
  initialDurationSec: number;
  durationSec: number;
  segments: Segment[];
  spans: Span[];
  beats: Map<string, Beat>;
  sounds: Map<string, SoundAnchor>;
  unbeats: UnbeatCue[];
  offers: Map<string, PatchOffer>;
  entityRevisions: Map<string, string>;
  segmentMutations: SegmentMutationRecord[];
  suppressedOffers: PatchOffer[];
  suppressions: RejectionSuppression[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedRanges: Range[];
  ready: boolean;
  readyAtSeq: number | null;
  events: TraceEvent[];
  eventIds: Set<string>;
  retractedEventIds: Set<string>;
  lastSeq: number;
  foldWarnings: FoldWarning[];
}

export interface SerializedSessionState {
  audio: AudioDescriptor | null;
  durationSec: number;
  segments: Segment[];
  spans: Span[];
  beats: Beat[];
  sounds: SoundAnchor[];
  unbeats: UnbeatCue[];
  offers: PatchOffer[];
  entityRevisions: Record<string, string>;
  suppressions: RejectionSuppression[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedRanges: Range[];
  ready: boolean;
  readyAtSeq: number | null;
  retractedEventIds: string[];
  lastSeq: number;
}

export interface TraceCheckpoint {
  schemaVersion: "0.2";
  lastSeq: number;
  eventCount: number;
  initialDurationSec?: number;
  stateHash: string;
  snapshot: SerializedSessionState;
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
  timeSec: number;
  nearestBeatDistMs: number;
  nearestDivisor: number | null;
  slotTimeSec: number | null;
  /** Signed `(sound.timeSec - slotTimeSec) * 1000` (negative = early, positive = late; `Infinity` when 0 active beats). */
  residualMs: number;
  status: GridFitStatus;
}

export interface GridFitOptions {
  divisors?: number[];
  tolMs?: number;
  maxInterpolatedIbiSec?: number;
}

export interface RedLine {
  offsetMs: number;
  beatLengthMs: number;
  bpm: number;
  meter: number;
  startBeatId: string;
  beatCount: number;
  kind: "steady_run" | "free_pulse";
}

export interface RedLineProjection {
  barPolicy: "barlines_not_inferred";
  redLines: RedLine[];
  osuTimingPoints: string;
  losses: string[];
}

export interface RedLineOptions {
  defaultMeter?: number;
  jitterTolMs?: number;
  freeJitterTolMs?: number;
  maxInterpolatedIbiSec?: number;
  spans?: Iterable<Span> | Map<string, Span>;
}

export interface InboxSpanOptions {
  windowSec?: number;
  lowConfThreshold?: number;
  jumpRatioThreshold?: number;
  includeReviewedClean?: boolean;
  gridFitOptions?: GridFitOptions;
}

export interface ProviderCapabilities {
  draft: boolean;
  sounds: boolean;
  snapSuggestions: boolean;
  constrainedRecompute: boolean;
  supportedSnapFamilies: SnapFamily[];
  supportedSnapRoles: SnapRole[];
  diagnostics: boolean;
  alternatives: boolean;
  repeatPropagation: boolean;
}

export interface ProviderManifest {
  id: string;
  version: string;
  kind: ProviderKind;
  capabilities: ProviderCapabilities;
}

export type PreparedEvidence = Record<string, unknown>;

export interface AnalysisDraft {
  segments?: SegmentInput[];
  spans?: SpanInput[];
  beats?: BeatInput[];
  sounds?: SoundAnchorInput[];
  unbeats?: UnbeatCueInput[];
}

export interface SuggestSnapsRequest {
  timeSec: number;
  windowSec: number;
  generation: number;
}

export interface RecomputeRequest {
  scope: Range | "song";
  reason: string;
  generation: number;
  basisSeq: number;
  constraints: CompiledConstraints;
}

export interface RecomputeResult {
  batchId?: string;
  offers: PatchOffer[];
}

export interface TimingProvider {
  manifest(): ProviderManifest;
  prepare(audio: AudioDescriptor, signal?: AbortSignal): Promise<PreparedEvidence>;
  analyze(evidence: PreparedEvidence, signal?: AbortSignal): Promise<AnalysisDraft>;
  suggestSnaps(
    evidence: PreparedEvidence,
    state: SessionState,
    request: SuggestSnapsRequest,
    signal?: AbortSignal,
  ): Promise<SnapCandidate[]>;
  recompute(
    evidence: PreparedEvidence,
    state: SessionState,
    request: RecomputeRequest,
    signal?: AbortSignal,
  ): Promise<RecomputeResult>;
}

export type TriggerPolicyOverride = {
  [K in TriggerLayer]?: Partial<LayerTriggerRule>;
};

export interface SessionControllerOptions {
  provider: TimingProvider;
  durationSec?: number;
  triggerPolicy?: TriggerPolicyOverride;
  clock?: () => number;
  idGenerator?: (prefix: string, seq: number) => string;
  initialEvents?: TraceEvent[];
}

export interface LayerLifecycleState {
  songId: string | null;
  layer: TriggerLayer;
  generation: number;
  inFlight: boolean;
}

export interface AnalyzeOutcome {
  status: "applied" | "manual" | "cancelled" | "superseded";
  songId: string | null;
  generation: number;
  event: TraceEvent | null;
  state: SessionState;
}

export interface SuggestSnapsOutcome {
  status: "ok" | "cancelled" | "superseded";
  songId: string | null;
  generation: number;
  candidates: SnapCandidate[];
}

export interface RecomputeOutcome {
  status: "applied" | "held" | "cancelled" | "superseded";
  songId: string | null;
  generation: number;
  offers: PatchOffer[];
  suppressedOffers: PatchOffer[];
}

export type AcceptOfferOutcome =
  | {
      ok: true;
      conflicts: [];
      offer: PatchOffer | undefined;
      supersededOfferIds: string[];
    }
  | {
      ok: false;
      conflicts: OfferConflict[];
    };

export interface RejectOfferOutcome {
  ok: true;
  offer: PatchOffer | undefined;
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

export interface SyntheticDraftOptions {
  durationSec?: number;
  bpm?: number;
  jitterMs?: number;
  soundCount?: number;
  segmentCount?: number;
  seed?: number;
  startSec?: number;
}

export interface SyntheticDraft {
  segments: Segment[];
  spans: Span[];
  beats: Beat[];
  sounds: SoundAnchor[];
}

export interface ScriptedMockProviderOptions {
  id?: string;
  version?: string;
  draft?: AnalysisDraft;
  defaultScenario?:
    | "triplet_vs_reset"
    | "local_rewrite"
    | "forward"
    | "forward_continuation"
    | "cross_region"
    | "protected_keep_conflict"
    | "hard_constraint_conflict"
    | "slow_cancellable";
  delayMs?: number;
  analyzeDelayMs?: number;
  snapDelayMs?: number;
  ignoreAbort?: boolean;
  script?: Array<
    | RecomputeResult
    | ((ctx: {
        evidence: PreparedEvidence;
        state: SessionState;
        request: RecomputeRequest;
        signal?: AbortSignal;
        callIdx: number;
      }) => RecomputeResult | Promise<RecomputeResult>)
  >;
  snapScript?: Array<
    | SnapCandidate[]
    | ((ctx: {
        evidence: PreparedEvidence;
        state: SessionState;
        request: SuggestSnapsRequest;
        signal?: AbortSignal;
        callIdx: number;
      }) => SnapCandidate[] | Promise<SnapCandidate[]>)
  >;
}

export declare const TRACE_SCHEMA_VERSION: "0.2";
export declare const DEFAULT_BEAT_GUIDE_TOL_SEC: number;
export declare const DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC: number;
export declare const MAX_UNBEAT_REPULSION_RADIUS_SEC: number;
export declare const DEFAULT_TRIGGER_POLICY: TriggerPolicy;

export declare const ENTITY_SOURCES: readonly EntitySource[];
export declare const SEGMENT_REGIMES: readonly SegmentRegime[];
export declare const SPAN_KINDS: readonly SpanKind[];
export declare const SPAN_PRIORITIES: readonly SpanPriority[];
export declare const SNAP_ROLES: readonly SnapRole[];
export declare const SNAP_FAMILIES: readonly SnapFamily[];
export declare const TRIGGER_LAYERS: readonly TriggerLayer[];
export declare const OFFER_SCOPE_KINDS: readonly OfferScopeKind[];
export declare const OFFER_STATUSES: readonly OfferStatus[];
export declare const CONFLICT_KINDS: readonly ConflictKind[];
export declare const TRACE_EVENT_TYPES: readonly TraceEventType[];
export declare const AUTHOR_KINDS: readonly AuthorKind[];
export declare const PROVIDER_KINDS: readonly ProviderKind[];
export declare const EDIT_OP_KINDS: readonly EditOpKind[];

export declare class EnsomiError extends Error {
  code: string;
  details?: Record<string, unknown>;
  constructor(code: string, message: string, details?: Record<string, unknown>);
}

export declare function sha256Hex(input: string): string;
export declare function canonicalJson(value: unknown): string;
export declare function roundTime(value: number, decimals?: number): number;

export declare function clampUnit(value: number): number;
export declare function formatTimeCode(timeSec: number): string;
export declare function assertRange(range: Range, durationSec?: number): Range;
export declare function rangeContainsTime(range: Range, timeSec: number): boolean;
export declare function rangesOverlap(a: Range | "song", b: Range | "song"): boolean;
export declare function decomposeRangeToSpans(
  segments: readonly Segment[],
  range: Range,
): WriteScope[];
export declare function getSegmentBoundaryTimes(segments: readonly Segment[]): number[];
export declare function normalizeAudioDescriptor(raw: AudioDescriptorInput): AudioDescriptor;
export declare function normalizeSegment(raw: SegmentInput, defaultRevision?: number): Segment;
export declare function normalizeSegmentPartition(
  rawSegments: readonly SegmentInput[] | undefined,
  durationSec: number,
  defaultSource?: EntitySource,
): Segment[];
export declare function normalizeSpan(raw: SpanInput, defaultRevision?: number): Span;
export declare function normalizeBeat(raw: BeatInput, defaultRevision?: number): Beat;
export declare function normalizeSoundAnchor(
  raw: SoundAnchorInput,
  defaultRevision?: number,
): SoundAnchor;
export declare function normalizeUnbeatCue(
  raw: UnbeatCueInput,
  defaultRevision?: number,
): UnbeatCue;
export declare function normalizeSnapCandidate(
  raw: SnapCandidate,
  querySec?: number,
): SnapCandidate;
export declare function assertTraceEventEnvelope(raw: unknown): TraceEvent;
export declare function assertOfferShape(raw: unknown): void;

export declare function compileConstraints(state: SessionState): CompiledConstraints;

export declare function computeSemanticKey(
  writeScopes: readonly WriteScope[],
  ops?: PatchOp,
  expectedRevisions?: Record<string, string>,
): string;
export declare function getScopeBeatIds(
  state: SessionState,
  writeScopes: readonly WriteScope[],
): string[];
export declare function computeConsequences(
  state: SessionState,
  ops?: PatchOp,
): OfferConsequences;
export declare function formatConsequenceSummary(state: SessionState, ops?: PatchOp): string;
export declare function detectSiblingConflicts(
  offers: readonly PatchOffer[],
): Map<string, number[]>;
export declare function isOfferSuppressed(
  state: SessionState,
  offer: {
    semanticKey?: string;
    writeScopes: readonly WriteScope[];
    ops?: PatchOp;
    expectedRevisions?: Record<string, string>;
  },
): boolean;
export declare function validatePatchOffer(
  state: SessionState,
  offer: PatchOffer,
  precompiledConstraints?: CompiledConstraints,
): OfferValidationResult;

export declare function createInitialState(options?: { durationSec?: number }): SessionState;
export declare function resolveActiveRetractions(events: TraceEvent[]): Set<string>;
export declare function computeUndoRedoTargets(
  state: SessionState | readonly TraceEvent[],
): UndoRedoTargets;
export declare function applyEvent(state: SessionState, event: TraceEvent): SessionState;
export declare function foldTrace(
  events: TraceEvent[],
  options?: { durationSec?: number },
): SessionState;
export declare function serializeTraceNdjson(events: readonly TraceEvent[]): string;
export declare function parseTraceNdjson(
  text: string,
  options?: { discardTruncatedTail?: boolean },
): TraceEvent[];
export declare function serializeState(state: SessionState): SerializedSessionState;
export declare function createCheckpoint(state: SessionState): TraceCheckpoint;
export declare function verifyCheckpoint(
  checkpoint: TraceCheckpoint,
  prefixEvents: TraceEvent[],
  options?: { durationSec?: number },
): CheckpointVerification;

export declare function computeGridFit(
  sounds: Iterable<SoundAnchor> | Map<string, SoundAnchor>,
  beats: Iterable<Beat> | Map<string, Beat>,
  segments?: Iterable<Segment> | Map<string, Segment>,
  options?: GridFitOptions,
): GridFitItem[];

export declare function projectRedLines(
  beats: Iterable<Beat> | Map<string, Beat>,
  segments?: Iterable<Segment> | Map<string, Segment>,
  options?: RedLineOptions,
): RedLineProjection;

export declare function rankInboxSpans(
  state: SessionState,
  options?: InboxSpanOptions,
): Span[];

export declare function assertTimingProvider(provider: TimingProvider): void;

export declare class SessionController {
  constructor(options: SessionControllerOptions);
  getManifest(): ProviderManifest;
  getTriggerPolicy(): TriggerPolicy;
  isClosed(): boolean;
  getState(): SessionState;
  getTrace(): readonly TraceEvent[];
  getLayerState(layer?: TriggerLayer): LayerLifecycleState;
  getGeneration(): number;
  getEffortLedger(): EffortLedger;
  getValidOffers(): PatchOffer[];
  getHeldOffers(): PatchOffer[];
  isHoldingOffers(): boolean;
  setHoldOffers(hold: boolean): { flushed: boolean; offers: PatchOffer[] };
  exportNdjson(): string;
  abortLayer(layer: TriggerLayer, reason?: string): void;
  abortAll(reason?: string, errorCode?: "ABORTED" | "SUPERSEDED"): void;
  close(): void;
  bindAudio(audio: AudioDescriptorInput, signal?: AbortSignal): Promise<PreparedEvidence>;
  openSong(audio: AudioDescriptorInput, signal?: AbortSignal): Promise<AnalyzeOutcome>;
  analyze(signal?: AbortSignal, options?: { basisSeq?: number }): Promise<AnalyzeOutcome>;
  suggestSnaps(
    request: { timeSec: number; windowSec?: number; immediate?: boolean },
    signal?: AbortSignal,
  ): Promise<SuggestSnapsOutcome>;
  loadDraft(
    draft: AnalysisDraft & { replace?: boolean; durationSec?: number },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  applyEdit(
    opOrOps: EditOp | EditOp[],
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
    effort: { listenSec?: number; waitSec?: number; range?: Range },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  markReady(
    notes?: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  recompute(
    request: { scope: Range | "song"; reason: string },
    author?: TraceAuthor,
  ): Promise<RecomputeOutcome>;
  acceptOffer(offerId: string, author?: TraceAuthor): AcceptOfferOutcome;
  rejectOffer(offerId: string, reason?: string, author?: TraceAuthor): RejectOfferOutcome;
}

export declare function generateSyntheticDraft(options?: SyntheticDraftOptions): SyntheticDraft;

export declare class ScriptedMockProvider implements TimingProvider {
  recomputeCalls: Array<{ generation: number; reason: string; aborted: boolean }>;
  suggestSnapsCalls: Array<{
    generation: number;
    timeSec: number;
    windowSec: number;
    aborted: boolean;
  }>;
  analyzeCalls: Array<{ aborted: boolean }>;
  constructor(options?: ScriptedMockProviderOptions);
  manifest(): ProviderManifest;
  prepare(audio: AudioDescriptor, signal?: AbortSignal): Promise<PreparedEvidence>;
  analyze(evidence: PreparedEvidence, signal?: AbortSignal): Promise<AnalysisDraft>;
  suggestSnaps(
    evidence: PreparedEvidence,
    state: SessionState,
    request: SuggestSnapsRequest,
    signal?: AbortSignal,
  ): Promise<SnapCandidate[]>;
  recompute(
    evidence: PreparedEvidence,
    state: SessionState,
    request: RecomputeRequest,
    signal?: AbortSignal,
  ): Promise<RecomputeResult>;
}
