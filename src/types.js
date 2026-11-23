/**
 * Core tempo-contract constants, entity normalizers, segment partition helpers,
 * and transient Range geometry utilities.
 * All timestamps are in canonical source-audio seconds (clock-agnostic).
 */

import { roundTime } from "./hash.js";

export const TRACE_SCHEMA_VERSION = "0.2";

export const ENTITY_SOURCES = Object.freeze(["draft", "user", "offer"]);
export const SEGMENT_REGIMES = Object.freeze(["auto", "free", "break"]);
export const SPAN_KINDS = Object.freeze(["keep", "free", "inbox"]);
export const SPAN_PRIORITIES = Object.freeze(["high", "medium", "low"]);

export const SNAP_ROLES = Object.freeze([
  "transient_attack",
  "p_center",
  "harmonic_change",
  "release",
]);

export const SNAP_FAMILIES = Object.freeze([
  "percussion",
  "piano_keys",
  "strings_winds",
  "vocal",
  "bass_synth",
  "other",
]);

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

export const OFFER_SCOPE_KINDS = Object.freeze(["local", "forward", "cross_region"]);

export const OFFER_STATUSES = Object.freeze([
  "valid",
  "stale",
  "invalid",
  "accepted",
  "rejected",
  "superseded",
]);

export const CONFLICT_KINDS = Object.freeze([
  "protected_keep",
  "version_mismatch",
  "range_changed",
  "out_of_scope",
  "sibling_conflict",
]);

