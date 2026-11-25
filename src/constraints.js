/**
 * Epistemic constraint compiler (`compileConstraints(snapshotOrState)`).
 *
 * Compiles human-truth cues and locks from either a live `SessionState` or a JSON `Snapshot`:
 *  - `beatGuides`          : ONLY `Beat`s carrying a human `verdict`
 *  - `unbeatRepulsions`    : ONLY `Unbeat`s carrying a human `verdict`
 *  - `soundObservations`   : `Sound`s with explicit `verified: boolean` + facet metadata
 *  - `soundClasses`        : `SoundClass`es with explicit `verified: boolean`
 *  - `boundaryConstraints` : ONLY `Boundary`s carrying a human `verdict`
 *  - `freeIntervals`       : `Segment`s with `regime === "free"` + child `"free"` `Span`s
 *  - `keepLocks`           : `Approval` locks from `Segment.approval` and `Span.approval`
 */

import { compareCodeUnits } from "./jcs.js";
import { DEFAULT_BEAT_TOL_US, DEFAULT_UNBEAT_TOL_US, deepFreeze } from "./types.js";

export const DEFAULT_UNBEAT_FALLBACK_RADIUS_US = DEFAULT_UNBEAT_TOL_US;
export const MAX_UNBEAT_REPULSION_RADIUS_US = 80_000;

const LOCAL_IBI_WINDOW_US = 4_000_000;
const LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE = 4;
const MAX_PLAUSIBLE_IBI_US = 2_500_000;

/**
 * Convert a Map or Array collection to an Array.
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
 * Binary-search the enclosing segment for `atUs`.
 * @param {readonly import("../index.d.ts").Segment[]} segments
 * @param {number} atUs
 * @returns {import("../index.d.ts").Segment | null}
 */
function findEnclosingSegment(segments, atUs) {
  let lo = 0;
  let hi = segments.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const seg = segments[mid];
    if (atUs < seg.startUs) {
      hi = mid - 1;
    } else if (atUs >= seg.endUs) {
      lo = mid + 1;
    } else {
      return seg;
    }
  }
  if (segments.length > 0 && atUs === segments[segments.length - 1].endUs) {
    return segments[segments.length - 1];
  }
  return null;
}

/**
 * Lower-bound index in `sortedBeats` where `beat.atUs >= atUs`.
 * @param {readonly import("../index.d.ts").Beat[]} sortedBeats
 * @param {number} atUs
 */
function lowerBoundBeats(sortedBeats, atUs) {
  let lo = 0;
  let hi = sortedBeats.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (sortedBeats[mid].atUs < atUs) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return lo;
}

/**
 * Integer-rounded median of a non-empty numeric array.
 * @param {number[]} values
 */
