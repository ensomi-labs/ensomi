/**
 * Core `ensomi@0.1.0` contract constants, enums, epistemic value-digest builders,
 * entity normalizers, partition utilities, and envelope validators.
 */

import { canonicalJson, compareCodeUnits, EnsomiError, jcsSha256Hex, sha256Hex } from "./jcs.js";
import {
  assertInteriorPointUs,
  assertIntervalUs,
  assertNonNegSafeIntUs,
  assertPointUs,
  assertRangeUs,
  assertSafeIntUs,
} from "./time.js";

export const TRACE_SCHEMA_VERSION = "0.3";

export const DEFAULT_BEAT_TOL_US = 20_000;
export const DEFAULT_SNAP_BEAT_TOL_US = 2_000;
export const DEFAULT_UNBEAT_TOL_US = 35_000;
export const DEFAULT_SOUND_TOL_US = 15_000;
export const DEFAULT_BOUNDARY_TOL_US = 50_000;

export const ORIGINS = Object.freeze(["import", "model", "rule", "user"]);
export const VERDICT_KINDS = Object.freeze(["placed", "adjusted", "confirmed"]);
export const VERDICT_VIAS = Object.freeze([
  "tap",
  "snap",
  "nudge",
  "review",
  "challenge_accept",
]);
export const ENTITY_KINDS = Object.freeze([
  "beat",
  "unbeat",
  "sound",
  "sound_class",
  "boundary",
  "segment",
  "span",
  "approval",
]);
export const VERDICT_TARGET_KINDS = Object.freeze([
  "beat",
  "unbeat",
  "sound",
  "sound_class",
  "boundary",
]);

export const SEGMENT_REGIMES = Object.freeze(["auto", "free", "break"]);
export const SEGMENT_KINEMATICS = Object.freeze(["steady", "ramp", "rubato", "unknown"]);
export const SPAN_KINDS = Object.freeze(["free", "keep"]);
export const INBOX_PRIORITIES = Object.freeze(["high", "medium", "low"]);

export const SNAP_ROLES = Object.freeze([
  "transient_attack",
  "p_center",
  "harmonic_change",
  "release",
]);

export const WELL_KNOWN_SOUND_FAMILIES = Object.freeze([
  "percussion",
  "kick",
  "snare",
  "hihat_cymbal",
  "piano_keys",
  "plucked_guitar",
  "bass_synth",
  "strings_winds",
  "vocal",
  "fx_riser",
  "other",
]);

export const SOUND_REGISTERS = Object.freeze(["sub", "low", "mid", "high"]);
export const SOUND_END_KINDS = Object.freeze(["observed", "estimated", "right_censored"]);
export const SOUND_BLEND_STATUSES = Object.freeze(["isolated", "blended"]);
export const SOUND_MATCH_MODES = Object.freeze(["hit", "voice", "figure"]);
export const PULSE_LAYERS = Object.freeze(["tactus", "subdivision", "any"]);
export const BEAT_ANCHOR_KINDS = Object.freeze(["anchored", "interpolated"]);

export const TRIGGER_LAYERS = Object.freeze([
  "song_open",
  "micro_assist",
  "heavy_recompute",
]);

export const DEFAULT_TRIGGER_POLICY = Object.freeze({
  song_open: Object.freeze({
    mode: "auto",
    debounceMs: 0,
    cancellation: "latest_wins",
  }),
  micro_assist: Object.freeze({
    mode: "auto",
    debounceMs: 30,
    cancellation: "latest_wins",
  }),
  heavy_recompute: Object.freeze({
    mode: "manual",
    debounceMs: 0,
    cancellation: "latest_wins",
  }),
});

export const OFFER_KINDS = Object.freeze([
  "pulse",
  "segmentation",
  "sound_match",
  "propagation",
]);

export const OFFER_STATUSES = Object.freeze([
  "valid",
  "requires_challenge_confirm",
  "stale",
  "invalid",
  "accepted",
  "rejected",
  "superseded",
]);

export const CONFLICT_KINDS = Object.freeze([
  "protected_keep",
  "protected_verified",
  "authority_violation",
  "version_mismatch",
  "range_changed",
  "policy_changed",
  "source_changed",
  "out_of_scope",
  "invalid_op",
  "sibling_conflict",
]);

export const CHALLENGE_DISPOSITIONS = Object.freeze([
  "confirm_replacement",
  "clear_verdict",
]);

export const TRACE_EVENT_TYPES = Object.freeze([
  "audio_bound",
  "reset_document",
  "draft_loaded",
  "edit",
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

export const TRANSACTION_OP_KINDS = Object.freeze([
  "upsert_beat",
  "move_beat",
  "snap_beat",
  "shift_beats",
  "remove_beats",
  "place_unbeat",
  "remove_unbeats",
  "beat_to_unbeat",
  "upsert_sound",
  "update_sound",
  "remove_sounds",
  "upsert_sound_class",
  "remove_sound_classes",
  "confirm_marks",
  "clear_verdicts",
  "split_segment",
  "merge_segments",
  "move_boundary",
  "promote_range_to_segments",
  "set_segment_regime",
  "set_segment_kinematics",
  "set_segment_keep",
  "set_range_regime",
  "set_range_keep",
]);

const FORBIDDEN_OFFER_OP_SET = new Set([
  "confirm_marks",
  "clear_verdicts",
  "set_segment_keep",
  "set_range_keep",
  "place_unbeat",
  "beat_to_unbeat",
]);

export const OFFER_OP_KINDS = Object.freeze(
  TRANSACTION_OP_KINDS.filter((k) => !FORBIDDEN_OFFER_OP_SET.has(k)),
);

const ORIGIN_SET = new Set(ORIGINS);
const VERDICT_KIND_SET = new Set(VERDICT_KINDS);
const VERDICT_VIA_SET = new Set(VERDICT_VIAS);
const VERDICT_TARGET_KIND_SET = new Set(VERDICT_TARGET_KINDS);
const SEGMENT_REGIME_SET = new Set(SEGMENT_REGIMES);
const SEGMENT_KINEMATICS_SET = new Set(SEGMENT_KINEMATICS);
const SPAN_KIND_SET = new Set(SPAN_KINDS);
const SNAP_ROLE_SET = new Set(SNAP_ROLES);
const SOUND_REGISTER_SET = new Set(SOUND_REGISTERS);
const SOUND_END_KIND_SET = new Set(SOUND_END_KINDS);
const SOUND_BLEND_SET = new Set(SOUND_BLEND_STATUSES);
const SOUND_MATCH_MODE_SET = new Set(SOUND_MATCH_MODES);
const PULSE_LAYER_SET = new Set(PULSE_LAYERS);
const BEAT_ANCHOR_KIND_SET = new Set(BEAT_ANCHOR_KINDS);
const OFFER_KIND_SET = new Set(OFFER_KINDS);
const OFFER_STATUS_SET = new Set(OFFER_STATUSES);
const CHALLENGE_DISPOSITION_SET = new Set(CHALLENGE_DISPOSITIONS);
const TRACE_EVENT_TYPE_SET = new Set(TRACE_EVENT_TYPES);
const AUTHOR_KIND_SET = new Set(AUTHOR_KINDS);
const TRANSACTION_OP_KIND_SET = new Set(TRANSACTION_OP_KINDS);
const OFFER_OP_KIND_SET = new Set(OFFER_OP_KINDS);

/**
 * Recursively freeze a plain JSON-serializable object/array in place.
 *
 * @template T
 * @param {T} value
 * @returns {T}
 */
export function deepFreeze(value) {
  if (value === null || typeof value !== "object" || Object.isFrozen(value)) {
    return value;
  }
  Object.freeze(value);
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i += 1) {
      deepFreeze(value[i]);
    }
  } else {
    const keys = Object.keys(value);
    for (let i = 0; i < keys.length; i += 1) {
      deepFreeze(/** @type {Record<string, unknown>} */ (value)[keys[i]]);
    }
  }
  return value;
}

