/**
 * Core v0 contract constants, validators, and span geometry helpers.
 * All times are in source-audio seconds (clock-agnostic).
 */

export const TRACE_SCHEMA_VERSION = "0.1";

export const ENTITY_SOURCES = Object.freeze(["model", "user", "import", "rule"]);
export const BOUNDARY_KINDS = Object.freeze([
  "steady_start",
  "free_start",
  "reset",
  "gap_start",
  "gap_end",
]);
export const STATEMENT_KINDS = Object.freeze([
  "steady",
  "free",
  "no_pulse",
  "keep",
  "beat_run",
  "sound_at",
  "beat_at",
  "forbid_beat",
]);
export const CONSTRAINT_FORMS = Object.freeze(["observe", "clamp", "forbid", "freeze"]);
export const OFFER_SCOPE_KINDS = Object.freeze([
  "local",
  "forward_continuation",
  "cross_region",
]);
export const OFFER_ROLES = Object.freeze([
  "extend",
  "rewrite",
  "quick_fix",
  "alternative",
  "propagate",
]);
export const OFFER_STATUSES = Object.freeze([
  "pending",
  "accepted",
  "rejected",
  "stale",
  "superseded",
]);
export const CONFLICT_KINDS = Object.freeze([
  "version_mismatch",
  "range_changed",
  "constraint_violation",
  "sibling_conflict",
]);
export const TRACE_EVENT_TYPES = Object.freeze([
  "audio_bound",
  "draft_loaded",
  "edit",
  "statement",
  "signal",
  "offer_batch",
  "offer_accept",
  "offer_reject",
  "retract",
  "listen_effort",
  "ready",
]);
export const AUTHOR_KINDS = Object.freeze(["user", "model", "rule", "host", "import"]);
export const PROVIDER_KINDS = Object.freeze(["model", "rule", "mock"]);
export const EDIT_OP_KINDS = Object.freeze([
  "upsert_beat",
  "move_beat",
  "remove_beat",
  "shift_run_offset",
  "upsert_boundary",
  "remove_boundary",
  "upsert_sound",
  "remove_sound",
]);

const ENTITY_SOURCE_SET = new Set(ENTITY_SOURCES);
const BOUNDARY_KIND_SET = new Set(BOUNDARY_KINDS);
const STATEMENT_KIND_SET = new Set(STATEMENT_KINDS);
const OFFER_SCOPE_KIND_SET = new Set(OFFER_SCOPE_KINDS);
const OFFER_ROLE_SET = new Set(OFFER_ROLES);
const OFFER_STATUS_SET = new Set(OFFER_STATUSES);
const TRACE_EVENT_TYPE_SET = new Set(TRACE_EVENT_TYPES);
const AUTHOR_KIND_SET = new Set(AUTHOR_KINDS);
const PROVIDER_KIND_SET = new Set(PROVIDER_KINDS);

export class EnsomiError extends Error {
  /**
   * @param {string} code
   * @param {string} message
   * @param {Record<string, unknown>} [details]
   */
  constructor(code, message, details) {
    super(message);
    this.name = "EnsomiError";
    this.code = code;
    if (details !== undefined) {
      this.details = details;
    }
  }
}

/**
 * Clamp a finite emphasis value into [0, 1]. Throws on non-finite input.
 * @param {number} value
 * @returns {number}
 */
export function clampUnit(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new EnsomiError("INVALID_UNIT", `Expected finite number for [0, 1] value, got ${value}`);
  }
  if (value < 0) return 0;
  if (value > 1) return 1;
  return value;
}

/**
 * Validate a half-open span [t0, t1) in source-audio seconds.
 * @param {{ t0: number, t1: number }} span
 * @returns {{ t0: number, t1: number }}
 */
export function assertSpan(span) {
  if (!span || typeof span !== "object") {
    throw new EnsomiError("INVALID_SPAN", "Span must be an object with { t0, t1 }");
  }
  if (typeof span.t0 !== "number" || !Number.isFinite(span.t0)) {
    throw new EnsomiError("INVALID_SPAN", `Span t0 must be a finite number, got ${span.t0}`);
  }
  if (typeof span.t1 !== "number" || !Number.isFinite(span.t1)) {
    throw new EnsomiError("INVALID_SPAN", `Span t1 must be a finite number, got ${span.t1}`);
  }
  if (span.t1 < span.t0) {
    throw new EnsomiError("INVALID_SPAN", `Span t1 (${span.t1}) must be >= t0 (${span.t0})`);
  }
  return { t0: span.t0, t1: span.t1 };
}