export const TRACE_EVENT_TYPES = Object.freeze([
  "audio_bound",
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

export const EDIT_OP_KINDS = Object.freeze([
  "upsert_beat",
  "move_beat",
  "snap_beat",
  "remove_beat",
  "shift_beats",
  "upsert_sound",
  "move_sound",
  "remove_sound",
  "apply_unbeat_cue",
  "move_unbeat",
  "remove_unbeat",
  "move_segment_boundary",
  "split_segment",
  "merge_segments",
  "set_segment_keep",
  "set_segment_regime",
  "set_range_keep",
  "set_range_free",
  "promote_range_to_segments",
  "upsert_span",
  "remove_span",
]);

const ENTITY_SOURCE_SET = new Set(ENTITY_SOURCES);
const SEGMENT_REGIME_SET = new Set(SEGMENT_REGIMES);
const SPAN_KIND_SET = new Set(SPAN_KINDS);
const SPAN_PRIORITY_SET = new Set(SPAN_PRIORITIES);
const SNAP_ROLE_SET = new Set(SNAP_ROLES);
const SNAP_FAMILY_SET = new Set(SNAP_FAMILIES);
const OFFER_SCOPE_KIND_SET = new Set(OFFER_SCOPE_KINDS);
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
 * Clamp a finite unit value into [0, 1]. Throws on non-finite input.
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
 * Format a source-audio timestamp in human-readable musical time (`M:SS.mmm`).
 * @param {number} timeSec
 * @returns {string}
 */
export function formatTimeCode(timeSec) {
  const clamped = Math.max(0, typeof timeSec === "number" && Number.isFinite(timeSec) ? timeSec : 0);
  const totalMs = Math.round(clamped * 1000);
  const minutes = Math.floor(totalMs / 60000);
  const remMs = totalMs - minutes * 60000;
  const seconds = Math.floor(remMs / 1000);
  const millis = remMs - seconds * 1000;
  return `${minutes}:${String(seconds).padStart(2, "0")}.${String(millis).padStart(3, "0")}`;
}

/**
 * Validate a non-empty half-open transient Range `{ t0, t1 }` (`0 <= t0 < t1`).
 * When `durationSec > 0` is provided, also enforces `t1 <= durationSec` (clamping tiny float epsilons).
 *
 * @param {{ t0: number, t1: number }} range
 * @param {number} [durationSec]
 * @returns {{ t0: number, t1: number }}
 */
export function assertRange(range, durationSec) {
  if (!range || typeof range !== "object") {
    throw new EnsomiError("INVALID_RANGE", "Range must be an object with { t0, t1 }");
  }
  if (typeof range.t0 !== "number" || !Number.isFinite(range.t0) || range.t0 < 0) {
    throw new EnsomiError(
      "INVALID_RANGE",
      `Range t0 must be a non-negative finite number, got ${range.t0}`,
    );
  }
  if (typeof range.t1 !== "number" || !Number.isFinite(range.t1)) {
    throw new EnsomiError("INVALID_RANGE", `Range t1 must be a finite number, got ${range.t1}`);
  }
  let t0 = Object.is(range.t0, -0) ? 0 : range.t0;
  let t1 = Object.is(range.t1, -0) ? 0 : range.t1;
  if (typeof durationSec === "number" && Number.isFinite(durationSec) && durationSec > 0) {
    if (t1 > durationSec + 1e-6) {
      throw new EnsomiError(
        "INVALID_RANGE",
        `Range t1 (${t1}) exceeds track duration (${durationSec})`,
      );
    }
    if (t1 > durationSec) t1 = durationSec;
  }
  if (t1 <= t0) {
    throw new EnsomiError("INVALID_RANGE", `Range t1 (${t1}) must be strictly greater than t0 (${t0})`);
  }
  return { t0, t1 };
}

/**
 * Check whether source-audio timestamp `timeSec` falls inside half-open `[range.t0, range.t1)`.
 * @param {{ t0: number, t1: number }} range
 * @param {number} timeSec
 * @returns {boolean}
 */
export function rangeContainsTime(range, timeSec) {
  if (!range || typeof timeSec !== "number" || !Number.isFinite(timeSec)) return false;
  return timeSec >= range.t0 && timeSec < range.t1;
}

/**
 * Check whether two half-open intervals `[a.t0, a.t1)` and `[b.t0, b.t1)` overlap.
 * Accepts `"song"` on either side as full-track overlap.
 * @param {{ t0: number, t1: number } | "song"} a
 * @param {{ t0: number, t1: number } | "song"} b
 * @returns {boolean}
 */
export function rangesOverlap(a, b) {
  if (!a || !b) return false;
  if (a === "song" || b === "song") return true;
  return a.t0 < b.t1 && b.t0 < a.t1;
}

/**
 * Decompose a transient `Range` across an ordered `Segment[]` partition into
 * clipped, non-empty `{ segmentId, t0, t1 }` slices strictly inside each touched segment.
 *
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @param {{ t0: number, t1: number }} range
 * @returns {Array<{ segmentId: string, t0: number, t1: number }>}
 */
export function decomposeRangeToSpans(segments, range) {
  if (!Array.isArray(segments)) {
    throw new EnsomiError("INVALID_SEGMENTS", "segments must be an array");
  }
  const r = assertRange(range);
  /** @type {Array<{ segmentId: string, t0: number, t1: number }>} */
  const slices = [];
  for (const seg of segments) {
    const t0 = Math.max(seg.t0, r.t0);
    const t1 = Math.min(seg.t1, r.t1);
    if (t1 > t0 + 1e-12) {
      slices.push({ segmentId: seg.id, t0, t1 });
    }
  }
  return slices;
}

/**
 * Derive interior segment boundary timestamps directly from `segments[i].t0` (`i >= 1`).
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @returns {number[]}
 */
export function getSegmentBoundaryTimes(segments) {
  if (!Array.isArray(segments) || segments.length <= 1) return [];
  /** @type {number[]} */
  const times = [];
  for (let i = 1; i < segments.length; i += 1) {
    times.push(segments[i].t0);
  }
  return times;
}

function assertPositiveRevision(revision, entityName) {
  if (typeof revision !== "number" || !Number.isInteger(revision) || revision < 1) {
    throw new EnsomiError(
      `INVALID_${entityName.toUpperCase()}`,
      `${entityName} revision must be a positive integer, got ${revision}`,
    );
  }
  return revision;
}

/**
 * Validate and normalize an AudioDescriptor.
 * @param {Record<string, unknown>} raw
 * @returns {import("../index.d.ts").AudioDescriptor}
 */
export function normalizeAudioDescriptor(raw) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor must be an object");
  }
  const songId = typeof raw.songId === "string" && raw.songId.length > 0 ? raw.songId : raw.audioId;
  if (typeof songId !== "string" || songId.length === 0) {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor songId must be a non-empty string");
  }
  if (typeof raw.durationSec !== "number" || !Number.isFinite(raw.durationSec) || raw.durationSec < 0) {
    throw new EnsomiError(
      "INVALID_AUDIO",
      `AudioDescriptor durationSec must be a non-negative finite number, got ${raw.durationSec}`,
    );
  }
  const clock = raw.clock === undefined ? "source-audio-sec" : raw.clock;
  if (typeof clock !== "string" || clock.length === 0) {
    throw new EnsomiError("INVALID_AUDIO", "AudioDescriptor clock must be a non-empty string");
  }
  /** @type {import("../index.d.ts").AudioDescriptor} */
  const audio = {
    songId,
    durationSec: Object.is(raw.durationSec, -0) ? 0 : raw.durationSec,
    clock,
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
 * Validate and normalize a Segment entity.
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultRevision=1]
 * @returns {import("../index.d.ts").Segment}
 */
export function normalizeSegment(raw, defaultRevision = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SEGMENT", "Segment must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_SEGMENT", "Segment id must be a non-empty string");
  }
  if (typeof raw.t0 !== "number" || !Number.isFinite(raw.t0) || raw.t0 < 0) {
    throw new EnsomiError("INVALID_SEGMENT", `Segment t0 must be a non-negative finite number, got ${raw.t0}`);
  }
  if (typeof raw.t1 !== "number" || !Number.isFinite(raw.t1) || raw.t1 <= raw.t0) {
    throw new EnsomiError("INVALID_SEGMENT", `Segment t1 (${raw.t1}) must be > t0 (${raw.t0})`);
  }
  const regime = raw.regime ?? "auto";
  if (!SEGMENT_REGIME_SET.has(/** @type {string} */ (regime))) {
    throw new EnsomiError("INVALID_SEGMENT", `Invalid segment regime: ${regime}`);
  }
  const source = raw.source ?? "draft";
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (source))) {
    throw new EnsomiError("INVALID_SEGMENT", `Invalid segment source: ${source}`);
  }
  const revision = assertPositiveRevision(
    raw.revision === undefined ? defaultRevision : raw.revision,
    "Segment",
  );
  return {
    id: raw.id,
    t0: Object.is(raw.t0, -0) ? 0 : raw.t0,
    t1: raw.t1,
    regime: /** @type {import("../index.d.ts").SegmentRegime} */ (regime),
    kept: Boolean(raw.kept),
    source: /** @type {import("../index.d.ts").EntitySource} */ (source),
    revision,
  };
}