/**
 * Validate a non-empty string identifier.
 *
 * @param {unknown} value
 * @param {string} label
 * @param {string} [errorCode="INVALID_ID"]
 * @returns {string}
 */
export function assertNonEmptyId(value, label, errorCode = "INVALID_ID") {
  if (typeof value !== "string" || value.length === 0) {
    throw new EnsomiError(errorCode, `${label} must be a non-empty string`);
  }
  return value;
}

/**
 * Clamp a finite unit value into `[0, 1]`. Throws on non-finite input.
 *
 * @param {number} value
 * @returns {number}
 */
export function clampUnit(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new EnsomiError("INVALID_UNIT", `Expected finite number in [0, 1], got ${String(value)}`);
  }
  if (value <= 0) return 0;
  if (value >= 1) return 1;
  return value;
}

/**
 * Validate positive integer entity revision (`>= 1`).
 *
 * @param {unknown} revision
 * @param {string} entityName
 * @returns {number}
 */
export function assertPositiveRevision(revision, entityName) {
  if (typeof revision !== "number" || !Number.isSafeInteger(revision) || revision < 1) {
    throw new EnsomiError(
      `INVALID_${entityName.toUpperCase()}`,
      `${entityName} revision must be a positive safe integer, got ${String(revision)}`,
    );
  }
  return revision;
}

/**
 * Derive entity `Origin` from a `TraceAuthor.kind`.
 *
 * @param {string} authorKind
 * @returns {"import" | "model" | "rule" | "user"}
 */
export function authorKindToOrigin(authorKind) {
  if (authorKind === "user") return "user";
  if (authorKind === "model") return "model";
  if (authorKind === "import") return "import";
  if (authorKind === "rule" || authorKind === "host") return "rule";
  throw new EnsomiError("INVALID_AUTHOR", `Unknown author kind: ${String(authorKind)}`);
}

/**
 * Validate a `Sound` verdict facet (`"occurrence" | "voice" | "extent" | "anchor:<anchorId>"`).
 *
 * @param {unknown} facet
 * @param {readonly { id: string }[]} [anchors]
 * @returns {string}
 */
export function validateSoundFacet(facet, anchors) {
  const f = facet === undefined ? "occurrence" : facet;
  if (typeof f !== "string" || f.length === 0) {
    throw new EnsomiError("INVALID_VERDICT", "Sound facet must be a non-empty string");
  }
  if (f === "occurrence" || f === "voice" || f === "extent") {
    return f;
  }
  if (f.startsWith("anchor:") && f.length > 7) {
    const anchorId = f.slice(7);
    if (anchors && !anchors.some((a) => a.id === anchorId)) {
      throw new EnsomiError(
        "INVALID_VERDICT",
        `Sound facet "${f}" references unknown anchor "${anchorId}"`,
      );
    }
    return f;
  }
  throw new EnsomiError(
    "INVALID_VERDICT",
    `Invalid Sound verdict facet "${f}"; expected "occurrence", "voice", "extent", or "anchor:<anchorId>"`,
  );
}

/**
 * Compute deterministic JCS SHA-256 `valueDigest` for a mark (or facet of a `Sound`).
 * Used by `Verdict.valueDigest`, `OfferChallenge.expectedValueDigest`, and `ChallengeAuthorization`.
 *
 * @param {"beat" | "unbeat" | "sound" | "sound_class" | "boundary"} kind
 * @param {Record<string, any>} entity
 * @param {string} [facet]
 * @returns {string}
 */
export function computeEntityValueDigest(kind, entity, facet) {
  if (!VERDICT_TARGET_KIND_SET.has(kind)) {
    throw new EnsomiError("INVALID_ENTITY_KIND", `Unsupported verdict target kind: ${String(kind)}`);
  }
  switch (kind) {
    case "beat":
      if (!entity.boundTo && !entity.structural) {
        return sha256Hex(
          `{"atUs":${entity.atUs === 0 ? 0 : entity.atUs},"boundTo":null,"id":${canonicalJson(entity.id)},"kind":"beat","structural":null}`,
        );
      }
      return jcsSha256Hex({
        kind: "beat",
        id: entity.id,
        atUs: entity.atUs,
        boundTo: entity.boundTo
          ? {
              soundId: entity.boundTo.soundId ?? null,
              candidateEventId: entity.boundTo.candidateEventId ?? null,
              anchorId: entity.boundTo.anchorId ?? null,
              anchorRole: entity.boundTo.anchorRole,
            }
          : null,
        structural: entity.structural ?? null,
      });

    case "unbeat":
      return jcsSha256Hex({
        kind: "unbeat",
        id: entity.id,
        atUs: entity.atUs,
        tolUs: entity.tolUs,
        pulseLayer: entity.pulseLayer,
      });

    case "boundary":
      return jcsSha256Hex({
        kind: "boundary",
        id: entity.id,
        atUs: entity.atUs,
      });

    case "sound_class":
      return jcsSha256Hex({
        kind: "sound_class",
        id: entity.id,
        exemplarSoundIds: entity.exemplarSoundIds,
        matchMode: entity.matchMode,
        label: entity.label ?? null,
      });

    case "sound": {
      const resolvedFacet = validateSoundFacet(facet, entity.anchors);
      const primary =
        entity.anchors.find((/** @type {any} */ a) => a.id === entity.primaryAnchorId) ??
        entity.anchors[0];
      if (resolvedFacet === "occurrence") {
        return jcsSha256Hex({
          kind: "sound",
          facet: "occurrence",
          id: entity.id,
          startUs: entity.startUs,
          primaryAnchorId: entity.primaryAnchorId,
          primaryAnchor: primary
            ? { id: primary.id, role: primary.role, atUs: primary.atUs }
            : null,
          family: entity.characteristics?.family ?? null,
          voiceGroupId: entity.voiceGroupId ?? null,
          classId: entity.classId ?? null,
        });
      }
      if (resolvedFacet === "voice") {
        return jcsSha256Hex({
          kind: "sound",
          facet: "voice",
          id: entity.id,
          startUs: entity.startUs,
          primaryAtUs: primary ? primary.atUs : entity.startUs,
          family: entity.characteristics?.family ?? null,
          register: entity.characteristics?.register ?? null,
          voiceGroupId: entity.voiceGroupId ?? null,
          classId: entity.classId ?? null,
        });
      }
      if (resolvedFacet === "extent") {
        return jcsSha256Hex({
          kind: "sound",
          facet: "extent",
          id: entity.id,
          startUs: entity.startUs,
          endUs: entity.endUs,
          endKind: entity.endKind,
          anchors: entity.anchors.map((/** @type {any} */ a) => ({
            id: a.id,
            role: a.role,
            atUs: a.atUs,
          })),
        });
      }
      const anchorId = resolvedFacet.slice(7);
      const anchor = entity.anchors.find((/** @type {any} */ a) => a.id === anchorId);
      return jcsSha256Hex({
        kind: "sound",
        facet: resolvedFacet,
        id: entity.id,
        anchor: anchor ? { id: anchor.id, role: anchor.role, atUs: anchor.atUs } : null,
      });
    }

    default:
      throw new EnsomiError("INVALID_ENTITY_KIND", `Unhandled verdict target kind: ${kind}`);
  }
}

