/**
 * Pure derived views (never stored in SessionState):
 *  - computeGridFit: classify SoundAnchors against beats/boundaries into on_grid | subdivision | unexplained
 *  - projectRedLines: collapse steady runs into .osu [TimingPoints] red lines without per-beat jitter spam
 *  - rankTroubleSpans: rank song spans for trouble-first listening tours
 */

import { roundTime } from "./hash.js";
import { EnsomiError, spanContainsTime, spansOverlap } from "./types.js";

/**
 * Normalize a Map or Iterable of entities to an array sorted by time ascending.
 * @template {{ id: string, t: number }} T
 * @param {Iterable<T> | Map<string, T>} items
 * @returns {T[]}
 */
function toSortedByTime(items) {
  if (!items) return [];
  const arr = items instanceof Map ? Array.from(items.values()) : Array.from(items);
  return arr.slice().sort((a, b) => a.t - b.t || a.id.localeCompare(b.id));
}

/**
 * Find the index of the rightmost beat with beat.t <= t using binary search.
 * Returns -1 if all beats are > t.
 * @param {Array<{ t: number }>} sortedBeats
 * @param {number} t
 * @returns {number}
 */
function findLeftBeatIndex(sortedBeats, t) {
  let lo = 0;
  let hi = sortedBeats.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (sortedBeats[mid].t <= t) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  return ans;
}

/**
 * Sweep gap_start / gap_end boundaries independently of beats to build active half-open gap spans [t0, t1).
 * A gap_start at t opens a gap immediately at t (covering [t, ...)), closed by a subsequent gap_end at t2.
 * When both gap_end and gap_start share the exact same timestamp, gap_end closes first before gap_start opens.
 *
 * @param {Array<{ id: string, t: number, kind: string }>} sortedBoundaries
 * @returns {Array<{ t0: number, t1: number }>}
 */
function buildGapSpans(sortedBoundaries) {
  const ordered = sortedBoundaries
    .filter((b) => b.kind === "gap_start" || b.kind === "gap_end")
    .sort((a, b) => {
      if (a.t !== b.t) return a.t - b.t;
      if (a.kind !== b.kind) return a.kind === "gap_end" ? -1 : 1;
      return a.id.localeCompare(b.id);
    });

  /** @type {Array<{ t0: number, t1: number }>} */
  const spans = [];
  /** @type {number | null} */
  let gapOpenAt = null;

  for (const b of ordered) {
    if (b.kind === "gap_start") {
      if (gapOpenAt === null) {
        gapOpenAt = b.t;
      }
    } else if (b.kind === "gap_end") {
      if (gapOpenAt !== null) {
        if (b.t > gapOpenAt) {
          spans.push({ t0: gapOpenAt, t1: b.t });
        }
        gapOpenAt = null;
      }
    }
  }
  if (gapOpenAt !== null) {
    spans.push({ t0: gapOpenAt, t1: Infinity });
  }
  return spans;
}

/**
 * Check whether an open beat interval (t0, t1) intersects any active gap span [g.t0, g.t1).
 * @param {Array<{ t0: number, t1: number }>} gapSpans
 * @param {number} t0
 * @param {number} t1
 */
function hasActiveGapInInterval(gapSpans, t0, t1) {
  for (const g of gapSpans) {
    if (g.t0 >= t1) break;
    if (g.t1 > t0) return true;
  }
  return false;
}

/**
 * Compute per-sound grid fit against current beats and boundaries.
 * Moving a beat automatically reclassifies every affected sound.
 * `residualMs` is signed: `(sound.t - nearestSlotTime) * 1000`
 * (negative = sound is early relative to slot, positive = sound is late relative to slot;
 * `Infinity` when there are 0 beats).
 *
 * @param {Iterable<import("../index.d.ts").SoundAnchor> | Map<string, import("../index.d.ts").SoundAnchor>} sounds
 * @param {Iterable<import("../index.d.ts").Beat> | Map<string, import("../index.d.ts").Beat>} beats
 * @param {Iterable<import("../index.d.ts").Boundary> | Map<string, import("../index.d.ts").Boundary>} [boundaries=[]]
 * @param {{ divisors?: number[], tolMs?: number }} [options]
 * @returns {import("../index.d.ts").GridFitItem[]}
 */
