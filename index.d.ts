// Type definitions for ensomi v0 contract
// Zero-dependency ESM package (Node 22/24+ and browser-compatible sans-IO core)

export type EntitySource = "model" | "user" | "import" | "rule";

export type BoundaryKind =
  | "steady_start"
  | "free_start"
  | "reset"
  | "gap_start"
  | "gap_end";

export type StatementKind =
  | "steady"
  | "free"
  | "no_pulse"
  | "keep"
  | "beat_run"
  | "sound_at"
  | "beat_at"
  | "forbid_beat";

export type ConstraintForm = "observe" | "clamp" | "forbid" | "freeze";

export type OfferScopeKind = "local" | "forward_continuation" | "cross_region";

export type OfferRole =
  | "extend"
  | "rewrite"
  | "quick_fix"
  | "alternative"
  | "propagate";

export type OfferStatus =
  | "pending"
  | "accepted"
  | "rejected"
  | "stale"
  | "superseded";

export type ConflictKind =
  | "version_mismatch"
  | "range_changed"
  | "constraint_violation"
  | "sibling_conflict";

export type TraceEventType =
  | "audio_bound"
  | "draft_loaded"
  | "edit"
  | "statement"
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
  | "remove_beat"
  | "shift_run_offset"
  | "upsert_boundary"
  | "remove_boundary"
  | "upsert_sound"
  | "remove_sound";

export interface Beat {
  id: string;
  t: number;
  source: EntitySource;
  version: number;
  conf?: number;
  meta?: Record<string, unknown>;
}

export interface Boundary {
  id: string;
  t: number;
  kind: BoundaryKind;
  source: EntitySource;
  version: number;
  bpm?: number;
  meta?: Record<string, unknown>;
}

export interface SoundAnchor {
  id: string;
  t: number;
  emphasis: number;
  source: EntitySource;
  version: number;
  conf?: number;
  experimental?: boolean;
  meta?: Record<string, unknown>;
}

/** Half-open interval [t0, t1) in source-audio seconds. */
export interface Span {
  t0: number;
  t1: number;
}

export interface AudioDescriptor {
  audioId: string;
  durationSec: number;
  sampleRate?: number;
  clock: string;
  decoder?: string;
  leadOffsetSec?: number;
}

export type BeatInput = Omit<Beat, "version"> & { version?: number };
export type BoundaryInput = Omit<Boundary, "version"> & { version?: number };
export type SoundAnchorInput = Omit<SoundAnchor, "version"> & { version?: number };

export type EditOp =
  | { op: "upsert_beat"; beat: BeatInput }
  | {
      op: "move_beat";
      id: string;
      t: number;
      source?: EntitySource;
      conf?: number;
      meta?: Record<string, unknown>;
    }
  | { op: "remove_beat"; id: string }
  | {
      op: "shift_run_offset";
      span?: Span;
      beatIds?: string[];
      deltaSec: number;
      source?: EntitySource;
      includeBoundaries?: boolean;
    }
  | { op: "upsert_boundary"; boundary: BoundaryInput }
  | { op: "remove_boundary"; id: string }
  | { op: "upsert_sound"; sound: SoundAnchorInput }
  | { op: "remove_sound"; id: string };

export interface Statement {
  id: string;
  kind: StatementKind;
  span?: Span;
  t?: number;
  value?: Record<string, unknown>;
  active: boolean;
  version: number;
  createdAtSeq: number;
}

export type StatementInput = Omit<Statement, "active" | "version" | "createdAtSeq"> & {
  active?: boolean;
  version?: number;
  createdAtSeq?: number;
};

export interface Constraint {
  id: string;
  statementId: string;
  statementKind: StatementKind;
  form: ConstraintForm;
  target: "sound" | "tempo_regime" | "beat" | "beat_run" | "pulse" | "span";
  span?: Span;
  t?: number;
  params: Record<string, unknown>;
}

export interface SignalPayload {
  scope: Span | "song";
  reason: string;
  generation: number;
  statementIds?: string[];
}

