/**
 * ensomi — MIT, LLM-free, clock-agnostic, zero-dependency ESM core contract,
 * three-tier Segment/Range/Span reducer, soft-first cue/offer validation,
 * break-aware derived views, 3-layer SessionController, and ScriptedMockProvider.
 */

export {
  AUTHOR_KINDS,
  CONFLICT_KINDS,
  DEFAULT_TRIGGER_POLICY,
  EDIT_OP_KINDS,
  ENTITY_SOURCES,
  EnsomiError,
  OFFER_SCOPE_KINDS,
  OFFER_STATUSES,
  PROVIDER_KINDS,
  SEGMENT_REGIMES,
  SNAP_FAMILIES,
  SNAP_ROLES,
  SPAN_KINDS,
  SPAN_PRIORITIES,
  TRACE_EVENT_TYPES,
  TRACE_SCHEMA_VERSION,
  TRIGGER_LAYERS,
  assertOfferShape,
  assertRange,
  assertTraceEventEnvelope,
  clampUnit,
  decomposeRangeToSpans,
  formatTimeCode,
  getSegmentBoundaryTimes,
  normalizeAudioDescriptor,
  normalizeBeat,
  normalizeSegment,
  normalizeSegmentPartition,
  normalizeSnapCandidate,
  normalizeSoundAnchor,
  normalizeSpan,
  normalizeUnbeatCue,
  rangeContainsTime,
  rangesOverlap,
} from "./src/types.js";

export { canonicalJson, roundTime, sha256Hex } from "./src/hash.js";

export {
  DEFAULT_BEAT_GUIDE_TOL_SEC,
  DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC,
  MAX_UNBEAT_REPULSION_RADIUS_SEC,
  compileConstraints,
} from "./src/constraints.js";

export {
  computeConsequences,
  computeSemanticKey,
  detectSiblingConflicts,
  formatConsequenceSummary,
  getScopeBeatIds,
  isOfferSuppressed,
  validatePatchOffer,
} from "./src/offers.js";

export {
  applyEvent,
  createCheckpoint,
  createInitialState,
  foldTrace,
  parseTraceNdjson,
  resolveActiveRetractions,
  serializeState,
  serializeTraceNdjson,
  verifyCheckpoint,
} from "./src/trace.js";

export { computeGridFit, projectRedLines, rankInboxSpans } from "./src/views.js";

export { assertTimingProvider, SessionController } from "./src/session.js";

export { generateSyntheticDraft, ScriptedMockProvider } from "./src/mock-provider.js";