/**
 * Normalize/heal a candidate list of segments into an exact ordered, contiguous,
 * non-overlapping partition covering `[0, durationSec)` whenever `durationSec > 0`.
 * When `durationSec <= 0`, returns `[]`.
 *
 * @param {readonly Record<string, unknown>[] | undefined} rawSegments
 * @param {number} durationSec
 * @param {import("../index.d.ts").EntitySource} [defaultSource="draft"]
 * @returns {import("../index.d.ts").Segment[]}
 */
export function normalizeSegmentPartition(rawSegments, durationSec, defaultSource = "draft") {
  if (typeof durationSec !== "number" || !Number.isFinite(durationSec) || durationSec < 0) {
    throw new EnsomiError("INVALID_DURATION", `durationSec must be a non-negative finite number, got ${durationSec}`);
  }
  if (durationSec === 0) {
    return [];
  }

  const eps = 1e-9;
  const usedIds = new Set();
  let fillerCounter = 0;
  const mintFillerId = () => {
    while (usedIds.has(`seg_${fillerCounter}`)) {
      fillerCounter += 1;
    }
    const id = `seg_${fillerCounter}`;
    usedIds.add(id);
    fillerCounter += 1;
    return id;
  };

  if (!Array.isArray(rawSegments) || rawSegments.length === 0) {
    return [
      {
        id: "seg_0",
        t0: 0,
        t1: durationSec,
        regime: "auto",
        kept: false,
        source: defaultSource,
        revision: 1,
      },
    ];
  }

  /** @type {import("../index.d.ts").Segment[]} */
  const candidates = [];
  for (const item of rawSegments) {
    if (!item || typeof item !== "object") continue;
    const rawId = typeof item.id === "string" && item.id.length > 0 ? item.id : mintFillerId();
    if (usedIds.has(rawId)) {
      throw new EnsomiError("DUPLICATE_SEGMENT_ID", `Duplicate segment id "${rawId}"`);
    }
    const rawT0 = typeof item.t0 === "number" && Number.isFinite(item.t0) ? Math.max(0, item.t0) : 0;
    const rawT1 =
      typeof item.t1 === "number" && Number.isFinite(item.t1) ? Math.min(durationSec, item.t1) : durationSec;
    if (rawT1 <= rawT0 + eps) continue;
    usedIds.add(rawId);
    candidates.push(
      normalizeSegment(
        {
          ...item,
          id: rawId,
          t0: rawT0,
          t1: rawT1,
          source: item.source ?? defaultSource,
        },
        typeof item.revision === "number" ? item.revision : 1,
      ),
    );
  }

  if (candidates.length === 0) {
    return [
      {
        id: "seg_0",
        t0: 0,
        t1: durationSec,
        regime: "auto",
        kept: false,
        source: defaultSource,
        revision: 1,
      },
    ];
  }

  candidates.sort((a, b) => a.t0 - b.t0 || a.t1 - b.t1 || a.id.localeCompare(b.id));

  /** @type {import("../index.d.ts").Segment[]} */
  const partition = [];
  let cursor = 0;

  for (const seg of candidates) {
    let t0 = seg.t0;
    let t1 = Math.min(durationSec, seg.t1);

    if (Math.abs(t0 - cursor) <= 1e-6) {
      t0 = cursor;
    } else if (t0 > cursor) {
      partition.push({
        id: mintFillerId(),
        t0: cursor,
        t1: t0,
        regime: "auto",
        kept: false,
        source: defaultSource,
        revision: 1,
      });
      cursor = t0;
    } else {
      t0 = cursor;
    }

    if (t1 <= t0 + eps) {
      continue;
    }

    partition.push({
      ...seg,
      t0,
      t1,
    });
    cursor = t1;
  }

  if (partition.length === 0) {
    return [
      {
        id: "seg_0",
        t0: 0,
        t1: durationSec,
        regime: "auto",
        kept: false,
        source: defaultSource,
        revision: 1,
      },
    ];
  }

  const last = partition[partition.length - 1];
  if (Math.abs(durationSec - last.t1) <= 1e-6) {
    partition[partition.length - 1] = { ...last, t1: durationSec };
  } else if (last.t1 < durationSec) {
    partition.push({
      id: mintFillerId(),
      t0: last.t1,
      t1: durationSec,
      regime: "auto",
      kept: false,
      source: defaultSource,
      revision: 1,
    });
  }

  partition[0] = { ...partition[0], t0: 0 };
  partition[partition.length - 1] = { ...partition[partition.length - 1], t1: durationSec };
  return partition;
}

