/**
 * Soft-first cue & hard Keep-lock compiler (`compileConstraints(state)`).
 *
 * Compiles current `SessionState` into:
 *  - `beatGuides`        : soft user-placed beat cues (`Beat` with `source === "user"`)
 *  - `unbeatRepulsions`  : period-scaled soft repulsion zones around `UnbeatCue`s
 *  - `soundObservations` : soft user-marked acoustic observations (`SoundAnchor` with `source === "user"`)
 *  - `freeIntervals`     : variable/rubato pulse regions (`Segment.regime === "free"` + `Span.kind === "free"`)
 *  - `keepLocks`         : hard immutable locks (`Segment.kept === true` + `Span.kind === "keep"`)
 */

import { roundTime } from "./hash.js";

export const DEFAULT_BEAT_GUIDE_TOL_SEC = 0.02;
export const DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC = 0.05;
export const MAX_UNBEAT_REPULSION_RADIUS_SEC = 0.08;

const LOCAL_IBI_WINDOW_SEC = 4.0;
const LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE = 4;
const MAX_PLAUSIBLE_IBI_SEC = 2.5;

/**
 * Find the segment enclosing `timeSec` via binary search on the contiguous `Segment[]` partition.
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @param {number} timeSec
 * @returns {import("../index.d.ts").Segment | null}
 */
function findEnclosingSegment(segments, timeSec) {
  let lo = 0;
  let hi = segments.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const seg = segments[mid];
    if (timeSec < seg.t0) {
      hi = mid - 1;
    } else if (timeSec >= seg.t1) {
      lo = mid + 1;
    } else {
      return seg;
    }
  }
  if (segments.length > 0 && timeSec === segments[segments.length - 1].t1) {
    return segments[segments.length - 1];
  }
  return null;
}

/**
 * Find index of first beat with `beat.timeSec >= timeSec`.
 * @param {readonly import("../index.d.ts").Beat[]} sortedBeats
 * @param {number} timeSec
 */
function lowerBoundBeats(sortedBeats, timeSec) {
  let lo = 0;
  let hi = sortedBeats.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (sortedBeats[mid].timeSec < timeSec) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return lo;
}

/**
 * Compute median of a non-empty numeric array.
 * @param {number[]} values
 */
function median(values) {
  const sorted = values.slice().sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  if ((sorted.length & 1) === 1) {
    return sorted[mid];
  }
  return (sorted[mid - 1] + sorted[mid]) / 2;
}

/**
 * Compute period-scaled repulsion radius around `timeSec`:
 * `Math.min(0.080, 0.25 * localIbiSec)` using local median IBI near `timeSec`,
 * falling back to `0.050` when fewer than 2 nearby beats exist.
 *
 * @param {number} timeSec
 * @param {readonly import("../index.d.ts").Beat[]} sortedBeats
 * @param {readonly import("../index.d.ts").Segment[]} segments
 */
function computeUnbeatRepulsion(timeSec, sortedBeats, segments) {
  if (sortedBeats.length < 2) {
    return {
      localIbiSec: null,
      repulsionRadiusSec: DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC,
    };
  }

  const seg = segments.length > 0 ? findEnclosingSegment(segments, timeSec) : null;
  const pivot = lowerBoundBeats(sortedBeats, timeSec);
  const startIdx = Math.max(0, pivot - LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE);
  const endIdx = Math.min(sortedBeats.length, pivot + LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE);

  /** @type {import("../index.d.ts").Beat[]} */
  const nearby = [];
  for (let i = startIdx; i < endIdx; i += 1) {
    const b = sortedBeats[i];
    if (Math.abs(b.timeSec - timeSec) > LOCAL_IBI_WINDOW_SEC) continue;
    if (seg && (b.timeSec < seg.t0 || b.timeSec >= seg.t1)) continue;
    nearby.push(b);
  }

  if (nearby.length < 2) {
    return {
      localIbiSec: null,
      repulsionRadiusSec: DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC,
    };
  }

  /** @type {number[]} */
  const ibis = [];
  for (let i = 1; i < nearby.length; i += 1) {
    const dt = nearby[i].timeSec - nearby[i - 1].timeSec;
    if (dt > 1e-6 && dt <= MAX_PLAUSIBLE_IBI_SEC) {
      ibis.push(dt);
    }
  }

  if (ibis.length === 0) {
    return {
      localIbiSec: null,
      repulsionRadiusSec: DEFAULT_UNBEAT_FALLBACK_RADIUS_SEC,
    };
  }

  const localIbiSec = roundTime(median(ibis), 6);
  const repulsionRadiusSec = roundTime(
    Math.min(MAX_UNBEAT_REPULSION_RADIUS_SEC, 0.25 * localIbiSec),
    6,
  );
  return { localIbiSec, repulsionRadiusSec };
}

