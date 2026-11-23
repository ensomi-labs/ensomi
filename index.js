/**
 * ensomi v0 — LLM-free, MIT, clock-agnostic, zero-dependency core contract,
 * sans-IO reducer, derived views, headless SessionController, and ScriptedMockProvider.
 */

export {
  AUTHOR_KINDS,
  BOUNDARY_KINDS,
  CONFLICT_KINDS,
  CONSTRAINT_FORMS,
  EDIT_OP_KINDS,
  ENTITY_SOURCES,
  EnsomiError,
  OFFER_ROLES,
  OFFER_SCOPE_KINDS,
  OFFER_STATUSES,
  PROVIDER_KINDS,
  STATEMENT_KINDS,
  TRACE_EVENT_TYPES,
  TRACE_SCHEMA_VERSION,
  assertOfferShape,
  assertSpan,
  assertTraceEventEnvelope,
  clampUnit,
  normalizeAudioDescriptor,
  normalizeBeat,
  normalizeBoundary,
  normalizeSoundAnchor,
  normalizeStatement,
  spanContainsTime,
  spansOverlap,
} from "./src/types.js";

export { canonicalJson, roundTime, sha256Hex } from "./src/hash.js";

export { compileConstraints, DEFAULT_POINT_TOL_SEC } from "./src/constraints.js";

export {
  computeConsequences,
  computeSemanticKey,
  detectSiblingConflicts,
  getRangeEntityIds,
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

export { computeGridFit, projectRedLines, rankTroubleSpans } from "./src/views.js";

export { assertTimingProvider, SessionController } from "./src/session.js";

export { generateSyntheticDraft, ScriptedMockProvider } from "./src/mock-provider.js";
