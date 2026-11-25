/**
 * ensomi@0.1.0 — Zero-dependency ESM public contract:
 *  - Integer-microsecond wire timebase (`atUs` / `[startUs, endUs)`) + boundary conversions
 *  - Strict RFC 8785 (JCS) canonical JSON & SHA-256 hashing
 *  - Global cross-entity ID registry & orthogonal epistemic model (`Origin` + `Verdict` + `Approval`)
 *  - Unified pure transaction interpreter (`executeTransaction`), `Offer` engine, and event-atomic `retract`
 *  - Format-neutral derived views (`computeGridFit`, `projectTempoMap`, `rankInboxItems`)
 *  - JSON-safe 3-layer `SessionController`
 */

export {
  canonicalJson,
  compareCodeUnits,
  EnsomiError,
  jcsSha256Hex,
  sha256Hex,
} from "./src/jcs.js";

export {
  assertInteriorPointUs,
  assertIntervalUs,
  assertNonNegSafeIntUs,
  assertPointUs,
  assertRangeUs,
  assertSafeIntUs,
  formatTimeUs,
  isNonNegSafeInt,
  mergeRangesUs,
  msToUs,
  rangeContainsUs,
  rangesOverlapUs,
  sampleToUs,
  secToUs,
  subtractRangeUs,
  US_PER_MS,
  US_PER_SEC,
  usToMs,
  usToNearestSample,
  usToSec,
} from "./src/time.js";

export {
  AUTHOR_KINDS,
  assertNonEmptyId,
  assertOfferShape,
  assertPositiveRevision,
  assertTraceEventEnvelope,
  authorKindToOrigin,
  BEAT_ANCHOR_KINDS,
  CHALLENGE_DISPOSITIONS,
  CONFLICT_KINDS,
  clampUnit,
  computeEntityValueDigest,
  DEFAULT_BEAT_TOL_US,
  DEFAULT_BOUNDARY_TOL_US,
  DEFAULT_SNAP_BEAT_TOL_US,
  DEFAULT_SOUND_TOL_US,
  DEFAULT_TRIGGER_POLICY,
  DEFAULT_UNBEAT_TOL_US,
  decomposeRangeToSegments,
  deepFreeze,
  ENTITY_KINDS,
  getInteriorBoundaryTimesUs,
  INBOX_PRIORITIES,
  isOfferOpKind,
  isTransactionOpKind,
  normalizeApproval,
  normalizeAudioBinding,
  normalizeBeat,
  normalizeBeatBinding,
  normalizeBeatStructural,
  normalizeBoundary,
  normalizeSegment,
  normalizeSnapCandidate,
  normalizeSound,
  normalizeSoundCharacteristics,
  normalizeSoundClass,
  normalizeSoundOptionCandidate,
  normalizeSpan,
  normalizeStructuredNote,
  normalizeUnbeat,
  normalizeVerdict,
  OFFER_KINDS,
  OFFER_OP_KINDS,
  OFFER_STATUSES,
  ORIGINS,
  PROVIDER_KINDS,
  PULSE_LAYERS,
  SEGMENT_KINEMATICS,
  SEGMENT_REGIMES,
  SNAP_ROLES,
  SOUND_BLEND_STATUSES,
  SOUND_END_KINDS,
  SOUND_MATCH_MODES,
  SOUND_REGISTERS,
  SPAN_KINDS,
  TRACE_EVENT_TYPES,
  TRACE_SCHEMA_VERSION,
  TRANSACTION_OP_KINDS,
  TRIGGER_LAYERS,
  VERDICT_KINDS,
  VERDICT_TARGET_KINDS,
  VERDICT_VIAS,
  validateSoundFacet,
  WELL_KNOWN_SOUND_FAMILIES,
} from "./src/types.js";

export {
  compileConstraints,
  DEFAULT_UNBEAT_FALLBACK_RADIUS_US,
  MAX_UNBEAT_REPULSION_RADIUS_US,
} from "./src/constraints.js";

export {
  collectApprovedKeepIntervals,
  computeGridFit,
  computeIntervalMusicalDigest,
  projectTempoMap,
  rankInboxItems,
} from "./src/views.js";

export {
  deriveChildEntityId,
  executeTransaction,
  getVerdictEntity,
} from "./src/interpreter.js";

export {
  bindOfferToBasis,
  computeOfferBasisDigest,
  computeOfferDependencies,
  computeOfferDiffAndConsequences,
  computeOfferSemanticKey,
  detectSiblingConflicts,
  formatConsequenceSummary,
  isOfferSuppressed,
  previewOffer,
  validateOffer,
} from "./src/offers.js";

export {
  applyEvent,
  computeSnapshotDigest,
  computeUndoRedoTargets,
  createCheckpoint,
  createInitialState,
  createProviderView,
  foldTrace,
  hasActiveHumanTruth,
  parseTraceNdjson,
  resolveActiveRetractions,
  serializeState,
  serializeTraceNdjson,
  toSnapshot,
  verifyCheckpoint,
} from "./src/trace.js";

export {
  assertProvider,
  SessionController,
} from "./src/session.js";
