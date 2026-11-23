/**
 * Break-aware pure derived views (never stored in `SessionState`):
 *  - `computeGridFit(sounds, beats, segments, options)`
 *  - `projectRedLines(beats, segments, options)`
 *  - `rankInboxSpans(state, options)`
 */

import { compileConstraints } from "./constraints.js";
import { roundTime } from "./hash.js";
import {
  EnsomiError,
  formatTimeCode,
  normalizeSpan,
  rangeContainsTime,
  rangesOverlap,
} from "./types.js";

const DEFAULT_DIVISORS = Object.freeze([1, 2, 3, 4, 6]);
const DEFAULT_TOL_MS = 12;
const DEFAULT_MAX_INTERPOLATED_IBI_SEC = 2.5;

/**
 * Normalize a Map or Iterable of timed entities to a sorted array (`timeSec` ascending).
 * @template {{ id: string, timeSec: number }} T
 * @param {Iterable<T> | Map<string, T>} items
 * @returns {T[]}
 */
function toSortedByTime(items) {
  if (!items) return [];
  const arr = items instanceof Map ? Array.from(items.values()) : Array.from(items);
  return arr.slice().sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id));
}

/**
 * Normalize `segments` to an array sorted by `t0` ascending.
 * @param {Iterable<import("../index.d.ts").Segment> | Map<string, import("../index.d.ts").Segment> | undefined} segments
 * @returns {import("../index.d.ts").Segment[]}
 */
function toSortedSegments(segments) {
  if (!segments) return [];
  const arr = segments instanceof Map ? Array.from(segments.values()) : Array.from(segments);
  return arr.slice().sort((a, b) => a.t0 - b.t0 || a.t1 - b.t1 || a.id.localeCompare(b.id));
}

/**
 * Find the segment enclosing `timeSec` (`seg.t0 <= timeSec < seg.t1`, or trailing boundary on final segment).
 * @param {readonly import("../index.d.ts").Segment[]} sortedSegments
 * @param {number} timeSec
 * @returns {import("../index.d.ts").Segment | null}
 */
function findSegmentAt(sortedSegments, timeSec) {
  if (sortedSegments.length === 0) return null;
  let lo = 0;
  let hi = sortedSegments.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const seg = sortedSegments[mid];
    if (timeSec < seg.t0) {
      hi = mid - 1;
    } else if (timeSec >= seg.t1) {
      lo = mid + 1;
    } else {
      return seg;
    }
  }
  const last = sortedSegments[sortedSegments.length - 1];
  if (timeSec === last.t1) return last;
  return null;
}

/**
 * Find index of rightmost beat with `beat.timeSec <= timeSec`.
 * @param {readonly Array<{ timeSec: number }>} sortedBeats
 * @param {number} timeSec
 */