export interface PatchOp {
  upsertBeats?: BeatInput[];
  removeBeatIds?: string[];
  upsertBoundaries?: BoundaryInput[];
  removeBoundaryIds?: string[];
  upsertSounds?: SoundAnchorInput[];
  removeSoundIds?: string[];
  expectedVersions?: Record<string, number>;
  expectedRangeEntityIds?: string[];
}

export interface Conflict {
  id: string;
  kind: ConflictKind;
  message: string;
  entityIds?: string[];
  statementIds?: string[];
  span?: Span;
}

export interface OfferConsequences {
  beatsAdded: number;
  beatsMoved: number;
  beatsRemoved: number;
  soundsReclassified?: number;
}

export interface PatchOffer {
  id: string;
  batchId: string;
  optionIndex?: number;
  providerId: string;
  providerKind: ProviderKind;
  generation: number;
  basisSeq: number;
  role: OfferRole;
  scopeKind: OfferScopeKind;
  readScope: Span | "song";
  writeScope: Span;
  ops: PatchOp;
  summary: string;
  consequences: OfferConsequences;
  conflicts: Conflict[];
  semanticKey: string;
  status: OfferStatus;
}

export interface TraceAuthor {
  kind: AuthorKind;
  id?: string;
}

export interface TraceEvent<TPayload = unknown> {
  schemaVersion: "0.1";
  seq: number;
  id: string;
  type: TraceEventType;
  tsMs?: number;
  author: TraceAuthor;
  payload: TPayload;
}