/**
 * Validate and normalize a Span entity (`Span ⊆ Segment` enforced by caller against `segments`).
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultRevision=1]
 * @returns {import("../index.d.ts").Span}
 */
export function normalizeSpan(raw, defaultRevision = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SPAN", "Span must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_SPAN", "Span id must be a non-empty string");
  }
  if (typeof raw.segmentId !== "string" || raw.segmentId.length === 0) {
    throw new EnsomiError("INVALID_SPAN", "Span segmentId must be a non-empty string");
  }
  if (typeof raw.t0 !== "number" || !Number.isFinite(raw.t0) || raw.t0 < 0) {
    throw new EnsomiError("INVALID_SPAN", `Span t0 must be a non-negative finite number, got ${raw.t0}`);
  }
  if (typeof raw.t1 !== "number" || !Number.isFinite(raw.t1) || raw.t1 <= raw.t0) {
    throw new EnsomiError("INVALID_SPAN", `Span t1 (${raw.t1}) must be > t0 (${raw.t0})`);
  }
  if (!SPAN_KIND_SET.has(/** @type {string} */ (raw.kind))) {
    throw new EnsomiError("INVALID_SPAN", `Invalid span kind: ${raw.kind}`);
  }
  const source = raw.source ?? "draft";
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (source))) {
    throw new EnsomiError("INVALID_SPAN", `Invalid span source: ${source}`);
  }
  const revision = assertPositiveRevision(
    raw.revision === undefined ? defaultRevision : raw.revision,
    "Span",
  );
  /** @type {import("../index.d.ts").Span} */
  const span = {
    id: raw.id,
    segmentId: raw.segmentId,
    t0: Object.is(raw.t0, -0) ? 0 : raw.t0,
    t1: raw.t1,
    kind: /** @type {import("../index.d.ts").SpanKind} */ (raw.kind),
    source: /** @type {import("../index.d.ts").EntitySource} */ (source),
    revision,
  };
  if (raw.priority !== undefined) {
    if (!SPAN_PRIORITY_SET.has(/** @type {string} */ (raw.priority))) {
      throw new EnsomiError("INVALID_SPAN", `Invalid span priority: ${raw.priority}`);
    }
    span.priority = /** @type {import("../index.d.ts").SpanPriority} */ (raw.priority);
  }
  if (raw.reason !== undefined) {
    if (typeof raw.reason !== "string") {
      throw new EnsomiError("INVALID_SPAN", "Span reason must be a string when provided");
    }
    span.reason = raw.reason;
  }
  if (raw.score !== undefined) {
    if (typeof raw.score !== "number" || !Number.isFinite(raw.score)) {
      throw new EnsomiError("INVALID_SPAN", "Span score must be a finite number when provided");
    }
    span.score = raw.score;
  }
  return span;
}