function findLeftBeatIndex(sortedBeats, timeSec) {
  let lo = 0;
  let hi = sortedBeats.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (sortedBeats[mid].timeSec <= timeSec) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/**
 * Compute per-sound grid alignment against `beats` and `segments`.
 * - Signed `residualMs = (sound.timeSec - slotTimeSec) * 1000`.
 * - Defaults: `{ divisors: [1, 2, 3, 4, 6], tolMs: 12, maxInterpolatedIbiSec: 2.5 }`.
 * - Never interpolates subdivisions across `Segment` boundaries, inside `regime === "break"`
 *   segments, or across IBIs `> maxInterpolatedIbiSec` (`2.5 s`).
 *
 * @param {Iterable<import("../index.d.ts").SoundAnchor> | Map<string, import("../index.d.ts").SoundAnchor>} sounds
 * @param {Iterable<import("../index.d.ts").Beat> | Map<string, import("../index.d.ts").Beat>} beats
 * @param {Iterable<import("../index.d.ts").Segment> | Map<string, import("../index.d.ts").Segment>} [segments=[]]
 * @param {import("../index.d.ts").GridFitOptions} [options]
 * @returns {import("../index.d.ts").GridFitItem[]}
 */
export function computeGridFit(sounds, beats, segments = [], options = {}) {
  const divisors = [...new Set(options.divisors ?? DEFAULT_DIVISORS)]
    .filter((d) => Number.isInteger(d) && d >= 1)
    .sort((a, b) => a - b);
  const tolMs = options.tolMs ?? DEFAULT_TOL_MS;
  const maxInterpolatedIbiSec =
    options.maxInterpolatedIbiSec ?? DEFAULT_MAX_INTERPOLATED_IBI_SEC;

  const sortedSounds = toSortedByTime(sounds);
  const sortedSegments = toSortedSegments(segments);
  const rawSortedBeats = toSortedByTime(beats);

  // Exclude stray beats inside `break` segments from forming active pulse slots
  const activeBeats =
    sortedSegments.length > 0
      ? rawSortedBeats.filter((b) => findSegmentAt(sortedSegments, b.timeSec)?.regime !== "break")
      : rawSortedBeats;

  /** @type {import("../index.d.ts").GridFitItem[]} */
  const results = [];

  for (const sound of sortedSounds) {
    const soundSeg =
      sortedSegments.length > 0 ? findSegmentAt(sortedSegments, sound.timeSec) : null;

    if (activeBeats.length === 0 || soundSeg?.regime === "break") {
      let nearestBeatDistMs = Infinity;
      let nearestBeatSignedMs = Infinity;
      let nearestSlotSec = null;
      if (activeBeats.length > 0) {
        const lIdx = findLeftBeatIndex(activeBeats, sound.timeSec);
        const rIdx = lIdx + 1 < activeBeats.length ? lIdx + 1 : -1;
        for (const idx of [lIdx, rIdx]) {
          if (idx < 0) continue;
          const signed = (sound.timeSec - activeBeats[idx].timeSec) * 1000;
          const dist = Math.abs(signed);
          if (dist < nearestBeatDistMs) {
            nearestBeatDistMs = dist;
            nearestBeatSignedMs = signed;
            nearestSlotSec = activeBeats[idx].timeSec;
          }
        }
      }
      results.push({
        soundId: sound.id,
        timeSec: sound.timeSec,
        nearestBeatDistMs: Number.isFinite(nearestBeatDistMs)
          ? roundTime(nearestBeatDistMs, 4)
          : Infinity,
        nearestDivisor: null,
        slotTimeSec: nearestSlotSec,
        residualMs: Number.isFinite(nearestBeatSignedMs)
          ? roundTime(nearestBeatSignedMs, 4)
          : Infinity,
        status: "unexplained",
      });
      continue;
    }

    const leftIdx = findLeftBeatIndex(activeBeats, sound.timeSec);
    const rightIdx = leftIdx + 1 < activeBeats.length ? leftIdx + 1 : -1;

    let nearestBeatDistMs = Infinity;
    let nearestBeatSignedMs = Infinity;
    let nearestBeatSec = activeBeats[0].timeSec;

    if (leftIdx >= 0) {
      const bL = activeBeats[leftIdx];
      const signedL = (sound.timeSec - bL.timeSec) * 1000;
      const distL = Math.abs(signedL);
      if (distL < nearestBeatDistMs) {
        nearestBeatDistMs = distL;
        nearestBeatSignedMs = signedL;
        nearestBeatSec = bL.timeSec;
      }
    }
    if (rightIdx >= 0) {
      const bR = activeBeats[rightIdx];
      const signedR = (sound.timeSec - bR.timeSec) * 1000;
      const distR = Math.abs(signedR);
      if (distR < nearestBeatDistMs) {
        nearestBeatDistMs = distR;
        nearestBeatSignedMs = signedR;
        nearestBeatSec = bR.timeSec;
      }
    }

    // Direct beat hit (divisor 1) within the same segment (or unconstrained when segments === [])
    const nearestBeatSeg =
      sortedSegments.length > 0 ? findSegmentAt(sortedSegments, nearestBeatSec) : null;
    const sameSegmentForBeatHit =
      sortedSegments.length === 0 ||
      (soundSeg !== null && nearestBeatSeg !== null && soundSeg.id === nearestBeatSeg.id);

    if (divisors.includes(1) && sameSegmentForBeatHit && nearestBeatDistMs <= tolMs) {
      results.push({
        soundId: sound.id,
        timeSec: sound.timeSec,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: 1,
        slotTimeSec: nearestBeatSec,
        residualMs: roundTime(nearestBeatSignedMs, 4),
        status: "on_grid",
      });
      continue;
    }

    let bestWithinDivisor = null;
    let bestWithinAbsErr = Infinity;
    let bestWithinSignedMs = 0;
    let bestWithinSlotSec = nearestBeatSec;

    let bestOverallAbsErr = nearestBeatDistMs;
    let bestOverallSignedMs = nearestBeatSignedMs;
    let bestOverallSlotSec = nearestBeatSec;

    if (leftIdx >= 0 && rightIdx >= 0) {
      const b0 = activeBeats[leftIdx];
      const b1 = activeBeats[rightIdx];
      const interval = b1.timeSec - b0.timeSec;

      const seg0 = sortedSegments.length > 0 ? findSegmentAt(sortedSegments, b0.timeSec) : null;
      const seg1 = sortedSegments.length > 0 ? findSegmentAt(sortedSegments, b1.timeSec) : null;
      const canInterpolate =
        interval > 0 &&
        interval <= maxInterpolatedIbiSec &&
        (sortedSegments.length === 0 ||
          (soundSeg !== null &&
            seg0 !== null &&
            seg1 !== null &&
            soundSeg.regime !== "break" &&
            seg0.id === soundSeg.id &&
            seg1.id === soundSeg.id));

      if (canInterpolate) {
        for (const d of divisors) {
          if (d === 1) continue;
          const pos = ((sound.timeSec - b0.timeSec) / interval) * d;
          const k = Math.round(pos);
          if (k <= 0 || k >= d) continue;
          const slotSec = b0.timeSec + (k / d) * interval;
          const signedMs = (sound.timeSec - slotSec) * 1000;
          const errMs = Math.abs(signedMs);
          if (errMs < bestOverallAbsErr) {
            bestOverallAbsErr = errMs;
            bestOverallSignedMs = signedMs;
            bestOverallSlotSec = slotSec;
          }
          if (errMs <= tolMs) {
            if (bestWithinDivisor === null || errMs < bestWithinAbsErr - 0.5) {
              bestWithinDivisor = d;
              bestWithinAbsErr = errMs;
              bestWithinSignedMs = signedMs;
              bestWithinSlotSec = slotSec;
            }
          }
        }
      }
    }

    if (bestWithinDivisor !== null) {
      results.push({
        soundId: sound.id,
        timeSec: sound.timeSec,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: bestWithinDivisor,
        slotTimeSec: roundTime(bestWithinSlotSec, 6),
        residualMs: roundTime(bestWithinSignedMs, 4),
        status: "subdivision",
      });
    } else {
      results.push({
        soundId: sound.id,
        timeSec: sound.timeSec,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: null,
        slotTimeSec: roundTime(bestOverallSlotSec, 6),
        residualMs: roundTime(bestOverallSignedMs, 4),
        status: "unexplained",
      });
    }
  }

  return results;
}

/**
 * Ordinary least-squares linear fit `timeSec[i] ≈ t0 + i * period` for `i = 0..n-1`.
 * @param {readonly Array<{ timeSec: number }>} run
 */
function fitSteadyLinearRun(run) {
  const n = run.length;
  if (n < 2) {
    return { t0: run[0].timeSec, period: 0.5, maxResidualMs: 0 };
  }
  const meanI = (n - 1) / 2;
  let sumT = 0;
  for (let i = 0; i < n; i += 1) sumT += run[i].timeSec;
  const meanT = sumT / n;

  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const di = i - meanI;
    num += di * (run[i].timeSec - meanT);
    den += di * di;
  }
  const period = den > 0 ? num / den : run[1].timeSec - run[0].timeSec;
  const t0 = meanT - period * meanI;

  let maxResidualMs = 0;
  for (let i = 0; i < n; i += 1) {
    const pred = t0 + i * period;
    const errMs = Math.abs(run[i].timeSec - pred) * 1000;
    if (errMs > maxResidualMs) maxResidualMs = errMs;
  }
  return { t0, period, maxResidualMs };
}