/**
 * Check whether source-audio time t falls inside half-open span [t0, t1).
 * For degenerate point spans (t0 === t1), matches t === t0.
 * @param {{ t0: number, t1: number }} span
 * @param {number} t
 * @returns {boolean}
 */
export function spanContainsTime(span, t) {
  if (!span || typeof t !== "number" || !Number.isFinite(t)) return false;
  if (span.t0 === span.t1) {
    return t === span.t0;
  }
  return t >= span.t0 && t < span.t1;
}

/**
 * Check whether two half-open spans [a.t0, a.t1) and [b.t0, b.t1) overlap.
 * Point spans (t0 === t1) overlap a half-open span if contained in it, or another point span if equal.
 * @param {{ t0: number, t1: number } | "song"} a
 * @param {{ t0: number, t1: number } | "song"} b
 * @returns {boolean}
 */
export function spansOverlap(a, b) {
  if (!a || !b) return false;
  if (a === "song" || b === "song") return true;
  const aPoint = a.t0 === a.t1;
  const bPoint = b.t0 === b.t1;
  if (aPoint && bPoint) {
    return a.t0 === b.t0;
  }
  if (aPoint) {
    return a.t0 >= b.t0 && a.t0 < b.t1;
  }
  if (bPoint) {
    return b.t0 >= a.t0 && b.t0 < a.t1;
  }
  return a.t0 < b.t1 && b.t0 < a.t1;
}

/**
 * Shallow-clone an optional metadata record.
 * @param {Record<string, unknown> | undefined} meta
 * @returns {Record<string, unknown> | undefined}
 */
export function cloneMeta(meta) {
  if (meta === undefined) return undefined;
  return structuredClone(meta);
}

/**
 * Validate and normalize a Beat entity.
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultVersion=1]
 */
export function normalizeBeat(raw, defaultVersion = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BEAT", "Beat must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_BEAT", "Beat id must be a non-empty string");
  }
  if (typeof raw.t !== "number" || !Number.isFinite(raw.t)) {
    throw new EnsomiError("INVALID_BEAT", `Beat t must be a finite number, got ${raw.t}`);
  }
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (raw.source))) {
    throw new EnsomiError("INVALID_BEAT", `Invalid beat source: ${raw.source}`);
  }
  const version = raw.version === undefined ? defaultVersion : raw.version;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
    throw new EnsomiError("INVALID_BEAT", `Beat version must be a positive integer, got ${version}`);
  }
  const beat = {
    id: raw.id,
    t: raw.t,
    source: /** @type {"model"|"user"|"import"|"rule"} */ (raw.source),
    version,
  };
  if (raw.conf !== undefined) {
    beat.conf = clampUnit(/** @type {number} */ (raw.conf));
  }
  if (raw.meta !== undefined) {
    beat.meta = cloneMeta(/** @type {Record<string, unknown>} */ (raw.meta));
  }
  return beat;
}

/**
 * Validate and normalize a Boundary entity.
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultVersion=1]
 */
export function normalizeBoundary(raw, defaultVersion = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BOUNDARY", "Boundary must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_BOUNDARY", "Boundary id must be a non-empty string");
  }
  if (typeof raw.t !== "number" || !Number.isFinite(raw.t)) {
    throw new EnsomiError("INVALID_BOUNDARY", `Boundary t must be a finite number, got ${raw.t}`);
  }
  if (!BOUNDARY_KIND_SET.has(/** @type {string} */ (raw.kind))) {
    throw new EnsomiError("INVALID_BOUNDARY", `Invalid boundary kind: ${raw.kind}`);
  }
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (raw.source))) {
    throw new EnsomiError("INVALID_BOUNDARY", `Invalid boundary source: ${raw.source}`);
  }
  const version = raw.version === undefined ? defaultVersion : raw.version;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
    throw new EnsomiError(
      "INVALID_BOUNDARY",
      `Boundary version must be a positive integer, got ${version}`,
    );
  }
  const boundary = {
    id: raw.id,
    t: raw.t,
    kind: /** @type {"steady_start"|"free_start"|"reset"|"gap_start"|"gap_end"} */ (raw.kind),
    source: /** @type {"model"|"user"|"import"|"rule"} */ (raw.source),
    version,
  };
  if (raw.bpm !== undefined) {
    if (typeof raw.bpm !== "number" || !Number.isFinite(raw.bpm) || raw.bpm <= 0) {
      throw new EnsomiError("INVALID_BOUNDARY", `Boundary bpm must be a positive finite number, got ${raw.bpm}`);
    }
    boundary.bpm = raw.bpm;
  }
  if (raw.meta !== undefined) {
    boundary.meta = cloneMeta(/** @type {Record<string, unknown>} */ (raw.meta));
  }
  return boundary;
}

