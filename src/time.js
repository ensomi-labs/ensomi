/**
 * Wire timebase utilities: integer microseconds (`atUs` / `[startUs, endUs)`),
 * boundary conversions (`secToUs`, `usToSec`, `sampleToUs`, `usToNearestSample`),
 * domain assertions, and half-open interval geometry.
 */

import { EnsomiError } from "./jcs.js";

export const US_PER_SEC = 1_000_000;
export const US_PER_MS = 1_000;

/**
 * Normalize `-0` to `0` and check whether `value` is a non-negative safe integer.
 *
 * @param {unknown} value
 * @returns {value is number}
 */
export function isNonNegSafeInt(value) {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

/**
 * Validate a non-negative safe integer in microseconds (`0 <= v <= Number.MAX_SAFE_INTEGER`).
 *
 * @param {unknown} value
 * @param {string} [label="value"]
 * @param {string} [errorCode="INVALID_TIME_US"]
 * @returns {number}
 */
export function assertNonNegSafeIntUs(value, label = "value", errorCode = "INVALID_TIME_US") {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new EnsomiError(
      errorCode,
      `${label} must be a non-negative safe integer in microseconds, got ${String(value)}`,
    );
  }
  return value === 0 ? 0 : value;
}

/**
 * Validate a signed safe integer in microseconds (e.g. `deltaUs`).
 *
 * @param {unknown} value
 * @param {string} [label="deltaUs"]
 * @param {string} [errorCode="INVALID_TIME_US"]
 * @returns {number}
 */
export function assertSafeIntUs(value, label = "deltaUs", errorCode = "INVALID_TIME_US") {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) {
    throw new EnsomiError(
      errorCode,
      `${label} must be a safe integer in microseconds, got ${String(value)}`,
    );
  }
  return value === 0 ? 0 : value;
}

/**
 * Enforce point domain `0 <= atUs < durationUs` (`Beat`, `Unbeat`).
 * When `durationUs === undefined`, enforces `atUs >= 0` safe integer.
 *
 * @param {unknown} atUs
 * @param {number | undefined} durationUs
 * @param {string} [label="atUs"]
 * @returns {number}
 */
export function assertPointUs(atUs, durationUs, label = "atUs") {
  const v = assertNonNegSafeIntUs(atUs, label);
  if (durationUs !== undefined) {
    const dur = assertNonNegSafeIntUs(durationUs, "durationUs", "INVALID_DURATION");
    if (v >= dur) {
      throw new EnsomiError(
        "OUT_OF_BOUNDS",
        `${label} (${v} us) must satisfy 0 <= ${label} < durationUs (${dur} us)`,
        { atUs: v, durationUs: dur },
      );
    }
  }
  return v;
}

/**
 * Enforce strict interior point domain `0 < atUs < durationUs` (interior `Boundary`).
 *
 * @param {unknown} atUs
 * @param {number | undefined} durationUs
 * @param {string} [label="boundary.atUs"]
 * @returns {number}
 */
export function assertInteriorPointUs(atUs, durationUs, label = "boundary.atUs") {
  const v = assertNonNegSafeIntUs(atUs, label);
  if (v <= 0) {
    throw new EnsomiError(
      "OUT_OF_BOUNDS",
      `${label} (${v} us) must be strictly greater than 0`,
      { atUs: v },
    );
  }
  if (durationUs !== undefined) {
    const dur = assertNonNegSafeIntUs(durationUs, "durationUs", "INVALID_DURATION");
    if (v >= dur) {
      throw new EnsomiError(
        "OUT_OF_BOUNDS",
        `${label} (${v} us) must satisfy 0 < ${label} < durationUs (${dur} us)`,
        { atUs: v, durationUs: dur },
      );
    }
  }
  return v;
}

/**
 * Enforce non-empty interval domain `0 <= startUs < endUs <= durationUs` (`Segment`, `Span`, `Sound`, `RangeUs`).
 *
 * @param {unknown} startUs
 * @param {unknown} endUs
 * @param {number | undefined} durationUs
 * @param {string} [label="interval"]
 * @param {string} [errorCode="INVALID_RANGE"]
 * @returns {{ startUs: number, endUs: number }}
 */
export function assertIntervalUs(
  startUs,
  endUs,
  durationUs,
  label = "interval",
  errorCode = "INVALID_RANGE",
) {
  const s = assertNonNegSafeIntUs(startUs, `${label}.startUs`, errorCode);
  const e = assertNonNegSafeIntUs(endUs, `${label}.endUs`, errorCode);
  if (e <= s) {
    throw new EnsomiError(
      errorCode,
      `${label}.endUs (${e} us) must be strictly greater than startUs (${s} us)`,
      { startUs: s, endUs: e },
    );
  }
  if (durationUs !== undefined) {
    const dur = assertNonNegSafeIntUs(durationUs, "durationUs", "INVALID_DURATION");
    if (e > dur) {
      throw new EnsomiError(
        "OUT_OF_BOUNDS",
        `${label} [${s}, ${e}) exceeds durationUs (${dur} us)`,
        { startUs: s, endUs: e, durationUs: dur },
      );
    }
  }
  return { startUs: s, endUs: e };
}