/**
 * Compile soft cues and hard Keep locks from `SessionState`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").CompiledConstraints}
 */
export function compileConstraints(state) {
  const beatsIter =
    state?.beats instanceof Map
      ? Array.from(state.beats.values())
      : Array.isArray(state?.beats)
        ? state.beats
        : [];
  const soundsIter =
    state?.sounds instanceof Map
      ? Array.from(state.sounds.values())
      : Array.isArray(state?.sounds)
        ? state.sounds
        : [];
  const segments = Array.isArray(state?.segments) ? state.segments : [];
  const spans = Array.isArray(state?.spans) ? state.spans : [];
  const unbeats = Array.isArray(state?.unbeats) ? state.unbeats : [];

  const sortedBeats = beatsIter
    .slice()
    .sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id));

  /** @type {import("../index.d.ts").BeatGuideConstraint[]} */
  const beatGuides = [];
  for (const beat of sortedBeats) {
    if (beat.source !== "user") continue;
    /** @type {import("../index.d.ts").BeatGuideConstraint} */
    const guide = {
      beatId: beat.id,
      timeSec: beat.timeSec,
      toleranceSec: DEFAULT_BEAT_GUIDE_TOL_SEC,
    };
    if (beat.snapEventId !== undefined) guide.snapEventId = beat.snapEventId;
    if (beat.snapFamily !== undefined) guide.snapFamily = beat.snapFamily;
    if (beat.snapRole !== undefined) guide.snapRole = beat.snapRole;
    beatGuides.push(guide);
  }

  const sortedUnbeats = unbeats
    .slice()
    .sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id));

  /** @type {import("../index.d.ts").UnbeatRepulsionConstraint[]} */
  const unbeatRepulsions = sortedUnbeats.map((u) => {
    const { localIbiSec, repulsionRadiusSec } = computeUnbeatRepulsion(
      u.timeSec,
      sortedBeats,
      segments,
    );
    return {
      unbeatId: u.id,
      timeSec: u.timeSec,
      localIbiSec,
      repulsionRadiusSec,
    };
  });

  const sortedSounds = soundsIter
    .filter((s) => s.source === "user")
    .sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id));

  /** @type {import("../index.d.ts").SoundObservationConstraint[]} */
  const soundObservations = sortedSounds.map((s) => {
    /** @type {import("../index.d.ts").SoundObservationConstraint} */
    const obs = {
      soundId: s.id,
      timeSec: s.timeSec,
    };
    if (s.strength !== undefined) obs.strength = s.strength;
    if (s.family !== undefined) obs.family = s.family;
    if (s.role !== undefined) obs.role = s.role;
    if (s.sigmaMs !== undefined) obs.sigmaMs = s.sigmaMs;
    return obs;
  });

  const segMap = new Map(segments.map((s) => [s.id, s]));

  /** @type {import("../index.d.ts").FreeIntervalConstraint[]} */
  const freeIntervals = [];
  /** @type {import("../index.d.ts").KeepLockConstraint[]} */
  const keepLocks = [];

  for (const seg of segments) {
    if (seg.regime === "free") {
      freeIntervals.push({
        segmentId: seg.id,
        t0: seg.t0,
        t1: seg.t1,
      });
    }
    if (seg.kept) {
      keepLocks.push({
        segmentId: seg.id,
        t0: seg.t0,
        t1: seg.t1,
      });
    }
  }

  for (const span of spans) {
    const parent = segMap.get(span.segmentId);
    if (span.kind === "free" && parent?.regime !== "free") {
      freeIntervals.push({
        segmentId: span.segmentId,
        spanId: span.id,
        t0: span.t0,
        t1: span.t1,
      });
    } else if (span.kind === "keep" && !parent?.kept) {
      keepLocks.push({
        segmentId: span.segmentId,
        spanId: span.id,
        t0: span.t0,
        t1: span.t1,
      });
    }
  }

  freeIntervals.sort(
    (a, b) => a.t0 - b.t0 || a.t1 - b.t1 || a.segmentId.localeCompare(b.segmentId),
  );
  keepLocks.sort(
    (a, b) => a.t0 - b.t0 || a.t1 - b.t1 || a.segmentId.localeCompare(b.segmentId),
  );

  return {
    beatGuides,
    unbeatRepulsions,
    soundObservations,
    freeIntervals,
    keepLocks,
  };
}