/**
 * Project `beats` and `segments` into `.osu` `[TimingPoints]` uninherited red lines:
 * - Exact transition-beat placement (pivot beat shared across tempo shifts, never one beat late).
 * - Explicit `barPolicy: "barlines_not_inferred"`.
 * - Respects `Segment` boundaries, preserves beat-level tempo changes inside `regime === "free"`,
 *   and NEVER emits slow bridging red lines across `regime === "break"` segments or IBIs
 *   `> maxInterpolatedIbiSec` (`2.5 s`) — starts a fresh red line at the first beat after the break.
 *
 * @param {Iterable<import("../index.d.ts").Beat> | Map<string, import("../index.d.ts").Beat>} beats
 * @param {Iterable<import("../index.d.ts").Segment> | Map<string, import("../index.d.ts").Segment>} [segments=[]]
 * @param {import("../index.d.ts").RedLineOptions} [options]
 * @returns {import("../index.d.ts").RedLineProjection}
 */
export function projectRedLines(beats, segments = [], options = {}) {
  const defaultMeter = options.defaultMeter ?? 4;
  const jitterTolMs = options.jitterTolMs ?? 15;
  const freeJitterTolMs = options.freeJitterTolMs ?? 2;
  const maxInterpolatedIbiSec =
    options.maxInterpolatedIbiSec ?? DEFAULT_MAX_INTERPOLATED_IBI_SEC;

  const sortedBeats = toSortedByTime(beats);
  const sortedSegments = toSortedSegments(segments);

  /** @type {string[]} */
  const losses = [
    `Meter defaulted to ${defaultMeter}/4 (barlines are not inferred)`,
  ];

  if (sortedBeats.length === 0) {
    losses.push("No beats available to project timing points");
    return {
      barPolicy: "barlines_not_inferred",
      redLines: [],
      osuTimingPoints: "",
      losses,
    };
  }

  /**
   * Partition beats into contiguous pulse groups:
   *  - Beats inside `regime === "break"` segments are skipped.
   *  - A group splits whenever segment ID changes OR consecutive beat gap > `maxInterpolatedIbiSec`
   *    OR a `break` segment lies between consecutive beats.
   *
   * @type {Array<{ regime: "auto" | "free", segmentId: string | null, beats: import("../index.d.ts").Beat[], nextConnectedBeatSec: number | null }>}
   */
  const groups = [];
  let omittedBreakBeats = 0;
  let breakSplitCount = 0;

  /** @type {Array<{ beat: import("../index.d.ts").Beat, seg: import("../index.d.ts").Segment | null }>} */
  const eligible = [];
  for (const beat of sortedBeats) {
    const seg = sortedSegments.length > 0 ? findSegmentAt(sortedSegments, beat.timeSec) : null;
    if (seg?.regime === "break") {
      omittedBreakBeats += 1;
      continue;
    }
    eligible.push({ beat, seg });
  }

  if (eligible.length === 0) {
    losses.push("All beats fell inside Break sections; no timing points emitted");
    return {
      barPolicy: "barlines_not_inferred",
      redLines: [],
      osuTimingPoints: "",
      losses,
    };
  }

  const hasBreakBetween = (tA, tB) =>
    sortedSegments.some((s) => s.regime === "break" && s.t1 > tA + 1e-9 && s.t0 < tB - 1e-9);

  let currentGroup = {
    regime: /** @type {"auto" | "free"} */ (eligible[0].seg?.regime === "free" ? "free" : "auto"),
    segmentId: eligible[0].seg?.id ?? null,
    beats: [eligible[0].beat],
    nextConnectedBeatSec: /** @type {number | null} */ (null),
  };

  for (let i = 1; i < eligible.length; i += 1) {
    const prev = eligible[i - 1];
    const curr = eligible[i];
    const dt = curr.beat.timeSec - prev.beat.timeSec;
    const crossedBreak = dt > maxInterpolatedIbiSec || hasBreakBetween(prev.beat.timeSec, curr.beat.timeSec);
    const crossedSegment = (curr.seg?.id ?? null) !== currentGroup.segmentId;

    if (crossedBreak || crossedSegment) {
      if (crossedBreak) {
        breakSplitCount += 1;
        currentGroup.nextConnectedBeatSec = null;
      } else if (dt > 1e-6 && dt <= maxInterpolatedIbiSec) {
        currentGroup.nextConnectedBeatSec = curr.beat.timeSec;
      }
      groups.push(currentGroup);
      currentGroup = {
        regime: curr.seg?.regime === "free" ? "free" : "auto",
        segmentId: curr.seg?.id ?? null,
        beats: [curr.beat],
        nextConnectedBeatSec: null,
      };
    } else {
      currentGroup.beats.push(curr.beat);
    }
  }
  groups.push(currentGroup);

  /** @type {import("../index.d.ts").RedLine[]} */
  const redLines = [];
  let collapsedBeatCount = 0;
  let steadyRunCount = 0;
  let freeGroupCount = 0;

  for (const grp of groups) {
    if (grp.regime === "free") {
      freeGroupCount += 1;
      // Preserve beat-level tempo changes inside `free` regime (only collapsing sub-`freeJitterTolMs` noise if >= 3 collinear beats)
      if (grp.beats.length === 1) {
        const b = grp.beats[0];
        let periodSec;
        if (grp.nextConnectedBeatSec !== null && grp.nextConnectedBeatSec > b.timeSec) {
          periodSec = grp.nextConnectedBeatSec - b.timeSec;
        } else if (redLines.length > 0) {
          periodSec = 60 / redLines[redLines.length - 1].bpm;
        } else {
          periodSec = 0.5;
        }
        const beatLengthMs = roundTime(periodSec * 1000, 6);
        redLines.push({
          offsetMs: roundTime(b.timeSec * 1000, 3),
          beatLengthMs,
          bpm: roundTime(60000 / beatLengthMs, 4),
          meter: defaultMeter,
          startBeatId: b.id,
          beatCount: 1,
          kind: "free_pulse",
        });
        continue;
      }

      let idx = 0;
      while (idx < grp.beats.length) {
        if (idx === grp.beats.length - 1) {
          // Final beat in free group: only emit a trailing line if connected seamlessly to next segment's downbeat at a different interval
          if (grp.nextConnectedBeatSec !== null) {
            const lastBeat = grp.beats[idx];
            const prevPeriodSec = grp.beats[idx].timeSec - grp.beats[idx - 1].timeSec;
            const bridgePeriodSec = grp.nextConnectedBeatSec - lastBeat.timeSec;
            if (
              bridgePeriodSec > 1e-6 &&
              Math.abs(bridgePeriodSec - prevPeriodSec) * 1000 > freeJitterTolMs
            ) {
              const beatLengthMs = roundTime(bridgePeriodSec * 1000, 6);
              redLines.push({
                offsetMs: roundTime(lastBeat.timeSec * 1000, 3),
                beatLengthMs,
                bpm: roundTime(60000 / beatLengthMs, 4),
                meter: defaultMeter,
                startBeatId: lastBeat.id,
                beatCount: 1,
                kind: "free_pulse",
              });
            }
          }
          break;
        }

        let end = idx + 1;
        let fit = fitSteadyLinearRun(grp.beats.slice(idx, end + 1));
        while (freeJitterTolMs > 0 && end + 1 < grp.beats.length) {
          const cand = fitSteadyLinearRun(grp.beats.slice(idx, end + 2));
          if (cand.period > 0 && cand.maxResidualMs <= freeJitterTolMs) {
            end += 1;
            fit = cand;
          } else {
            break;
          }
        }

        const isFinalInGroup = end === grp.beats.length - 1 && grp.nextConnectedBeatSec === null;
        const ownedBeatCount = isFinalInGroup ? end - idx + 1 : end - idx;
        const periodSec = fit.period > 0 ? fit.period : grp.beats[idx + 1].timeSec - grp.beats[idx].timeSec;
        const beatLengthMs = roundTime(periodSec * 1000, 6);
        redLines.push({
          offsetMs: roundTime(fit.t0 * 1000, 3),
          beatLengthMs,
          bpm: roundTime(60000 / beatLengthMs, 4),
          meter: defaultMeter,
          startBeatId: grp.beats[idx].id,
          beatCount: ownedBeatCount,
          kind: "free_pulse",
        });

        if (end === grp.beats.length - 1 && grp.nextConnectedBeatSec === null) {
          break;
        }
        idx = end;
      }
      continue;
    }

    // `auto` regime group
    if (grp.beats.length === 1) {
      const b = grp.beats[0];
      let periodSec;
      if (grp.nextConnectedBeatSec !== null && grp.nextConnectedBeatSec > b.timeSec) {
        periodSec = grp.nextConnectedBeatSec - b.timeSec;
      } else if (redLines.length > 0) {
        periodSec = 60 / redLines[redLines.length - 1].bpm;
      } else {
        periodSec = 0.5;
        losses.push("Defaulted isolated single beat to 120 BPM");
      }
      const beatLengthMs = roundTime(periodSec * 1000, 6);
      redLines.push({
        offsetMs: roundTime(b.timeSec * 1000, 3),
        beatLengthMs,
        bpm: roundTime(60000 / beatLengthMs, 4),
        meter: defaultMeter,
        startBeatId: b.id,
        beatCount: 1,
        kind: "steady_run",
      });
      steadyRunCount += 1;
      continue;
    }

    let idx = 0;
    while (idx < grp.beats.length - 1) {
      let end = idx + 1;
      let bestFit = fitSteadyLinearRun(grp.beats.slice(idx, end + 1));

      while (end + 1 < grp.beats.length) {
        const candidateRun = grp.beats.slice(idx, end + 2);
        const candidateFit = fitSteadyLinearRun(candidateRun);
        if (candidateFit.period > 0 && candidateFit.maxResidualMs <= jitterTolMs) {
          end += 1;
          bestFit = candidateFit;
        } else {
          break;
        }
      }

      const runBeats = grp.beats.slice(idx, end + 1);
      const isFinalRun = end === grp.beats.length - 1;
      const ownedBeatCount = isFinalRun ? runBeats.length : end - idx;
      const periodSec = bestFit.period > 0 ? bestFit.period : 0.5;
      const beatLengthMs = roundTime(periodSec * 1000, 6);
      const offsetMs = roundTime(bestFit.t0 * 1000, 3);

      redLines.push({
        offsetMs,
        beatLengthMs,
        bpm: roundTime(60000 / beatLengthMs, 4),
        meter: defaultMeter,
        startBeatId: runBeats[0].id,
        beatCount: ownedBeatCount,
        kind: "steady_run",
      });
      steadyRunCount += 1;
      if (runBeats.length > 1) {
        collapsedBeatCount += ownedBeatCount;
      }

      if (isFinalRun) {
        // If seamlessly connected to next segment's first beat at a shifted transition interval, bridge cleanly from last beat
        if (grp.nextConnectedBeatSec !== null) {
          const lastBeat = grp.beats[grp.beats.length - 1];
          const predNextSec = bestFit.t0 + runBeats.length * periodSec;
          if (Math.abs(grp.nextConnectedBeatSec - predNextSec) * 1000 > jitterTolMs) {
            const bridgeSec = grp.nextConnectedBeatSec - lastBeat.timeSec;
            if (bridgeSec > 1e-6 && bridgeSec <= maxInterpolatedIbiSec) {
              const bridgeMs = roundTime(bridgeSec * 1000, 6);
              redLines.push({
                offsetMs: roundTime(lastBeat.timeSec * 1000, 3),
                beatLengthMs: bridgeMs,
                bpm: roundTime(60000 / bridgeMs, 4),
                meter: defaultMeter,
                startBeatId: lastBeat.id,
                beatCount: 1,
                kind: "steady_run",
              });
            }
          }
        }
        break;
      }
      // Share pivot beat `end` so tempo change starts right at the transition beat (never one beat late)
      idx = end;
    }
  }

  if (collapsedBeatCount > 0) {
    losses.push(
      `Collapsed ${collapsedBeatCount} steady beats into ${steadyRunCount} linear BPM run${steadyRunCount === 1 ? "" : "s"}`,
    );
  }
  if (freeGroupCount > 0) {
    losses.push(
      `Preserved beat-level rubato timing across ${freeGroupCount} Free section${freeGroupCount === 1 ? "" : "s"}`,
    );
  }
  if (breakSplitCount > 0 || omittedBreakBeats > 0) {
    losses.push("Restarted red lines cleanly after Break or long silence intervals");
  }

  const osuTimingPoints = redLines
    .map((rl) => `${rl.offsetMs},${rl.beatLengthMs},${rl.meter},1,0,100,1,0`)
    .join("\n");

  return {
    barPolicy: "barlines_not_inferred",
    redLines,
    osuTimingPoints,
    losses,
  };
}