/**
 * Validate and normalize a Beat entity.
 * Any Beat with `source === "user"` acts directly as a soft beat guide cue.
 *
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultRevision=1]
 * @returns {import("../index.d.ts").Beat}
 */
export function normalizeBeat(raw, defaultRevision = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_BEAT", "Beat must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_BEAT", "Beat id must be a non-empty string");
  }
  if (typeof raw.timeSec !== "number" || !Number.isFinite(raw.timeSec) || raw.timeSec < 0) {
    throw new EnsomiError(
      "INVALID_BEAT",
      `Beat timeSec must be a non-negative finite number, got ${raw.timeSec}`,
    );
  }
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (raw.source))) {
    throw new EnsomiError("INVALID_BEAT", `Invalid beat source: ${raw.source}`);
  }
  const revision = assertPositiveRevision(
    raw.revision === undefined ? defaultRevision : raw.revision,
    "Beat",
  );
  /** @type {import("../index.d.ts").Beat} */
  const beat = {
    id: raw.id,
    timeSec: Object.is(raw.timeSec, -0) ? 0 : raw.timeSec,
    source: /** @type {import("../index.d.ts").EntitySource} */ (raw.source),
    revision,
  };
  if (raw.snapEventId !== undefined && raw.snapEventId !== null) {
    if (typeof raw.snapEventId !== "string" || raw.snapEventId.length === 0) {
      throw new EnsomiError("INVALID_BEAT", "Beat snapEventId must be a non-empty string");
    }
    beat.snapEventId = raw.snapEventId;
  }
  if (raw.snapFamily !== undefined && raw.snapFamily !== null) {
    if (!SNAP_FAMILY_SET.has(/** @type {string} */ (raw.snapFamily))) {
      throw new EnsomiError("INVALID_BEAT", `Invalid beat snapFamily: ${raw.snapFamily}`);
    }
    beat.snapFamily = /** @type {import("../index.d.ts").SnapFamily} */ (raw.snapFamily);
  }
  if (raw.snapRole !== undefined && raw.snapRole !== null) {
    if (!SNAP_ROLE_SET.has(/** @type {string} */ (raw.snapRole))) {
      throw new EnsomiError("INVALID_BEAT", `Invalid beat snapRole: ${raw.snapRole}`);
    }
    beat.snapRole = /** @type {import("../index.d.ts").SnapRole} */ (raw.snapRole);
  }
  if (raw.confidence !== undefined) {
    beat.confidence = clampUnit(/** @type {number} */ (raw.confidence));
  }
  return beat;
}

/**
 * Validate and normalize a SoundAnchor entity.
 * Any SoundAnchor with `source === "user"` acts directly as a soft sound observation cue.
 *
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultRevision=1]
 * @returns {import("../index.d.ts").SoundAnchor}
 */
