/**
 * Pure format-neutral derived views & full-footprint interval musical digest:
 *  - `computeGridFit(snapshot, options?)`
 *  - `projectTempoMap(snapshot, options?)` (freezes boundary conditions at `Approval` `keep` edges!)
 *  - `rankInboxItems(snapshot, options?)` (excludes `keep`-approved intervals)
 *  - `computeIntervalMusicalDigest(snapshot, startUs, endUs)`
 *
 * Uses integer microseconds (`atUs` / `startUs` / `endUs`) and `null` (NEVER `Infinity`)
 * so every output is 100% RFC 8785 (JCS) canonicalizable.
 */

import { compileConstraints } from "./constraints.js";
import { compareCodeUnits, EnsomiError, jcsSha256Hex } from "./jcs.js";
import {
  assertIntervalUs,
  assertNonNegSafeIntUs,
  formatTimeUs,
  mergeRangesUs,
  rangeContainsUs,
  rangesOverlapUs,
  subtractRangeUs,
} from "./time.js";
import { deepFreeze, SNAP_ROLES } from "./types.js";

const SNAP_ROLE_SET = new Set(SNAP_ROLES);
const DEFAULT_DIVISORS = Object.freeze([1, 2, 3, 4, 6]);
const DEFAULT_GRID_TOL_US = 12_000;
const DEFAULT_MAX_INTERPOLATED_IBI_US = 2_500_000;
const DEFAULT_STEADY_JITTER_TOL_US = 15_000;
const DEFAULT_FREE_JITTER_TOL_US = 2_000;
const DEFAULT_FALLBACK_PERIOD_US = 500_000; // 120 BPM

/**
 * @template T
 * @param {Map<string, T> | readonly T[] | Iterable<T> | undefined} coll
 * @returns {T[]}
 */
function valuesArray(coll) {
  if (!coll) return [];
  if (Array.isArray(coll)) return coll;
  if (coll instanceof Map) return Array.from(coll.values());
  return Array.from(coll);
}

/**
 * Find enclosing segment (`seg.startUs <= atUs < seg.endUs`, or trailing endpoint).
 * @param {readonly import("../index.d.ts").Segment[]} sortedSegments
 * @param {number} atUs
 * @returns {import("../index.d.ts").Segment | null}
 */
function findSegmentAtUs(sortedSegments, atUs) {
  if (sortedSegments.length === 0) return null;
  let lo = 0;
  let hi = sortedSegments.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const seg = sortedSegments[mid];
    if (atUs < seg.startUs) {
      hi = mid - 1;
    } else if (atUs >= seg.endUs) {
      lo = mid + 1;
    } else {
      return seg;
    }
  }
  const last = sortedSegments[sortedSegments.length - 1];
  if (atUs === last.endUs) return last;
  return null;
}

/**
 * Rightmost beat index with `beat.atUs <= atUs`.
 * @param {readonly import("../index.d.ts").Beat[]} sortedBeats
 * @param {number} atUs
 */