/**
 * Subtract sorted non-overlapping `keep` spans from `[segT0, segT1)`.
 * @param {number} segT0
 * @param {number} segT1
 * @param {readonly Array<{ t0: number, t1: number }>} keepSpans
 */
function unlockedSubIntervals(segT0, segT1, keepSpans) {
  /** @type {Array<{ t0: number, t1: number }>} */
  let intervals = [{ t0: segT0, t1: segT1 }];
  for (const ks of keepSpans) {
    /** @type {Array<{ t0: number, t1: number }>} */
    const next = [];
    for (const iv of intervals) {
      if (ks.t1 <= iv.t0 + 1e-9 || ks.t0 >= iv.t1 - 1e-9) {
        next.push(iv);
        continue;
      }
      if (iv.t0 < ks.t0 - 1e-9) next.push({ t0: iv.t0, t1: ks.t0 });
      if (iv.t1 > ks.t1 + 1e-9) next.push({ t0: ks.t1, t1: iv.t1 });
    }
    intervals = next;
    if (intervals.length === 0) break;
  }
  return intervals;
}

/**
 * Priority-rank reviewable Inbox `Span`s strictly inside single segments and
 * **excluding** all `keep` `Segment`s and `keep` `Span`s.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").InboxSpanOptions} [options]
 * @returns {import("../index.d.ts").Span[]}
 */