/**
 * Validate a non-empty half-open `RangeUs` (`{ startUs, endUs }`, `0 <= startUs < endUs <= durationUs`).
 *
 * @param {unknown} range
 * @param {number} [durationUs]
 * @returns {{ startUs: number, endUs: number }}
 */
export function assertRangeUs(range, durationUs) {
  if (!range || typeof range !== "object") {
    throw new EnsomiError("INVALID_RANGE", "Range must be an object with { startUs, endUs }");
  }
  const r = /** @type {Record<string, unknown>} */ (range);
  return assertIntervalUs(r.startUs, r.endUs, durationUs, "range", "INVALID_RANGE");
}

/**
 * Convert floating-point seconds to safe integer microseconds (`Math.round(sec * 1_000_000)`).
 * Supports signed finite values (e.g. `deltaSec`) while normalizing `-0` to `0`.
 *
 * @param {number} sec
 * @returns {number}
 */
export function secToUs(sec) {
  if (typeof sec !== "number" || !Number.isFinite(sec)) {
    throw new EnsomiError("INVALID_TIME", `secToUs expects a finite number, got ${String(sec)}`);
  }
  const us = Math.round(sec * US_PER_SEC);
  if (!Number.isSafeInteger(us)) {
    throw new EnsomiError(
      "INVALID_TIME",
      `secToUs overflowed safe integer range for ${String(sec)}`,
    );
  }
  return us === 0 ? 0 : us;
}

/**
 * Convert safe integer microseconds to floating-point seconds (`us / 1_000_000`).
 *
 * @param {number} us
 * @returns {number}
 */
export function usToSec(us) {
  const valid = assertSafeIntUs(us, "us", "INVALID_TIME_US");
  const sec = valid / US_PER_SEC;
  return sec === 0 ? 0 : sec;
}

/**
 * Convert floating-point milliseconds to safe integer microseconds (`Math.round(ms * 1_000)`).
 *
 * @param {number} ms
 * @returns {number}
 */
export function msToUs(ms) {
  if (typeof ms !== "number" || !Number.isFinite(ms)) {
    throw new EnsomiError("INVALID_TIME", `msToUs expects a finite number, got ${String(ms)}`);
  }
  const us = Math.round(ms * US_PER_MS);
  if (!Number.isSafeInteger(us)) {
    throw new EnsomiError("INVALID_TIME", `msToUs overflowed safe integer range for ${String(ms)}`);
  }
  return us === 0 ? 0 : us;
}

/**
 * Convert safe integer microseconds to floating-point milliseconds (`us / 1_000`).
 *
 * @param {number} us
 * @returns {number}
 */
export function usToMs(us) {
  const valid = assertSafeIntUs(us, "us", "INVALID_TIME_US");
  const ms = valid / US_PER_MS;
  return ms === 0 ? 0 : ms;
}

/**
 * Single-rounding sample-index -> microsecond conversion (`Math.round((sampleIndex * 1_000_000) / sampleRateHz)`).
 * Zero cumulative drift across long audio files.
 *
 * @param {number} sampleIndex
 * @param {number} sampleRateHz
 * @returns {number}
 */
export function sampleToUs(sampleIndex, sampleRateHz) {
  if (typeof sampleIndex !== "number" || !Number.isSafeInteger(sampleIndex) || sampleIndex < 0) {
    throw new EnsomiError(
      "INVALID_SAMPLE_INDEX",
      `sampleIndex must be a non-negative safe integer, got ${String(sampleIndex)}`,
    );
  }
  if (typeof sampleRateHz !== "number" || !Number.isFinite(sampleRateHz) || sampleRateHz <= 0) {
    throw new EnsomiError(
      "INVALID_SAMPLE_RATE",
      `sampleRateHz must be a positive finite number, got ${String(sampleRateHz)}`,
    );
  }
  // Use BigInt intermediate if `sampleIndex * 1_000_000` could exceed MAX_SAFE_INTEGER
  // (e.g. > 9,007,199,254 samples, ~52 hours at 48 kHz) while matching exact nearest-integer half-up rounding!
  const prod = sampleIndex * US_PER_SEC;
  let us;
  if (Number.isSafeInteger(prod)) {
    us = Math.round(prod / sampleRateHz);
  } else {
    us = Math.round((sampleIndex / sampleRateHz) * US_PER_SEC);
  }
  if (!Number.isSafeInteger(us) || us < 0) {
    throw new EnsomiError("INVALID_TIME_US", "sampleToUs result exceeded safe integer range");
  }
  return us === 0 ? 0 : us;
}