function findLeftBeatIndex(sortedBeats, atUs) {
  let lo = 0;
  let hi = sortedBeats.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (sortedBeats[mid].atUs <= atUs) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/**
 * Select the anchor point on `sound` matching `anchorSelector` (`"primary" | SnapRole`).
 *
 * @param {import("../index.d.ts").Sound} sound
 * @param {"primary" | import("../index.d.ts").SnapRole} anchorSelector
 * @returns {import("../index.d.ts").SoundAnchorPoint | null}
 */
function selectSoundAnchor(sound, anchorSelector) {
  if (!sound || !Array.isArray(sound.anchors) || sound.anchors.length === 0) return null;
  if (anchorSelector === "primary") {
    return sound.anchors.find((a) => a.id === sound.primaryAnchorId) ?? sound.anchors[0];
  }
  return sound.anchors.find((a) => a.role === anchorSelector) ?? null;
}

/**
 * Compute per-sound grid alignment against `beats` and `segments`.
 * - Supports selecting `"primary"` or a specific `SnapRole` anchor on spanned `Sound`s.
 * - Supports filtering by `classId`, `voiceGroupId`, and `verifiedOnly`.
 * - Honors `Segment` boundaries, `break` regimes, and `> maxInterpolatedIbiUs` (`2_500_000 µs`) gaps.
 * - Uses `null` (NEVER `Infinity`) when no beat applies.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshot
 * @param {import("../index.d.ts").GridFitOptions} [options]
 * @returns {import("../index.d.ts").GridFitItem[]}
 */
export function computeGridFit(snapshot, options = {}) {
  const anchorSelector = options.anchorSelector ?? "primary";
  if (anchorSelector !== "primary" && !SNAP_ROLE_SET.has(anchorSelector)) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `computeGridFit anchorSelector must be "primary" or a SnapRole, got "${String(anchorSelector)}"`,
    );
  }
  const divisors = [...new Set(options.divisors ?? DEFAULT_DIVISORS)]
    .filter((d) => Number.isInteger(d) && d >= 1)
    .sort((a, b) => a - b);
  const tolUs =
    options.tolUs !== undefined
      ? assertNonNegSafeIntUs(options.tolUs, "options.tolUs", "INVALID_OPTIONS")
      : DEFAULT_GRID_TOL_US;
  const maxInterpolatedIbiUs =
    options.maxInterpolatedIbiUs !== undefined
      ? assertNonNegSafeIntUs(
          options.maxInterpolatedIbiUs,
          "options.maxInterpolatedIbiUs",
          "INVALID_OPTIONS",
        )
      : DEFAULT_MAX_INTERPOLATED_IBI_US;

  const sortedSegments = valuesArray(snapshot?.segments)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const rawBeats = valuesArray(snapshot?.beats)
    .slice()
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const rawSounds = valuesArray(snapshot?.sounds)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  /** @type {import("../index.d.ts").Beat[]} */
  const activeBeats = [];
  /** @type {Map<string, import("../index.d.ts").Beat[]>} */
  const beatsBySegId = new Map();

  if (sortedSegments.length > 0) {
    for (let i = 0; i < rawBeats.length; i += 1) {
      const b = rawBeats[i];
      const seg = findSegmentAtUs(sortedSegments, b.atUs);
      if (!seg || seg.regime === "break") continue;
      activeBeats.push(b);
      let list = beatsBySegId.get(seg.id);
      if (!list) {
        list = [];
        beatsBySegId.set(seg.id, list);
      }
      list.push(b);
    }
  } else {
    activeBeats.push(...rawBeats);
  }

  /** @type {Array<{ sound: import("../index.d.ts").Sound, anchor: import("../index.d.ts").SoundAnchorPoint }>} */
  const filtered = [];
  for (let i = 0; i < rawSounds.length; i += 1) {
    const s = rawSounds[i];
    if (options.verifiedOnly && !s.verdict) continue;
    if (options.classId !== undefined && s.classId !== options.classId) continue;
    if (options.voiceGroupId !== undefined && s.voiceGroupId !== options.voiceGroupId) continue;
    const anchor = selectSoundAnchor(s, anchorSelector);
    if (!anchor) continue;
    filtered.push({ sound: s, anchor });
  }
  filtered.sort((a, b) => a.anchor.atUs - b.anchor.atUs || compareCodeUnits(a.sound.id, b.sound.id));

  const minPartitionStart = sortedSegments.length > 0 ? sortedSegments[0].startUs : 0;
  const maxPartitionEnd =
    sortedSegments.length > 0
      ? sortedSegments[sortedSegments.length - 1].endUs
      : Number.MAX_SAFE_INTEGER;

  /** @type {import("../index.d.ts").GridFitItem[]} */
  const results = [];

  for (let i = 0; i < filtered.length; i += 1) {
    const { sound, anchor } = filtered[i];
    const atUs = anchor.atUs;

    if (sortedSegments.length > 0 && (atUs < minPartitionStart || atUs > maxPartitionEnd)) {
      continue;
    }

    const soundSeg = sortedSegments.length > 0 ? findSegmentAtUs(sortedSegments, atUs) : null;
    const candidateBeats =
      sortedSegments.length > 0
        ? soundSeg && soundSeg.regime !== "break"
          ? beatsBySegId.get(soundSeg.id) ?? []
          : []
        : activeBeats;

    if (
      candidateBeats.length === 0 ||
      (sortedSegments.length > 0 && (!soundSeg || soundSeg.regime === "break"))
    ) {
      results.push(
        deepFreeze({
          soundId: sound.id,
          anchorId: anchor.id,
          anchorRole: anchor.role,
          atUs,
          nearestBeatId: null,
          nearestBeatDistUs: null,
          nearestDivisor: null,
          slotAtUs: null,
          residualUs: null,
          status: "unexplained",
        }),
      );
      continue;
    }

    const leftIdx = findLeftBeatIndex(candidateBeats, atUs);
    const rightIdx = leftIdx + 1 < candidateBeats.length ? leftIdx + 1 : -1;

    let nearestBeat = candidateBeats[0];
    let nearestSignedUs = atUs - nearestBeat.atUs;
    let nearestDistUs = Math.abs(nearestSignedUs);

    for (const idx of [leftIdx, rightIdx]) {
      if (idx < 0) continue;
      const b = candidateBeats[idx];
      const signed = atUs - b.atUs;
      const dist = Math.abs(signed);
      if (dist < nearestDistUs) {
        nearestBeat = b;
        nearestSignedUs = signed;
        nearestDistUs = dist;
      }
    }

    if (divisors.includes(1) && nearestDistUs <= tolUs) {
      results.push(
        deepFreeze({
          soundId: sound.id,
          anchorId: anchor.id,
          anchorRole: anchor.role,
          atUs,
          nearestBeatId: nearestBeat.id,
          nearestBeatDistUs: nearestDistUs,
          nearestDivisor: 1,
          slotAtUs: nearestBeat.atUs,
          residualUs: nearestSignedUs === 0 ? 0 : nearestSignedUs,
          status: "on_grid",
        }),
      );
      continue;
    }

    /** @type {number | null} */
    let bestWithinDivisor = null;
    let bestWithinAbsErr = Number.MAX_SAFE_INTEGER;
    let bestWithinSignedUs = 0;
    let bestWithinSlotUs = nearestBeat.atUs;

    let bestOverallAbsErr = nearestDistUs;
    let bestOverallSignedUs = nearestSignedUs;
    let bestOverallSlotUs = nearestBeat.atUs;

    if (leftIdx >= 0 && rightIdx >= 0) {
      const b0 = candidateBeats[leftIdx];
      const b1 = candidateBeats[rightIdx];
      const intervalUs = b1.atUs - b0.atUs;
      if (intervalUs > 0 && intervalUs <= maxInterpolatedIbiUs) {
        for (let dIdx = 0; dIdx < divisors.length; dIdx += 1) {
          const d = divisors[dIdx];
          if (d === 1) continue;
          const pos = ((atUs - b0.atUs) / intervalUs) * d;
          const k = Math.round(pos);
          if (k <= 0 || k >= d) continue;
          const slotUs = Math.round(b0.atUs + (k * intervalUs) / d);
          const signedUs = atUs - slotUs;
          const errUs = Math.abs(signedUs);
          if (errUs < bestOverallAbsErr) {
            bestOverallAbsErr = errUs;
            bestOverallSignedUs = signedUs;
            bestOverallSlotUs = slotUs;
          }
          if (errUs <= tolUs) {
            if (bestWithinDivisor === null || errUs + 500 < bestWithinAbsErr) {
              bestWithinDivisor = d;
              bestWithinAbsErr = errUs;
              bestWithinSignedUs = signedUs;
              bestWithinSlotUs = slotUs;
            }
          }
        }
      }
    }

    if (bestWithinDivisor !== null) {
      results.push(
        deepFreeze({
          soundId: sound.id,
          anchorId: anchor.id,
          anchorRole: anchor.role,
          atUs,
          nearestBeatId: nearestBeat.id,
          nearestBeatDistUs: nearestDistUs,
          nearestDivisor: bestWithinDivisor,
          slotAtUs: bestWithinSlotUs,
          residualUs: bestWithinSignedUs === 0 ? 0 : bestWithinSignedUs,
          status: "subdivision",
        }),
      );
    } else {
      results.push(
        deepFreeze({
          soundId: sound.id,
          anchorId: anchor.id,
          anchorRole: anchor.role,
          atUs,
          nearestBeatId: nearestBeat.id,
          nearestBeatDistUs: nearestDistUs,
          nearestDivisor: null,
          slotAtUs: bestOverallSlotUs,
          residualUs: bestOverallSignedUs === 0 ? 0 : bestOverallSignedUs,
          status: "unexplained",
        }),
      );
    }
  }

  return results;
}

/**
 * Ordinary least-squares linear fit `atUs[i] ≈ t0 + i * period` over `i = 0..n-1`,
 * clamped so `t0Us >= minAtUs` (preventing steady runs in `[sliceStartUs, sliceEndUs)`
 * from ever projecting a `TempoPoint` earlier than `Math.max(sliceStartUs, prevSliceEndUs)`!).
 *
 * @param {readonly import("../index.d.ts").Beat[]} run
 * @param {number} [minAtUs=0]
 */