export function computeGridFit(sounds, beats, boundaries = [], options = {}) {
  const divisors = [...new Set(options.divisors ?? [1, 2, 3, 4, 6, 8])]
    .filter((d) => Number.isInteger(d) && d >= 1)
    .sort((a, b) => a - b);
  const tolMs = options.tolMs ?? 20;

  const sortedSounds = toSortedByTime(sounds);
  const sortedBeats = toSortedByTime(beats);
  const sortedBoundaries = toSortedByTime(boundaries);
  const gapSpans = buildGapSpans(sortedBoundaries);

  /** @type {import("../index.d.ts").GridFitItem[]} */
  const results = [];

  for (const sound of sortedSounds) {
    if (sortedBeats.length === 0) {
      results.push({
        soundId: sound.id,
        t: sound.t,
        nearestBeatDistMs: Infinity,
        nearestDivisor: null,
        residualMs: Infinity,
        status: "unexplained",
      });
      continue;
    }

    const leftIdx = findLeftBeatIndex(sortedBeats, sound.t);
    const rightIdx = leftIdx + 1 < sortedBeats.length ? leftIdx + 1 : -1;

    let nearestBeatDistMs = Infinity;
    let nearestBeatSignedMs = Infinity;
    if (leftIdx >= 0) {
      const signedL = (sound.t - sortedBeats[leftIdx].t) * 1000;
      const distL = Math.abs(signedL);
      if (distL < nearestBeatDistMs) {
        nearestBeatDistMs = distL;
        nearestBeatSignedMs = signedL;
      }
    }
    if (rightIdx >= 0) {
      const signedR = (sound.t - sortedBeats[rightIdx].t) * 1000;
      const distR = Math.abs(signedR);
      if (distR < nearestBeatDistMs) {
        nearestBeatDistMs = distR;
        nearestBeatSignedMs = signedR;
      }
    }

    // Direct beat hit (divisor 1) always takes precedence when within tolerance.
    if (divisors.includes(1) && nearestBeatDistMs <= tolMs) {
      results.push({
        soundId: sound.id,
        t: sound.t,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: 1,
        residualMs: roundTime(nearestBeatSignedMs, 4),
        status: "on_grid",
      });
      continue;
    }

    // Evaluate enclosing beat interval for subdivisions
    let bestWithinDivisor = null;
    let bestWithinAbsErr = Infinity;
    let bestWithinSignedMs = 0;
    let bestOverallAbsErr = nearestBeatDistMs;
    let bestOverallSignedMs = nearestBeatSignedMs;

    if (leftIdx >= 0 && rightIdx >= 0) {
      const b0 = sortedBeats[leftIdx];
      const b1 = sortedBeats[rightIdx];
      const interval = b1.t - b0.t;
      if (interval > 0 && !hasActiveGapInInterval(gapSpans, b0.t, b1.t)) {
        for (const d of divisors) {
          if (d === 1) continue;
          const pos = ((sound.t - b0.t) / interval) * d;
          const k = Math.round(pos);
          if (k <= 0 || k >= d) continue;
          const gridT = b0.t + (k / d) * interval;
          const signedMs = (sound.t - gridT) * 1000;
          const errMs = Math.abs(signedMs);
          if (errMs < bestOverallAbsErr) {
            bestOverallAbsErr = errMs;
            bestOverallSignedMs = signedMs;
          }
          if (errMs <= tolMs) {
            // Prefer significantly tighter residual, else simpler (smaller) divisor
            if (bestWithinDivisor === null || errMs < bestWithinAbsErr - 0.5) {
              bestWithinDivisor = d;
              bestWithinAbsErr = errMs;
              bestWithinSignedMs = signedMs;
            }
          }
        }
      }
    }

    if (bestWithinDivisor !== null) {
      results.push({
        soundId: sound.id,
        t: sound.t,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: bestWithinDivisor,
        residualMs: roundTime(bestWithinSignedMs, 4),
        status: "subdivision",
      });
    } else {
      results.push({
        soundId: sound.id,
        t: sound.t,
        nearestBeatDistMs: roundTime(nearestBeatDistMs, 4),
        nearestDivisor: null,
        residualMs: roundTime(bestOverallSignedMs, 4),
        status: "unexplained",
      });
    }
  }

  return results;
}