/**
 * Convert non-negative integer microseconds to nearest PCM sample frame index
 * (`Math.round((us * sampleRateHz) / 1_000_000)`).
 *
 * @param {number} us
 * @param {number} sampleRateHz
 * @returns {number}
 */
export function usToNearestSample(us, sampleRateHz) {
  const validUs = assertNonNegSafeIntUs(us, "us");
  if (typeof sampleRateHz !== "number" || !Number.isFinite(sampleRateHz) || sampleRateHz <= 0) {
    throw new EnsomiError(
      "INVALID_SAMPLE_RATE",
      `sampleRateHz must be a positive finite number, got ${String(sampleRateHz)}`,
    );
  }
  const prod = validUs * sampleRateHz;
  const sample = Number.isSafeInteger(prod)
    ? Math.round(prod / US_PER_SEC)
    : Math.round((validUs / US_PER_SEC) * sampleRateHz);
  if (!Number.isSafeInteger(sample) || sample < 0) {
    throw new EnsomiError(
      "INVALID_SAMPLE_INDEX",
      "usToNearestSample result exceeded safe integer range",
    );
  }
  return sample === 0 ? 0 : sample;
}

/**
 * Format a microsecond timestamp as musical timecode `M:SS.mmm`.
 *
 * @param {number} atUs
 * @returns {string}
 */
export function formatTimeUs(atUs) {
  const clampedUs =
    typeof atUs === "number" && Number.isFinite(atUs) ? Math.max(0, Math.round(atUs)) : 0;
  const totalMs = Math.round(clampedUs / 1000);
  const minutes = Math.floor(totalMs / 60000);
  const remMs = totalMs - minutes * 60000;
  const seconds = Math.floor(remMs / 1000);
  const millis = remMs - seconds * 1000;
  return `${minutes}:${String(seconds).padStart(2, "0")}.${String(millis).padStart(3, "0")}`;
}

/**
 * Check whether `atUs` falls inside half-open `[range.startUs, range.endUs)`.
 *
 * @param {{ startUs: number, endUs: number }} range
 * @param {number} atUs
 * @returns {boolean}
 */
export function rangeContainsUs(range, atUs) {
  if (!range || typeof atUs !== "number" || !Number.isSafeInteger(atUs)) return false;
  return atUs >= range.startUs && atUs < range.endUs;
}

/**
 * Check whether two half-open intervals `[a.startUs, a.endUs)` and `[b.startUs, b.endUs)` overlap.
 * Accepts `"song"` on either side as full-track overlap.
 *
 * @param {{ startUs: number, endUs: number } | "song"} a
 * @param {{ startUs: number, endUs: number } | "song"} b
 * @returns {boolean}
 */
export function rangesOverlapUs(a, b) {
  if (!a || !b) return false;
  if (a === "song" || b === "song") return true;
  return a.startUs < b.endUs && b.startUs < a.endUs;
}

/**
 * Merge overlapping or touching `[startUs, endUs)` integer intervals into sorted disjoint ranges.
 *
 * @param {readonly Array<{ startUs: number, endUs: number }>} ranges
 * @returns {Array<{ startUs: number, endUs: number }>}
 */
export function mergeRangesUs(ranges) {
  const valid = ranges
    .filter((r) => r && r.endUs > r.startUs)
    .map((r) => ({ startUs: r.startUs, endUs: r.endUs }))
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs);
  if (valid.length <= 1) return valid;
  /** @type {Array<{ startUs: number, endUs: number }>} */
  const merged = [valid[0]];
  for (let i = 1; i < valid.length; i += 1) {
    const prev = merged[merged.length - 1];
    const curr = valid[i];
    if (curr.startUs <= prev.endUs) {
      if (curr.endUs > prev.endUs) prev.endUs = curr.endUs;
    } else {
      merged.push(curr);
    }
  }
  return merged;
}

/**
 * Subtract `[cutStartUs, cutEndUs)` from a list of `[startUs, endUs)` intervals.
 *
 * @param {readonly Array<{ startUs: number, endUs: number }>} ranges
 * @param {number} cutStartUs
 * @param {number} cutEndUs
 * @returns {Array<{ startUs: number, endUs: number }>}
 */
export function subtractRangeUs(ranges, cutStartUs, cutEndUs) {
  if (cutEndUs <= cutStartUs) return ranges.map((r) => ({ startUs: r.startUs, endUs: r.endUs }));
  /** @type {Array<{ startUs: number, endUs: number }>} */
  const out = [];
  for (const r of ranges) {
    if (r.endUs <= cutStartUs || r.startUs >= cutEndUs) {
      out.push({ startUs: r.startUs, endUs: r.endUs });
      continue;
    }
    if (r.startUs < cutStartUs) {
      out.push({ startUs: r.startUs, endUs: cutStartUs });
    }
    if (r.endUs > cutEndUs) {
      out.push({ startUs: cutEndUs, endUs: r.endUs });
    }
  }
  return out;
}