function fitSteadyLinearRunUs(run, minAtUs = 0) {
  const n = run.length;
  if (n < 2) {
    return {
      t0Us: Math.max(minAtUs, run[0].atUs),
      periodUs: DEFAULT_FALLBACK_PERIOD_US,
      maxResidualUs: 0,
    };
  }
  const meanI = (n - 1) / 2;
  let sumT = 0;
  for (let i = 0; i < n; i += 1) sumT += run[i].atUs;
  const meanT = sumT / n;

  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const di = i - meanI;
    num += di * (run[i].atUs - meanT);
    den += di * di;
  }
  const rawPeriod = den > 0 ? num / den : run[1].atUs - run[0].atUs;
  const periodUs = Math.max(1, Math.round(rawPeriod));
  const t0Us = Math.max(minAtUs, Math.round(meanT - rawPeriod * meanI));

  let maxResidualUs = 0;
  for (let i = 0; i < n; i += 1) {
    const pred = t0Us + i * periodUs;
    const errUs = Math.abs(run[i].atUs - pred);
    if (errUs > maxResidualUs) maxResidualUs = errUs;
  }
  return { t0Us, periodUs, maxResidualUs };
}

/**
 * Compute BPM rounded to 4 decimals from integer `periodUs > 0`.
 * @param {number} periodUs
 */
function periodUsToBpm(periodUs) {
  return Math.round((60_000_000 / Math.max(1, periodUs)) * 10000) / 10000;
}

/**
 * Collect merged active `keep`-approved intervals from `segments` and `spans`.
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @param {readonly import("../index.d.ts").Span[]} spans
 * @returns {Array<{ startUs: number, endUs: number }>}
 */
export function collectApprovedKeepIntervals(segments, spans) {
  /** @type {Array<{ startUs: number, endUs: number }>} */
  const raw = [];
  for (let i = 0; i < segments.length; i += 1) {
    const seg = segments[i];
    if (seg.approval) {
      raw.push({ startUs: seg.startUs, endUs: seg.endUs });
    }
  }
  for (let i = 0; i < spans.length; i += 1) {
    const sp = spans[i];
    if (sp.kind === "keep" && sp.approval) {
      raw.push({ startUs: sp.startUs, endUs: sp.endUs });
    }
  }
  return mergeRangesUs(raw);
}

/**
 * Format-neutral `TempoMap` projection honoring:
 *  - `Segment` boundaries, `break` gaps, and `> maxInterpolatedIbiUs` (`2_500_000 µs`) gaps
 *  - `free` / `ramp` / `rubato` kinematics
 *  - `Beat.structural.phraseReset`, `downbeat`, and `fermata` pivots
 *  - **Frozen boundary conditions at `Approval` (`keep`) edges** so edits outside any
 *    kept interval `[L, R)` cannot perturb projected `TempoPoint`s inside `[L, R)`!
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshot
 * @param {import("../index.d.ts").TempoMapOptions} [options]
 * @returns {import("../index.d.ts").TempoMap}
 */