/**
 * Ordinary least-squares linear fit for beat timestamps t[i] ≈ t0 + i * period, i = 0..n-1.
 * @param {Array<{ t: number }>} run
 */
function fitSteadyLinearRun(run) {
  const n = run.length;
  if (n < 2) {
    return { t0: run[0].t, period: 0.5, maxResidualMs: 0 };
  }
  const meanI = (n - 1) / 2;
  let sumT = 0;
  for (let i = 0; i < n; i += 1) sumT += run[i].t;
  const meanT = sumT / n;

  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    const di = i - meanI;
    num += di * (run[i].t - meanT);
    den += di * di;
  }
  const period = den > 0 ? num / den : run[1].t - run[0].t;
  const t0 = meanT - period * meanI;

  let maxResidualMs = 0;
  for (let i = 0; i < n; i += 1) {
    const pred = t0 + i * period;
    const errMs = Math.abs(run[i].t - pred) * 1000;
    if (errMs > maxResidualMs) maxResidualMs = errMs;
  }
  return { t0, period, maxResidualMs };
}

/**
 * Project beats and boundaries into .osu [TimingPoints] red lines (uninherited timing points).
 * Collapses steady runs via linear fit within `jitterTolMs` so per-beat jitter is not spammed,
 * explicitly returning `barPolicy: "barlines_not_inferred"` and a `losses` report.
 *
 * @param {Iterable<import("../index.d.ts").Beat> | Map<string, import("../index.d.ts").Beat>} beats
 * @param {Iterable<import("../index.d.ts").Boundary> | Map<string, import("../index.d.ts").Boundary>} [boundaries=[]]
 * @param {{ defaultMeter?: number, jitterTolMs?: number }} [options]
 * @returns {import("../index.d.ts").RedLineProjection}
 */