export function normalizeSoundAnchor(raw, defaultRevision = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SOUND", "SoundAnchor must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_SOUND", "SoundAnchor id must be a non-empty string");
  }
  if (typeof raw.timeSec !== "number" || !Number.isFinite(raw.timeSec) || raw.timeSec < 0) {
    throw new EnsomiError(
      "INVALID_SOUND",
      `SoundAnchor timeSec must be a non-negative finite number, got ${raw.timeSec}`,
    );
  }
  if (!ENTITY_SOURCE_SET.has(/** @type {string} */ (raw.source))) {
    throw new EnsomiError("INVALID_SOUND", `Invalid sound source: ${raw.source}`);
  }
  const revision = assertPositiveRevision(
    raw.revision === undefined ? defaultRevision : raw.revision,
    "SoundAnchor",
  );
  /** @type {import("../index.d.ts").SoundAnchor} */
  const sound = {
    id: raw.id,
    timeSec: Object.is(raw.timeSec, -0) ? 0 : raw.timeSec,
    source: /** @type {import("../index.d.ts").EntitySource} */ (raw.source),
    revision,
  };
  if (raw.strength !== undefined) {
    sound.strength = clampUnit(/** @type {number} */ (raw.strength));
  }
  if (raw.family !== undefined) {
    if (!SNAP_FAMILY_SET.has(/** @type {string} */ (raw.family))) {
      throw new EnsomiError("INVALID_SOUND", `Invalid sound family: ${raw.family}`);
    }
    sound.family = /** @type {import("../index.d.ts").SnapFamily} */ (raw.family);
  }
  if (raw.role !== undefined) {
    if (!SNAP_ROLE_SET.has(/** @type {string} */ (raw.role))) {
      throw new EnsomiError("INVALID_SOUND", `Invalid sound role: ${raw.role}`);
    }
    sound.role = /** @type {import("../index.d.ts").SnapRole} */ (raw.role);
  }
  if (raw.sigmaMs !== undefined) {
    if (typeof raw.sigmaMs !== "number" || !Number.isFinite(raw.sigmaMs) || raw.sigmaMs < 0) {
      throw new EnsomiError("INVALID_SOUND", `SoundAnchor sigmaMs must be >= 0, got ${raw.sigmaMs}`);
    }
    sound.sigmaMs = raw.sigmaMs;
  }
  return sound;
}

/**
 * Validate and normalize a standalone UnbeatCue entity (`source: "user"`).
 * @param {Record<string, unknown>} raw
 * @param {number} [defaultRevision=1]
 * @returns {import("../index.d.ts").UnbeatCue}
 */
export function normalizeUnbeatCue(raw, defaultRevision = 1) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_UNBEAT", "UnbeatCue must be an object");
  }
  if (typeof raw.id !== "string" || raw.id.length === 0) {
    throw new EnsomiError("INVALID_UNBEAT", "UnbeatCue id must be a non-empty string");
  }
  if (typeof raw.timeSec !== "number" || !Number.isFinite(raw.timeSec) || raw.timeSec < 0) {
    throw new EnsomiError(
      "INVALID_UNBEAT",
      `UnbeatCue timeSec must be a non-negative finite number, got ${raw.timeSec}`,
    );
  }
  if (raw.source !== undefined && raw.source !== "user") {
    throw new EnsomiError("INVALID_UNBEAT", `UnbeatCue source must be "user", got ${raw.source}`);
  }
  const revision = assertPositiveRevision(
    raw.revision === undefined ? defaultRevision : raw.revision,
    "UnbeatCue",
  );
  return {
    id: raw.id,
    timeSec: Object.is(raw.timeSec, -0) ? 0 : raw.timeSec,
    source: "user",
    revision,
  };
}

/**
 * Validate and normalize a verifiable SnapCandidate.
 * When `querySec` is provided, enforces exact signed `deltaMs = (targetSec - querySec) * 1000`.
 *
 * @param {Record<string, unknown>} raw
 * @param {number} [querySec]
 * @returns {import("../index.d.ts").SnapCandidate}
 */