export function projectTempoMap(snapshot, options = {}) {
  const defaultMeter = options.defaultMeter ?? 4;
  if (!Number.isInteger(defaultMeter) || defaultMeter < 1) {
    throw new EnsomiError("INVALID_OPTIONS", `defaultMeter must be integer >= 1, got ${defaultMeter}`);
  }
  const steadyJitterTolUs =
    options.steadyJitterTolUs !== undefined
      ? assertNonNegSafeIntUs(options.steadyJitterTolUs, "steadyJitterTolUs", "INVALID_OPTIONS")
      : DEFAULT_STEADY_JITTER_TOL_US;
  const freeJitterTolUs =
    options.freeJitterTolUs !== undefined
      ? assertNonNegSafeIntUs(options.freeJitterTolUs, "freeJitterTolUs", "INVALID_OPTIONS")
      : DEFAULT_FREE_JITTER_TOL_US;
  const maxInterpolatedIbiUs =
    options.maxInterpolatedIbiUs !== undefined
      ? assertNonNegSafeIntUs(
          options.maxInterpolatedIbiUs,
          "maxInterpolatedIbiUs",
          "INVALID_OPTIONS",
        )
      : DEFAULT_MAX_INTERPOLATED_IBI_US;

  const sortedBeats = valuesArray(snapshot?.beats)
    .slice()
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const sortedSegments = valuesArray(snapshot?.segments)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const sortedSpans = valuesArray(snapshot?.spans)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  const freeSpans = sortedSpans.filter((sp) => sp.kind === "free" && sp.endUs > sp.startUs);
  const keepIntervals = collectApprovedKeepIntervals(sortedSegments, sortedSpans);

  /**
   * Return index `>= 0` if `atUs` falls inside `keepIntervals[idx]`, else `-1`.
   * @param {number} atUs
   */
  const findKeepIndex = (atUs) => {
    for (let i = 0; i < keepIntervals.length; i += 1) {
      const k = keepIntervals[i];
      if (atUs >= k.startUs && atUs < k.endUs) return i;
    }
    return -1;
  };

  /**
   * Check whether a `keep` boundary edge lies strictly between `tA` and `tB` (`tA < edge <= tB`).
   * @param {number} tA
   * @param {number} tB
   */
  const crossesAnyKeepEdge = (tA, tB) => {
    for (let i = 0; i < keepIntervals.length; i += 1) {
      const k = keepIntervals[i];
      if ((k.startUs > tA && k.startUs <= tB) || (k.endUs > tA && k.endUs <= tB)) {
        return true;
      }
    }
    return false;
  };

  /**
   * Resolve effective `{ regime, kinematics }` at `(seg, atUs)`.
   * @param {import("../index.d.ts").Segment | null} seg
   * @param {number} atUs
   */
  const resolvePolicy = (seg, atUs) => {
    const kinematics = seg?.kinematics ?? null;
    if (seg?.regime === "free" || kinematics === "rubato") {
      return { regime: /** @type {const} */ ("free"), kinematics };
    }
    const inFreeSpan = freeSpans.some(
      (sp) => (!seg || sp.segmentId === seg.id) && rangeContainsUs(sp, atUs),
    );
    return {
      regime: inFreeSpan ? /** @type {const} */ ("free") : /** @type {const} */ ("auto"),
      kinematics,
    };
  };

  /** @type {import("../index.d.ts").StructuredNote[]} */
  const notes = [
    {
      code: "barlines_explicit_only",
      message: `Meter defaulted to ${defaultMeter}/4 except where downbeats are explicitly marked`,
      params: { defaultMeter },
    },
  ];

  if (sortedBeats.length === 0) {
    notes.push({
      code: "no_beats",
      message: "No beats available to project tempo map",
    });
    return deepFreeze({
      barPolicy: "explicit_only",
      points: [],
      notes,
    });
  }

  /** @type {Array<{ beat: import("../index.d.ts").Beat, seg: import("../index.d.ts").Segment | null }>} */
  const rawEligible = [];
  let omittedBreakBeats = 0;
  for (let i = 0; i < sortedBeats.length; i += 1) {
    const beat = sortedBeats[i];
    const seg = sortedSegments.length > 0 ? findSegmentAtUs(sortedSegments, beat.atUs) : null;
    if (sortedSegments.length > 0 && !seg) continue;
    if (seg?.regime === "break") {
      omittedBreakBeats += 1;
      continue;
    }
    rawEligible.push({ beat, seg });
  }

  // Coalesce exact-same-microsecond beats deterministically (preferring verified beat)
  /** @type {Array<{ beat: import("../index.d.ts").Beat, seg: import("../index.d.ts").Segment | null }>} */
  const eligible = [];
  for (let i = 0; i < rawEligible.length; i += 1) {
    const item = rawEligible[i];
    const prev = eligible[eligible.length - 1];
    if (prev && item.beat.atUs === prev.beat.atUs) {
      if (!prev.beat.verdict && item.beat.verdict) {
        eligible[eligible.length - 1] = item;
      }
      continue;
    }
    eligible.push(item);
  }

  if (eligible.length === 0) {
    notes.push({
      code: "all_beats_in_break",
      message: "All beats fell inside Break sections; no tempo points emitted",
    });
    return deepFreeze({
      barPolicy: "explicit_only",
      points: [],
      notes,
    });
  }

  const hasBreakBetween = (tA, tB) =>
    sortedSegments.some((s) => s.regime === "break" && s.endUs > tA && s.startUs < tB);

  /**
   * @typedef {{
   *   regime: "auto" | "free",
   *   kinematics: import("../index.d.ts").SegmentKinematics | null,
   *   seg: import("../index.d.ts").Segment | null,
   *   segmentId: string | null,
   *   keepIndex: number,
   *   beats: import("../index.d.ts").Beat[],
   *   nextConnectedBeatUs: number | null,
   *   endsWithFermata: boolean
   * }} PulseGroup
   */

  /** @type {PulseGroup[]} */
  const groups = [];
  const firstPolicy = resolvePolicy(eligible[0].seg, eligible[0].beat.atUs);
  /** @type {PulseGroup} */
  let currentGroup = {
    regime: firstPolicy.regime,
    kinematics: firstPolicy.kinematics,
    seg: eligible[0].seg,
    segmentId: eligible[0].seg?.id ?? null,
    keepIndex: findKeepIndex(eligible[0].beat.atUs),
    beats: [eligible[0].beat],
    nextConnectedBeatUs: null,
    endsWithFermata: Boolean(eligible[0].beat.structural?.fermata),
  };

  let breakSplitCount = 0;

  for (let i = 1; i < eligible.length; i += 1) {
    const prev = eligible[i - 1];
    const curr = eligible[i];
    const dt = curr.beat.atUs - prev.beat.atUs;
    const crossedBreak = dt > maxInterpolatedIbiUs || hasBreakBetween(prev.beat.atUs, curr.beat.atUs);
    const currKeepIndex = findKeepIndex(curr.beat.atUs);
    const crossedKeep =
      currKeepIndex !== currentGroup.keepIndex ||
      crossesAnyKeepEdge(prev.beat.atUs, curr.beat.atUs);
    const crossedSegment = (curr.seg?.id ?? null) !== currentGroup.segmentId;
    const currPolicy = resolvePolicy(curr.seg, curr.beat.atUs);
    const crossedPolicy =
      currPolicy.regime !== currentGroup.regime ||
      currPolicy.kinematics !== currentGroup.kinematics;
    const prevWasFermata = Boolean(prev.beat.structural?.fermata);
    const currIsPhraseReset = Boolean(curr.beat.structural?.phraseReset);

    if (
      crossedBreak ||
      crossedKeep ||
      crossedSegment ||
      crossedPolicy ||
      prevWasFermata ||
      currIsPhraseReset
    ) {
      if (crossedBreak) {
        breakSplitCount += 1;
        currentGroup.nextConnectedBeatUs = null;
      } else if (crossedKeep) {
        // Freeze boundary conditions strictly at Keep edges: NEVER bridge across Keep edges!
        currentGroup.nextConnectedBeatUs = null;
      } else if (dt > 0 && dt <= maxInterpolatedIbiUs) {
        currentGroup.nextConnectedBeatUs = curr.beat.atUs;
      }
      groups.push(currentGroup);
      currentGroup = {
        regime: currPolicy.regime,
        kinematics: currPolicy.kinematics,
        seg: curr.seg,
        segmentId: curr.seg?.id ?? null,
        keepIndex: currKeepIndex,
        beats: [curr.beat],
        nextConnectedBeatUs: null,
        endsWithFermata: Boolean(curr.beat.structural?.fermata),
      };
    } else {
      currentGroup.beats.push(curr.beat);
      currentGroup.endsWithFermata = Boolean(curr.beat.structural?.fermata);
    }
  }
  groups.push(currentGroup);

  /** @type {import("../index.d.ts").TempoPoint[]} */
  const points = [];

  /**
   * Append a TempoPoint with optional structural flags from `startBeat`.
   * @param {Omit<import("../index.d.ts").TempoPoint, "bpm">} pt
   * @param {import("../index.d.ts").Beat} startBeat
   */
  const pushPoint = (pt, startBeat) => {
    /** @type {import("../index.d.ts").TempoPoint} */
    const full = {
      ...pt,
      bpm: periodUsToBpm(pt.periodUs),
    };
    if (startBeat.structural?.downbeat) full.downbeat = true;
    if (startBeat.structural?.phraseReset) full.phraseReset = true;
    points.push(deepFreeze(full));
  };

  for (let gIdx = 0; gIdx < groups.length; gIdx += 1) {
    const grp = groups[gIdx];
    const inKeep = grp.keepIndex >= 0;

    // Single-beat group (including explicit fermata beat or isolated phrase-end beat)
    if (grp.beats.length === 1) {
      const b = grp.beats[0];
      if (b.structural?.fermata && grp.nextConnectedBeatUs !== null) {
        const holdUs = Math.max(1, grp.nextConnectedBeatUs - b.atUs);
        pushPoint(
          {
            atUs: b.atUs,
            endUs: grp.nextConnectedBeatUs,
            periodUs: holdUs,
            endPeriodUs: null,
            meter: defaultMeter,
            startBeatId: b.id,
            beatCount: 1,
            intervalCount: 1,
            segmentId: grp.segmentId,
            kind: "fermata_hold",
          },
          b,
        );
        continue;
      }
      let periodUs;
      if (grp.nextConnectedBeatUs !== null && grp.nextConnectedBeatUs > b.atUs) {
        periodUs = grp.nextConnectedBeatUs - b.atUs;
      } else if (!inKeep && points.length > 0) {
        // Non-kept isolated trailing beat may inherit preceding tempo; kept groups stay self-contained!
        periodUs = points[points.length - 1].periodUs;
      } else {
        periodUs = DEFAULT_FALLBACK_PERIOD_US;
      }
      pushPoint(
        {
          atUs: b.atUs,
          endUs: grp.nextConnectedBeatUs,
          periodUs,
          endPeriodUs: null,
          meter: defaultMeter,
          startBeatId: b.id,
          beatCount: 1,
          intervalCount: grp.nextConnectedBeatUs !== null ? 1 : 0,
          segmentId: grp.segmentId,
          kind: grp.regime === "free" ? "free_pulse" : "steady",
        },
        b,
      );
      continue;
    }

    // Explicit `ramp` kinematics on >= 2 beats: emit smooth ramp point (`periodUs` -> `endPeriodUs`)
    if (grp.kinematics === "ramp" && grp.regime !== "free") {
      const firstIbi = Math.max(1, grp.beats[1].atUs - grp.beats[0].atUs);
      const lastIbi = Math.max(
        1,
        grp.beats[grp.beats.length - 1].atUs - grp.beats[grp.beats.length - 2].atUs,
      );
      const endUs =
        grp.nextConnectedBeatUs !== null
          ? grp.nextConnectedBeatUs
          : grp.beats[grp.beats.length - 1].atUs;
      const intervalCount =
        grp.nextConnectedBeatUs !== null ? grp.beats.length : grp.beats.length - 1;
      pushPoint(
        {
          atUs: grp.beats[0].atUs,
          endUs,
          periodUs: firstIbi,
          endPeriodUs: lastIbi,
          meter: defaultMeter,
          startBeatId: grp.beats[0].id,
          beatCount: grp.beats.length,
          intervalCount,
          segmentId: grp.segmentId,
          kind: "ramp",
        },
        grp.beats[0],
      );
      continue;
    }

    const jitterTolUs = grp.regime === "free" ? freeJitterTolUs : steadyJitterTolUs;
    const pointKind = grp.regime === "free" ? "free_pulse" : "steady";

    // Compute exact left slice boundary for `grp` so OLS steady fitting never projects `t0Us`
    // across a preceding segment, free-span, or Keep-approval boundary!
    let grpSliceStartUs = grp.seg?.startUs ?? 0;
    const grpFirstBeatUs = grp.beats[0].atUs;
    for (let k = 0; k < keepIntervals.length; k += 1) {
      const ki = keepIntervals[k];
      if (ki.startUs <= grpFirstBeatUs && grpFirstBeatUs < ki.endUs) {
        if (ki.startUs > grpSliceStartUs) grpSliceStartUs = ki.startUs;
      } else if (ki.endUs <= grpFirstBeatUs) {
        if (ki.endUs > grpSliceStartUs) grpSliceStartUs = ki.endUs;
      }
    }
    for (let k = 0; k < freeSpans.length; k += 1) {
      const fs = freeSpans[k];
      if (grp.seg && fs.segmentId !== grp.seg.id) continue;
      if (fs.startUs <= grpFirstBeatUs && grpFirstBeatUs < fs.endUs) {
        if (fs.startUs > grpSliceStartUs) grpSliceStartUs = fs.startUs;
      } else if (fs.endUs <= grpFirstBeatUs) {
        if (fs.endUs > grpSliceStartUs) grpSliceStartUs = fs.endUs;
      }
    }

    let idx = 0;
    while (idx < grp.beats.length - 1) {
      const prevSliceEndUs =
        idx > 0
          ? grp.beats[idx].atUs
          : points.length > 0
            ? Math.max(points[points.length - 1].atUs, points[points.length - 1].endUs ?? 0)
            : 0;
      const minAtUs = Math.max(grpSliceStartUs, prevSliceEndUs);

      let end = idx + 1;
      let bestFit = fitSteadyLinearRunUs(grp.beats.slice(idx, end + 1), minAtUs);

      while (jitterTolUs > 0 && end + 1 < grp.beats.length) {
        const cand = fitSteadyLinearRunUs(grp.beats.slice(idx, end + 2), minAtUs);
        if (cand.periodUs > 0 && cand.maxResidualUs <= jitterTolUs) {
          end += 1;
          bestFit = cand;
        } else {
          break;
        }
      }

      const runBeats = grp.beats.slice(idx, end + 1);
      const isFinalRun = end === grp.beats.length - 1;
      const periodUs = Math.max(1, bestFit.periodUs);

      let needsBridge = false;
      let bridgePeriodUs = 0;
      if (isFinalRun && grp.nextConnectedBeatUs !== null) {
        const lastBeat = grp.beats[end];
        bridgePeriodUs = grp.nextConnectedBeatUs - lastBeat.atUs;
        const predNextUs = bestFit.t0Us + runBeats.length * periodUs;
        if (
          lastBeat.structural?.fermata ||
          (bridgePeriodUs > 0 &&
            bridgePeriodUs <= maxInterpolatedIbiUs &&
            Math.abs(grp.nextConnectedBeatUs - predNextUs) > jitterTolUs)
        ) {
          needsBridge = true;
        }
      }

      const ownedBeatCount = isFinalRun
        ? needsBridge
          ? runBeats.length - 1
          : runBeats.length
        : end - idx;

      const ownedIntervalCount = isFinalRun
        ? needsBridge
          ? runBeats.length - 1
          : grp.nextConnectedBeatUs !== null
            ? runBeats.length
            : runBeats.length - 1
        : end - idx;

      const runEndUs = isFinalRun
        ? needsBridge
          ? grp.beats[end].atUs
          : grp.nextConnectedBeatUs ?? grp.beats[end].atUs
        : grp.beats[end].atUs;

      pushPoint(
        {
          atUs: bestFit.t0Us,
          endUs: runEndUs,
          periodUs,
          endPeriodUs: null,
          meter: defaultMeter,
          startBeatId: runBeats[0].id,
          beatCount: ownedBeatCount,
          intervalCount: ownedIntervalCount,
          segmentId: grp.segmentId,
          kind: pointKind,
        },
        runBeats[0],
      );

      if (isFinalRun) {
        if (needsBridge && grp.nextConnectedBeatUs !== null) {
          const lastBeat = grp.beats[end];
          pushPoint(
            {
              atUs: lastBeat.atUs,
              endUs: grp.nextConnectedBeatUs,
              periodUs: Math.max(1, bridgePeriodUs),
              endPeriodUs: null,
              meter: defaultMeter,
              startBeatId: lastBeat.id,
              beatCount: 1,
              intervalCount: 1,
              segmentId: grp.segmentId,
              kind: lastBeat.structural?.fermata ? "fermata_hold" : pointKind,
            },
            lastBeat,
          );
        }
        break;
      }
      idx = end;
    }
  }

  if (breakSplitCount > 0 || omittedBreakBeats > 0) {
    notes.push({
      code: "break_restarts",
      message: "Restarted tempo projection cleanly after Break or long silence intervals",
      params: { breakSplitCount, omittedBreakBeats },
    });
  }

  return deepFreeze({
    barPolicy: "explicit_only",
    points,
    notes: notes.map((n) => deepFreeze(n)),
  });
}