/**
 * Validate and normalize a SoundAnchor entity (stored independently of beats).
 * Emphasis is validated as finite and clamped to [0, 1].
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultVersion=1]
 */
export function normalizeSoundAnchor(raw, defaultVersion = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND", "SoundAnchor must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_SOUND", "SoundAnchor id must be a non-empty string");
  }
  if (typeof raw.t !== "number" || !Number.isFinite(raw.t)) {
    throw new EnsomiError("INVALID_SOUND", `SoundAnchor t must be a finite number, got ${raw.t}`);
  }
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (raw.source))) {
    throw new EnsomiError("INVALID_SOUND", `Invalid sound source: ${raw.source}`);
  }
  const version = raw.version === undefined ? defaultVersion : raw.version;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
    throw new EnsomiError(
      "INVALID_SOUND",
      `SoundAnchor version must be a positive integer, got ${version}`,
    );
  }
  const sound = {
    id: raw.id,
    t: raw.t,
    emphasis: clampUnit(/** @type {number} */ (raw.emphasis)),
    source: /** @type {"model"|"user"|"import"|"rule"} */ (raw.source),
    version,
  };
  if (raw.conf !== undefined) {
    sound.conf = clampUnit(/** @type {number} */ (raw.conf));
  }
  if (raw.experimental !== undefined) {
    sound.experimental = Boolean(raw.experimental);
  }
  if (raw.meta !== undefined) {
    sound.meta = cloneMeta(/** @type {Record<string, unknown>} */ (raw.meta));
  }
  return sound;
}

/**
 * Validate and normalize an AudioDescriptor.
 * @param {Record<string, unknown>} raw
 */
export function normalizeAudioDescriptor(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor must be an object");
  }
  if (typeof raw.audioId !== "string" || raw.audioId.length === 0) {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor audioId must be a non-empty string");
  }
  if (typeof raw.durationSec !== "number" || !Number.isFinite(raw.durationSec) || raw.durationSec < 0) {
    throw new EnsomiError(
      "INVALID_AUDIO",
      `AudioDescriptor durationSec must be a non-negative finite number, got ${raw.durationSec}`,
    );
  }
  if (typeof raw.clock !== "string" || raw.clock.length === 0) {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor clock must be a non-empty string");
  }
  const audio = {
    audioId: raw.audioId,
    durationSec: raw.durationSec,
    clock: raw.clock,
  };
  if (raw.sampleRate !== undefined) {
    if (typeof raw.sampleRate !== "number" || !Number.isFinite(raw.sampleRate) || raw.sampleRate <= 0) {
      throw new EnsomiError("INVALID_AUDIO", `Invalid sampleRate: ${raw.sampleRate}`);
    }
    audio.sampleRate = raw.sampleRate;
  }
  if (raw.decoder !== undefined) {
    audio.decoder = String(raw.decoder);
  }
  if (raw.leadOffsetSec !== undefined) {
    if (typeof raw.leadOffsetSec !== "number" || !Number.isFinite(raw.leadOffsetSec)) {
      throw new EnsomiError("INVALID_AUDIO", `Invalid leadOffsetSec: ${raw.leadOffsetSec}`);
    }
    audio.leadOffsetSec = raw.leadOffsetSec;
  }
  return audio;
}

/**
 * Validate and normalize a Statement.
 * @param {Record<string, unknown>} raw
 * @param {number} defaultSeq
 * @param {number} [defaultVersion=1]
 */