export interface RejectionSuppression {
  semanticKey: string;
  writeScope: Span;
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

export interface SessionState {
  audio: AudioDescriptor | null;
  beats: Map<string, Beat>;
  boundaries: Map<string, Boundary>;
  sounds: Map<string, SoundAnchor>;
  statements: Map<string, Statement>;
  offers: Map<string, PatchOffer>;
  suppressedOffers: PatchOffer[];
  suppressions: RejectionSuppression[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedSpans: Span[];
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
  beats: Beat[];
  boundaries: Boundary[];
  sounds: SoundAnchor[];
  statements: Statement[];
  offers: PatchOffer[];
  suppressions: RejectionSuppression[];
  lastSignal: SignalPayload | null;
  generation: number;
  effort: EffortLedger;
  visitedSpans: Span[];
  ready: boolean;
  readyAtSeq: number | null;
  retractedEventIds: string[];
  lastSeq: number;
}

export interface TraceCheckpoint {
  schemaVersion: "0.1";
  lastSeq: number;
  eventCount: number;
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
  t: number;
  nearestBeatDistMs: number;
  nearestDivisor: number | null;
  residualMs: number;
  status: GridFitStatus;
}

export interface GridFitOptions {
  divisors?: number[];
  tolMs?: number;
}

export interface RedLine {
  offsetMs: number;
  beatLengthMs: number;
  bpm: number;
  meter: number;
  startBeatId: string;
  beatCount: number;
  kind: "steady_run" | "free_fallback";
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
}

export interface TroubleSpanBreakdown {
  lowConf: number;
  intervalJumps: number;
  unexplainedSounds: number;
  activeConflicts: number;
  unvisited: number;
}

export interface TroubleSpan {
  span: Span;
  score: number;
  visited: boolean;
  breakdown: TroubleSpanBreakdown;
  reasons: string[];
}

export interface TroubleSpanOptions {
  windowSec?: number;
  lowConfThreshold?: number;
  jumpRatioThreshold?: number;
  gridFitOptions?: GridFitOptions;
}

export interface ProviderCapabilities {
  draft: boolean;
  sounds: boolean;
  soundsExperimental?: boolean;
  constrainedRecompute: boolean;
  honoredStatements: StatementKind[];
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
  beats?: BeatInput[];
  boundaries?: BoundaryInput[];
  sounds?: SoundAnchorInput[];
  statements?: StatementInput[];
}

export interface RecomputeRequest {
  scope: Span | "song";
  reason: string;
  generation: number;
  basisSeq: number;
  constraints: Constraint[];
  statementIds?: string[];
}

export interface RecomputeResult {
  batchId?: string;
  offers: PatchOffer[];
}

export interface TimingProvider {
  manifest(): ProviderManifest;
  prepare(audio: AudioDescriptor, signal?: AbortSignal): Promise<PreparedEvidence>;
  analyze(evidence: PreparedEvidence, signal?: AbortSignal): Promise<AnalysisDraft>;
  recompute(
    evidence: PreparedEvidence,
    state: SessionState,
    request: RecomputeRequest,
    signal?: AbortSignal,
  ): Promise<RecomputeResult>;
}

export interface SessionControllerOptions {
  provider: TimingProvider;
  clock?: () => number;
  idGenerator?: (prefix: string, seq: number) => string;
  initialEvents?: TraceEvent[];
}

export interface TriggerRecomputeOutcome {
  status: "applied" | "buffered" | "cancelled" | "superseded";
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
      conflicts: Conflict[];
    };

export interface RejectOfferOutcome {
  ok: true;
  offer: PatchOffer | undefined;
}

export interface SyntheticDraftOptions {
  durationSec?: number;
  bpm?: number;
  jitterMs?: number;
  soundCount?: number;
  seed?: number;
  startSec?: number;
}

export interface ScriptedMockProviderOptions {
  id?: string;
  version?: string;
  draft?: AnalysisDraft;
  defaultScenario?:
    | "triplet_vs_reset"
    | "local_rewrite"
    | "forward_continuation"
    | "cross_region"
    | "hard_constraint_conflict"
    | "slow_cancellable";
  delayMs?: number;
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
}

export declare const TRACE_SCHEMA_VERSION: "0.1";
export declare const DEFAULT_POINT_TOL_SEC: number;

export declare const ENTITY_SOURCES: readonly EntitySource[];
export declare const BOUNDARY_KINDS: readonly BoundaryKind[];
export declare const STATEMENT_KINDS: readonly StatementKind[];
export declare const CONSTRAINT_FORMS: readonly ConstraintForm[];
export declare const OFFER_SCOPE_KINDS: readonly OfferScopeKind[];
export declare const OFFER_ROLES: readonly OfferRole[];
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
export declare function assertSpan(span: Span): Span;
export declare function spanContainsTime(span: Span, t: number): boolean;
export declare function spansOverlap(a: Span | "song", b: Span | "song"): boolean;
export declare function normalizeBeat(raw: BeatInput, defaultVersion?: number): Beat;
export declare function normalizeBoundary(raw: BoundaryInput, defaultVersion?: number): Boundary;
export declare function normalizeSoundAnchor(
  raw: SoundAnchorInput,
  defaultVersion?: number,
): SoundAnchor;
export declare function normalizeAudioDescriptor(raw: AudioDescriptor): AudioDescriptor;
export declare function normalizeStatement(
  raw: StatementInput,
  defaultSeq: number,
  defaultVersion?: number,
): Statement;
export declare function assertTraceEventEnvelope(raw: unknown): TraceEvent;
export declare function assertOfferShape(raw: unknown): void;

export declare function compileConstraints(
  statements: Iterable<Statement> | Map<string, Statement> | Record<string, Statement>,
): Constraint[];

export declare function computeSemanticKey(writeScope: Span, ops?: PatchOp): string;
export declare function getRangeEntityIds(
  state: SessionState,
  span: Span,
  options?: { includeSounds?: boolean },
): string[];
export declare function computeConsequences(state: SessionState, ops?: PatchOp): OfferConsequences;
export declare function detectSiblingConflicts(offers: PatchOffer[]): Map<string, Conflict[]>;
export declare function isOfferSuppressed(
  state: SessionState,
  offer: { semanticKey?: string; writeScope: Span; ops?: PatchOp },
): boolean;
export declare function validatePatchOffer(state: SessionState, offer: PatchOffer): Conflict[];

export declare function createInitialState(): SessionState;
export declare function resolveActiveRetractions(events: TraceEvent[]): Set<string>;
export declare function applyEvent(state: SessionState, event: TraceEvent): SessionState;
export declare function foldTrace(events: TraceEvent[]): SessionState;
export declare function serializeTraceNdjson(events: TraceEvent[]): string;
export declare function parseTraceNdjson(
  text: string,
  options?: { discardTruncatedTail?: boolean },
): TraceEvent[];
export declare function serializeState(state: SessionState): SerializedSessionState;
export declare function createCheckpoint(state: SessionState): TraceCheckpoint;
export declare function verifyCheckpoint(
  checkpoint: TraceCheckpoint,
  prefixEvents: TraceEvent[],
): CheckpointVerification;

export declare function computeGridFit(
  sounds: Iterable<SoundAnchor> | Map<string, SoundAnchor>,
  beats: Iterable<Beat> | Map<string, Beat>,
  boundaries?: Iterable<Boundary> | Map<string, Boundary>,
  options?: GridFitOptions,
): GridFitItem[];

export declare function projectRedLines(
  beats: Iterable<Beat> | Map<string, Beat>,
  boundaries?: Iterable<Boundary> | Map<string, Boundary>,
  options?: RedLineOptions,
): RedLineProjection;

export declare function rankTroubleSpans(
  state: SessionState,
  options?: TroubleSpanOptions,
): TroubleSpan[];

export declare function assertTimingProvider(provider: TimingProvider): void;

export declare class SessionController {
  constructor(options: SessionControllerOptions);
  getManifest(): ProviderManifest;
  getState(): SessionState;
  getTrace(): readonly TraceEvent[];
  getGeneration(): number;
  getEffortLedger(): EffortLedger;
  getPendingOffers(): PatchOffer[];
  getBufferedOffers(): PatchOffer[];
  isAuditionLocked(): boolean;
  setAuditionLock(locked: boolean): { flushed: boolean; offers: PatchOffer[] };
  exportNdjson(): string;
  bindAudio(audio: AudioDescriptor, signal?: AbortSignal): Promise<PreparedEvidence>;
  loadInitialDraft(
    signal?: AbortSignal,
  ): Promise<{ event: TraceEvent; state: SessionState }>;
  loadDraft(
    draft: AnalysisDraft & { replace?: boolean },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  applyEdit(
    opOrOps: EditOp | EditOp[],
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  upsertStatement(
    statement: StatementInput,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  deactivateStatement(
    id: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  retract(
    targetEventIdOrSeq: string | number,
    reason?: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  recordListenEffort(
    effort: { listenSec?: number; waitSec?: number; span?: Span },
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  markReady(
    notes?: string,
    author?: TraceAuthor,
  ): { event: TraceEvent; state: SessionState };
  cancelRecompute(): void;
  triggerRecompute(
    request: { scope: Span | "song"; reason: string; statementIds?: string[] },
    author?: TraceAuthor,
  ): Promise<TriggerRecomputeOutcome>;
  acceptOffer(offerId: string, author?: TraceAuthor): AcceptOfferOutcome;
  rejectOffer(offerId: string, reason?: string, author?: TraceAuthor): RejectOfferOutcome;
}

export declare function generateSyntheticDraft(
  options?: SyntheticDraftOptions,
): { beats: Beat[]; boundaries: Boundary[]; sounds: SoundAnchor[] };

export declare class ScriptedMockProvider implements TimingProvider {
  recomputeCalls: Array<{ generation: number; reason: string; aborted: boolean }>;
  constructor(options?: ScriptedMockProviderOptions);
  manifest(): ProviderManifest;
  prepare(audio: AudioDescriptor, signal?: AbortSignal): Promise<PreparedEvidence>;
  analyze(evidence: PreparedEvidence, signal?: AbortSignal): Promise<AnalysisDraft>;
  recompute(
    evidence: PreparedEvidence,
    state: SessionState,
    request: RecomputeRequest,
    signal?: AbortSignal,
  ): Promise<RecomputeResult>;
}