/**
 * Validate and normalize a `Verdict` object.
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").Verdict}
 */
export function normalizeVerdict(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_VERDICT", "Verdict must be an object");
  }
  const v = /** @type {Record<string, unknown>} */ (raw);
  if (!VERDICT_KIND_SET.has(/** @type {any} */ (v.kind))) {
    throw new EnsomiError("INVALID_VERDICT", `Invalid verdict kind: ${String(v.kind)}`);
  }
  const eventId = assertNonEmptyId(v.eventId, "verdict.eventId", "INVALID_VERDICT");
  const valueDigest = assertNonEmptyId(v.valueDigest, "verdict.valueDigest", "INVALID_VERDICT");
  const tolUs = assertNonNegSafeIntUs(v.tolUs, "verdict.tolUs", "INVALID_VERDICT");
  /** @type {import("../index.d.ts").Verdict} */
  const out = {
    kind: /** @type {import("../index.d.ts").VerdictKind} */ (v.kind),
    eventId,
    valueDigest,
    tolUs,
  };
  if (v.facet !== undefined) {
    out.facet = assertNonEmptyId(v.facet, "verdict.facet", "INVALID_VERDICT");
  }
  if (v.via !== undefined) {
    if (!VERDICT_VIA_SET.has(/** @type {any} */ (v.via))) {
      throw new EnsomiError("INVALID_VERDICT", `Invalid verdict via: ${String(v.via)}`);
    }
    out.via = /** @type {import("../index.d.ts").VerdictVia} */ (v.via);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize an `Approval` (`keep` lock) object.
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").Approval}
 */
export function normalizeApproval(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_APPROVAL", "Approval must be an object");
  }
  const a = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(a.id, "approval.id", "INVALID_APPROVAL");
  const eventId = assertNonEmptyId(a.eventId, "approval.eventId", "INVALID_APPROVAL");
  const contentDigest = assertNonEmptyId(
    a.contentDigest,
    "approval.contentDigest",
    "INVALID_APPROVAL",
  );
  /** @type {import("../index.d.ts").Approval} */
  const out = {
    id,
    eventId,
    contentDigest,
  };
  if (a.modifiedSinceApproval !== undefined) {
    if (typeof a.modifiedSinceApproval !== "boolean") {
      throw new EnsomiError("INVALID_APPROVAL", "approval.modifiedSinceApproval must be boolean");
    }
    if (a.modifiedSinceApproval) {
      out.modifiedSinceApproval = true;
    }
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize an `AudioBinding` (`audio_bound` payload).
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").AudioBinding}
 */
export function normalizeAudioBinding(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_AUDIO", "AudioBinding must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const audioId = assertNonEmptyId(r.audioId, "audio.audioId", "INVALID_AUDIO");
  const durationUs = assertNonNegSafeIntUs(r.durationUs, "audio.durationUs", "INVALID_AUDIO");
  if (durationUs <= 0) {
    throw new EnsomiError("INVALID_AUDIO", "audio.durationUs must be > 0");
  }
  /** @type {import("../index.d.ts").AudioBinding} */
  const out = {
    audioId,
    durationUs,
  };
  if (r.sampleRateHz !== undefined) {
    if (
      typeof r.sampleRateHz !== "number" ||
      !Number.isFinite(r.sampleRateHz) ||
      r.sampleRateHz <= 0
    ) {
      throw new EnsomiError("INVALID_AUDIO", `Invalid sampleRateHz: ${String(r.sampleRateHz)}`);
    }
    out.sampleRateHz = r.sampleRateHz === 0 ? 0 : r.sampleRateHz;
  }
  if (r.sampleCount !== undefined) {
    out.sampleCount = assertNonNegSafeIntUs(r.sampleCount, "audio.sampleCount", "INVALID_AUDIO");
  }
  if (r.audioSha256 !== undefined) {
    out.audioSha256 = assertNonEmptyId(r.audioSha256, "audio.audioSha256", "INVALID_AUDIO");
  }
  if (r.decodedPcmSha256 !== undefined) {
    out.decodedPcmSha256 = assertNonEmptyId(
      r.decodedPcmSha256,
      "audio.decodedPcmSha256",
      "INVALID_AUDIO",
    );
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize an interior `Boundary` (`0 < atUs < durationUs`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Boundary}
 */
export function normalizeBoundary(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BOUNDARY", "Boundary must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "boundary.id", "INVALID_BOUNDARY");
  const atUs = assertInteriorPointUs(r.atUs, ctx.durationUs, "boundary.atUs");
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_BOUNDARY", `Invalid boundary origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "boundary.createdByEventId",
    "INVALID_BOUNDARY",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Boundary");
  /** @type {import("../index.d.ts").Boundary} */
  const out = {
    id,
    atUs,
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
  };
  if (r.verdict !== undefined) {
    out.verdict = normalizeVerdict(r.verdict);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a `Segment` (`0 <= startUs < endUs <= durationUs`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Segment}
 */
export function normalizeSegment(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SEGMENT", "Segment must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "segment.id", "INVALID_SEGMENT");
  const { startUs, endUs } = assertIntervalUs(
    r.startUs,
    r.endUs,
    ctx.durationUs,
    "segment",
    "INVALID_SEGMENT",
  );
  const startBoundaryId =
    r.startBoundaryId === null || r.startBoundaryId === undefined
      ? null
      : assertNonEmptyId(r.startBoundaryId, "segment.startBoundaryId", "INVALID_SEGMENT");
  const endBoundaryId =
    r.endBoundaryId === null || r.endBoundaryId === undefined
      ? null
      : assertNonEmptyId(r.endBoundaryId, "segment.endBoundaryId", "INVALID_SEGMENT");
  const regime = r.regime ?? "auto";
  if (!SEGMENT_REGIME_SET.has(/** @type {any} */ (regime))) {
    throw new EnsomiError("INVALID_SEGMENT", `Invalid segment regime: ${String(regime)}`);
  }
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_SEGMENT", `Invalid segment origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "segment.createdByEventId",
    "INVALID_SEGMENT",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Segment");
  /** @type {import("../index.d.ts").Segment} */
  const out = {
    id,
    startUs,
    endUs,
    startBoundaryId,
    endBoundaryId,
    regime: /** @type {import("../index.d.ts").SegmentRegime} */ (regime),
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
  };
  if (r.kinematics !== undefined && r.kinematics !== null) {
    if (!SEGMENT_KINEMATICS_SET.has(/** @type {any} */ (r.kinematics))) {
      throw new EnsomiError("INVALID_SEGMENT", `Invalid segment kinematics: ${String(r.kinematics)}`);
    }
    out.kinematics = /** @type {import("../index.d.ts").SegmentKinematics} */ (r.kinematics);
  }
  if (r.approval !== undefined) {
    out.approval = normalizeApproval(r.approval);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a persistent policy `Span` (`"free" | "keep"`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Span}
 */
export function normalizeSpan(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SPAN", "Span must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "span.id", "INVALID_SPAN");
  const segmentId = assertNonEmptyId(r.segmentId, "span.segmentId", "INVALID_SPAN");
  const { startUs, endUs } = assertIntervalUs(
    r.startUs,
    r.endUs,
    ctx.durationUs,
    "span",
    "INVALID_SPAN",
  );
  if (!SPAN_KIND_SET.has(/** @type {any} */ (r.kind))) {
    throw new EnsomiError("INVALID_SPAN", `Invalid span kind: ${String(r.kind)}`);
  }
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_SPAN", `Invalid span origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "span.createdByEventId",
    "INVALID_SPAN",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Span");
  /** @type {import("../index.d.ts").Span} */
  const out = {
    id,
    segmentId,
    startUs,
    endUs,
    kind: /** @type {import("../index.d.ts").SpanKind} */ (r.kind),
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
  };
  if (r.approval !== undefined) {
    out.approval = normalizeApproval(r.approval);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize `Beat.boundTo` provenance.
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").BeatBinding}
 */
export function normalizeBeatBinding(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BEAT", "Beat boundTo must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  if (!SNAP_ROLE_SET.has(/** @type {any} */ (r.anchorRole))) {
    throw new EnsomiError("INVALID_BEAT", `Invalid boundTo.anchorRole: ${String(r.anchorRole)}`);
  }
  /** @type {import("../index.d.ts").BeatBinding} */
  const out = {
    anchorRole: /** @type {import("../index.d.ts").SnapRole} */ (r.anchorRole),
  };
  if (r.soundId !== undefined && r.soundId !== null) {
    out.soundId = assertNonEmptyId(r.soundId, "boundTo.soundId", "INVALID_BEAT");
  }
  if (r.candidateEventId !== undefined && r.candidateEventId !== null) {
    out.candidateEventId = assertNonEmptyId(
      r.candidateEventId,
      "boundTo.candidateEventId",
      "INVALID_BEAT",
    );
  }
  if (r.anchorId !== undefined && r.anchorId !== null) {
    out.anchorId = assertNonEmptyId(r.anchorId, "boundTo.anchorId", "INVALID_BEAT");
  }
  if (!out.soundId && !out.candidateEventId) {
    throw new EnsomiError(
      "INVALID_BEAT",
      "boundTo requires at least one of soundId or candidateEventId",
    );
  }
  if (r.alignmentStale !== undefined) {
    if (typeof r.alignmentStale !== "boolean") {
      throw new EnsomiError("INVALID_BEAT", "boundTo.alignmentStale must be boolean");
    }
    if (r.alignmentStale) out.alignmentStale = true;
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize `Beat.structural` metadata.
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").BeatStructural}
 */
export function normalizeBeatStructural(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BEAT", "Beat structural must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  /** @type {import("../index.d.ts").BeatStructural} */
  const out = {};
  if (r.downbeat !== undefined) {
    if (typeof r.downbeat !== "boolean") {
      throw new EnsomiError("INVALID_BEAT", "structural.downbeat must be boolean");
    }
    out.downbeat = r.downbeat;
  }
  if (r.phraseReset !== undefined) {
    if (typeof r.phraseReset !== "boolean") {
      throw new EnsomiError("INVALID_BEAT", "structural.phraseReset must be boolean");
    }
    out.phraseReset = r.phraseReset;
  }
  if (r.fermata !== undefined) {
    if (typeof r.fermata !== "boolean") {
      throw new EnsomiError("INVALID_BEAT", "structural.fermata must be boolean");
    }
    out.fermata = r.fermata;
  }
  if (r.anchorKind !== undefined) {
    if (!BEAT_ANCHOR_KIND_SET.has(/** @type {any} */ (r.anchorKind))) {
      throw new EnsomiError("INVALID_BEAT", `Invalid structural.anchorKind: ${String(r.anchorKind)}`);
    }
    out.anchorKind = /** @type {"anchored" | "interpolated"} */ (r.anchorKind);
  }
  if (r.groupId !== undefined) {
    out.groupId = assertNonEmptyId(r.groupId, "structural.groupId", "INVALID_BEAT");
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a `Beat` (`0 <= atUs < durationUs`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Beat}
 */
export function normalizeBeat(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BEAT", "Beat must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "beat.id", "INVALID_BEAT");
  const atUs = assertPointUs(r.atUs, ctx.durationUs, "beat.atUs");
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_BEAT", `Invalid beat origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "beat.createdByEventId",
    "INVALID_BEAT",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Beat");
  /** @type {import("../index.d.ts").Beat} */
  const out = {
    id,
    atUs,
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
  };
  if (r.confidence !== undefined) {
    out.confidence = clampUnit(/** @type {number} */ (r.confidence));
  }
  if (r.boundTo !== undefined && r.boundTo !== null) {
    out.boundTo = normalizeBeatBinding(r.boundTo);
  }
  if (r.structural !== undefined && r.structural !== null) {
    const st = normalizeBeatStructural(r.structural);
    if (Object.keys(st).length > 0) {
      out.structural = st;
    }
  }
  if (r.lineage !== undefined) {
    if (!r.lineage || typeof r.lineage !== "object") {
      throw new EnsomiError("INVALID_BEAT", "beat.lineage must be an object");
    }
    const ln = /** @type {Record<string, unknown>} */ (r.lineage);
    if (!ORIGIN_SET.has(/** @type {any} */ (ln.origin))) {
      throw new EnsomiError("INVALID_BEAT", `Invalid lineage.origin: ${String(ln.origin)}`);
    }
    /** @type {import("../index.d.ts").BeatLineage} */
    const lineage = {
      origin: /** @type {import("../index.d.ts").Origin} */ (ln.origin),
      atUs: assertNonNegSafeIntUs(ln.atUs, "lineage.atUs", "INVALID_BEAT"),
    };
    if (ln.confidence !== undefined) {
      lineage.confidence = clampUnit(/** @type {number} */ (ln.confidence));
    }
    out.lineage = lineage;
  }
  if (r.verdict !== undefined) {
    out.verdict = normalizeVerdict(r.verdict);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize an `Unbeat` (`0 <= atUs < durationUs`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Unbeat}
 */
export function normalizeUnbeat(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_UNBEAT", "Unbeat must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "unbeat.id", "INVALID_UNBEAT");
  const atUs = assertPointUs(r.atUs, ctx.durationUs, "unbeat.atUs");
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_UNBEAT", `Invalid unbeat origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "unbeat.createdByEventId",
    "INVALID_UNBEAT",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Unbeat");
  const tolUs = assertNonNegSafeIntUs(
    r.tolUs ?? DEFAULT_UNBEAT_TOL_US,
    "unbeat.tolUs",
    "INVALID_UNBEAT",
  );
  const pulseLayer = r.pulseLayer ?? "tactus";
  if (!PULSE_LAYER_SET.has(/** @type {any} */ (pulseLayer))) {
    throw new EnsomiError("INVALID_UNBEAT", `Invalid unbeat pulseLayer: ${String(pulseLayer)}`);
  }
  /** @type {import("../index.d.ts").Unbeat} */
  const out = {
    id,
    atUs,
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
    tolUs,
    pulseLayer: /** @type {import("../index.d.ts").PulseLayer} */ (pulseLayer),
  };
  if (r.replaces !== undefined) {
    if (!r.replaces || typeof r.replaces !== "object") {
      throw new EnsomiError("INVALID_UNBEAT", "unbeat.replaces must be an object");
    }
    const rp = /** @type {Record<string, unknown>} */ (r.replaces);
    if (!ORIGIN_SET.has(/** @type {any} */ (rp.origin))) {
      throw new EnsomiError("INVALID_UNBEAT", `Invalid replaces.origin: ${String(rp.origin)}`);
    }
    /** @type {import("../index.d.ts").ReplacedBeatRef} */
    const replaces = {
      beatId: assertNonEmptyId(rp.beatId, "replaces.beatId", "INVALID_UNBEAT"),
      origin: /** @type {import("../index.d.ts").Origin} */ (rp.origin),
      createdByEventId: assertNonEmptyId(
        rp.createdByEventId,
        "replaces.createdByEventId",
        "INVALID_UNBEAT",
      ),
      priorRevision: assertPositiveRevision(rp.priorRevision, "ReplacedBeatRef"),
      atUs: assertNonNegSafeIntUs(rp.atUs, "replaces.atUs", "INVALID_UNBEAT"),
    };
    if (rp.confidence !== undefined) {
      replaces.confidence = clampUnit(/** @type {number} */ (rp.confidence));
    }
    out.replaces = replaces;
  }
  if (r.verdict !== undefined) {
    out.verdict = normalizeVerdict(r.verdict);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize `SoundCharacteristics`.
 * `family` is an open non-empty string (`WELL_KNOWN_SOUND_FAMILIES` provides standard constants).
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").SoundCharacteristics}
 */
export function normalizeSoundCharacteristics(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND", "Sound characteristics must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  /** @type {import("../index.d.ts").SoundCharacteristics} */
  const out = {};
  if (r.family !== undefined) {
    if (typeof r.family !== "string" || r.family.trim().length === 0) {
      throw new EnsomiError("INVALID_SOUND", "Sound characteristics.family must be a non-empty string");
    }
    out.family = r.family;
  }
  if (r.register !== undefined) {
    if (!SOUND_REGISTER_SET.has(/** @type {any} */ (r.register))) {
      throw new EnsomiError("INVALID_SOUND", `Invalid sound register: ${String(r.register)}`);
    }
    out.register = /** @type {import("../index.d.ts").SoundRegister} */ (r.register);
  }
  if (r.f0Hz !== undefined) {
    if (typeof r.f0Hz !== "number" || !Number.isFinite(r.f0Hz) || r.f0Hz <= 0) {
      throw new EnsomiError("INVALID_SOUND", `Invalid sound f0Hz: ${String(r.f0Hz)}`);
    }
    out.f0Hz = r.f0Hz === 0 ? 0 : r.f0Hz;
  }
  if (r.harmonicity !== undefined) {
    out.harmonicity = clampUnit(/** @type {number} */ (r.harmonicity));
  }
  if (r.stereoPan !== undefined) {
    if (
      typeof r.stereoPan !== "number" ||
      !Number.isFinite(r.stereoPan) ||
      r.stereoPan < -1 ||
      r.stereoPan > 1
    ) {
      throw new EnsomiError(
        "INVALID_SOUND",
        `Sound stereoPan must be a finite number in [-1, 1], got ${String(r.stereoPan)}`,
      );
    }
    out.stereoPan = r.stereoPan === 0 ? 0 : r.stereoPan;
  }
  if (r.stereoWidth !== undefined) {
    out.stereoWidth = clampUnit(/** @type {number} */ (r.stereoWidth));
  }
  if (r.confidence !== undefined) {
    out.confidence = clampUnit(/** @type {number} */ (r.confidence));
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a spanned `Sound` (`0 <= startUs < endUs <= durationUs`).
 *
 * @param {unknown} raw
 * @param {{ durationUs?: number, defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").Sound}
 */
export function normalizeSound(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND", "Sound must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "sound.id", "INVALID_SOUND");
  const { startUs, endUs } = assertIntervalUs(
    r.startUs,
    r.endUs,
    ctx.durationUs,
    "sound",
    "INVALID_SOUND",
  );
  const endKind = r.endKind ?? "estimated";
  if (!SOUND_END_KIND_SET.has(/** @type {any} */ (endKind))) {
    throw new EnsomiError("INVALID_SOUND", `Invalid sound endKind: ${String(endKind)}`);
  }
  if (!Array.isArray(r.anchors) || r.anchors.length === 0) {
    throw new EnsomiError("INVALID_SOUND", "Sound requires at least one SoundAnchorPoint in anchors");
  }
  const seenAnchorIds = new Set();
  /** @type {import("../index.d.ts").SoundAnchorPoint[]} */
  const anchors = [];
  for (let i = 0; i < r.anchors.length; i += 1) {
    const rawAnchor = r.anchors[i];
    if (!rawAnchor || typeof rawAnchor !== "object") {
      throw new EnsomiError("INVALID_SOUND", `Sound anchor[${i}] must be an object`);
    }
    const a = /** @type {Record<string, unknown>} */ (rawAnchor);
    const anchorId = assertNonEmptyId(a.id, `sound.anchors[${i}].id`, "INVALID_SOUND");
    if (seenAnchorIds.has(anchorId)) {
      throw new EnsomiError("INVALID_SOUND", `Duplicate anchor id "${anchorId}" on sound "${id}"`);
    }
    seenAnchorIds.add(anchorId);
    if (!SNAP_ROLE_SET.has(/** @type {any} */ (a.role))) {
      throw new EnsomiError("INVALID_SOUND", `Invalid anchor role: ${String(a.role)}`);
    }
    const atUs = assertNonNegSafeIntUs(a.atUs, `sound.anchors[${i}].atUs`, "INVALID_SOUND");
    if (atUs < startUs || atUs > endUs) {
      throw new EnsomiError(
        "OUT_OF_BOUNDS",
        `Sound anchor "${anchorId}" atUs (${atUs}) must lie within sound [${startUs}, ${endUs}]`,
      );
    }
    /** @type {import("../index.d.ts").SoundAnchorPoint} */
    const anchor = {
      id: anchorId,
      role: /** @type {import("../index.d.ts").SnapRole} */ (a.role),
      atUs,
    };
    if (a.sigmaUs !== undefined) {
      anchor.sigmaUs = assertNonNegSafeIntUs(
        a.sigmaUs,
        `sound.anchors[${i}].sigmaUs`,
        "INVALID_SOUND",
      );
    }
    anchors.push(anchor);
  }
  const primaryAnchorId = assertNonEmptyId(
    r.primaryAnchorId ?? anchors[0].id,
    "sound.primaryAnchorId",
    "INVALID_SOUND",
  );
  if (!seenAnchorIds.has(primaryAnchorId)) {
    throw new EnsomiError(
      "INVALID_SOUND",
      `Sound primaryAnchorId "${primaryAnchorId}" does not match any entry in anchors`,
    );
  }

  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_SOUND", `Invalid sound origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "sound.createdByEventId",
    "INVALID_SOUND",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "Sound");

  /** @type {import("../index.d.ts").Sound} */
  const out = {
    id,
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
    startUs,
    endUs,
    endKind: /** @type {import("../index.d.ts").SoundEndKind} */ (endKind),
    anchors,
    primaryAnchorId,
  };
  if (r.salience !== undefined) {
    out.salience = clampUnit(/** @type {number} */ (r.salience));
  }
  if (r.characteristics !== undefined && r.characteristics !== null) {
    const ch = normalizeSoundCharacteristics(r.characteristics);
    if (Object.keys(ch).length > 0) out.characteristics = ch;
  }
  if (r.descriptor !== undefined && r.descriptor !== null) {
    if (typeof r.descriptor !== "object") {
      throw new EnsomiError("INVALID_SOUND", "Sound descriptor must be an object");
    }
    const d = /** @type {Record<string, unknown>} */ (r.descriptor);
    const schema = assertNonEmptyId(d.schema, "sound.descriptor.schema", "INVALID_SOUND");
    if (!Array.isArray(d.values)) {
      throw new EnsomiError("INVALID_SOUND", "Sound descriptor.values must be a number[]");
    }
    const values = d.values.map((v, idx) => {
      if (typeof v !== "number" || !Number.isFinite(v)) {
        throw new EnsomiError(
          "INVALID_SOUND",
          `Sound descriptor.values[${idx}] must be finite number`,
        );
      }
      return v === 0 ? 0 : v;
    });
    out.descriptor = { schema, values };
  }
  if (r.voiceGroupId !== undefined && r.voiceGroupId !== null) {
    out.voiceGroupId = assertNonEmptyId(r.voiceGroupId, "sound.voiceGroupId", "INVALID_SOUND");
  }
  if (r.classId !== undefined && r.classId !== null) {
    out.classId = assertNonEmptyId(r.classId, "sound.classId", "INVALID_SOUND");
  }
  if (r.blendStatus !== undefined && r.blendStatus !== null) {
    if (!SOUND_BLEND_SET.has(/** @type {any} */ (r.blendStatus))) {
      throw new EnsomiError("INVALID_SOUND", `Invalid sound blendStatus: ${String(r.blendStatus)}`);
    }
    out.blendStatus = /** @type {"isolated" | "blended"} */ (r.blendStatus);
  }
  if (r.verdict !== undefined) {
    const v = normalizeVerdict(r.verdict);
    const facet = validateSoundFacet(v.facet, anchors);
    out.verdict = v.facet === facet ? v : deepFreeze({ ...v, facet });
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a `SoundClass`.
 *
 * @param {unknown} raw
 * @param {{ defaultOrigin?: import("../index.d.ts").Origin, defaultEventId?: string, defaultRevision?: number }} [ctx]
 * @returns {import("../index.d.ts").SoundClass}
 */
export function normalizeSoundClass(raw, ctx = {}) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND_CLASS", "SoundClass must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const id = assertNonEmptyId(r.id, "soundClass.id", "INVALID_SOUND_CLASS");
  if (!Array.isArray(r.exemplarSoundIds) || r.exemplarSoundIds.length === 0) {
    throw new EnsomiError(
      "INVALID_SOUND_CLASS",
      "SoundClass exemplarSoundIds must be a non-empty string[]",
    );
  }
  const exemplarSoundIds = [
    ...new Set(
      r.exemplarSoundIds.map((sid, i) =>
        assertNonEmptyId(sid, `soundClass.exemplarSoundIds[${i}]`, "INVALID_SOUND_CLASS"),
      ),
    ),
  ];
  if (!SOUND_MATCH_MODE_SET.has(/** @type {any} */ (r.matchMode))) {
    throw new EnsomiError(
      "INVALID_SOUND_CLASS",
      `Invalid SoundClass matchMode: ${String(r.matchMode)}`,
    );
  }
  const origin = r.origin ?? ctx.defaultOrigin ?? "model";
  if (!ORIGIN_SET.has(/** @type {any} */ (origin))) {
    throw new EnsomiError("INVALID_SOUND_CLASS", `Invalid SoundClass origin: ${String(origin)}`);
  }
  const createdByEventId = assertNonEmptyId(
    r.createdByEventId ?? ctx.defaultEventId ?? "init",
    "soundClass.createdByEventId",
    "INVALID_SOUND_CLASS",
  );
  const revision = assertPositiveRevision(r.revision ?? ctx.defaultRevision ?? 1, "SoundClass");

  /** @type {import("../index.d.ts").SoundClass} */
  const out = {
    id,
    origin: /** @type {import("../index.d.ts").Origin} */ (origin),
    createdByEventId,
    revision,
    exemplarSoundIds,
    matchMode: /** @type {import("../index.d.ts").SoundMatchMode} */ (r.matchMode),
  };
  if (r.label !== undefined && r.label !== null) {
    if (typeof r.label !== "string" || r.label.trim().length === 0) {
      throw new EnsomiError("INVALID_SOUND_CLASS", "SoundClass label must be a non-empty string");
    }
    out.label = r.label;
  }
  if (r.verdict !== undefined) {
    out.verdict = normalizeVerdict(r.verdict);
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a `StructuredNote` (`{ code, message, params? }`).
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").StructuredNote}
 */
export function normalizeStructuredNote(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_NOTE", "StructuredNote must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const code = assertNonEmptyId(r.code, "note.code", "INVALID_NOTE");
  if (typeof r.message !== "string" || r.message.trim().length === 0) {
    throw new EnsomiError("INVALID_NOTE", "note.message must be a non-empty string");
  }
  /** @type {import("../index.d.ts").StructuredNote} */
  const out = {
    code,
    message: r.message,
  };
  if (r.params !== undefined && r.params !== null) {
    if (typeof r.params !== "object" || Array.isArray(r.params)) {
      throw new EnsomiError("INVALID_NOTE", "note.params must be a plain object");
    }
    /** @type {Record<string, string | number | boolean | null>} */
    const params = {};
    for (const [k, val] of Object.entries(/** @type {Record<string, unknown>} */ (r.params))) {
      if (val === undefined) continue;
      if (
        val !== null &&
        typeof val !== "string" &&
        typeof val !== "boolean" &&
        (typeof val !== "number" || !Number.isFinite(val))
      ) {
        throw new EnsomiError("INVALID_NOTE", `Invalid note.params["${k}"] value`);
      }
      params[k] = typeof val === "number" && val === 0 ? 0 : val;
    }
    out.params = params;
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a `SnapCandidate` (carries embedded candidate `Sound` metadata + `anchorId`, `role`, `atUs`, `deltaUs`).
 *
 * @param {unknown} raw
 * @param {number} [queryAtUs]
 * @param {number} [durationUs]
 * @returns {import("../index.d.ts").SnapCandidate}
 */
export function normalizeSnapCandidate(raw, queryAtUs, durationUs) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", "SnapCandidate must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const candidateId = assertNonEmptyId(
    r.candidateId,
    "snapCandidate.candidateId",
    "INVALID_SNAP_CANDIDATE",
  );
  const atUs = assertPointUs(r.atUs, durationUs, "snapCandidate.atUs");
  const anchorId = assertNonEmptyId(
    r.anchorId,
    "snapCandidate.anchorId",
    "INVALID_SNAP_CANDIDATE",
  );
  if (!SNAP_ROLE_SET.has(/** @type {any} */ (r.role))) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", `Invalid snap role: ${String(r.role)}`);
  }
  const deltaUs =
    queryAtUs !== undefined
      ? atUs - assertNonNegSafeIntUs(queryAtUs, "queryAtUs")
      : assertSafeIntUs(r.deltaUs, "snapCandidate.deltaUs", "INVALID_SNAP_CANDIDATE");
  const labelCode = assertNonEmptyId(
    r.labelCode ?? "acoustic_onset",
    "snapCandidate.labelCode",
    "INVALID_SNAP_CANDIDATE",
  );
  if (typeof r.label !== "string" || r.label.trim().length === 0) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", "snapCandidate.label must be a non-empty string");
  }
  const salience = clampUnit(/** @type {number} */ (r.salience));

  if (!r.sound || typeof r.sound !== "object") {
    throw new EnsomiError(
      "INVALID_SNAP_CANDIDATE",
      "snapCandidate.sound embedded occurrence metadata is required",
    );
  }
  const rawSound = /** @type {Record<string, unknown>} */ (r.sound);
  const normalizedSound = normalizeSound(
    {
      ...rawSound,
      id: typeof rawSound.id === "string" && rawSound.id.length > 0 ? rawSound.id : candidateId,
      origin: "model",
      createdByEventId: "micro_assist",
      revision: 1,
    },
    { durationUs },
  );
  if (!normalizedSound.anchors.some((a) => a.id === anchorId && a.role === r.role && a.atUs === atUs)) {
    throw new EnsomiError(
      "INVALID_SNAP_CANDIDATE",
      `snapCandidate anchor "${anchorId}" (${String(r.role)} @ ${atUs}) must match embedded sound.anchors`,
    );
  }

  /** @type {import("../index.d.ts").SnapCandidate["sound"]} */
  const soundMeta = {
    ...(typeof rawSound.id === "string" && rawSound.id.length > 0 ? { id: rawSound.id } : {}),
    startUs: normalizedSound.startUs,
    endUs: normalizedSound.endUs,
    endKind: normalizedSound.endKind,
    anchors: normalizedSound.anchors,
    primaryAnchorId: normalizedSound.primaryAnchorId,
    ...(normalizedSound.characteristics ? { characteristics: normalizedSound.characteristics } : {}),
    ...(normalizedSound.voiceGroupId ? { voiceGroupId: normalizedSound.voiceGroupId } : {}),
    ...(normalizedSound.classId ? { classId: normalizedSound.classId } : {}),
    ...(normalizedSound.blendStatus ? { blendStatus: normalizedSound.blendStatus } : {}),
  };

  /** @type {import("../index.d.ts").SnapCandidate} */
  const out = {
    candidateId,
    atUs,
    deltaUs: deltaUs === 0 ? 0 : deltaUs,
    anchorId,
    role: /** @type {import("../index.d.ts").SnapRole} */ (r.role),
    salience,
    labelCode,
    label: r.label,
    sound: soundMeta,
  };
  if (r.sigmaUs !== undefined) {
    out.sigmaUs = assertNonNegSafeIntUs(
      r.sigmaUs,
      "snapCandidate.sigmaUs",
      "INVALID_SNAP_CANDIDATE",
    );
  }
  return deepFreeze(out);
}

/**
 * Validate and normalize a polyphonic `SoundOptionCandidate` returned by `suggestSoundOptions`.
 *
 * @param {unknown} raw
 * @param {number} [durationUs]
 * @returns {import("../index.d.ts").SoundOptionCandidate}
 */
export function normalizeSoundOptionCandidate(raw, durationUs) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND_OPTION", "SoundOptionCandidate must be an object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const optionId = assertNonEmptyId(
    r.optionId,
    "soundOption.optionId",
    "INVALID_SOUND_OPTION",
  );
  const salience = clampUnit(/** @type {number} */ (r.salience));
  const labelCode = assertNonEmptyId(
    r.labelCode ?? "sound_option",
    "soundOption.labelCode",
    "INVALID_SOUND_OPTION",
  );
  if (typeof r.label !== "string" || r.label.trim().length === 0) {
    throw new EnsomiError("INVALID_SOUND_OPTION", "soundOption.label must be a non-empty string");
  }
  if (!r.sound || typeof r.sound !== "object") {
    throw new EnsomiError("INVALID_SOUND_OPTION", "soundOption.sound must be an object");
  }
  const rawSound = /** @type {Record<string, unknown>} */ (r.sound);
  const normalizedSound = normalizeSound(
    {
      ...rawSound,
      id: typeof rawSound.id === "string" && rawSound.id.length > 0 ? rawSound.id : optionId,
      origin: "model",
      createdByEventId: "micro_assist",
      revision: 1,
    },
    { durationUs },
  );
  /** @type {import("../index.d.ts").SoundInput} */
  const sound = {
    id: normalizedSound.id,
    startUs: normalizedSound.startUs,
    endUs: normalizedSound.endUs,
    endKind: normalizedSound.endKind,
    anchors: normalizedSound.anchors,
    primaryAnchorId: normalizedSound.primaryAnchorId,
    ...(normalizedSound.salience !== undefined ? { salience: normalizedSound.salience } : {}),
    ...(normalizedSound.characteristics ? { characteristics: normalizedSound.characteristics } : {}),
    ...(normalizedSound.descriptor ? { descriptor: normalizedSound.descriptor } : {}),
    ...(normalizedSound.voiceGroupId ? { voiceGroupId: normalizedSound.voiceGroupId } : {}),
    ...(normalizedSound.classId ? { classId: normalizedSound.classId } : {}),
    ...(normalizedSound.blendStatus ? { blendStatus: normalizedSound.blendStatus } : {}),
  };
  return deepFreeze({
    optionId,
    salience,
    labelCode,
    label: r.label,
    sound,
  });
}

/**
 * Decompose a half-open `RangeUs` across a contiguous `Segment[]` partition into
 * clipped `{ segmentId, startUs, endUs }` slices.
 *
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @param {{ startUs: number, endUs: number }} range
 * @returns {Array<{ segmentId: string, startUs: number, endUs: number }>}
 */
export function decomposeRangeToSegments(segments, range) {
  if (!Array.isArray(segments)) {
    throw new EnsomiError("INVALID_SEGMENTS", "segments must be an array");
  }
  const r = assertRangeUs(range);
  /** @type {Array<{ segmentId: string, startUs: number, endUs: number }>} */
  const out = [];
  for (let i = 0; i < segments.length; i += 1) {
    const seg = segments[i];
    const s = Math.max(seg.startUs, r.startUs);
    const e = Math.min(seg.endUs, r.endUs);
    if (e > s) {
      out.push({ segmentId: seg.id, startUs: s, endUs: e });
    }
  }
  return out;
}

/**
 * Return interior boundary timestamps `segments[i].startUs` (`i >= 1`).
 *
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @returns {number[]}
 */
export function getInteriorBoundaryTimesUs(segments) {
  if (!Array.isArray(segments) || segments.length <= 1) return [];
  const out = new Array(segments.length - 1);
  for (let i = 1; i < segments.length; i += 1) {
    out[i - 1] = segments[i].startUs;
  }
  return out;
}

/**
 * Validate a `TraceEvent` envelope (`schemaVersion: "0.3"`).
 * Forbids legacy `seedDurationSec`.
 *
 * @param {unknown} raw
 * @returns {import("../index.d.ts").TraceEvent}
 */
export function assertTraceEventEnvelope(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new EnsomiError("INVALID_EVENT", "TraceEvent must be a plain object");
  }
  const ev = /** @type {Record<string, unknown>} */ (raw);
  if (ev.schemaVersion !== TRACE_SCHEMA_VERSION) {
    throw new EnsomiError(
      "UNSUPPORTED_SCHEMA_VERSION",
      `Expected TraceEvent schemaVersion "${TRACE_SCHEMA_VERSION}", got "${String(ev.schemaVersion)}"`,
    );
  }
  if ("seedDurationSec" in ev && ev.seedDurationSec !== undefined) {
    throw new EnsomiError(
      "INVALID_EVENT",
      "TraceEvent.seedDurationSec is removed in schemaVersion 0.3; bind durationUs via audio_bound",
    );
  }
  if (typeof ev.seq !== "number" || !Number.isSafeInteger(ev.seq) || ev.seq < 0) {
    throw new EnsomiError("INVALID_EVENT", `TraceEvent.seq must be a non-negative integer, got ${String(ev.seq)}`);
  }
  assertNonEmptyId(ev.id, "TraceEvent.id", "INVALID_EVENT");
  if (!TRACE_EVENT_TYPE_SET.has(/** @type {any} */ (ev.type))) {
    throw new EnsomiError("INVALID_EVENT", `Invalid TraceEvent.type: ${String(ev.type)}`);
  }
  if (ev.tsMs !== undefined && (typeof ev.tsMs !== "number" || !Number.isFinite(ev.tsMs))) {
    throw new EnsomiError("INVALID_EVENT", `Invalid TraceEvent.tsMs: ${String(ev.tsMs)}`);
  }
  if (!ev.author || typeof ev.author !== "object" || Array.isArray(ev.author)) {
    throw new EnsomiError("INVALID_EVENT", "TraceEvent.author must be an object");
  }
  const author = /** @type {Record<string, unknown>} */ (ev.author);
  if (!AUTHOR_KIND_SET.has(/** @type {any} */ (author.kind))) {
    throw new EnsomiError("INVALID_EVENT", `Invalid TraceEvent.author.kind: ${String(author.kind)}`);
  }
  if (author.id !== undefined && (typeof author.id !== "string" || author.id.length === 0)) {
    throw new EnsomiError("INVALID_EVENT", "TraceEvent.author.id must be a non-empty string when set");
  }
  if (!("payload" in ev)) {
    throw new EnsomiError("INVALID_EVENT", "TraceEvent.payload is required");
  }
  return /** @type {import("../index.d.ts").TraceEvent} */ (raw);
}

/**
 * Check whether an op kind is allowed inside an automated `Offer`.
 *
 * @param {string} opKind
 * @returns {boolean}
 */
export function isOfferOpKind(opKind) {
  return OFFER_OP_KIND_SET.has(/** @type {any} */ (opKind));
}

/**
 * Check whether an op kind is a valid `TransactionOp`.
 *
 * @param {string} opKind
 * @returns {boolean}
 */
export function isTransactionOpKind(opKind) {
  return TRANSACTION_OP_KIND_SET.has(/** @type {any} */ (opKind));
}

/**
 * Validate the structural shape of an `Offer` envelope.
 *
 * @param {unknown} raw
 */
export function assertOfferShape(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new EnsomiError("INVALID_OFFER", "Offer must be a plain object");
  }
  const o = /** @type {Record<string, unknown>} */ (raw);
  assertNonEmptyId(o.id, "offer.id", "INVALID_OFFER");
  assertNonEmptyId(o.batchId, "offer.batchId", "INVALID_OFFER");
  if (o.alternativeGroup !== undefined) {
    assertNonEmptyId(o.alternativeGroup, "offer.alternativeGroup", "INVALID_OFFER");
  }
  if (!OFFER_KIND_SET.has(/** @type {any} */ (o.kind))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer.kind: ${String(o.kind)}`);
  }
  assertNonEmptyId(o.providerId, "offer.providerId", "INVALID_OFFER");
  assertNonEmptyId(o.audioId, "offer.audioId", "INVALID_OFFER");
  if (typeof o.basisSeq !== "number" || !Number.isSafeInteger(o.basisSeq) || o.basisSeq < -1) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer.basisSeq: ${String(o.basisSeq)}`);
  }
  if (!Array.isArray(o.writeRanges) || o.writeRanges.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "offer.writeRanges must be a non-empty RangeUs[]");
  }
  for (let i = 0; i < o.writeRanges.length; i += 1) {
    assertRangeUs(o.writeRanges[i]);
  }
  if (!o.dependencies || typeof o.dependencies !== "object") {
    throw new EnsomiError("INVALID_OFFER", "offer.dependencies must be an object");
  }
  assertNonEmptyId(o.basisDigest, "offer.basisDigest", "INVALID_OFFER");
  if (!Array.isArray(o.ops)) {
    throw new EnsomiError("INVALID_OFFER", "offer.ops must be an array of OfferOp");
  }
  if (!Array.isArray(o.notes)) {
    throw new EnsomiError("INVALID_OFFER", "offer.notes must be an array of StructuredNote");
  }
  for (let i = 0; i < o.notes.length; i += 1) {
    normalizeStructuredNote(o.notes[i]);
  }
  if (o.challenges !== undefined) {
    if (!Array.isArray(o.challenges)) {
      throw new EnsomiError("INVALID_OFFER", "offer.challenges must be an array");
    }
    for (const ch of o.challenges) {
      if (!ch || typeof ch !== "object") {
        throw new EnsomiError("INVALID_OFFER", "OfferChallenge must be an object");
      }
      const c = /** @type {Record<string, unknown>} */ (ch);
      assertNonEmptyId(c.challengeId, "challenge.challengeId", "INVALID_OFFER");
      if (!VERDICT_TARGET_KIND_SET.has(/** @type {any} */ (c.targetKind))) {
        throw new EnsomiError("INVALID_OFFER", `Invalid challenge.targetKind: ${String(c.targetKind)}`);
      }
      assertNonEmptyId(c.targetId, "challenge.targetId", "INVALID_OFFER");
      assertPositiveRevision(c.expectedRevision, "OfferChallenge");
      assertNonEmptyId(c.expectedValueDigest, "challenge.expectedValueDigest", "INVALID_OFFER");
      assertNonEmptyId(c.reasonCode, "challenge.reasonCode", "INVALID_OFFER");
    }
  }
  if (o.status !== undefined && !OFFER_STATUS_SET.has(/** @type {any} */ (o.status))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer.status: ${String(o.status)}`);
  }
}

export { CHALLENGE_DISPOSITION_SET, compareCodeUnits };