export function projectRedLines(beats, boundaries = [], options = {}) {
  const defaultMeter = options.defaultMeter ?? 4;
  const jitterTolMs = options.jitterTolMs ?? 15;

  const sortedBeats = toSortedByTime(beats);
  const sortedBoundaries = toSortedByTime(boundaries);

  /** @type {string[]} */
  const losses = [
    `barlines_not_inferred: meter defaulted to ${defaultMeter}/4 without downbeat inference`,
  ];

  if (sortedBeats.length === 0) {
    losses.push("empty_beats: no beats available to project red lines");
    return {
      barPolicy: "barlines_not_inferred",
      redLines: [],
      osuTimingPoints: "",
      losses,
    };
  }

  // Partition beats into contiguous regime segments guided by boundaries.
  /** @type {Array<{ regime: "steady" | "free", bpmHint?: number, beats: import("../index.d.ts").Beat[] }>} */
  const segments = [];
  let currentRegime = /** @type {"steady" | "free" | "gap"} */ ("steady");
  /** @type {number | undefined} */
  let currentBpmHint = undefined;
  /** @type {import("../index.d.ts").Beat[]} */
  let currentBeats = [];
  let bIdx = 0;
  let gapCount = 0;
  let freeSegmentCount = 0;

  const flushSegment = () => {
    if (currentBeats.length > 0 && currentRegime !== "gap") {
      segments.push({
        regime: currentRegime,
        bpmHint: currentBpmHint,
        beats: currentBeats,
      });
    }
    currentBeats = [];
  };

  for (const beat of sortedBeats) {
    while (bIdx < sortedBoundaries.length && sortedBoundaries[bIdx].t <= beat.t + 1e-6) {
      const boundary = sortedBoundaries[bIdx];
      bIdx += 1;
      if (boundary.kind === "steady_start") {
        flushSegment();
        currentRegime = "steady";
        currentBpmHint = boundary.bpm;
      } else if (boundary.kind === "reset") {
        flushSegment();
        currentRegime = "steady";
        currentBpmHint = boundary.bpm ?? currentBpmHint;
      } else if (boundary.kind === "free_start") {
        flushSegment();
        currentRegime = "free";
        currentBpmHint = undefined;
        freeSegmentCount += 1;
      } else if (boundary.kind === "gap_start") {
        flushSegment();
        currentRegime = "gap";
        currentBpmHint = undefined;
        gapCount += 1;
      } else if (boundary.kind === "gap_end") {
        flushSegment();
        currentRegime = "steady";
        currentBpmHint = boundary.bpm;
      }
    }

    if (currentRegime === "gap") {
      // A beat explicitly present during a gap resumes a steady segment at that beat.
      currentRegime = "steady";
    }
    currentBeats.push(beat);
  }
  flushSegment();

  /** @type {import("../index.d.ts").RedLine[]} */
  const redLines = [];
  let collapsedBeatCount = 0;
  let steadyRunCount = 0;

  for (const seg of segments) {
    if (seg.regime === "free") {
      for (let i = 0; i < seg.beats.length; i += 1) {
        const b = seg.beats[i];
        let periodSec;
        if (i + 1 < seg.beats.length && seg.beats[i + 1].t > b.t) {
          periodSec = seg.beats[i + 1].t - b.t;
        } else if (i > 0 && b.t > seg.beats[i - 1].t) {
          periodSec = b.t - seg.beats[i - 1].t;
        } else {
          periodSec = 0.5;
        }
        const beatLengthMs = roundTime(periodSec * 1000, 6);
        redLines.push({
          offsetMs: roundTime(b.t * 1000, 3),
          beatLengthMs,
          bpm: roundTime(60000 / beatLengthMs, 4),
          meter: defaultMeter,
          startBeatId: b.id,
          beatCount: 1,
          kind: "free_fallback",
        });
      }
      continue;
    }

    // Steady segment: segment inter-beat intervals via OLS linear fit within jitterTolMs,
    // sharing the pivot beat between adjoining runs so tempo transitions start at the pivot beat
    // with zero phantom beats.
    if (seg.beats.length === 1) {
      const b = seg.beats[0];
      const fallbackBpm =
        seg.bpmHint ??
        (redLines.length > 0 ? redLines[redLines.length - 1].bpm : 120);
      const beatLengthMs = roundTime(60000 / fallbackBpm, 6);
      redLines.push({
        offsetMs: roundTime(b.t * 1000, 3),
        beatLengthMs,
        bpm: roundTime(fallbackBpm, 4),
        meter: defaultMeter,
        startBeatId: b.id,
        beatCount: 1,
        kind: "steady_run",
      });
      steadyRunCount += 1;
      if (!seg.bpmHint && redLines.length === 1) {
        losses.push("single_beat_tempo_assumed: defaulted isolated beat to 120 BPM");
      }
      continue;
    }

    let idx = 0;
    while (idx < seg.beats.length - 1) {
      let end = idx + 1;
      let bestFit = fitSteadyLinearRun(seg.beats.slice(idx, end + 1));

      while (end + 1 < seg.beats.length) {
        const candidateRun = seg.beats.slice(idx, end + 2);
        const candidateFit = fitSteadyLinearRun(candidateRun);
        if (candidateFit.period > 0 && candidateFit.maxResidualMs <= jitterTolMs) {
          end += 1;
          bestFit = candidateFit;
        } else {
          break;
        }
      }

      const runBeats = seg.beats.slice(idx, end + 1);
      const isFinalRun = end === seg.beats.length - 1;
      const ownedBeatCount = isFinalRun ? runBeats.length : end - idx;
      const periodSec =
        bestFit.period > 0
          ? bestFit.period
          : seg.bpmHint
            ? 60 / seg.bpmHint
            : 0.5;
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
        break;
      }
      // Share the pivot beat `end` as the starting beat of the next run!
      idx = end;
    }
  }

  if (collapsedBeatCount > 0) {
    losses.push(
      `jitter_collapsed: collapsed ${collapsedBeatCount} steady beats into ${steadyRunCount} run(s) within ${jitterTolMs}ms tolerance`,
    );
  }
  if (freeSegmentCount > 0) {
    losses.push(
      `free_region_approximated: ${freeSegmentCount} free/rubato segment(s) projected as per-beat fallback red lines`,
    );
  }
  if (gapCount > 0) {
    losses.push(`gap_regions_unrepresented: ${gapCount} gap boundary transition(s) split runs without silence markers`);
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
 * Rank song spans by a transparent combination of:
 *  - low beat confidence
 *  - unexplained inter-beat interval (IBI) jumps
 *  - unexplained sound anchor density
 *  - active offer conflicts
 *  - unvisited status
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {{ windowSec?: number, lowConfThreshold?: number, jumpRatioThreshold?: number, gridFitOptions?: { divisors?: number[], tolMs?: number } }} [options]
 * @returns {import("../index.d.ts").TroubleSpan[]}
 */
export function rankTroubleSpans(state, options = {}) {
  const windowSec = options.windowSec ?? 4;
  const lowConfThreshold = options.lowConfThreshold ?? 0.7;
  const jumpRatioThreshold = options.jumpRatioThreshold ?? 0.12;
  if (typeof windowSec !== "number" || !Number.isFinite(windowSec) || windowSec <= 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankTroubleSpans windowSec must be a positive finite number, got ${windowSec}`,
    );
  }
  if (typeof lowConfThreshold !== "number" || !Number.isFinite(lowConfThreshold)) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankTroubleSpans lowConfThreshold must be a finite number, got ${lowConfThreshold}`,
    );
  }
  if (
    typeof jumpRatioThreshold !== "number" ||
    !Number.isFinite(jumpRatioThreshold) ||
    jumpRatioThreshold < 0
  ) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `rankTroubleSpans jumpRatioThreshold must be a non-negative finite number, got ${jumpRatioThreshold}`,
    );
  }

  const sortedBeats = toSortedByTime(state.beats);
  const sortedBoundaries = toSortedByTime(state.boundaries);
  const sortedSounds = toSortedByTime(state.sounds);

  let maxT = state.audio?.durationSec ?? 0;
  if (sortedBeats.length > 0) maxT = Math.max(maxT, sortedBeats[sortedBeats.length - 1].t + 0.001);
  if (sortedSounds.length > 0) maxT = Math.max(maxT, sortedSounds[sortedSounds.length - 1].t + 0.001);
  if (sortedBoundaries.length > 0) {
    maxT = Math.max(maxT, sortedBoundaries[sortedBoundaries.length - 1].t + 0.001);
  }

  if (maxT <= 0) return [];

  const gridFit = computeGridFit(sortedSounds, sortedBeats, sortedBoundaries, options.gridFitOptions);
  const soundById = new Map(sortedSounds.map((s) => [s.id, s]));

  // Precompute unexplained IBI jumps (times where IBI changes sharply without a reset/steady_start boundary)
  /** @type {Array<{ t: number, ratio: number }>} */
  const ibiJumps = [];
  for (let i = 2; i < sortedBeats.length; i += 1) {
    const prevIbi = sortedBeats[i - 1].t - sortedBeats[i - 2].t;
    const currIbi = sortedBeats[i].t - sortedBeats[i - 1].t;
    if (prevIbi <= 0 || currIbi <= 0) continue;
    const ratio = Math.abs(currIbi - prevIbi) / Math.max(currIbi, prevIbi);
    if (ratio > jumpRatioThreshold) {
      const explainedByBoundary = sortedBoundaries.some(
        (b) =>
          (b.kind === "reset" || b.kind === "steady_start" || b.kind === "gap_start") &&
          b.t >= sortedBeats[i - 2].t - 1e-6 &&
          b.t <= sortedBeats[i].t + 1e-6,
      );
      if (!explainedByBoundary) {
        ibiJumps.push({ t: sortedBeats[i - 1].t, ratio });
      }
    }
  }

  // Active offers with conflicts
  const conflictedOffers = Array.from(state.offers.values()).filter(
    (o) => (o.status === "pending" || o.status === "stale") && o.conflicts && o.conflicts.length > 0,
  );

  const windowCount = Math.max(1, Math.ceil(maxT / windowSec));
  /** @type {import("../index.d.ts").TroubleSpan[]} */
  const ranked = [];

  for (let w = 0; w < windowCount; w += 1) {
    const t0 = roundTime(w * windowSec, 4);
    const t1 = roundTime(Math.min(maxT, (w + 1) * windowSec), 4);
    if (t1 <= t0) continue;
    const span = { t0, t1 };
    const duration = t1 - t0;

    // 1. Low confidence beats
    let lowConfSum = 0;
    let lowConfCount = 0;
    for (const beat of sortedBeats) {
      if (!spanContainsTime(span, beat.t)) continue;
      if (beat.conf !== undefined && beat.conf < lowConfThreshold) {
        lowConfCount += 1;
        lowConfSum += 1 - beat.conf;
      }
    }
    const lowConf = roundTime(lowConfSum * 2.0, 4);

    // 2. Interval jumps
    let jumpScore = 0;
    let jumpCount = 0;
    for (const jump of ibiJumps) {
      if (spanContainsTime(span, jump.t)) {
        jumpCount += 1;
        jumpScore += 1.5 + jump.ratio * 2.0;
      }
    }
    const intervalJumps = roundTime(jumpScore, 4);

    // 3. Unexplained sound density (emphasis-weighted)
    let unexplainedWeight = 0;
    let unexplainedCount = 0;
    for (const gf of gridFit) {
      if (gf.status !== "unexplained") continue;
      if (!spanContainsTime(span, gf.t)) continue;
      unexplainedCount += 1;
      const emphasis = soundById.get(gf.soundId)?.emphasis ?? 0.5;
      unexplainedWeight += 0.5 + emphasis;
    }
    const unexplainedSounds = roundTime((unexplainedWeight / Math.max(duration, 1)) * 3.0, 4);

    // 4. Active conflicts overlapping window
    let conflictCount = 0;
    for (const offer of conflictedOffers) {
      if (spansOverlap(offer.writeScope, span)) {
        conflictCount += offer.conflicts.length;
      }
    }
    const activeConflicts = roundTime(conflictCount * 2.5, 4);

    // 5. Unvisited status
    const visited = state.visitedSpans.some((vs) => spansOverlap(vs, span));
    const unvisited = visited ? 0 : 0.5;

    const score = roundTime(
      lowConf + intervalJumps + unexplainedSounds + activeConflicts + unvisited,
      4,
    );

    /** @type {string[]} */
    const reasons = [];
    if (lowConfCount > 0) reasons.push(`${lowConfCount} low-confidence beat(s)`);
    if (jumpCount > 0) reasons.push(`${jumpCount} interval jump(s)`);
    if (unexplainedCount > 0) reasons.push(`${unexplainedCount} unexplained sound(s)`);
    if (conflictCount > 0) reasons.push(`${conflictCount} active conflict(s)`);
    if (!visited) reasons.push("unvisited");

    ranked.push({
      span,
      score,
      visited,
      breakdown: {
        lowConf,
        intervalJumps,
        unexplainedSounds,
        activeConflicts,
        unvisited,
      },
      reasons,
    });
  }

  ranked.sort((a, b) => b.score - a.score || a.span.t0 - b.span.t0);
  return ranked;
}