export function normalizeStatement(raw, defaultSeq, defaultVersion = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_STATEMENT", "Statement must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_STATEMENT", "Statement id must be a non-empty string");
  }
  if (!STATEMENT_KIND_SET.has(/** @type {string} */ (raw.kind))) {
    throw new EnsomiError("INVALID_STATEMENT", `Invalid statement kind: ${raw.kind}`);
  }
  const version = raw.version === undefined ? defaultVersion : raw.version;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
    throw new EnsomiError(
      "INVALID_STATEMENT",
      `Statement version must be a positive integer, got ${version}`,
    );
  }
  const createdAtSeq = raw.createdAtSeq === undefined ? defaultSeq : raw.createdAtSeq;
  if (typeof createdAtSeq !== "number" || !Number.isInteger(createdAtSeq) || createdAtSeq < 0) {
    throw new EnsomiError("INVALID_STATEMENT", `Invalid createdAtSeq: ${createdAtSeq}`);
  }
  const statement = {
    id: raw.id,
    kind: /** @type {any} */ (raw.kind),
    active: raw.active === undefined ? true : Boolean(raw.active),
    version,
    createdAtSeq,
  };
  if (raw.span !== undefined) {
    statement.span = assertSpan(/** @type {{ t0: number, t1: number }} */ (raw.span));
  }
  if (raw.t !== undefined) {
    if (typeof raw.t !== "number" || !Number.isFinite(raw.t)) {
      throw new EnsomiError("INVALID_STATEMENT", `Statement t must be a finite number, got ${raw.t}`);
    }
    statement.t = raw.t;
  }
  if (raw.value !== undefined) {
    if (typeof raw.value !== "object" || raw.value === null || Array.isArray(raw.value)) {
      throw new EnsomiError("INVALID_STATEMENT", "Statement value must be a plain object");
    }
    statement.value = structuredClone(raw.value);
  }
  return statement;
}

/**
 * Validate a trace event envelope.
 * @param {unknown} raw
 */
export function assertTraceEventEnvelope(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new EnsomiError("INVALID_EVENT", "Trace event must be an object");
  }
  const ev = /** @type {Record<string, unknown>} */ (raw);
  if (ev.schemaVersion !== TRACE_SCHEMA_VERSION) {
    throw new EnsomiError(
      "INVALID_EVENT",
      `Unsupported schemaVersion: ${ev.schemaVersion} (expected "${TRACE_SCHEMA_VERSION}")`,
    );
  }
  if (typeof ev.seq !== "number" || !Number.isInteger(ev.seq) || ev.seq < 0) {
    throw new EnsomiError("INVALID_EVENT", `Event seq must be a non-negative integer, got ${ev.seq}`);
  }
  if (typeof ev.id !== "string" || ev.id.length === 0) {
    throw new EnsomiError("INVALID_EVENT", "Event id must be a non-empty string");
  }
  if (!TRACE_EVENT_TYPE_SET.has(/** @type {string} */ (ev.type))) {
    throw new EnsomiError("INVALID_EVENT", `Invalid trace event type: ${ev.type}`);
  }
  if (ev.tsMs !== undefined && (typeof ev.tsMs !== "number" || !Number.isFinite(ev.tsMs))) {
    throw new EnsomiError("INVALID_EVENT", `Event tsMs must be a finite number, got ${ev.tsMs}`);
  }
  if (!ev.author || typeof ev.author !== "object" || Array.isArray(ev.author)) {
    throw new EnsomiError("INVALID_EVENT", "Event author must be an object");
  }
  const author = /** @type {Record<string, unknown>} */ (ev.author);
  if (!AUTHOR_KIND_SET.has(/** @type {string} */ (author.kind))) {
    throw new EnsomiError("INVALID_EVENT", `Invalid event author kind: ${author.kind}`);
  }
  if (author.id !== undefined && typeof author.id !== "string") {
    throw new EnsomiError("INVALID_EVENT", "Event author.id must be a string when provided");
  }
  if (!("payload" in ev)) {
    throw new EnsomiError("INVALID_EVENT", "Event payload property is required");
  }
  return ev;
}

/**
 * Validate PatchOffer shape (basic structural validation).
 * @param {Record<string, unknown>} raw
 */
export function assertOfferShape(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer id must be a non-empty string");
  }
  if (typeof raw.batchId !== "string" || raw.batchId.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer batchId must be a non-empty string");
  }
  if (typeof raw.providerId !== "string" || raw.providerId.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer providerId must be a non-empty string");
  }
  if (!PROVIDER_KIND_SET.has(/** @type {string} */ (raw.providerKind))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid providerKind: ${raw.providerKind}`);
  }
  if (!OFFER_ROLE_SET.has(/** @type {string} */ (raw.role))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer role: ${raw.role}`);
  }
  if (!OFFER_SCOPE_KIND_SET.has(/** @type {string} */ (raw.scopeKind))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer scopeKind: ${raw.scopeKind}`);
  }
  if (raw.readScope !== "song") {
    assertSpan(/** @type {{ t0: number, t1: number }} */ (raw.readScope));
  }
  assertSpan(/** @type {{ t0: number, t1: number }} */ (raw.writeScope));
  if (raw.status !== undefined && !OFFER_STATUS_SET.has(/** @type {string} */ (raw.status))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer status: ${raw.status}`);
  }
  if (!raw.ops || typeof raw.ops !== "object") {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer ops must be an object");
  }
}