/**
 * Compute the canonical JCS SHA-256 musical digest over `[startUs, endUs)`.
 * Hashes:
 *  - boundary cuts at or inside `[startUs, endUs]`
 *  - effective `regime` & `kinematics` sub-intervals over `[startUs, endUs)`
 *  - contained `Beat`s, `Unbeat`s, and overlapping `Sound`s (coordinates + bindings + roles,
 *    excluding UI/verdict timestamps & revision counters)
 *  - projected `TempoMap` points intersecting `[startUs, endUs)` (with frozen `keep` edges!)
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshot
 * @param {number} startUs
 * @param {number} endUs
 * @returns {string}
 */
export function computeIntervalMusicalDigest(snapshot, startUs, endUs) {
  const iv = assertIntervalUs(startUs, endUs, undefined, "digestInterval");
  const sUs = iv.startUs;
  const eUs = iv.endUs;

  const segments = valuesArray(snapshot?.segments)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const boundaries = valuesArray(snapshot?.boundaries)
    .filter((b) => b.atUs >= sUs && b.atUs <= eUs)
    .map((b) => ({ id: b.id, atUs: b.atUs }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const spans = valuesArray(snapshot?.spans);

  // Effective canonical merged policy sub-slices across `[sUs, eUs)` (excluding segmentId so
  // splitting a parent segment to the left vs right of a kept Span behaves 100% symmetrically!)
  /** @type {Array<{ startUs: number, endUs: number, regime: string, kinematics: string | null }>} */
  const policySlices = [];
  const pushPolicySlice = (startUsSlice, endUsSlice, regime, kinematics) => {
    if (endUsSlice <= startUsSlice) return;
    const prev = policySlices[policySlices.length - 1];
    if (
      prev &&
      prev.endUs === startUsSlice &&
      prev.regime === regime &&
      prev.kinematics === kinematics
    ) {
      prev.endUs = endUsSlice;
    } else {
      policySlices.push({
        startUs: startUsSlice,
        endUs: endUsSlice,
        regime,
        kinematics,
      });
    }
  };

  for (let i = 0; i < segments.length; i += 1) {
    const seg = segments[i];
    const clipStart = Math.max(sUs, seg.startUs);
    const clipEnd = Math.min(eUs, seg.endUs);
    if (clipEnd <= clipStart) continue;
    const kin = seg.kinematics ?? null;

    const freeInSeg = mergeRangesUs(
      spans
        .filter((sp) => sp.segmentId === seg.id && sp.kind === "free")
        .map((sp) => ({
          startUs: Math.max(clipStart, sp.startUs),
          endUs: Math.min(clipEnd, sp.endUs),
        }))
        .filter((r) => r.endUs > r.startUs),
    );

    if (seg.regime !== "auto" || freeInSeg.length === 0) {
      pushPolicySlice(clipStart, clipEnd, seg.regime, kin);
    } else {
      let cursor = clipStart;
      for (const fr of freeInSeg) {
        if (fr.startUs > cursor) {
          pushPolicySlice(cursor, fr.startUs, "auto", kin);
        }
        pushPolicySlice(fr.startUs, fr.endUs, "free", kin);
        cursor = fr.endUs;
      }
      if (cursor < clipEnd) {
        pushPolicySlice(cursor, clipEnd, "auto", kin);
      }
    }
  }

  const beats = valuesArray(snapshot?.beats)
    .filter((b) => b.atUs >= sUs && b.atUs < eUs)
    .map((b) => ({
      id: b.id,
      atUs: b.atUs,
      boundTo: b.boundTo
        ? {
            soundId: b.boundTo.soundId ?? null,
            candidateEventId: b.boundTo.candidateEventId ?? null,
            anchorId: b.boundTo.anchorId ?? null,
            anchorRole: b.boundTo.anchorRole,
            ...(b.boundTo.alignmentStale ? { alignmentStale: true } : {}),
          }
        : null,
      structural: b.structural ?? null,
    }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  const unbeats = valuesArray(snapshot?.unbeats)
    .filter((u) => u.atUs >= sUs && u.atUs < eUs)
    .map((u) => ({
      id: u.id,
      atUs: u.atUs,
      tolUs: u.tolUs,
      pulseLayer: u.pulseLayer,
    }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  const sounds = valuesArray(snapshot?.sounds)
    .filter((s) => s.startUs < eUs && s.endUs > sUs)
    .map((s) => ({
      id: s.id,
      startUs: s.startUs,
      endUs: s.endUs,
      endKind: s.endKind,
      primaryAnchorId: s.primaryAnchorId,
      anchors: s.anchors.map((a) => ({
        id: a.id,
        role: a.role,
        atUs: a.atUs,
        sigmaUs: a.sigmaUs ?? null,
      })),
      characteristics: s.characteristics
        ? {
            family: s.characteristics.family ?? null,
            register: s.characteristics.register ?? null,
            f0Hz: s.characteristics.f0Hz ?? null,
            harmonicity: s.characteristics.harmonicity ?? null,
            stereoPan: s.characteristics.stereoPan ?? null,
            stereoWidth: s.characteristics.stereoWidth ?? null,
            confidence: s.characteristics.confidence ?? null,
          }
        : null,
      salience: s.salience ?? null,
      blendStatus: s.blendStatus ?? null,
      voiceGroupId: s.voiceGroupId ?? null,
      classId: s.classId ?? null,
      descriptor: s.descriptor
        ? {
            schema: s.descriptor.schema,
            values: s.descriptor.values,
          }
        : null,
    }))
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  // Projected TempoMap clipped to `[sUs, eUs)`, treating `[sUs, eUs)` itself as a frozen Keep edge
  // so computing the digest BEFORE vs AFTER granting Approval on `[sUs, eUs)` produces the exact same hash!
  const augSpans = [
    ...spans,
    {
      id: "__digest_keep__",
      segmentId: segments[0]?.id ?? "__seg__",
      startUs: sUs,
      endUs: eUs,
      kind: /** @type {const} */ ("keep"),
      origin: /** @type {const} */ ("user"),
      createdByEventId: "__digest__",
      revision: 1,
      approval: {
        id: "__digest_app__",
        eventId: "__digest__",
        contentDigest: "__pending__",
      },
    },
  ];
  const tempoMap = projectTempoMap({
    ...snapshot,
    segments,
    spans: augSpans,
  });
  const tempoSlices = tempoMap.points
    .filter((pt) => {
      if (pt.atUs >= sUs && pt.atUs < eUs) return true;
      if (pt.atUs < sUs && pt.endUs !== null && pt.endUs > sUs) return true;
      return false;
    })
    .map((pt) => ({
      atUs: Math.max(sUs, pt.atUs),
      endUs: pt.endUs !== null ? Math.min(eUs, pt.endUs) : null,
      periodUs: pt.periodUs,
      endPeriodUs: pt.endPeriodUs,
      meter: pt.meter,
      kind: pt.kind,
      downbeat: Boolean(pt.downbeat),
      phraseReset: Boolean(pt.phraseReset),
    }));

  return jcsSha256Hex({
    v: 1,
    startUs: sUs,
    endUs: eUs,
    boundaries,
    policySlices,
    beats,
    unbeats,
    sounds,
    tempoSlices,
  });
}

/**
 * Pure derived Inbox ranking returning `InboxItem[]`.
 * Automatically excludes all `keep`-approved intervals.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshot
 * @param {import("../index.d.ts").InboxOptions} [options]
 * @returns {import("../index.d.ts").InboxItem[]}
 */
export function rankInboxItems(snapshot, options = {}) {
  const windowUs =
    options.windowUs !== undefined
      ? assertNonNegSafeIntUs(options.windowUs, "options.windowUs", "INVALID_OPTIONS")
      : 4_000_000;
  if (windowUs <= 0) {
    throw new EnsomiError("INVALID_OPTIONS", "rankInboxItems windowUs must be > 0");
  }
  const lowConfThreshold = options.lowConfThreshold ?? 0.7;
  const jumpRatioThreshold = options.jumpRatioThreshold ?? 0.12;
  const includeReviewedClean = Boolean(options.includeReviewedClean);

  if (typeof lowConfThreshold !== "number" || !Number.isFinite(lowConfThreshold)) {
    throw new EnsomiError("INVALID_OPTIONS", "lowConfThreshold must be finite");
  }
  if (
    typeof jumpRatioThreshold !== "number" ||
    !Number.isFinite(jumpRatioThreshold) ||
    jumpRatioThreshold < 0
  ) {
    throw new EnsomiError("INVALID_OPTIONS", "jumpRatioThreshold must be >= 0");
  }

  const durationUs = snapshot?.durationUs ?? snapshot?.audio?.durationUs ?? 0;
  const sortedBeats = valuesArray(snapshot?.beats)
    .filter((b) => durationUs <= 0 || b.atUs < durationUs)
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const sortedSounds = valuesArray(snapshot?.sounds)
    .filter((s) => durationUs <= 0 || s.endUs <= durationUs)
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const sortedSpans = valuesArray(snapshot?.spans);
  let segments = valuesArray(snapshot?.segments)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  if (segments.length === 0) {
    if (durationUs <= 0) return [];
    segments = [
      {
        id: "seg_0",
        startUs: 0,
        endUs: durationUs,
        startBoundaryId: null,
        endBoundaryId: null,
        regime: "auto",
        origin: "rule",
        createdByEventId: "init",
        revision: 1,
      },
    ];
  }

  const gridFit = computeGridFit(
    { ...snapshot, segments, beats: sortedBeats, sounds: sortedSounds },
    options.gridFitOptions,
  );
  const soundById = new Map(sortedSounds.map((s) => [s.id, s]));
  const constraints = compileConstraints({
    ...snapshot,
    segments,
    beats: sortedBeats,
    sounds: sortedSounds,
  });

  const maxIbiUs = options.gridFitOptions?.maxInterpolatedIbiUs ?? DEFAULT_MAX_INTERPOLATED_IBI_US;

  /** @type {Array<{ segmentId: string, atUs: number, ratio: number }>} */
  const ibiJumps = [];
  for (let i = 2; i < sortedBeats.length; i += 1) {
    const b0 = sortedBeats[i - 2];
    const b1 = sortedBeats[i - 1];
    const b2 = sortedBeats[i];
    const s0 = findSegmentAtUs(segments, b0.atUs);
    const s1 = findSegmentAtUs(segments, b1.atUs);
    const s2 = findSegmentAtUs(segments, b2.atUs);
    if (!s0 || !s1 || !s2 || s0.id !== s1.id || s1.id !== s2.id || s1.regime !== "auto") {
      continue;
    }
    const inFree = constraints.freeIntervals.some(
      (fi) => fi.segmentId === s1.id && rangesOverlapUs(fi, { startUs: b0.atUs, endUs: b2.atUs + 1 }),
    );
    if (inFree) continue;
    const prevIbi = b1.atUs - b0.atUs;
    const currIbi = b2.atUs - b1.atUs;
    if (prevIbi <= 0 || currIbi <= 0 || prevIbi > maxIbiUs || currIbi > maxIbiUs) continue;
    const ratio = Math.abs(currIbi - prevIbi) / Math.max(currIbi, prevIbi);
    if (ratio > jumpRatioThreshold) {
      ibiJumps.push({ segmentId: s1.id, atUs: b1.atUs, ratio });
    }
  }

  const conflictedOffers = valuesArray(snapshot?.offers).filter(
    (o) =>
      (o.status === "stale" || o.status === "invalid" || o.status === "requires_challenge_confirm") &&
      ((o.conflicts && o.conflicts.length > 0) || (o.challenges && o.challenges.length > 0)),
  );
  const visitedRanges = Array.isArray(snapshot?.visitedRanges) ? snapshot.visitedRanges : [];

  /** @type {import("../index.d.ts").InboxItem[]} */
  const items = [];

  for (let sIdx = 0; sIdx < segments.length; sIdx += 1) {
    const seg = segments[sIdx];
    // Automatically exclude Keep-approved segments!
    if (seg.approval) continue;

    const segStart = seg.startUs;
    const segEnd = durationUs > 0 ? Math.min(seg.endUs, durationUs) : seg.endUs;
    if (segEnd <= segStart) continue;

    const keepSpansInSeg = sortedSpans
      .filter((sp) => sp.segmentId === seg.id && sp.kind === "keep" && sp.approval)
      .map((sp) => ({ startUs: sp.startUs, endUs: sp.endUs }));

    let unlocked = [{ startUs: segStart, endUs: segEnd }];
    for (let k = 0; k < keepSpansInSeg.length; k += 1) {
      unlocked = subtractRangeUs(unlocked, keepSpansInSeg[k].startUs, keepSpansInSeg[k].endUs);
      if (unlocked.length === 0) break;
    }

    for (let uIdx = 0; uIdx < unlocked.length; uIdx += 1) {
      const sub = unlocked[uIdx];
      const lenUs = sub.endUs - sub.startUs;
      if (lenUs <= 0) continue;

      if (seg.regime === "break") {
        const strayCount = sortedBeats.filter((b) => rangeContainsUs(sub, b.atUs)).length;
        if (strayCount > 0) {
          items.push(
            deepFreeze({
              startUs: sub.startUs,
              endUs: sub.endUs,
              segmentId: seg.id,
              priority: "medium",
              score: Math.round((2 + strayCount) * 10000) / 10000,
              reasonCode: "stray_beats_in_break",
              reasonCodes: ["stray_beats_in_break"],
              params: { strayBeatCount: strayCount },
            }),
          );
        }
        continue;
      }

      const winCount = Math.max(1, Math.ceil(lenUs / windowUs));
      for (let w = 0; w < winCount; w += 1) {
        const wStart = sub.startUs + w * windowUs;
        const wEnd = w === winCount - 1 ? sub.endUs : Math.min(sub.endUs, sub.startUs + (w + 1) * windowUs);
        if (wEnd <= wStart) continue;
        const win = { startUs: wStart, endUs: wEnd };
        const durSec = Math.max(1, (wEnd - wStart) / 1_000_000);

        let lowConfSum = 0;
        let lowConfCount = 0;
        for (let bIdx = 0; bIdx < sortedBeats.length; bIdx += 1) {
          const b = sortedBeats[bIdx];
          if (!rangeContainsUs(win, b.atUs)) continue;
          if (!b.verdict && b.confidence !== undefined && b.confidence < lowConfThreshold) {
            lowConfCount += 1;
            lowConfSum += 1 - b.confidence;
          }
        }
        const lowConfScore = lowConfSum * 2.2;

        let jumpScore = 0;
        let jumpCount = 0;
        /** @type {number | null} */
        let firstJumpAtUs = null;
        for (let jIdx = 0; jIdx < ibiJumps.length; jIdx += 1) {
          const j = ibiJumps[jIdx];
          if (j.segmentId === seg.id && rangeContainsUs(win, j.atUs)) {
            jumpCount += 1;
            if (firstJumpAtUs === null) firstJumpAtUs = j.atUs;
            jumpScore += 1.6 + j.ratio * 2.2;
          }
        }

        let unexplainedWeight = 0;
        let unexplainedCount = 0;
        for (let gIdx = 0; gIdx < gridFit.length; gIdx += 1) {
          const gf = gridFit[gIdx];
          if (gf.status !== "unexplained" || !rangeContainsUs(win, gf.atUs)) continue;
          unexplainedCount += 1;
          const salience = soundById.get(gf.soundId)?.salience ?? 0.6;
          unexplainedWeight += 0.5 + salience;
        }
        const unexplainedScore = (unexplainedWeight / durSec) * 3.0;

        let unbeatTensionCount = 0;
        for (let rIdx = 0; rIdx < constraints.unbeatRepulsions.length; rIdx += 1) {
          const rep = constraints.unbeatRepulsions[rIdx];
          if (!rangeContainsUs(win, rep.atUs)) continue;
          const hit = sortedBeats.some(
            (b) => Math.abs(b.atUs - rep.atUs) <= rep.repulsionRadiusUs,
          );
          if (hit) unbeatTensionCount += 1;
        }
        const mutedScore = unbeatTensionCount * 2.0;

        let conflictCount = 0;
        for (let oIdx = 0; oIdx < conflictedOffers.length; oIdx += 1) {
          const off = conflictedOffers[oIdx];
          if (off.writeRanges.some((wr) => rangesOverlapUs(wr, win))) {
            conflictCount += 1;
          }
        }
        const conflictScore = conflictCount * 1.8;

        const visited = visitedRanges.some((vr) => rangesOverlapUs(vr, win));
        const unvisitedScore = visited ? 0 : 0.6;

        const totalScore =
          Math.round(
            (lowConfScore +
              jumpScore +
              unexplainedScore +
              mutedScore +
              conflictScore +
              unvisitedScore) *
              10000,
          ) / 10000;

        if (totalScore <= 0 && !includeReviewedClean) continue;

        /** @type {Array<{ code: import("../index.d.ts").InboxReasonCode, weight: number }>} */
        const rankedReasons = [];
        if (unexplainedCount > 0) {
          rankedReasons.push({ code: "unexplained_sounds", weight: unexplainedScore });
        }
        if (jumpCount > 0) {
          rankedReasons.push({ code: "sudden_tempo_jump", weight: jumpScore });
        }
        if (lowConfCount > 0) {
          rankedReasons.push({ code: "low_confidence_beats", weight: lowConfScore });
        }
        if (unbeatTensionCount > 0) {
          rankedReasons.push({ code: "unbeat_repulsion_tension", weight: mutedScore });
        }
        if (conflictCount > 0) {
          rankedReasons.push({ code: "conflicted_offers", weight: conflictScore });
        }
        if (rankedReasons.length === 0) {
          rankedReasons.push({
            code: visited ? "reviewed_passage" : "unreviewed_passage",
            weight: unvisitedScore,
          });
        }
        rankedReasons.sort((a, b) => b.weight - a.weight);

        const reasonCodes = rankedReasons.map((r) => r.code);
        const priority = totalScore >= 4.0 ? "high" : totalScore >= 1.5 ? "medium" : "low";

        items.push(
          deepFreeze({
            startUs: win.startUs,
            endUs: win.endUs,
            segmentId: seg.id,
            priority,
            score: totalScore,
            reasonCode: reasonCodes[0],
            reasonCodes,
            params: {
              unexplainedCount,
              jumpCount,
              firstJumpTime: firstJumpAtUs !== null ? formatTimeUs(firstJumpAtUs) : null,
              lowConfCount,
              unbeatTensionCount,
              conflictCount,
              visited,
            },
          }),
        );
      }
    }
  }

  const priorityRank = { high: 3, medium: 2, low: 1 };
  items.sort(
    (a, b) =>
      priorityRank[b.priority] - priorityRank[a.priority] ||
      b.score - a.score ||
      a.startUs - b.startUs ||
      compareCodeUnits(a.segmentId, b.segmentId),
  );
  return items;
}