function medianRoundUs(values) {
  const sorted = values.slice().sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  if ((sorted.length & 1) === 1) {
    return sorted[mid];
  }
  return Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

/**
 * Compute local IBI and effective repulsion radius in integer microseconds for an `Unbeat`.
 *
 * @param {import("../index.d.ts").Unbeat} unbeat
 * @param {readonly import("../index.d.ts").Beat[]} sortedBeats
 * @param {readonly import("../index.d.ts").Segment[]} segments
 */
function computeUnbeatRepulsionRadius(unbeat, sortedBeats, segments) {
  const baseTolUs = Math.min(MAX_UNBEAT_REPULSION_RADIUS_US, unbeat.tolUs);
  if (sortedBeats.length < 2) {
    return {
      localIbiUs: null,
      repulsionRadiusUs: baseTolUs,
    };
  }

  const seg = segments.length > 0 ? findEnclosingSegment(segments, unbeat.atUs) : null;
  const pivot = lowerBoundBeats(sortedBeats, unbeat.atUs);
  const startIdx = Math.max(0, pivot - LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE);
  const endIdx = Math.min(sortedBeats.length, pivot + LOCAL_IBI_MAX_NEIGHBORS_PER_SIDE);

  /** @type {import("../index.d.ts").Beat[]} */
  const nearby = [];
  for (let i = startIdx; i < endIdx; i += 1) {
    const b = sortedBeats[i];
    if (Math.abs(b.atUs - unbeat.atUs) > LOCAL_IBI_WINDOW_US) continue;
    if (seg && (b.atUs < seg.startUs || b.atUs >= seg.endUs)) continue;
    nearby.push(b);
  }

  if (nearby.length < 2) {
    return {
      localIbiUs: null,
      repulsionRadiusUs: baseTolUs,
    };
  }

  /** @type {number[]} */
  const ibis = [];
  for (let i = 1; i < nearby.length; i += 1) {
    const dt = nearby[i].atUs - nearby[i - 1].atUs;
    if (dt > 0 && dt <= MAX_PLAUSIBLE_IBI_US) {
      ibis.push(dt);
    }
  }

  if (ibis.length === 0) {
    return {
      localIbiUs: null,
      repulsionRadiusUs: baseTolUs,
    };
  }

  const localIbiUs = medianRoundUs(ibis);
  const quarterPeriodUs = Math.round(0.25 * localIbiUs);
  const repulsionRadiusUs = Math.min(
    MAX_UNBEAT_REPULSION_RADIUS_US,
    Math.min(unbeat.tolUs, quarterPeriodUs),
  );
  return { localIbiUs, repulsionRadiusUs };
}

/**
 * Compile JSON-safe `CompiledConstraints` from `Snapshot` or `SessionState`.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} stateOrSnapshot
 * @returns {import("../index.d.ts").CompiledConstraints}
 */
export function compileConstraints(stateOrSnapshot) {
  const beats = valuesArray(stateOrSnapshot?.beats)
    .slice()
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const unbeats = valuesArray(stateOrSnapshot?.unbeats)
    .slice()
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const sounds = valuesArray(stateOrSnapshot?.sounds)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const soundClasses = valuesArray(stateOrSnapshot?.soundClasses)
    .slice()
    .sort((a, b) => compareCodeUnits(a.id, b.id));
  const boundaries = valuesArray(stateOrSnapshot?.boundaries)
    .slice()
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  const segments = valuesArray(stateOrSnapshot?.segments)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));
  const spans = valuesArray(stateOrSnapshot?.spans)
    .slice()
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  /** @type {import("../index.d.ts").BeatGuideConstraint[]} */
  const beatGuides = [];
  for (let i = 0; i < beats.length; i += 1) {
    const b = beats[i];
    if (!b.verdict) continue;
    /** @type {import("../index.d.ts").BeatGuideConstraint} */
    const guide = {
      beatId: b.id,
      atUs: b.atUs,
      tolUs: b.verdict.tolUs,
      verdictKind: b.verdict.kind,
    };
    if (b.boundTo) guide.boundTo = b.boundTo;
    if (b.structural) guide.structural = b.structural;
    beatGuides.push(guide);
  }

  /** @type {import("../index.d.ts").UnbeatRepulsionConstraint[]} */
  const unbeatRepulsions = [];
  for (let i = 0; i < unbeats.length; i += 1) {
    const u = unbeats[i];
    if (!u.verdict) continue;
    const { localIbiUs, repulsionRadiusUs } = computeUnbeatRepulsionRadius(u, beats, segments);
    /** @type {import("../index.d.ts").UnbeatRepulsionConstraint} */
    const rep = {
      unbeatId: u.id,
      atUs: u.atUs,
      tolUs: u.tolUs,
      localIbiUs,
      repulsionRadiusUs,
      pulseLayer: u.pulseLayer,
    };
    if (u.replaces) rep.replaces = u.replaces;
    unbeatRepulsions.push(rep);
  }

  /** @type {import("../index.d.ts").SoundObservationConstraint[]} */
  const soundObservations = [];
  for (let i = 0; i < sounds.length; i += 1) {
    const s = sounds[i];
    const primary = s.anchors.find((a) => a.id === s.primaryAnchorId) ?? s.anchors[0];
    /** @type {import("../index.d.ts").SoundObservationConstraint} */
    const obs = {
      soundId: s.id,
      verified: Boolean(s.verdict),
      startUs: s.startUs,
      endUs: s.endUs,
      endKind: s.endKind,
      primaryAnchorId: s.primaryAnchorId,
      primaryAtUs: primary.atUs,
      primaryRole: primary.role,
      anchors: s.anchors,
    };
    if (s.verdict) {
      obs.tolUs = s.verdict.tolUs;
      if (s.verdict.facet) obs.verdictFacet = s.verdict.facet;
    }
    if (s.salience !== undefined) obs.salience = s.salience;
    if (s.characteristics?.family) obs.family = s.characteristics.family;
    if (s.voiceGroupId) obs.voiceGroupId = s.voiceGroupId;
    if (s.classId) obs.classId = s.classId;
    soundObservations.push(obs);
  }

  /** @type {import("../index.d.ts").SoundClassConstraint[]} */
  const compiledClasses = soundClasses.map((sc) => ({
    classId: sc.id,
    verified: Boolean(sc.verdict),
    exemplarSoundIds: sc.exemplarSoundIds,
    matchMode: sc.matchMode,
    ...(sc.label ? { label: sc.label } : {}),
  }));

  /** @type {import("../index.d.ts").BoundaryConstraint[]} */
  const boundaryConstraints = [];
  for (let i = 0; i < boundaries.length; i += 1) {
    const bnd = boundaries[i];
    if (!bnd.verdict) continue;
    boundaryConstraints.push({
      boundaryId: bnd.id,
      atUs: bnd.atUs,
      tolUs: bnd.verdict.tolUs,
      verdictKind: bnd.verdict.kind,
    });
  }

  const segMap = new Map(segments.map((s) => [s.id, s]));
  /** @type {import("../index.d.ts").FreeIntervalConstraint[]} */
  const freeIntervals = [];
  /** @type {import("../index.d.ts").KeepLockConstraint[]} */
  const keepLocks = [];

  for (let i = 0; i < segments.length; i += 1) {
    const seg = segments[i];
    if (seg.regime === "free") {
      freeIntervals.push({
        segmentId: seg.id,
        startUs: seg.startUs,
        endUs: seg.endUs,
        ...(seg.kinematics ? { kinematics: seg.kinematics } : {}),
      });
    }
    if (seg.approval) {
      keepLocks.push({
        approvalId: seg.approval.id,
        segmentId: seg.id,
        startUs: seg.startUs,
        endUs: seg.endUs,
        contentDigest: seg.approval.contentDigest,
        modifiedSinceApproval: Boolean(seg.approval.modifiedSinceApproval),
      });
    }
  }

  for (let i = 0; i < spans.length; i += 1) {
    const sp = spans[i];
    const parent = segMap.get(sp.segmentId);
    if (sp.kind === "free" && parent?.regime !== "free") {
      freeIntervals.push({
        segmentId: sp.segmentId,
        spanId: sp.id,
        startUs: sp.startUs,
        endUs: sp.endUs,
        ...(parent?.kinematics ? { kinematics: parent.kinematics } : {}),
      });
    } else if (sp.kind === "keep" && sp.approval && !parent?.approval) {
      keepLocks.push({
        approvalId: sp.approval.id,
        segmentId: sp.segmentId,
        spanId: sp.id,
        startUs: sp.startUs,
        endUs: sp.endUs,
        contentDigest: sp.approval.contentDigest,
        modifiedSinceApproval: Boolean(sp.approval.modifiedSinceApproval),
      });
    }
  }

  freeIntervals.sort(
    (a, b) =>
      a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.segmentId, b.segmentId),
  );
  keepLocks.sort(
    (a, b) =>
      a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.segmentId, b.segmentId),
  );

  return deepFreeze({
    beatGuides,
    unbeatRepulsions,
    soundObservations,
    soundClasses: compiledClasses,
    boundaryConstraints,
    freeIntervals,
    keepLocks,
  });
}

export { DEFAULT_BEAT_TOL_US };