export function normalizeSnapCandidate(raw, querySec) {
  if (!raw || typeof raw !== "object") {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", "SnapCandidate must be an object");
  }
  if (typeof raw.eventId !== "string" || raw.eventId.length === 0) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", "SnapCandidate eventId must be a non-empty string");
  }
  if (typeof raw.targetSec !== "number" || !Number.isFinite(raw.targetSec) || raw.targetSec < 0) {
    throw new EnsomiError(
      "INVALID_SNAP_CANDIDATE",
      `SnapCandidate targetSec must be a non-negative finite number, got ${raw.targetSec}`,
    );
  }
  if (!SNAP_FAMILY_SET.has(/** @type {string} */ (raw.family))) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", `Invalid snap family: ${raw.family}`);
  }
  if (!SNAP_ROLE_SET.has(/** @type {string} */ (raw.role))) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", `Invalid snap role: ${raw.role}`);
  }
  if (typeof raw.label !== "string" || raw.label.trim().length === 0) {
    throw new EnsomiError("INVALID_SNAP_CANDIDATE", "SnapCandidate label must be a non-empty string");
  }
  const targetSec = Object.is(raw.targetSec, -0) ? 0 : raw.targetSec;
  let deltaMs;
  if (typeof querySec === "number" && Number.isFinite(querySec)) {
    deltaMs = roundTime((targetSec - querySec) * 1000, 4);
  } else {
    if (typeof raw.deltaMs !== "number" || !Number.isFinite(raw.deltaMs)) {
      throw new EnsomiError("INVALID_SNAP_CANDIDATE", `Invalid deltaMs: ${raw.deltaMs}`);
    }
    deltaMs = Object.is(raw.deltaMs, -0) ? 0 : raw.deltaMs;
  }
  /** @type {import("../index.d.ts").SnapCandidate} */
  const candidate = {
    eventId: raw.eventId,
    targetSec,
    deltaMs,
    family: /** @type {import("../index.d.ts").SnapFamily} */ (raw.family),
    role: /** @type {import("../index.d.ts").SnapRole} */ (raw.role),
    label: raw.label,
    salience: clampUnit(/** @type {number} */ (raw.salience)),
  };
  if (raw.sigmaMs !== undefined) {
    if (typeof raw.sigmaMs !== "number" || !Number.isFinite(raw.sigmaMs) || raw.sigmaMs < 0) {
      throw new EnsomiError("INVALID_SNAP_CANDIDATE", `Invalid sigmaMs: ${raw.sigmaMs}`);
    }
    candidate.sigmaMs = raw.sigmaMs;
  }
  return candidate;
}

/**
 * Validate an append-only trace event envelope.
 * @param {unknown} raw
 * @returns {import("../index.d.ts").TraceEvent}
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
  return /** @type {import("../index.d.ts").TraceEvent} */ (ev);
}

/**
 * Validate structural shape of a PatchOffer.
 * @param {unknown} raw
 */
export function assertOfferShape(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer must be an object");
  }
  const offer = /** @type {Record<string, unknown>} */ (raw);
  if (typeof offer.id !== "string" || offer.id.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer id must be a non-empty string");
  }
  if (typeof offer.batchId !== "string" || offer.batchId.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer batchId must be a non-empty string");
  }
  if (typeof offer.providerId !== "string" || offer.providerId.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer providerId must be a non-empty string");
  }
  if (!PROVIDER_KIND_SET.has(/** @type {string} */ (offer.providerKind))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid providerKind: ${offer.providerKind}`);
  }
  if (!OFFER_SCOPE_KIND_SET.has(/** @type {string} */ (offer.scopeKind))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer scopeKind: ${offer.scopeKind}`);
  }
  if (typeof offer.title !== "string" || offer.title.trim().length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer title must be a non-empty string");
  }
  if (!Array.isArray(offer.writeScopes) || offer.writeScopes.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer writeScopes must be a non-empty array");
  }
  for (const ws of offer.writeScopes) {
    if (!ws || typeof ws !== "object" || typeof ws.segmentId !== "string" || ws.segmentId.length === 0) {
      throw new EnsomiError("INVALID_OFFER", "Each writeScope requires a non-empty segmentId");
    }
    assertRange(ws);
  }
  if (offer.readScope !== undefined && offer.readScope !== "song") {
    assertRange(/** @type {{ t0: number, t1: number }} */ (offer.readScope));
  }
  if (offer.optionIndex !== undefined) {
    if (typeof offer.optionIndex !== "number" || !Number.isInteger(offer.optionIndex) || offer.optionIndex < 0) {
      throw new EnsomiError("INVALID_OFFER", `Invalid optionIndex: ${offer.optionIndex}`);
    }
  }
  if (offer.optionCount !== undefined) {
    if (typeof offer.optionCount !== "number" || !Number.isInteger(offer.optionCount) || offer.optionCount < 1) {
      throw new EnsomiError("INVALID_OFFER", `Invalid optionCount: ${offer.optionCount}`);
    }
  }
  if (offer.status !== undefined && !OFFER_STATUS_SET.has(/** @type {string} */ (offer.status))) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer status: ${offer.status}`);
  }
  if (!offer.ops || typeof offer.ops !== "object" || Array.isArray(offer.ops)) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer ops must be an object");
  }
  if (
    offer.expectedRevisions !== undefined &&
    (typeof offer.expectedRevisions !== "object" ||
      offer.expectedRevisions === null ||
      Array.isArray(offer.expectedRevisions))
  ) {
    throw new EnsomiError("INVALID_OFFER", "PatchOffer expectedRevisions must be a plain object");
  }
}