export function rankInboxSpans(state, options = {}) {
  const windowSec = options.windowSec ?? 4;
  const lowConfThreshold = options.lowConfThreshold ?? 0.7;
  const jumpRatioThreshold = options.jumpRatioThreshold ?? 0.12;
  const includeReviewedClean = Boolean(options.includeReviewedClean);

  if (typeof windowSec !== "number" || !Number.isFinite(windowSec) || windowSec <= 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankInboxSpans windowSec must be a positive finite number, got ${windowSec}`,
    );
  }
  if (typeof lowConfThreshold !== "number" || !Number.isFinite(lowConfThreshold)) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankInboxSpans lowConfThreshold must be a finite number, got ${lowConfThreshold}`,
    );
  }
  if (
    typeof jumpRatioThreshold !== "number" ||
    !Number.isFinite(jumpRatioThreshold) ||
    jumpRatioThreshold < 0
  ) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankInboxSpans jumpRatioThreshold must be a non-negative finite number, got ${jumpRatioThreshold}`,
    );
  }

  const sortedBeats = toSortedByTime(state.beats);
  const sortedSounds = toSortedByTime(state.sounds);

  /** @type {import("../index.d.ts").Segment[]} */
  let segments = toSortedSegments(state.segments);
  if (segments.length === 0) {
    let maxT = state.durationSec || state.audio?.durationSec || 0;
    if (sortedBeats.length > 0) {
      maxT = Math.max(maxT, sortedBeats[sortedBeats.length - 1].timeSec + 0.001);
    }
    if (sortedSounds.length > 0) {
      maxT = Math.max(maxT, sortedSounds[sortedSounds.length - 1].timeSec + 0.001);
    }
    if (maxT <= 0) return [];
    segments = [
      {
        id: "seg_0",
        t0: 0,
        t1: maxT,
        regime: "auto",
        kept: false,
        source: "draft",
        revision: 1,
      },
    ];
  }

  const gridFit = computeGridFit(sortedSounds, sortedBeats, segments, options.gridFitOptions);
  const soundById = new Map(sortedSounds.map((s) => [s.id, s]));
  const constraints = compileConstraints({
    ...state,
    segments,
  });

  const maxIbi = options.gridFitOptions?.maxInterpolatedIbiSec ?? DEFAULT_MAX_INTERPOLATED_IBI_SEC;

  // Precompute sudden IBI jumps strictly within the same `auto` segment
  /** @type {Array<{ segmentId: string, timeSec: number, ratio: number }>} */
  const ibiJumps = [];
  for (let i = 2; i < sortedBeats.length; i += 1) {
    const b0 = sortedBeats[i - 2];
    const b1 = sortedBeats[i - 1];
    const b2 = sortedBeats[i];
    const s0 = findSegmentAt(segments, b0.timeSec);
    const s1 = findSegmentAt(segments, b1.timeSec);
    const s2 = findSegmentAt(segments, b2.timeSec);
    if (!s0 || !s1 || !s2 || s0.id !== s1.id || s1.id !== s2.id || s1.regime !== "auto") {
      continue;
    }
    const prevIbi = b1.timeSec - b0.timeSec;
    const currIbi = b2.timeSec - b1.timeSec;
    if (prevIbi <= 0 || currIbi <= 0 || prevIbi > maxIbi || currIbi > maxIbi) continue;
    const ratio = Math.abs(currIbi - prevIbi) / Math.max(currIbi, prevIbi);
    if (ratio > jumpRatioThreshold) {
      ibiJumps.push({ segmentId: s1.id, timeSec: b1.timeSec, ratio });
    }
  }

  const conflictedOffers = Array.from(state.offers?.values?.() ?? []).filter(
    (o) => (o.status === "stale" || o.status === "invalid") && o.conflicts?.length > 0,
  );
  const visitedRanges = Array.isArray(state.visitedRanges) ? state.visitedRanges : [];

  /** @type {import("../index.d.ts").Span[]} */
  const candidates = [];
  let inboxIdx = 0;

  for (const seg of segments) {
    if (seg.kept) continue;

    const keepSpansInSeg = (state.spans ?? [])
      .filter((sp) => sp.segmentId === seg.id && sp.kind === "keep")
      .sort((a, b) => a.t0 - b.t0 || a.t1 - b.t1);

    const unlocked = unlockedSubIntervals(seg.t0, seg.t1, keepSpansInSeg);

    // Explicit provider `inbox` spans inside this segment (clipped against Keep locks)
    const explicitInbox = (state.spans ?? []).filter(
      (sp) => sp.segmentId === seg.id && sp.kind === "inbox",
    );

    for (const sub of unlocked) {
      const len = sub.t1 - sub.t0;
      if (len <= 1e-6) continue;

      if (seg.regime === "break") {
        // Break segments only surface in Inbox if stray beats still sit inside them
        const strayBeats = sortedBeats.filter((b) => rangeContainsTime(sub, b.timeSec));
        if (strayBeats.length > 0) {
          candidates.push(
            normalizeSpan({
              id: `inbox_${inboxIdx++}`,
              segmentId: seg.id,
              t0: roundTime(sub.t0, 4),
              t1: roundTime(sub.t1, 4),
              kind: "inbox",
              priority: "medium",
              reason: `${strayBeats.length} stray beat${strayBeats.length === 1 ? "" : "s"} inside Break section`,
              score: roundTime(2.0 + strayBeats.length, 4),
              source: "draft",
              revision: 1,
            }),
          );
        }
        continue;
      }

      const winCount = Math.max(1, Math.ceil(len / windowSec - 1e-9));
      for (let w = 0; w < winCount; w += 1) {
        const t0 = roundTime(sub.t0 + w * windowSec, 4);
        const t1 = roundTime(Math.min(sub.t1, sub.t0 + (w + 1) * windowSec), 4);
        if (t1 <= t0 + 1e-6) continue;
        const win = { t0, t1 };
        const dur = Math.max(1, t1 - t0);

        // 1. Low-confidence beats
        let lowConfSum = 0;
        let lowConfCount = 0;
        for (const beat of sortedBeats) {
          if (!rangeContainsTime(win, beat.timeSec)) continue;
          if (beat.confidence !== undefined && beat.confidence < lowConfThreshold) {
            lowConfCount += 1;
            lowConfSum += 1 - beat.confidence;
          }
        }
        const lowConfScore = lowConfSum * 2.2;

        // 2. Sudden tempo jumps
        let jumpScore = 0;
        /** @type {number | null} */
        let firstJumpSec = null;
        let jumpCount = 0;
        for (const j of ibiJumps) {
          if (j.segmentId === seg.id && rangeContainsTime(win, j.timeSec)) {
            jumpCount += 1;
            if (firstJumpSec === null) firstJumpSec = j.timeSec;
            jumpScore += 1.6 + j.ratio * 2.2;
          }
        }

        // 3. Off-grid / unexplained sound onsets
        let unexplainedWeight = 0;
        let unexplainedCount = 0;
        for (const gf of gridFit) {
          if (gf.status !== "unexplained" || !rangeContainsTime(win, gf.timeSec)) continue;
          unexplainedCount += 1;
          const strength = soundById.get(gf.soundId)?.strength ?? 0.6;
          unexplainedWeight += 0.5 + strength;
        }
        const unexplainedScore = (unexplainedWeight / dur) * 3.0;

        // 4. Beats lingering inside an UnbeatCue repulsion zone
        let mutedTensionCount = 0;
        /** @type {number | null} */
        let firstMutedSec = null;
        for (const rep of constraints.unbeatRepulsions) {
          if (!rangeContainsTime(win, rep.timeSec)) continue;
          const hit = sortedBeats.some(
            (b) => Math.abs(b.timeSec - rep.timeSec) <= rep.repulsionRadiusSec,
          );
          if (hit) {
            mutedTensionCount += 1;
            if (firstMutedSec === null) firstMutedSec = rep.timeSec;
          }
        }
        const mutedScore = mutedTensionCount * 2.0;

        // 5. Conflicted / stale suggestions overlapping window
        let conflictCount = 0;
        for (const off of conflictedOffers) {
          if (off.writeScopes.some((ws) => rangesOverlap(ws, win))) {
            conflictCount += 1;
          }
        }
        const conflictScore = conflictCount * 1.8;

        // 6. Explicit provider inbox hint overlapping window
        const explicitMatch = explicitInbox.find((sp) => rangesOverlap(sp, win));
        const explicitBonus =
          explicitMatch?.priority === "high"
            ? 4.0
            : explicitMatch?.priority === "medium"
              ? 2.0
              : explicitMatch
                ? 1.0
                : 0;

        const visited = visitedRanges.some((vr) => rangesOverlap(vr, win));
        const unvisitedScore = visited ? 0 : 0.6;

        const totalScore = roundTime(
          lowConfScore +
            jumpScore +
            unexplainedScore +
            mutedScore +
            conflictScore +
            explicitBonus +
            unvisitedScore,
          4,
        );

        if (totalScore <= 0 && !includeReviewedClean) {
          continue;
        }

        /** @type {string[]} */
        const reasonParts = [];
        if (explicitMatch?.reason) {
          reasonParts.push(explicitMatch.reason);
        }
        if (unexplainedCount > 0) {
          reasonParts.push(
            `${unexplainedCount} off-grid onset${unexplainedCount === 1 ? "" : "s"}`,
          );
        }
        if (jumpCount > 0 && firstJumpSec !== null) {
          reasonParts.push(
            jumpCount === 1
              ? `sudden tempo shift near ${formatTimeCode(firstJumpSec)}`
              : `${jumpCount} sudden tempo shifts`,
          );
        }
        if (lowConfCount > 0) {
          reasonParts.push("uncertain beat detection");
        }
        if (mutedTensionCount > 0 && firstMutedSec !== null) {
          reasonParts.push(`beat near muted tick at ${formatTimeCode(firstMutedSec)}`);
        }
        if (conflictCount > 0) {
          reasonParts.push("outdated timing suggestion");
        }
        if (reasonParts.length === 0) {
          reasonParts.push(visited ? "Reviewed passage" : "Unreviewed passage");
        }

        const rawReason = reasonParts.slice(0, 2).join(" and ");
        const reason = rawReason.charAt(0).toUpperCase() + rawReason.slice(1);
        const priority =
          totalScore >= 4.0 ? "high" : totalScore >= 1.5 ? "medium" : "low";

        candidates.push(
          normalizeSpan({
            id: `inbox_${inboxIdx++}`,
            segmentId: seg.id,
            t0: win.t0,
            t1: win.t1,
            kind: "inbox",
            priority,
            reason,
            score: totalScore,
            source: "draft",
            revision: 1,
          }),
        );
      }
    }
  }

  const priorityRank = { high: 3, medium: 2, low: 1 };
  candidates.sort(
    (a, b) =>
      (priorityRank[b.priority ?? "low"] ?? 0) - (priorityRank[a.priority ?? "low"] ?? 0) ||
      (b.score ?? 0) - (a.score ?? 0) ||
      a.t0 - b.t0,
  );
  return candidates;
}
