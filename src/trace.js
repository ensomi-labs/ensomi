/**
 * Append-only NDJSON trace, sans-IO state reducer, partition-safe Segment/Range/Span
 * reducer ops, atomic UnbeatCue retraction, crash-safe NDJSON parser, and verifiable checkpoints.
 */

import { compileConstraints } from "./constraints.js";
import { canonicalJson, roundTime, sha256Hex } from "./hash.js";
import {
  computeConsequences,
  computeSemanticKey,
  formatConsequenceSummary,
  getScopeBeatIds,
  isOfferSuppressed,
  validatePatchOffer,
} from "./offers.js";
import {
  assertOfferShape,
  assertRange,
  assertTraceEventEnvelope,
  decomposeRangeToSpans,
  ENTITY_SOURCES,
  EnsomiError,
  normalizeAudioDescriptor,
  normalizeBeat,
  normalizeSegment,
  normalizeSegmentPartition,
  normalizeSoundAnchor,
  normalizeSpan,
  normalizeUnbeatCue,
  rangeContainsTime,
  rangesOverlap,
  SEGMENT_REGIMES,
  TRACE_SCHEMA_VERSION,
} from "./types.js";

const USER_ACTION_TYPES = new Set([
  "edit",
  "signal",
  "offer_accept",
  "offer_reject",
]);

const UNDOABLE_EVENT_TYPES = new Set([
  "edit",
  "offer_accept",
  "offer_reject",
  "ready",
]);

const REPLAY_ORPHAN_ERROR_CODES = new Set([
  "INVALID_SEGMENT_EDIT",
  "DUPLICATE_SEGMENT_ID",
  "INVALID_SPAN",
  "INVALID_RANGE",
]);

const ENTITY_SOURCE_SET = new Set(ENTITY_SOURCES);
const SEGMENT_REGIME_SET = new Set(SEGMENT_REGIMES);

const DEEP_FROZEN_EVENTS = new WeakSet();

/**
 * Recursively freeze a plain JSON-like value in place.
 * @template T
 * @param {T} value
 * @returns {T}
 */
function deepFreezeInPlace(value) {
  if (value === null || typeof value !== "object") return value;
  Object.freeze(value);
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i += 1) {
      deepFreezeInPlace(value[i]);
    }
  } else {
    for (const key of Object.keys(value)) {
      deepFreezeInPlace(/** @type {Record<string, unknown>} */ (value)[key]);
    }
  }
  return value;
}

/**
 * Single-pass JS clone + deep freeze for JSON-like event values (avoids C++ structuredClone
 * serialization overhead on thousands of TraceEvents during foldTrace).
 * @template T
 * @param {T} value
 * @returns {T}
 */
function cloneAndFreezePlain(value) {
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) {
    const len = value.length;
    const arr = new Array(len);
    for (let i = 0; i < len; i += 1) {
      arr[i] = cloneAndFreezePlain(value[i]);
    }
    return /** @type {T} */ (Object.freeze(arr));
  }
  const proto = Object.getPrototypeOf(value);
  if (proto !== Object.prototype && proto !== null) {
    const cloned = structuredClone(value);
    return deepFreezeInPlace(cloned);
  }
  /** @type {Record<string, unknown>} */
  const out = {};
  for (const key of Object.keys(value)) {
    out[key] = cloneAndFreezePlain(/** @type {Record<string, unknown>} */ (value)[key]);
  }
  return /** @type {T} */ (Object.freeze(out));
}

/**
 * Clone and recursively freeze a `TraceEvent` once so `state.events[i]` is 100% immutable
 * across `getState()`, provider snapshots, and incremental/full folds.
 * @param {import("../index.d.ts").TraceEvent} ev
 * @returns {import("../index.d.ts").TraceEvent}
 */
function cloneAndFreezeEvent(ev) {
  if (DEEP_FROZEN_EVENTS.has(ev)) return ev;
  const cloned = cloneAndFreezePlain(ev);
  DEEP_FROZEN_EVENTS.add(cloned);
  return cloned;
}

/**
 * Create a clean initial sans-IO SessionState.
 * @param {{ durationSec?: number }} [options]
 * @returns {import("../index.d.ts").SessionState}
 */
export function createInitialState(options = {}) {
  const durationSec =
    typeof options?.durationSec === "number" &&
    Number.isFinite(options.durationSec) &&
    options.durationSec > 0
      ? options.durationSec
      : 0;

  const segments =
    durationSec > 0
      ? [
          {
            id: "seg_0",
            t0: 0,
            t1: durationSec,
            regime: /** @type {const} */ ("auto"),
            kept: false,
            source: /** @type {const} */ ("draft"),
            revision: 1,
          },
        ]
      : [];

  /** @type {Map<string, string>} */
  const entityRevisions = new Map();
  if (segments.length > 0) {
    entityRevisions.set("seg_0", "0:init");
  }

  return {
    audio: null,
    initialDurationSec: durationSec,
    durationSec,
    segments,
    spans: [],
    beats: new Map(),
    sounds: new Map(),
    unbeats: [],
    offers: new Map(),
    entityRevisions,
    segmentMutations: [],
    suppressedOffers: [],
    suppressions: [],
    lastSignal: null,
    generation: 0,
    effort: {
      listenSec: 0,
      actionCount: 0,
      waitSec: 0,
    },
    visitedRanges: [],
    ready: false,
    readyAtSeq: null,
    events: [],
    eventIds: new Set(),
    retractedEventIds: new Set(),
    lastSeq: -1,
    foldWarnings: [],
  };
}

/**
 * Shallow-clone state containers so incremental `applyEvent` never mutates caller state.
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").SessionState}
 */
function cloneStateShallow(state) {
  return {
    audio: state.audio ? { ...state.audio } : null,
    initialDurationSec: state.initialDurationSec ?? 0,
    durationSec: state.durationSec,
    segments: state.segments.slice(),
    spans: state.spans.slice(),
    beats: new Map(state.beats),
    sounds: new Map(state.sounds),
    unbeats: state.unbeats.slice(),
    offers: new Map(state.offers),
    entityRevisions: new Map(state.entityRevisions),
    segmentMutations: state.segmentMutations.slice(),
    suppressedOffers: state.suppressedOffers.slice(),
    suppressions: state.suppressions.slice(),
    lastSignal: state.lastSignal ? structuredClone(state.lastSignal) : null,
    generation: state.generation,
    effort: { ...state.effort },
    visitedRanges: state.visitedRanges.slice(),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    events: state.events.slice(),
    eventIds: new Set(state.eventIds),
    retractedEventIds: new Set(state.retractedEventIds),
    lastSeq: state.lastSeq,
    foldWarnings: state.foldWarnings.slice(),
  };
}

function sortSpansInPlace(spans) {
  spans.sort(
    (a, b) =>
      a.t0 - b.t0 ||
      a.t1 - b.t1 ||
      a.kind.localeCompare(b.kind) ||
      a.id.localeCompare(b.id),
  );
}

function sortUnbeatsInPlace(unbeats) {
  unbeats.sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id));
}

/**
 * Merge overlapping or touching `[t0, t1)` intervals.
 * @param {Array<{ t0: number, t1: number }>} intervals
 * @returns {Array<{ t0: number, t1: number }>}
 */
function mergeIntervals(intervals) {
  const valid = intervals
    .filter((iv) => iv.t1 > iv.t0 + 1e-9)
    .map((iv) => ({ t0: iv.t0, t1: iv.t1 }))
    .sort((a, b) => a.t0 - b.t0 || a.t1 - b.t1);
  if (valid.length <= 1) return valid;
  /** @type {Array<{ t0: number, t1: number }>} */
  const merged = [valid[0]];
  for (let i = 1; i < valid.length; i += 1) {
    const prev = merged[merged.length - 1];
    const curr = valid[i];
    if (curr.t0 <= prev.t1 + 1e-9) {
      if (curr.t1 > prev.t1) prev.t1 = curr.t1;
    } else {
      merged.push(curr);
    }
  }
  return merged;
}

/**
 * Subtract `[cut0, cut1)` from a sorted non-overlapping list of intervals.
 * @param {Array<{ t0: number, t1: number }>} intervals
 * @param {number} cut0
 * @param {number} cut1
 * @returns {Array<{ t0: number, t1: number }>}
 */
function subtractInterval(intervals, cut0, cut1) {
  /** @type {Array<{ t0: number, t1: number }>} */
  const out = [];
  for (const iv of intervals) {
    if (iv.t1 <= cut0 + 1e-9 || iv.t0 >= cut1 - 1e-9) {
      out.push(iv);
      continue;
    }
    if (iv.t0 < cut0 - 1e-9) {
      out.push({ t0: iv.t0, t1: cut0 });
    }
    if (iv.t1 > cut1 + 1e-9) {
      out.push({ t0: cut1, t1: iv.t1 });
    }
  }
  return out;
}

/**
 * Clean up redundant `keep` / `free` child spans and coalesce same-kind overlapping spans
 * inside a single segment while maintaining `Span ⊆ Segment` and `entityRevisions`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").Segment} seg
 * @param {string} rev
 * @param {string} idPrefix
 */
function normalizeChildSpansForSegment(state, seg, rev, idPrefix) {
  const others = [];
  const inSeg = [];
  for (const sp of state.spans) {
    if (sp.segmentId === seg.id) {
      inSeg.push(sp);
    } else {
      others.push(sp);
    }
  }

  /** @type {import("../index.d.ts").Span[]} */
  const keptSpans = [];

  for (const kind of /** @type {const} */ (["keep", "free"])) {
    const kindSpans = inSeg.filter((sp) => sp.kind === kind);
    if (kindSpans.length === 0) continue;

    const isRedundantWithSeg =
      (kind === "keep" && seg.kept) || (kind === "free" && seg.regime === "free");
    if (isRedundantWithSeg) {
      for (const sp of kindSpans) {
        state.entityRevisions.delete(sp.id);
      }
      continue;
    }

    const clipped = kindSpans
      .map((sp) => ({
        sp,
        t0: Math.max(seg.t0, sp.t0),
        t1: Math.min(seg.t1, sp.t1),
      }))
      .filter((x) => x.t1 > x.t0 + 1e-9);

    for (const sp of kindSpans) {
      if (!clipped.some((c) => c.sp.id === sp.id)) {
        state.entityRevisions.delete(sp.id);
      }
    }

    const merged = mergeIntervals(clipped.map((c) => ({ t0: c.t0, t1: c.t1 })));
    const usedOldIds = new Set();

    for (let k = 0; k < merged.length; k += 1) {
      const iv = merged[k];
      const matchingOld = clipped.find(
        (c) =>
          !usedOldIds.has(c.sp.id) &&
          c.t0 <= iv.t1 + 1e-9 &&
          c.t1 >= iv.t0 - 1e-9,
      );
      if (matchingOld) {
        usedOldIds.add(matchingOld.sp.id);
        const unchanged =
          Math.abs(matchingOld.sp.t0 - iv.t0) <= 1e-12 &&
          Math.abs(matchingOld.sp.t1 - iv.t1) <= 1e-12;
        if (unchanged) {
          keptSpans.push(matchingOld.sp);
        } else {
          const updated = {
            ...matchingOld.sp,
            t0: iv.t0,
            t1: iv.t1,
            revision: matchingOld.sp.revision + 1,
          };
          keptSpans.push(updated);
          state.entityRevisions.set(updated.id, rev);
        }
      } else {
        const freshId = `${idPrefix}_${kind}_${seg.id}_${k}`;
        const created = normalizeSpan(
          {
            id: freshId,
            segmentId: seg.id,
            t0: iv.t0,
            t1: iv.t1,
            kind,
            source: "user",
          },
          1,
        );
        keptSpans.push(created);
        state.entityRevisions.set(created.id, rev);
      }
    }

    for (const c of clipped) {
      if (!usedOldIds.has(c.sp.id)) {
        state.entityRevisions.delete(c.sp.id);
      }
    }
  }

  for (const sp of inSeg) {
    if (sp.kind !== "keep" && sp.kind !== "free") {
      const t0 = Math.max(seg.t0, sp.t0);
      const t1 = Math.min(seg.t1, sp.t1);
      if (t1 <= t0 + 1e-9) {
        state.entityRevisions.delete(sp.id);
      } else if (t0 !== sp.t0 || t1 !== sp.t1) {
        const updated = { ...sp, t0, t1, revision: sp.revision + 1 };
        keptSpans.push(updated);
        state.entityRevisions.set(updated.id, rev);
      } else {
        keptSpans.push(sp);
      }
    }
  }

  state.spans = [...others, ...keptSpans];
  sortSpansInPlace(state.spans);
}

/**
 * Prune beats, sounds, unbeats, and visitedRanges strictly beyond `maxDurationSec` (`> maxDurationSec`),
 * cleaning up `state.entityRevisions` so shrunk tracks never retain out-of-range entities.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {number} maxDurationSec
 */
function pruneEntitiesBeyondDurationInPlace(state, maxDurationSec) {
  if (typeof maxDurationSec !== "number" || !Number.isFinite(maxDurationSec) || maxDurationSec <= 0) {
    return;
  }
  for (const [id, beat] of state.beats.entries()) {
    if (beat.timeSec > maxDurationSec) {
      state.beats.delete(id);
      state.entityRevisions.delete(id);
    }
  }
  for (const [id, sound] of state.sounds.entries()) {
    if (sound.timeSec > maxDurationSec) {
      state.sounds.delete(id);
      state.entityRevisions.delete(id);
    }
  }
  if (state.unbeats.length > 0) {
    const survivingUnbeats = [];
    for (const u of state.unbeats) {
      if (u.timeSec > maxDurationSec) {
        state.entityRevisions.delete(u.id);
      } else {
        survivingUnbeats.push(u);
      }
    }
    state.unbeats = survivingUnbeats;
  }
  if (state.visitedRanges.length > 0) {
    const survivingRanges = [];
    for (const vr of state.visitedRanges) {
      if (vr.t0 >= maxDurationSec - 1e-9) continue;
      survivingRanges.push({
        t0: vr.t0,
        t1: Math.min(vr.t1, maxDurationSec),
      });
    }
    state.visitedRanges = survivingRanges;
  }
}

/**
 * Reconcile all existing `state.spans` against `state.segments` (reparenting or splitting
 * spans whose parent segment ID changed or resized, and pruning spans outside `[0, durationSec)`).
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} rev
 * @param {string} idPrefix
 */
function reconcileSpansWithSegmentsInPlace(state, rev, idPrefix) {
  if (state.segments.length === 0) {
    for (const sp of state.spans) {
      state.entityRevisions.delete(sp.id);
    }
    state.spans = [];
    return;
  }

  const segById = new Map(state.segments.map((s) => [s.id, s]));
  /** @type {import("../index.d.ts").Span[]} */
  const reconciled = [];
  let splitCounter = 0;

  for (const sp of state.spans) {
    const parent = segById.get(sp.segmentId);
    if (
      parent &&
      sp.t0 >= parent.t0 - 1e-9 &&
      sp.t1 <= parent.t1 + 1e-9 &&
      Math.min(parent.t1, sp.t1) > Math.max(parent.t0, sp.t0) + 1e-9
    ) {
      const clampedT0 = Math.max(parent.t0, sp.t0);
      const clampedT1 = Math.min(parent.t1, sp.t1);
      if (clampedT0 !== sp.t0 || clampedT1 !== sp.t1) {
        const updated = { ...sp, t0: clampedT0, t1: clampedT1, revision: sp.revision + 1 };
        reconciled.push(updated);
        state.entityRevisions.set(updated.id, rev);
      } else {
        reconciled.push(sp);
        if (!state.entityRevisions.has(sp.id)) {
          state.entityRevisions.set(sp.id, rev);
        }
      }
      continue;
    }

    if (sp.t1 <= sp.t0 + 1e-9) {
      state.entityRevisions.delete(sp.id);
      continue;
    }

    const slices = decomposeRangeToSpans(state.segments, {
      t0: Math.max(0, sp.t0),
      t1: sp.t1,
    });
    if (slices.length === 0) {
      state.entityRevisions.delete(sp.id);
      continue;
    }

    for (let k = 0; k < slices.length; k += 1) {
      const sl = slices[k];
      if (k === 0) {
        const reparented = normalizeSpan(
          {
            ...sp,
            id: sp.id,
            segmentId: sl.segmentId,
            t0: sl.t0,
            t1: sl.t1,
            revision: sp.revision + 1,
          },
          sp.revision + 1,
        );
        reconciled.push(reparented);
        state.entityRevisions.set(reparented.id, rev);
      } else {
        const freshId = `${idPrefix}_reparent_${splitCounter++}_${sp.id}`;
        const created = normalizeSpan(
          {
            ...sp,
            id: freshId,
            segmentId: sl.segmentId,
            t0: sl.t0,
            t1: sl.t1,
            revision: 1,
          },
          1,
        );
        reconciled.push(created);
        state.entityRevisions.set(created.id, rev);
      }
    }
  }

  state.spans = reconciled;
  for (const seg of state.segments) {
    normalizeChildSpansForSegment(state, seg, rev, `${idPrefix}_${seg.id}`);
  }
}

/**
 * Clear all segments, spans, beats, sounds, unbeats, and visitedRanges when duration shrinks to 0,
 * removing their IDs from `state.entityRevisions`.
 *
 * @param {import("../index.d.ts").SessionState} state
 */
function clearAllDurationEntitiesInPlace(state) {
  for (const seg of state.segments) state.entityRevisions.delete(seg.id);
  for (const sp of state.spans) state.entityRevisions.delete(sp.id);
  for (const id of state.beats.keys()) state.entityRevisions.delete(id);
  for (const id of state.sounds.keys()) state.entityRevisions.delete(id);
  for (const u of state.unbeats) state.entityRevisions.delete(u.id);
  state.segments = [];
  state.spans = [];
  state.beats.clear();
  state.sounds.clear();
  state.unbeats = [];
  state.visitedRanges = [];
}

/**
 * Synchronize `state.segments` when `durationSec` is initialized or updated,
 * preserving existing segments when possible while enforcing exact `[0, durationSec)` contiguity.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {number} nextDurationSec
 * @param {string} rev
 * @param {number} seq
 */
function syncDurationPartitionInPlace(state, nextDurationSec, rev, seq) {
  const prevDuration = state.durationSec;
  state.durationSec = nextDurationSec;

  if (nextDurationSec === 0) {
    clearAllDurationEntitiesInPlace(state);
    if (prevDuration > 0) {
      state.segmentMutations.push({
        seq,
        segmentIds: [],
        t0: 0,
        t1: prevDuration,
      });
    }
    return;
  }

  if (state.segments.length === 0) {
    const seg = {
      id: "seg_0",
      t0: 0,
      t1: nextDurationSec,
      regime: /** @type {const} */ ("auto"),
      kept: false,
      source: /** @type {const} */ ("draft"),
      revision: 1,
    };
    state.segments = [seg];
    state.entityRevisions.set(seg.id, rev);
    if (state.spans.length > 0) {
      reconcileSpansWithSegmentsInPlace(state, rev, `sync_${seq}`);
    }
    pruneEntitiesBeyondDurationInPlace(state, nextDurationSec);
    return;
  }

  if (Math.abs(nextDurationSec - prevDuration) <= 1e-12) {
    const last = state.segments[state.segments.length - 1];
    if (last.t1 !== nextDurationSec) {
      state.segments[state.segments.length - 1] = { ...last, t1: nextDurationSec };
    }
    return;
  }

  if (nextDurationSec > prevDuration) {
    const lastIdx = state.segments.length - 1;
    const last = state.segments[lastIdx];
    const updated = {
      ...last,
      t1: nextDurationSec,
      revision: last.revision + 1,
    };
    state.segments[lastIdx] = updated;
    state.entityRevisions.set(updated.id, rev);
    state.segmentMutations.push({
      seq,
      segmentIds: [updated.id],
      t0: last.t1,
      t1: nextDurationSec,
    });
    return;
  }

  // nextDurationSec < prevDuration: drop segments starting >= nextDurationSec and clamp last segment
  /** @type {import("../index.d.ts").Segment[]} */
  const trimmed = [];
  /** @type {string[]} */
  const droppedSegIds = [];
  for (const seg of state.segments) {
    if (seg.t0 >= nextDurationSec - 1e-9) {
      droppedSegIds.push(seg.id);
      state.entityRevisions.delete(seg.id);
      continue;
    }
    if (seg.t1 > nextDurationSec) {
      const clamped = {
        ...seg,
        t1: nextDurationSec,
        revision: seg.revision + 1,
      };
      trimmed.push(clamped);
      state.entityRevisions.set(clamped.id, rev);
    } else {
      trimmed.push(seg);
    }
  }

  if (trimmed.length === 0) {
    const fallback = {
      id: "seg_0",
      t0: 0,
      t1: nextDurationSec,
      regime: /** @type {const} */ ("auto"),
      kept: false,
      source: /** @type {const} */ ("draft"),
      revision: 1,
    };
    trimmed.push(fallback);
    state.entityRevisions.set(fallback.id, rev);
  } else {
    const lastIdx = trimmed.length - 1;
    if (trimmed[lastIdx].t1 !== nextDurationSec) {
      const updatedLast = {
        ...trimmed[lastIdx],
        t1: nextDurationSec,
        revision: trimmed[lastIdx].revision + 1,
      };
      trimmed[lastIdx] = updatedLast;
      state.entityRevisions.set(updatedLast.id, rev);
    }
  }

  const droppedSet = new Set(droppedSegIds);
  state.segments = trimmed;
  const survivingSpans = [];
  for (const sp of state.spans) {
    if (droppedSet.has(sp.segmentId) || sp.t0 >= nextDurationSec - 1e-9) {
      state.entityRevisions.delete(sp.id);
      continue;
    }
    if (sp.t1 > nextDurationSec) {
      const updatedSp = { ...sp, t1: nextDurationSec, revision: sp.revision + 1 };
      survivingSpans.push(updatedSp);
      state.entityRevisions.set(updatedSp.id, rev);
    } else {
      survivingSpans.push(sp);
    }
  }
  state.spans = survivingSpans;
  reconcileSpansWithSegmentsInPlace(state, rev, `sync_${seq}`);
  pruneEntitiesBeyondDurationInPlace(state, nextDurationSec);
  state.segmentMutations.push({
    seq,
    segmentIds: [...droppedSegIds, trimmed[trimmed.length - 1].id],
    t0: nextDurationSec,
    t1: prevDuration,
  });
}

function splitSegmentInPlace(state, segmentId, newSegmentId, timeSec, source, event, opTag) {
  const idx = state.segments.findIndex((s) => s.id === segmentId);
  if (idx < 0) {
    throw new EnsomiError("INVALID_SEGMENT_EDIT", `Unknown segment "${segmentId}"`);
  }
  if (typeof newSegmentId !== "string" || newSegmentId.length === 0) {
    throw new EnsomiError("INVALID_SEGMENT_EDIT", "split_segment requires non-empty newSegmentId");
  }
  if (
    state.segments.some((s) => s.id === newSegmentId) ||
    state.entityRevisions.has(newSegmentId)
  ) {
    throw new EnsomiError("DUPLICATE_SEGMENT_ID", `Segment id "${newSegmentId}" already exists`);
  }
  const seg = state.segments[idx];
  if (typeof timeSec !== "number" || !Number.isFinite(timeSec) || timeSec <= seg.t0 || timeSec >= seg.t1) {
    throw new EnsomiError(
      "INVALID_SEGMENT_EDIT",
      `split_segment timeSec (${timeSec}) must be strictly inside (${seg.t0}, ${seg.t1})`,
    );
  }

  const rev = `${event.seq}:${event.id}`;
  const leftSeg = normalizeSegment(
    {
      ...seg,
      t1: timeSec,
      source: source ?? "user",
      revision: seg.revision + 1,
    },
    seg.revision + 1,
  );
  const rightSeg = normalizeSegment(
    {
      id: newSegmentId,
      t0: timeSec,
      t1: seg.t1,
      regime: seg.regime,
      kept: seg.kept,
      source: source ?? "user",
      revision: 1,
    },
    1,
  );

  state.segments.splice(idx, 1, leftSeg, rightSeg);
  state.entityRevisions.set(leftSeg.id, rev);
  state.entityRevisions.set(rightSeg.id, rev);

  /** @type {import("../index.d.ts").Span[]} */
  const nextSpans = [];
  for (const sp of state.spans) {
    if (sp.segmentId !== segmentId) {
      nextSpans.push(sp);
      continue;
    }
    if (sp.t1 <= timeSec + 1e-12) {
      const clamped = sp.t1 === timeSec ? sp : { ...sp, t1: Math.min(sp.t1, timeSec) };
      nextSpans.push(clamped);
    } else if (sp.t0 >= timeSec - 1e-12) {
      const reparented = {
        ...sp,
        segmentId: newSegmentId,
        t0: Math.max(sp.t0, timeSec),
        revision: sp.revision + 1,
      };
      nextSpans.push(reparented);
      state.entityRevisions.set(reparented.id, rev);
    } else {
      const leftSpan = {
        ...sp,
        t1: timeSec,
        revision: sp.revision + 1,
      };
      const rightSpanId = `span_split_${event.seq}_${opTag}_${sp.id}`;
      const rightSpan = normalizeSpan(
        {
          ...sp,
          id: rightSpanId,
          segmentId: newSegmentId,
          t0: timeSec,
          t1: sp.t1,
          revision: 1,
        },
        1,
      );
      nextSpans.push(leftSpan, rightSpan);
      state.entityRevisions.set(leftSpan.id, rev);
      state.entityRevisions.set(rightSpan.id, rev);
    }
  }
  state.spans = nextSpans;
  sortSpansInPlace(state.spans);

  state.segmentMutations.push({
    seq: event.seq,
    segmentIds: [leftSeg.id, rightSeg.id],
    t0: seg.t0,
    t1: seg.t1,
  });

  return [
    { t0: seg.t0, t1: seg.t1 },
  ];
}

function moveSegmentBoundaryInPlace(
  state,
  leftSegmentId,
  rightSegmentId,
  timeSec,
  source,
  event,
  opTag,
) {
  const idx = state.segments.findIndex((s) => s.id === leftSegmentId);
  if (idx < 0 || idx + 1 >= state.segments.length || state.segments[idx + 1].id !== rightSegmentId) {
    throw new EnsomiError(
      "INVALID_SEGMENT_EDIT",
      `move_segment_boundary requires adjacent segments ("${leftSegmentId}", "${rightSegmentId}")`,
    );
  }
  const left = state.segments[idx];
  const right = state.segments[idx + 1];
  if (
    typeof timeSec !== "number" ||
    !Number.isFinite(timeSec) ||
    timeSec <= left.t0 ||
    timeSec >= right.t1
  ) {
    throw new EnsomiError(
      "INVALID_SEGMENT_EDIT",
      `move_segment_boundary timeSec (${timeSec}) must satisfy ${left.t0} < timeSec < ${right.t1}`,
    );
  }

  const oldBoundary = left.t1;
  const rev = `${event.seq}:${event.id}`;
  const nextLeft = normalizeSegment(
    {
      ...left,
      t1: timeSec,
      source: source ?? "user",
      revision: left.revision + 1,
    },
    left.revision + 1,
  );
  const nextRight = normalizeSegment(
    {
      ...right,
      t0: timeSec,
      source: source ?? "user",
      revision: right.revision + 1,
    },
    right.revision + 1,
  );
  state.segments[idx] = nextLeft;
  state.segments[idx + 1] = nextRight;
  state.entityRevisions.set(nextLeft.id, rev);
  state.entityRevisions.set(nextRight.id, rev);

  /** @type {import("../index.d.ts").Span[]} */
  const nextSpans = [];
  for (const sp of state.spans) {
    if (sp.segmentId !== left.id && sp.segmentId !== right.id) {
      nextSpans.push(sp);
      continue;
    }

    if (sp.segmentId === left.id) {
      if (sp.t1 <= timeSec + 1e-12) {
        nextSpans.push(sp.t1 > timeSec ? { ...sp, t1: timeSec } : sp);
      } else if (sp.t0 >= timeSec - 1e-12) {
        const movedSp = {
          ...sp,
          segmentId: right.id,
          t0: Math.max(sp.t0, timeSec),
          revision: sp.revision + 1,
        };
        nextSpans.push(movedSp);
        state.entityRevisions.set(movedSp.id, rev);
      } else {
        const leftPart = { ...sp, t1: timeSec, revision: sp.revision + 1 };
        const rightPart = normalizeSpan(
          {
            ...sp,
            id: `span_move_${event.seq}_${opTag}_${sp.id}`,
            segmentId: right.id,
            t0: timeSec,
            t1: sp.t1,
            revision: 1,
          },
          1,
        );
        nextSpans.push(leftPart, rightPart);
        state.entityRevisions.set(leftPart.id, rev);
        state.entityRevisions.set(rightPart.id, rev);
      }
    } else {
      // sp.segmentId === right.id
      if (sp.t0 >= timeSec - 1e-12) {
        nextSpans.push(sp.t0 < timeSec ? { ...sp, t0: timeSec } : sp);
      } else if (sp.t1 <= timeSec + 1e-12) {
        const movedSp = {
          ...sp,
          segmentId: left.id,
          t1: Math.min(sp.t1, timeSec),
          revision: sp.revision + 1,
        };
        nextSpans.push(movedSp);
        state.entityRevisions.set(movedSp.id, rev);
      } else {
        const leftPart = normalizeSpan(
          {
            ...sp,
            id: `span_move_${event.seq}_${opTag}_${sp.id}`,
            segmentId: left.id,
            t0: sp.t0,
            t1: timeSec,
            revision: 1,
          },
          1,
        );
        const rightPart = { ...sp, t0: timeSec, revision: sp.revision + 1 };
        nextSpans.push(leftPart, rightPart);
        state.entityRevisions.set(leftPart.id, rev);
        state.entityRevisions.set(rightPart.id, rev);
      }
    }
  }

  state.spans = nextSpans;
  normalizeChildSpansForSegment(state, nextLeft, rev, `norm_${event.seq}_${opTag}`);
  normalizeChildSpansForSegment(state, nextRight, rev, `norm_${event.seq}_${opTag}`);

  const lo = Math.min(oldBoundary, timeSec);
  const hi = Math.max(oldBoundary, timeSec);
  state.segmentMutations.push({
    seq: event.seq,
    segmentIds: [nextLeft.id, nextRight.id],
    t0: lo,
    t1: hi,
  });

  return [{ t0: lo, t1: Math.max(hi, lo + 1e-6) }];
}

function mergeSegmentsInPlace(state, leftSegmentId, rightSegmentId, source, event, opTag) {
  const idx = state.segments.findIndex((s) => s.id === leftSegmentId);
  if (idx < 0 || idx + 1 >= state.segments.length || state.segments[idx + 1].id !== rightSegmentId) {
    throw new EnsomiError(
      "INVALID_SEGMENT_EDIT",
      `merge_segments requires adjacent segments ("${leftSegmentId}", "${rightSegmentId}")`,
    );
  }

  const left = state.segments[idx];
  const right = state.segments[idx + 1];
  const rev = `${event.seq}:${event.id}`;

  const mergedKept = left.kept && right.kept;
  const mergedRegime = left.regime === right.regime ? left.regime : "auto";

  const mergedSeg = normalizeSegment(
    {
      id: left.id,
      t0: left.t0,
      t1: right.t1,
      regime: mergedRegime,
      kept: mergedKept,
      source: source ?? "user",
      revision: left.revision + 1,
    },
    left.revision + 1,
  );

  state.segments.splice(idx, 2, mergedSeg);
  state.entityRevisions.set(mergedSeg.id, rev);
  state.entityRevisions.delete(right.id);

  // Reparent child spans from right -> left
  state.spans = state.spans.map((sp) => {
    if (sp.segmentId !== right.id) return sp;
    const reparented = {
      ...sp,
      segmentId: left.id,
      revision: sp.revision + 1,
    };
    state.entityRevisions.set(reparented.id, rev);
    return reparented;
  });

  // Symmetric preservation of half-segment `kept: true` when only one half was kept
  if (left.kept !== right.kept) {
    const keptHalf = left.kept ? left : right;
    const keepSpan = normalizeSpan(
      {
        id: `span_merge_keep_${event.seq}_${opTag}_${keptHalf.id}`,
        segmentId: left.id,
        t0: keptHalf.t0,
        t1: keptHalf.t1,
        kind: "keep",
        source: keptHalf.source,
        revision: 1,
      },
      1,
    );
    state.spans.push(keepSpan);
    state.entityRevisions.set(keepSpan.id, rev);
  }

  // Symmetric preservation of half-segment `regime: "free"` when merged regime fell back to `"auto"`
  if (mergedRegime === "auto") {
    for (const half of [left, right]) {
      if (half.regime === "free") {
        const freeSpan = normalizeSpan(
          {
            id: `span_merge_free_${event.seq}_${opTag}_${half.id}`,
            segmentId: left.id,
            t0: half.t0,
            t1: half.t1,
            kind: "free",
            source: half.source,
            revision: 1,
          },
          1,
        );
        state.spans.push(freeSpan);
        state.entityRevisions.set(freeSpan.id, rev);
      }
    }
  }

  normalizeChildSpansForSegment(state, mergedSeg, rev, `merge_${event.seq}_${opTag}`);

  state.segmentMutations.push({
    seq: event.seq,
    segmentIds: [left.id, right.id],
    t0: left.t0,
    t1: right.t1,
  });

  return [{ t0: left.t0, t1: right.t1 }];
}

/**
 * Apply `keep` or `free` toggle over a slice `[s0, s1)` strictly inside `seg`.
 * Automatically upgrades/downgrades whole-segment flags when appropriate.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {number} segIdx
 * @param {"keep" | "free"} kind
 * @param {number} s0
 * @param {number} s1
 * @param {boolean} active
 * @param {import("../index.d.ts").EntitySource} source
 * @param {import("../index.d.ts").TraceEvent} event
 * @param {string} opTag
 */
function applySliceFlagInPlace(state, segIdx, kind, s0, s1, active, source, event, opTag) {
  const seg = state.segments[segIdx];
  const rev = `${event.seq}:${event.id}`;
  const coversWholeSeg = s0 <= seg.t0 + 1e-9 && s1 >= seg.t1 - 1e-9;

  if (kind === "keep") {
    if (active) {
      if (seg.kept) return;
      if (coversWholeSeg) {
        const nextSeg = { ...seg, kept: true, source, revision: seg.revision + 1 };
        state.segments[segIdx] = nextSeg;
        state.entityRevisions.set(nextSeg.id, rev);
        normalizeChildSpansForSegment(state, nextSeg, rev, `rk_${event.seq}_${opTag}`);
        return;
      }
      const existingKeep = state.spans.filter(
        (sp) => sp.segmentId === seg.id && sp.kind === "keep",
      );
      const merged = mergeIntervals([
        ...existingKeep.map((sp) => ({ t0: sp.t0, t1: sp.t1 })),
        { t0: s0, t1: s1 },
      ]);
      if (merged.length === 1 && merged[0].t0 <= seg.t0 + 1e-9 && merged[0].t1 >= seg.t1 - 1e-9) {
        const nextSeg = { ...seg, kept: true, source, revision: seg.revision + 1 };
        state.segments[segIdx] = nextSeg;
        state.entityRevisions.set(nextSeg.id, rev);
        normalizeChildSpansForSegment(state, nextSeg, rev, `rk_${event.seq}_${opTag}`);
        return;
      }
      const addSpan = normalizeSpan(
        {
          id: `span_keep_${event.seq}_${opTag}_${seg.id}`,
          segmentId: seg.id,
          t0: s0,
          t1: s1,
          kind: "keep",
          source,
          revision: 1,
        },
        1,
      );
      state.spans.push(addSpan);
      state.entityRevisions.set(addSpan.id, rev);
      normalizeChildSpansForSegment(state, seg, rev, `rk_${event.seq}_${opTag}`);
      return;
    }

    // active === false (unlocking Keep over [s0, s1))
    let workingSeg = seg;
    /** @type {Array<{ t0: number, t1: number }>} */
    let currentIntervals;
    if (seg.kept) {
      workingSeg = { ...seg, kept: false, source, revision: seg.revision + 1 };
      state.segments[segIdx] = workingSeg;
      state.entityRevisions.set(workingSeg.id, rev);
      currentIntervals = [{ t0: seg.t0, t1: seg.t1 }];
    } else {
      currentIntervals = state.spans
        .filter((sp) => sp.segmentId === seg.id && sp.kind === "keep")
        .map((sp) => ({ t0: sp.t0, t1: sp.t1 }));
    }

    const remaining = subtractInterval(currentIntervals, s0, s1);
    const oldKeepSpans = state.spans.filter(
      (sp) => sp.segmentId === seg.id && sp.kind === "keep",
    );
    for (const old of oldKeepSpans) state.entityRevisions.delete(old.id);
    state.spans = state.spans.filter(
      (sp) => !(sp.segmentId === seg.id && sp.kind === "keep"),
    );

    for (let k = 0; k < remaining.length; k += 1) {
      const iv = remaining[k];
      const sp = normalizeSpan(
        {
          id: `span_keep_rem_${event.seq}_${opTag}_${seg.id}_${k}`,
          segmentId: seg.id,
          t0: iv.t0,
          t1: iv.t1,
          kind: "keep",
          source,
          revision: 1,
        },
        1,
      );
      state.spans.push(sp);
      state.entityRevisions.set(sp.id, rev);
    }
    sortSpansInPlace(state.spans);
    return;
  }

  // kind === "free"
  if (active) {
    if (seg.regime === "free") return;
    if (coversWholeSeg) {
      const nextSeg = { ...seg, regime: /** @type {const} */ ("free"), source, revision: seg.revision + 1 };
      state.segments[segIdx] = nextSeg;
      state.entityRevisions.set(nextSeg.id, rev);
      normalizeChildSpansForSegment(state, nextSeg, rev, `rf_${event.seq}_${opTag}`);
      return;
    }
    const existingFree = state.spans.filter(
      (sp) => sp.segmentId === seg.id && sp.kind === "free",
    );
    const merged = mergeIntervals([
      ...existingFree.map((sp) => ({ t0: sp.t0, t1: sp.t1 })),
      { t0: s0, t1: s1 },
    ]);
    if (
      seg.regime === "auto" &&
      merged.length === 1 &&
      merged[0].t0 <= seg.t0 + 1e-9 &&
      merged[0].t1 >= seg.t1 - 1e-9
    ) {
      const nextSeg = { ...seg, regime: /** @type {const} */ ("free"), source, revision: seg.revision + 1 };
      state.segments[segIdx] = nextSeg;
      state.entityRevisions.set(nextSeg.id, rev);
      normalizeChildSpansForSegment(state, nextSeg, rev, `rf_${event.seq}_${opTag}`);
      return;
    }
    const addSpan = normalizeSpan(
      {
        id: `span_free_${event.seq}_${opTag}_${seg.id}`,
        segmentId: seg.id,
        t0: s0,
        t1: s1,
        kind: "free",
        source,
        revision: 1,
      },
      1,
    );
    state.spans.push(addSpan);
    state.entityRevisions.set(addSpan.id, rev);
    normalizeChildSpansForSegment(state, seg, rev, `rf_${event.seq}_${opTag}`);
    return;
  }

  // active === false (clearing Free over [s0, s1))
  let currentIntervals;
  if (seg.regime === "free") {
    const nextSeg = { ...seg, regime: /** @type {const} */ ("auto"), source, revision: seg.revision + 1 };
    state.segments[segIdx] = nextSeg;
    state.entityRevisions.set(nextSeg.id, rev);
    currentIntervals = [{ t0: seg.t0, t1: seg.t1 }];
  } else {
    currentIntervals = state.spans
      .filter((sp) => sp.segmentId === seg.id && sp.kind === "free")
      .map((sp) => ({ t0: sp.t0, t1: sp.t1 }));
  }

  const remaining = subtractInterval(currentIntervals, s0, s1);
  const oldFreeSpans = state.spans.filter(
    (sp) => sp.segmentId === seg.id && sp.kind === "free",
  );
  for (const old of oldFreeSpans) state.entityRevisions.delete(old.id);
  state.spans = state.spans.filter(
    (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
  );

  for (let k = 0; k < remaining.length; k += 1) {
    const iv = remaining[k];
    const sp = normalizeSpan(
      {
        id: `span_free_rem_${event.seq}_${opTag}_${seg.id}_${k}`,
        segmentId: seg.id,
        t0: iv.t0,
        t1: iv.t1,
        kind: "free",
        source,
        revision: 1,
      },
      1,
    );
    state.spans.push(sp);
    state.entityRevisions.set(sp.id, rev);
  }
  sortSpansInPlace(state.spans);
}

/**
 * Resolve which target event a retract event references, enforcing backward-only references.
 * @param {import("../index.d.ts").TraceEvent} retractEvent
 * @param {Map<string, import("../index.d.ts").TraceEvent>} byId
 * @param {Map<number, import("../index.d.ts").TraceEvent>} bySeq
 */
function resolveRetractTarget(retractEvent, byId, bySeq) {
  const payload = /** @type {Record<string, unknown>} */ (retractEvent.payload);
  if (!payload || typeof payload !== "object") {
    throw new EnsomiError("INVALID_RETRACTION", "Retract payload must be an object");
  }
  let target = null;
  if (typeof payload.targetEventId === "string" && payload.targetEventId.length > 0) {
    target = byId.get(payload.targetEventId) ?? null;
  } else if (typeof payload.targetSeq === "number" && Number.isInteger(payload.targetSeq)) {
    target = bySeq.get(payload.targetSeq) ?? null;
  } else {
    throw new EnsomiError(
      "INVALID_RETRACTION",
      "Retract payload must specify targetEventId (string) or targetSeq (integer)",
    );
  }
  if (!target) {
    throw new EnsomiError(
      "INVALID_RETRACTION",
      `Retract event "${retractEvent.id}" references unknown target`,
    );
  }
  if (target.seq >= retractEvent.seq) {
    throw new EnsomiError(
      "INVALID_RETRACTION",
      `Retract event "${retractEvent.id}" (seq ${retractEvent.seq}) must reference a strictly earlier event (got seq ${target.seq})`,
    );
  }
  return target;
}

/**
 * Resolve active retractions newest-first.
 * Retracting an earlier retraction cancels it cleanly (redo semantics).
 *
 * @param {import("../index.d.ts").TraceEvent[]} events
 * @returns {Set<string>}
 */
export function resolveActiveRetractions(events) {
  /** @type {Map<string, import("../index.d.ts").TraceEvent>} */
  const byId = new Map();
  /** @type {Map<number, import("../index.d.ts").TraceEvent>} */
  const bySeq = new Map();
  for (const ev of events) {
    byId.set(ev.id, ev);
    bySeq.set(ev.seq, ev);
  }

  /** @type {Set<string>} */
  const retractedIds = new Set();
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const ev = events[i];
    if (ev.type !== "retract") continue;
    const target = resolveRetractTarget(ev, byId, bySeq);
    if (retractedIds.has(ev.id)) continue;
    retractedIds.add(target.id);
  }
  return retractedIds;
}

/**
 * Deterministically reconstruct `undoStack` and `redoStack` directly from `state.events`
 * so Undo/Redo survives `.jsonl` persistence and process restarts with zero in-memory desync:
 *  - Non-retract user actions (`edit`, `offer_accept`, `offer_reject`, `ready`) push onto
 *    `undoStack` and clear `redoStack` (branch cleared).
 *  - A `retract` event `R` targeting an entry in `undoStack` removes that entry from `undoStack`
 *    and pushes `R.id` onto `redoStack`.
 *  - A `retract` event `R2` targeting an active undo-retract `R` in `redoStack` (Redo) removes
 *    `R` from `redoStack` and restores `R`'s original target event back onto `undoStack`.
 *
 * @param {import("../index.d.ts").SessionState | readonly import("../index.d.ts").TraceEvent[]} stateOrEvents
 * @returns {import("../index.d.ts").UndoRedoTargets}
 */
export function computeUndoRedoTargets(stateOrEvents) {
  const events = Array.isArray(stateOrEvents)
    ? stateOrEvents
    : Array.isArray(stateOrEvents?.events)
      ? stateOrEvents.events
      : [];

  if (events.length === 0) {
    return {
      canUndo: false,
      canRedo: false,
      undoTargetEventId: null,
      redoTargetEventId: null,
      undoStack: [],
      redoStack: [],
    };
  }

  /** @type {Map<string, import("../index.d.ts").TraceEvent>} */
  const byId = new Map();
  /** @type {Map<number, import("../index.d.ts").TraceEvent>} */
  const bySeq = new Map();
  const activeRetractedIds = resolveActiveRetractions(
    /** @type {import("../index.d.ts").TraceEvent[]} */ (events),
  );

  /** @type {Set<string>} */
  const undoableRoots = new Set();
  /** @type {Map<string, string>} */
  const parentOf = new Map();
  /** @type {Map<string, string>} */
  const rootOf = new Map();
  /** @type {Map<string, string[]>} */
  const childrenOf = new Map();
  /** @type {Map<string, boolean>} */
  const effective = new Map();
  /** @type {string[]} */
  const undoStack = [];
  /** @type {string[]} */
  const redoStack = [];
  /** @type {string | null} */
  let currentSongId = null;

  for (const ev of events) {
    byId.set(ev.id, ev);
    bySeq.set(ev.seq, ev);

    if (ev.type === "audio_bound" && !activeRetractedIds.has(ev.id)) {
      const rawAudio = /** @type {any} */ (ev.payload)?.audio ?? ev.payload;
      const nextSongId =
        typeof rawAudio?.songId === "string" && rawAudio.songId.length > 0
          ? rawAudio.songId
          : typeof rawAudio?.audioId === "string"
            ? rawAudio.audioId
            : null;
      if (currentSongId !== null && nextSongId !== null && nextSongId !== currentSongId) {
        undoableRoots.clear();
        undoStack.length = 0;
        redoStack.length = 0;
      }
      if (nextSongId !== null) {
        currentSongId = nextSongId;
      }
      continue;
    }

    if (UNDOABLE_EVENT_TYPES.has(ev.type) && (ev.author?.kind ?? "user") === "user") {
      undoableRoots.add(ev.id);
      rootOf.set(ev.id, ev.id);
      childrenOf.set(ev.id, []);
      effective.set(ev.id, true);
      undoStack.push(ev.id);
      redoStack.length = 0;
      continue;
    }

    if (ev.type === "retract") {
      const target = resolveRetractTarget(ev, byId, bySeq);
      parentOf.set(ev.id, target.id);
      childrenOf.set(ev.id, []);
      effective.set(ev.id, true);
      const rootId = rootOf.get(target.id) ?? target.id;
      rootOf.set(ev.id, rootId);

      let targetKids = childrenOf.get(target.id);
      if (!targetKids) {
        targetKids = [];
        childrenOf.set(target.id, targetKids);
      }
      targetKids.push(ev.id);

      // Recompute effective status bottom-up along the ancestor chain (node x is effective iff
      // zero children of x are currently effective).
      /** @type {string | undefined} */
      let curr = target.id;
      while (curr !== undefined) {
        const kids = childrenOf.get(curr) ?? [];
        const nextEff = !kids.some((cid) => effective.get(cid) === true);
        const prevEff = effective.get(curr);
        effective.set(curr, nextEff);
        if (prevEff === nextEff) break;
        curr = parentOf.get(curr);
      }

      if (undoableRoots.has(rootId)) {
        const directKids = childrenOf.get(rootId) ?? [];
        const effectiveDirectSet = new Set(
          directKids.filter((cid) => effective.get(cid) === true),
        );
        for (let rIdx = redoStack.length - 1; rIdx >= 0; rIdx -= 1) {
          const rid = redoStack[rIdx];
          if (directKids.includes(rid) && !effectiveDirectSet.has(rid)) {
            redoStack.splice(rIdx, 1);
          }
        }

        if (effective.get(rootId) === true) {
          if (!undoStack.includes(rootId)) {
            undoStack.push(rootId);
          }
        } else {
          const undoIdx = undoStack.lastIndexOf(rootId);
          if (undoIdx >= 0) {
            undoStack.splice(undoIdx, 1);
          }
          for (const cid of directKids) {
            if (effectiveDirectSet.has(cid) && !redoStack.includes(cid)) {
              redoStack.push(cid);
            }
          }
        }
      }
    }
  }

  return {
    canUndo: undoStack.length > 0,
    canRedo: redoStack.length > 0,
    undoTargetEventId: undoStack.length > 0 ? undoStack[undoStack.length - 1] : null,
    redoTargetEventId: redoStack.length > 0 ? redoStack[redoStack.length - 1] : null,
    undoStack,
    redoStack,
  };
}

/**
 * Clear any rejection suppressions overlapping touched `[t0, t1)` ranges or points.
 * @param {import("../index.d.ts").SessionState} state
 * @param {Array<{ t0: number, t1: number }>} touched
 */
function clearOverlappingSuppressions(state, touched) {
  if (state.suppressions.length === 0 || touched.length === 0) return;
  state.suppressions = state.suppressions.filter(
    (entry) =>
      !entry.writeScopes.some((ws) =>
        touched.some((t) =>
          t.t0 === t.t1 ? rangeContainsTime(ws, t.t0) : rangesOverlap(ws, t),
        ),
      ),
  );
}

/**
 * Re-evaluate all non-terminal (`"valid"` / `"invalid"`) offers in `state.offers`.
 * Fast O(1) no-op when no active offers exist.
 *
 * @param {import("../index.d.ts").SessionState} state
 */
function refreshOfferStatuses(state) {
  if (state.offers.size === 0) return;

  /** @type {import("../index.d.ts").PatchOffer[]} */
  const activeOffers = [];
  for (const offer of state.offers.values()) {
    if (offer.status === "valid" || offer.status === "invalid") {
      activeOffers.push(offer);
    }
  }
  if (activeOffers.length === 0) return;

  const constraints = compileConstraints(state);
  for (const offer of activeOffers) {
    const check = validatePatchOffer(state, offer, constraints);
    state.offers.set(offer.id, {
      ...offer,
      status: check.status,
      conflicts: check.conflicts,
      advisoryNotes: check.advisoryNotes,
    });
  }
}

/**
 * Apply a single `EditOp` in place on a cloned `SessionState`.
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").EditOp} op
 * @param {import("../index.d.ts").TraceEvent} event
 * @param {number} opIndex
 * @returns {Array<{ t0: number, t1: number }>}
 */
function applyEditOpInPlace(state, op, event, opIndex) {
  if (!op || typeof op !== "object") {
    throw new EnsomiError("INVALID_EDIT", "EditOp must be an object");
  }
  const rev = `${event.seq}:${event.id}`;
  const opTag = `o${opIndex}`;
  const defaultUserSource = event.author?.kind === "user" ? "user" : undefined;
  /** @type {Array<{ t0: number, t1: number }>} */
  const touched = [];

  switch (op.op) {
    case "upsert_beat": {
      const raw = op.beat;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_beat requires a beat object");
      }
      const existing = state.beats.get(raw.id);
      const nextRev = existing ? existing.revision + 1 : raw.revision ?? 1;
      const beat = normalizeBeat(
        {
          ...raw,
          source: raw.source ?? defaultUserSource ?? "user",
          revision: nextRev,
        },
        nextRev,
      );
      if (existing) touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      touched.push({ t0: beat.timeSec, t1: beat.timeSec });
      state.beats.set(beat.id, beat);
      state.entityRevisions.set(beat.id, rev);
      break;
    }

    case "move_beat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "move_beat requires non-empty id");
      }
      if (typeof op.timeSec !== "number" || !Number.isFinite(op.timeSec) || op.timeSec < 0) {
        throw new EnsomiError("INVALID_EDIT", `move_beat requires non-negative finite timeSec, got ${op.timeSec}`);
      }
      const existing = state.beats.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `move_beat skipped missing beat "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      touched.push({ t0: op.timeSec, t1: op.timeSec });

      const nextSnapEventId =
        op.snapEventId === null
          ? undefined
          : op.snapEventId !== undefined
            ? op.snapEventId
            : existing.snapEventId;
      const nextSnapFamily =
        op.snapEventId === null || op.snapFamily === null
          ? undefined
          : op.snapFamily !== undefined
            ? op.snapFamily
            : existing.snapFamily;
      const nextSnapRole =
        op.snapEventId === null || op.snapRole === null
          ? undefined
          : op.snapRole !== undefined
            ? op.snapRole
            : existing.snapRole;

      const updated = normalizeBeat(
        {
          id: existing.id,
          timeSec: op.timeSec,
          source: op.source ?? defaultUserSource ?? existing.source,
          ...(nextSnapEventId !== undefined ? { snapEventId: nextSnapEventId } : {}),
          ...(nextSnapFamily !== undefined ? { snapFamily: nextSnapFamily } : {}),
          ...(nextSnapRole !== undefined ? { snapRole: nextSnapRole } : {}),
          ...(op.confidence !== undefined
            ? { confidence: op.confidence }
            : existing.confidence !== undefined
              ? { confidence: existing.confidence }
              : {}),
          revision: existing.revision + 1,
        },
        existing.revision + 1,
      );
      state.beats.set(updated.id, updated);
      state.entityRevisions.set(updated.id, rev);
      break;
    }

    case "snap_beat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "snap_beat requires non-empty id");
      }
      const existing = state.beats.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `snap_beat skipped missing beat "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      touched.push({ t0: op.timeSec, t1: op.timeSec });
      const snapped = normalizeBeat(
        {
          id: existing.id,
          timeSec: op.timeSec,
          source: op.source ?? "user",
          snapEventId: op.snapEventId,
          snapFamily: op.snapFamily,
          snapRole: op.snapRole,
          ...(existing.confidence !== undefined ? { confidence: existing.confidence } : {}),
          revision: existing.revision + 1,
        },
        existing.revision + 1,
      );
      state.beats.set(snapped.id, snapped);
      state.entityRevisions.set(snapped.id, rev);
      break;
    }

    case "remove_beat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "remove_beat requires non-empty id");
      }
      const existing = state.beats.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `remove_beat skipped missing beat "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      state.beats.delete(op.id);
      state.entityRevisions.delete(op.id);
      break;
    }

    case "shift_beats": {
      if (typeof op.deltaSec !== "number" || !Number.isFinite(op.deltaSec)) {
        throw new EnsomiError("INVALID_EDIT", `shift_beats requires finite deltaSec, got ${op.deltaSec}`);
      }
      if (op.source !== undefined && !ENTITY_SOURCE_SET.has(op.source)) {
        throw new EnsomiError("INVALID_BEAT", `Invalid beat source: ${op.source}`);
      }
      const range = op.range ? assertRange(op.range) : null;
      const beatIdSet = Array.isArray(op.beatIds) ? new Set(op.beatIds) : null;
      if (!range && !beatIdSet) {
        throw new EnsomiError("INVALID_EDIT", "shift_beats requires range or beatIds");
      }
      if (range) {
        touched.push(range);
        touched.push({
          t0: Math.max(0, Math.min(range.t0, range.t0 + op.deltaSec)),
          t1: Math.max(range.t1, range.t1 + op.deltaSec),
        });
      }
      for (const [id, existing] of state.beats.entries()) {
        const matchId = beatIdSet ? beatIdSet.has(id) : true;
        const matchRange = range ? rangeContainsTime(range, existing.timeSec) : true;
        if (matchId && matchRange) {
          const nextTime = Math.max(0, roundTime(existing.timeSec + op.deltaSec, 6));
          touched.push({ t0: existing.timeSec, t1: existing.timeSec });
          touched.push({ t0: nextTime, t1: nextTime });
          const shifted = normalizeBeat(
            {
              id: existing.id,
              timeSec: nextTime,
              source: op.source ?? defaultUserSource ?? existing.source,
              ...(existing.confidence !== undefined ? { confidence: existing.confidence } : {}),
              revision: existing.revision + 1,
            },
            existing.revision + 1,
          );
          state.beats.set(id, shifted);
          state.entityRevisions.set(id, rev);
        }
      }
      break;
    }

    case "upsert_sound": {
      const raw = op.sound;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_sound requires a sound object");
      }
      const existing = state.sounds.get(raw.id);
      const nextRev = existing ? existing.revision + 1 : raw.revision ?? 1;
      const sound = normalizeSoundAnchor(
        {
          ...raw,
          source: raw.source ?? defaultUserSource ?? "user",
          revision: nextRev,
        },
        nextRev,
      );
      if (existing) touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      touched.push({ t0: sound.timeSec, t1: sound.timeSec });
      state.sounds.set(sound.id, sound);
      state.entityRevisions.set(sound.id, rev);
      break;
    }

    case "move_sound": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "move_sound requires non-empty id");
      }
      const existing = state.sounds.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `move_sound skipped missing sound "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      touched.push({ t0: op.timeSec, t1: op.timeSec });
      const updated = normalizeSoundAnchor(
        {
          ...existing,
          timeSec: op.timeSec,
          source: op.source ?? defaultUserSource ?? existing.source,
          ...(op.strength !== undefined ? { strength: op.strength } : {}),
          ...(op.family !== undefined ? { family: op.family } : {}),
          ...(op.role !== undefined ? { role: op.role } : {}),
          ...(op.sigmaMs !== undefined ? { sigmaMs: op.sigmaMs } : {}),
          revision: existing.revision + 1,
        },
        existing.revision + 1,
      );
      state.sounds.set(updated.id, updated);
      state.entityRevisions.set(updated.id, rev);
      break;
    }

    case "remove_sound": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "remove_sound requires non-empty id");
      }
      const existing = state.sounds.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `remove_sound skipped missing sound "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.timeSec, t1: existing.timeSec });
      state.sounds.delete(op.id);
      state.entityRevisions.delete(op.id);
      break;
    }

    case "apply_unbeat_cue": {
      const captureRadiusSec =
        op.captureRadiusSec === undefined ? 0 : op.captureRadiusSec;
      if (
        typeof captureRadiusSec !== "number" ||
        !Number.isFinite(captureRadiusSec) ||
        captureRadiusSec < 0
      ) {
        throw new EnsomiError(
          "INVALID_EDIT",
          `apply_unbeat_cue captureRadiusSec must be >= 0, got ${captureRadiusSec}`,
        );
      }
      const existingIdx = state.unbeats.findIndex((u) => u.id === op.id);
      const nextRev = existingIdx >= 0 ? state.unbeats[existingIdx].revision + 1 : 1;
      const unbeat = normalizeUnbeatCue(
        {
          id: op.id,
          timeSec: op.timeSec,
          source: "user",
          revision: nextRev,
        },
        nextRev,
      );
      if (existingIdx >= 0) {
        touched.push({
          t0: state.unbeats[existingIdx].timeSec,
          t1: state.unbeats[existingIdx].timeSec,
        });
        state.unbeats[existingIdx] = unbeat;
      } else {
        state.unbeats.push(unbeat);
      }
      sortUnbeatsInPlace(state.unbeats);
      state.entityRevisions.set(unbeat.id, rev);
      touched.push({
        t0: Math.max(0, unbeat.timeSec - Math.max(captureRadiusSec, 0.05)),
        t1: unbeat.timeSec + Math.max(captureRadiusSec, 0.05),
      });

      if (captureRadiusSec > 0) {
        const removeUserBeats = Boolean(op.removeUserBeats);
        /** @type {string[]} */
        const toRemove = [];
        for (const beat of state.beats.values()) {
          if (Math.abs(beat.timeSec - unbeat.timeSec) <= captureRadiusSec) {
            if (beat.source !== "user" || removeUserBeats) {
              toRemove.push(beat.id);
            }
          }
        }
        for (const beatId of toRemove) {
          const b = state.beats.get(beatId);
          if (b) touched.push({ t0: b.timeSec, t1: b.timeSec });
          state.beats.delete(beatId);
          state.entityRevisions.delete(beatId);
        }
      }
      break;
    }

    case "move_unbeat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "move_unbeat requires non-empty id");
      }
      const idx = state.unbeats.findIndex((u) => u.id === op.id);
      if (idx < 0) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `move_unbeat skipped missing unbeat "${op.id}"`,
        });
        break;
      }
      const prev = state.unbeats[idx];
      const updated = normalizeUnbeatCue(
        {
          id: prev.id,
          timeSec: op.timeSec,
          source: "user",
          revision: prev.revision + 1,
        },
        prev.revision + 1,
      );
      state.unbeats[idx] = updated;
      sortUnbeatsInPlace(state.unbeats);
      state.entityRevisions.set(updated.id, rev);
      touched.push({ t0: prev.timeSec, t1: prev.timeSec });
      touched.push({ t0: updated.timeSec, t1: updated.timeSec });
      break;
    }

    case "remove_unbeat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "remove_unbeat requires non-empty id");
      }
      const idx = state.unbeats.findIndex((u) => u.id === op.id);
      if (idx < 0) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `remove_unbeat skipped missing unbeat "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: state.unbeats[idx].timeSec, t1: state.unbeats[idx].timeSec });
      state.unbeats.splice(idx, 1);
      state.entityRevisions.delete(op.id);
      break;
    }

    case "move_segment_boundary": {
      touched.push(
        ...moveSegmentBoundaryInPlace(
          state,
          op.leftSegmentId,
          op.rightSegmentId,
          op.timeSec,
          op.source ?? "user",
          event,
          opTag,
        ),
      );
      break;
    }

    case "split_segment": {
      touched.push(
        ...splitSegmentInPlace(
          state,
          op.segmentId,
          op.newSegmentId,
          op.timeSec,
          op.source ?? "user",
          event,
          opTag,
        ),
      );
      break;
    }

    case "merge_segments": {
      touched.push(
        ...mergeSegmentsInPlace(
          state,
          op.leftSegmentId,
          op.rightSegmentId,
          op.source ?? "user",
          event,
          opTag,
        ),
      );
      break;
    }

    case "set_segment_keep": {
      const idx = state.segments.findIndex((s) => s.id === op.segmentId);
      if (idx < 0) {
        throw new EnsomiError("INVALID_SEGMENT_EDIT", `Unknown segment "${op.segmentId}"`);
      }
      const seg = state.segments[idx];
      const nextSeg = normalizeSegment(
        {
          ...seg,
          kept: Boolean(op.kept),
          source: op.source ?? "user",
          revision: seg.revision + 1,
        },
        seg.revision + 1,
      );
      state.segments[idx] = nextSeg;
      state.entityRevisions.set(nextSeg.id, rev);
      if (nextSeg.kept) {
        normalizeChildSpansForSegment(state, nextSeg, rev, `sk_${event.seq}_${opTag}`);
      }
      touched.push({ t0: nextSeg.t0, t1: nextSeg.t1 });
      break;
    }

    case "set_segment_regime": {
      if (!SEGMENT_REGIME_SET.has(op.regime)) {
        throw new EnsomiError("INVALID_SEGMENT_EDIT", `Invalid segment regime: ${op.regime}`);
      }
      const idx = state.segments.findIndex((s) => s.id === op.segmentId);
      if (idx < 0) {
        throw new EnsomiError("INVALID_SEGMENT_EDIT", `Unknown segment "${op.segmentId}"`);
      }
      const seg = state.segments[idx];
      const nextSeg = normalizeSegment(
        {
          ...seg,
          regime: op.regime,
          source: op.source ?? "user",
          revision: seg.revision + 1,
        },
        seg.revision + 1,
      );
      state.segments[idx] = nextSeg;
      state.entityRevisions.set(nextSeg.id, rev);
      if (nextSeg.regime === "free") {
        normalizeChildSpansForSegment(state, nextSeg, rev, `sr_${event.seq}_${opTag}`);
      }
      touched.push({ t0: nextSeg.t0, t1: nextSeg.t1 });
      break;
    }

    case "set_range_keep": {
      const source = op.source ?? "user";
      if (!ENTITY_SOURCE_SET.has(source)) {
        throw new EnsomiError("INVALID_SPAN", `Invalid span source: ${source}`);
      }
      const range = assertRange({ t0: op.t0, t1: op.t1 }, state.durationSec);
      const slices = decomposeRangeToSpans(state.segments, range);
      for (let sIdx = 0; sIdx < slices.length; sIdx += 1) {
        const sl = slices[sIdx];
        const segIdx = state.segments.findIndex((s) => s.id === sl.segmentId);
        if (segIdx >= 0) {
          applySliceFlagInPlace(
            state,
            segIdx,
            "keep",
            sl.t0,
            sl.t1,
            Boolean(op.kept),
            source,
            event,
            `${opTag}_s${sIdx}`,
          );
        }
      }
      touched.push(range);
      break;
    }

    case "set_range_free": {
      const source = op.source ?? "user";
      if (!ENTITY_SOURCE_SET.has(source)) {
        throw new EnsomiError("INVALID_SPAN", `Invalid span source: ${source}`);
      }
      const range = assertRange({ t0: op.t0, t1: op.t1 }, state.durationSec);
      const slices = decomposeRangeToSpans(state.segments, range);
      for (let sIdx = 0; sIdx < slices.length; sIdx += 1) {
        const sl = slices[sIdx];
        const segIdx = state.segments.findIndex((s) => s.id === sl.segmentId);
        if (segIdx >= 0) {
          applySliceFlagInPlace(
            state,
            segIdx,
            "free",
            sl.t0,
            sl.t1,
            Boolean(op.free),
            source,
            event,
            `${opTag}_s${sIdx}`,
          );
        }
      }
      touched.push(range);
      break;
    }

    case "promote_range_to_segments": {
      const range = assertRange({ t0: op.t0, t1: op.t1 }, state.durationSec);
      const source = op.source ?? "user";
      if (!ENTITY_SOURCE_SET.has(source)) {
        throw new EnsomiError("INVALID_SEGMENT", `Invalid segment source: ${source}`);
      }
      if (op.regime !== undefined && !SEGMENT_REGIME_SET.has(op.regime)) {
        throw new EnsomiError("INVALID_SEGMENT_EDIT", `Invalid segment regime: ${op.regime}`);
      }
      if (state.segments.length === 0) {
        throw new EnsomiError(
          "INVALID_SEGMENT_EDIT",
          "promote_range_to_segments requires an active segment partition",
        );
      }

      // Pre-validate BOTH t0 and t1 split preconditions (including simulated post-t0 partition
      // and minted span IDs) BEFORE mutating state.segments / state.spans / state.entityRevisions!
      const existingIds = new Set([
        ...state.segments.map((s) => s.id),
        ...state.spans.map((s) => s.id),
        ...state.beats.keys(),
        ...state.sounds.keys(),
        ...state.unbeats.map((u) => u.id),
        ...state.entityRevisions.keys(),
      ]);

      const segAtT0 = state.segments.find((s) => s.t0 < range.t0 && range.t0 < s.t1);
      const id0 =
        op.newSegmentIds && op.newSegmentIds[0] !== undefined
          ? op.newSegmentIds[0]
          : `seg_prom_${event.seq}_${opTag}_0`;
      /** @type {Set<string>} */
      const mintedSplit0SpanIds = new Set();
      /** @type {import("../index.d.ts").Segment[]} */
      let simSegments = state.segments;

      if (segAtT0) {
        if (typeof id0 !== "string" || id0.length === 0) {
          throw new EnsomiError(
            "INVALID_SEGMENT_EDIT",
            "split_segment requires non-empty newSegmentId",
          );
        }
        if (existingIds.has(id0)) {
          throw new EnsomiError("DUPLICATE_SEGMENT_ID", `Segment id "${id0}" already exists`);
        }
        if (
          typeof range.t0 !== "number" ||
          !Number.isFinite(range.t0) ||
          range.t0 <= segAtT0.t0 ||
          range.t0 >= segAtT0.t1
        ) {
          throw new EnsomiError(
            "INVALID_SEGMENT_EDIT",
            `split_segment timeSec (${range.t0}) must be strictly inside (${segAtT0.t0}, ${segAtT0.t1})`,
          );
        }
        const simLeft0 = normalizeSegment(
          { ...segAtT0, t1: range.t0, source, revision: segAtT0.revision + 1 },
          segAtT0.revision + 1,
        );
        const simRight0 = normalizeSegment(
          {
            id: id0,
            t0: range.t0,
            t1: segAtT0.t1,
            regime: segAtT0.regime,
            kept: segAtT0.kept,
            source,
            revision: 1,
          },
          1,
        );
        for (const sp of state.spans) {
          if (
            sp.segmentId === segAtT0.id &&
            sp.t1 > range.t0 + 1e-12 &&
            sp.t0 < range.t0 - 1e-12
          ) {
            const mintedId = `span_split_${event.seq}_${opTag}_p0_${sp.id}`;
            if (existingIds.has(mintedId) || mintedId === id0) {
              throw new EnsomiError(
                "DUPLICATE_SEGMENT_ID",
                `Split span id "${mintedId}" collides with existing id`,
              );
            }
            mintedSplit0SpanIds.add(mintedId);
          }
        }
        const t0Idx = state.segments.indexOf(segAtT0);
        simSegments = state.segments.slice();
        simSegments.splice(t0Idx, 1, simLeft0, simRight0);
      }

      const simSegAtT1 = simSegments.find((s) => s.t0 < range.t1 && range.t1 < s.t1);
      const id1 =
        op.newSegmentIds && op.newSegmentIds[1] !== undefined
          ? op.newSegmentIds[1]
          : `seg_prom_${event.seq}_${opTag}_1`;

      if (simSegAtT1) {
        if (typeof id1 !== "string" || id1.length === 0) {
          throw new EnsomiError(
            "INVALID_SEGMENT_EDIT",
            "split_segment requires non-empty newSegmentId",
          );
        }
        if (
          (segAtT0 && id1 === id0) ||
          existingIds.has(id1) ||
          mintedSplit0SpanIds.has(id1)
        ) {
          throw new EnsomiError("DUPLICATE_SEGMENT_ID", `Segment id "${id1}" already exists`);
        }
        if (
          typeof range.t1 !== "number" ||
          !Number.isFinite(range.t1) ||
          range.t1 <= simSegAtT1.t0 ||
          range.t1 >= simSegAtT1.t1
        ) {
          throw new EnsomiError(
            "INVALID_SEGMENT_EDIT",
            `split_segment timeSec (${range.t1}) must be strictly inside (${simSegAtT1.t0}, ${simSegAtT1.t1})`,
          );
        }
        normalizeSegment(
          { ...simSegAtT1, t1: range.t1, source, revision: simSegAtT1.revision + 1 },
          simSegAtT1.revision + 1,
        );
        normalizeSegment(
          {
            id: id1,
            t0: range.t1,
            t1: simSegAtT1.t1,
            regime: simSegAtT1.regime,
            kept: simSegAtT1.kept,
            source,
            revision: 1,
          },
          1,
        );
      }

      if (segAtT0) {
        splitSegmentInPlace(state, segAtT0.id, id0, range.t0, source, event, `${opTag}_p0`);
      }
      const segAtT1 = state.segments.find((s) => s.t0 < range.t1 && range.t1 < s.t1);
      if (segAtT1) {
        splitSegmentInPlace(state, segAtT1.id, id1, range.t1, source, event, `${opTag}_p1`);
      }
      if (op.regime !== undefined || op.kept !== undefined) {
        for (let i = 0; i < state.segments.length; i += 1) {
          const seg = state.segments[i];
          if (seg.t0 >= range.t0 - 1e-9 && seg.t1 <= range.t1 + 1e-9) {
            const nextSeg = normalizeSegment(
              {
                ...seg,
                ...(op.regime !== undefined ? { regime: op.regime } : {}),
                ...(op.kept !== undefined ? { kept: Boolean(op.kept) } : {}),
                source,
                revision: seg.revision + 1,
              },
              seg.revision + 1,
            );
            state.segments[i] = nextSeg;
            state.entityRevisions.set(nextSeg.id, rev);
            normalizeChildSpansForSegment(state, nextSeg, rev, `prom_${event.seq}_${opTag}_${i}`);
          }
        }
      }
      touched.push(range);
      break;
    }

    case "upsert_span": {
      const raw = op.span;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_span requires a span object");
      }
      const parent = state.segments.find((s) => s.id === raw.segmentId);
      if (!parent) {
        throw new EnsomiError("INVALID_SPAN", `Span references unknown segment "${raw.segmentId}"`);
      }
      if (raw.t0 < parent.t0 - 1e-9 || raw.t1 > parent.t1 + 1e-9) {
        throw new EnsomiError(
          "INVALID_SPAN",
          `Span [${raw.t0}, ${raw.t1}) must stay strictly within parent segment "${parent.id}" [${parent.t0}, ${parent.t1})`,
        );
      }
      const existingIdx = state.spans.findIndex((s) => s.id === raw.id);
      const nextRev = existingIdx >= 0 ? state.spans[existingIdx].revision + 1 : raw.revision ?? 1;
      const span = normalizeSpan(
        {
          ...raw,
          t0: Math.max(parent.t0, raw.t0),
          t1: Math.min(parent.t1, raw.t1),
          source: raw.source ?? defaultUserSource ?? "user",
          revision: nextRev,
        },
        nextRev,
      );
      if (existingIdx >= 0) {
        touched.push({ t0: state.spans[existingIdx].t0, t1: state.spans[existingIdx].t1 });
        state.spans[existingIdx] = span;
      } else {
        state.spans.push(span);
      }
      state.entityRevisions.set(span.id, rev);
      normalizeChildSpansForSegment(state, parent, rev, `usp_${event.seq}_${opTag}`);
      touched.push({ t0: span.t0, t1: span.t1 });
      break;
    }

    case "remove_span": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "remove_span requires non-empty id");
      }
      const idx = state.spans.findIndex((s) => s.id === op.id);
      if (idx < 0) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `remove_span skipped missing span "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: state.spans[idx].t0, t1: state.spans[idx].t1 });
      state.spans.splice(idx, 1);
      state.entityRevisions.delete(op.id);
      break;
    }

    default:
      throw new EnsomiError("INVALID_EDIT", `Unknown edit op: ${/** @type {any} */ (op).op}`);
  }

  return touched;
}

/**
 * Check whether an `EnsomiError` thrown by an edit op is a state-precondition failure
 * (e.g. missing/non-adjacent segment or out-of-bounds span/range after selective retraction)
 * vs. malformed op syntax.
 *
 * @param {unknown} err
 * @param {any} op
 */
function isReplayOrphanPreconditionError(err, op) {
  if (!(err instanceof EnsomiError) || !REPLAY_ORPHAN_ERROR_CODES.has(err.code)) {
    return false;
  }
  if (!op || typeof op !== "object") return false;
  if (op.op === "split_segment") {
    if (typeof op.newSegmentId !== "string" || op.newSegmentId.length === 0) return false;
    if (typeof op.timeSec !== "number" || !Number.isFinite(op.timeSec) || op.timeSec <= 0) {
      return false;
    }
  }
  if (op.op === "set_segment_regime" && !SEGMENT_REGIME_SET.has(op.regime)) {
    return false;
  }
  if (
    (op.op === "set_range_keep" ||
      op.op === "set_range_free" ||
      op.op === "promote_range_to_segments") &&
    (typeof op.t0 !== "number" ||
      typeof op.t1 !== "number" ||
      !Number.isFinite(op.t0) ||
      !Number.isFinite(op.t1) ||
      op.t0 < 0 ||
      op.t1 <= op.t0)
  ) {
    return false;
  }
  if (op.op === "promote_range_to_segments" && Array.isArray(op.newSegmentIds)) {
    for (const segId of op.newSegmentIds) {
      if (segId !== undefined && (typeof segId !== "string" || segId.length === 0)) {
        return false;
      }
    }
  }
  if (op.op === "upsert_span") {
    const sp = op.span;
    if (
      !sp ||
      typeof sp !== "object" ||
      typeof sp.id !== "string" ||
      sp.id.length === 0 ||
      typeof sp.segmentId !== "string" ||
      sp.segmentId.length === 0 ||
      typeof sp.t0 !== "number" ||
      typeof sp.t1 !== "number" ||
      !Number.isFinite(sp.t0) ||
      !Number.isFinite(sp.t1) ||
      sp.t0 < 0 ||
      sp.t1 <= sp.t0
    ) {
      return false;
    }
  }
  return true;
}

/**
 * Apply a single active (non-retracted, non-retract) event in place on `state`.
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").TraceEvent} event
 * @param {boolean} [isReplay=false]
 */
function applyActiveEventInPlace(state, event, isReplay = false) {
  if (event.author?.kind === "user" && USER_ACTION_TYPES.has(event.type)) {
    state.effort.actionCount += 1;
  }

  const payload = /** @type {any} */ (event.payload);
  const rev = `${event.seq}:${event.id}`;

  switch (event.type) {
    case "audio_bound": {
      const rawAudio = payload?.audio ?? payload;
      const prevSongId = state.audio?.songId ?? null;
      const nextAudio = normalizeAudioDescriptor(rawAudio);
      state.audio = nextAudio;
      if (prevSongId !== null && prevSongId !== nextAudio.songId) {
        // Switching song resets all song-local state and sets up fresh partition
        state.beats.clear();
        state.sounds.clear();
        state.unbeats = [];
        state.offers.clear();
        state.entityRevisions.clear();
        state.segmentMutations = [];
        state.suppressedOffers = [];
        state.suppressions = [];
        state.segments = [];
        state.spans = [];
        state.visitedRanges = [];
        state.lastSignal = null;
        state.ready = false;
        state.readyAtSeq = null;
        state.durationSec = 0;
      }
      syncDurationPartitionInPlace(state, nextAudio.durationSec, rev, event.seq);
      refreshOfferStatuses(state);
      break;
    }

    case "draft_loaded": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_PAYLOAD", "draft_loaded payload must be an object");
      }
      const prevDuration = state.durationSec;
      const replace = payload.replace !== false;
      if (replace) {
        state.beats.clear();
        state.sounds.clear();
        state.unbeats = [];
        state.spans = [];
        state.entityRevisions.clear();
      }

      let nextDurationSec = state.durationSec;
      if (
        typeof payload.durationSec === "number" &&
        Number.isFinite(payload.durationSec) &&
        payload.durationSec >= 0
      ) {
        nextDurationSec = payload.durationSec;
      } else if (
        nextDurationSec === 0 &&
        Array.isArray(payload.segments) &&
        payload.segments.length > 0
      ) {
        const maxSegT1 = Math.max(
          0,
          ...payload.segments.map((s) =>
            typeof s?.t1 === "number" && Number.isFinite(s.t1) ? s.t1 : 0,
          ),
        );
        if (maxSegT1 > 0) nextDurationSec = maxSegT1;
      }

      if (payload.durationSec === 0 || (prevDuration > 0 && nextDurationSec === 0)) {
        syncDurationPartitionInPlace(state, 0, rev, event.seq);
        refreshOfferStatuses(state);
        break;
      }

      if (Array.isArray(payload.segments) || replace || (nextDurationSec > 0 && state.segments.length === 0)) {
        state.durationSec = nextDurationSec;
        for (const oldSeg of state.segments) state.entityRevisions.delete(oldSeg.id);
        if (state.durationSec > 0) {
          state.segments = normalizeSegmentPartition(payload.segments, state.durationSec, "draft");
          for (const seg of state.segments) {
            state.entityRevisions.set(seg.id, rev);
          }
          state.segmentMutations.push({
            seq: event.seq,
            segmentIds: state.segments.map((s) => s.id),
            t0: 0,
            t1: state.durationSec,
          });
        } else {
          state.segments = [];
        }
        reconcileSpansWithSegmentsInPlace(state, rev, `draft_${event.seq}`);
      } else if (nextDurationSec !== state.durationSec) {
        syncDurationPartitionInPlace(state, nextDurationSec, rev, event.seq);
        for (const seg of state.segments) {
          if (!state.entityRevisions.has(seg.id)) state.entityRevisions.set(seg.id, rev);
        }
      } else {
        for (const seg of state.segments) {
          if (!state.entityRevisions.has(seg.id)) state.entityRevisions.set(seg.id, rev);
        }
      }

      for (const rawBeat of payload.beats ?? []) {
        const beat = normalizeBeat(
          { ...rawBeat, source: rawBeat.source ?? "draft" },
          rawBeat.revision ?? 1,
        );
        state.beats.set(beat.id, beat);
        state.entityRevisions.set(beat.id, rev);
      }

      for (const rawSound of payload.sounds ?? []) {
        const sound = normalizeSoundAnchor(
          { ...rawSound, source: rawSound.source ?? "draft" },
          rawSound.revision ?? 1,
        );
        state.sounds.set(sound.id, sound);
        state.entityRevisions.set(sound.id, rev);
      }

      for (const rawUnbeat of payload.unbeats ?? []) {
        const u = normalizeUnbeatCue(rawUnbeat, rawUnbeat.revision ?? 1);
        const existingIdx = state.unbeats.findIndex((x) => x.id === u.id);
        if (existingIdx >= 0) state.unbeats[existingIdx] = u;
        else state.unbeats.push(u);
        state.entityRevisions.set(u.id, rev);
      }
      sortUnbeatsInPlace(state.unbeats);

      if (Array.isArray(payload.spans) && state.segments.length > 0) {
        const segById = new Map(state.segments.map((s) => [s.id, s]));
        let spanCounter = 0;
        for (const rawSpan of payload.spans) {
          if (!rawSpan || typeof rawSpan !== "object") continue;
          const directParent =
            typeof rawSpan.segmentId === "string" ? segById.get(rawSpan.segmentId) : null;
          if (
            directParent &&
            rawSpan.t0 >= directParent.t0 - 1e-9 &&
            rawSpan.t1 <= directParent.t1 + 1e-9 &&
            Math.min(directParent.t1, rawSpan.t1) > Math.max(directParent.t0, rawSpan.t0) + 1e-9
          ) {
            const sp = normalizeSpan(
              {
                ...rawSpan,
                t0: Math.max(directParent.t0, rawSpan.t0),
                t1: Math.min(directParent.t1, rawSpan.t1),
                source: rawSpan.source ?? "draft",
              },
              rawSpan.revision ?? 1,
            );
            const existingSpIdx = state.spans.findIndex((x) => x.id === sp.id);
            if (existingSpIdx >= 0) state.spans[existingSpIdx] = sp;
            else state.spans.push(sp);
            state.entityRevisions.set(sp.id, rev);
          } else if (
            typeof rawSpan.t0 === "number" &&
            typeof rawSpan.t1 === "number" &&
            rawSpan.t1 > rawSpan.t0
          ) {
            const slices = decomposeRangeToSpans(state.segments, {
              t0: Math.max(0, rawSpan.t0),
              t1: rawSpan.t1,
            });
            for (const sl of slices) {
              const id =
                slices.length === 1 && typeof rawSpan.id === "string" && rawSpan.id.length > 0
                  ? rawSpan.id
                  : `span_draft_${event.seq}_${spanCounter++}`;
              const sp = normalizeSpan(
                {
                  ...rawSpan,
                  id,
                  segmentId: sl.segmentId,
                  t0: sl.t0,
                  t1: sl.t1,
                  source: rawSpan.source ?? "draft",
                },
                1,
              );
              const existingSpIdx = state.spans.findIndex((x) => x.id === sp.id);
              if (existingSpIdx >= 0) state.spans[existingSpIdx] = sp;
              else state.spans.push(sp);
              state.entityRevisions.set(sp.id, rev);
            }
          }
        }
        reconcileSpansWithSegmentsInPlace(state, rev, `draft_${event.seq}`);
      }

      if (state.durationSec > 0) {
        pruneEntitiesBeyondDurationInPlace(state, state.durationSec);
      }

      refreshOfferStatuses(state);
      break;
    }

    case "edit": {
      if (
        state.durationSec === 0 &&
        state.audio === null &&
        typeof payload?.basisDurationSec === "number" &&
        Number.isFinite(payload.basisDurationSec) &&
        payload.basisDurationSec > 0
      ) {
        state.initialDurationSec = payload.basisDurationSec;
        syncDurationPartitionInPlace(state, payload.basisDurationSec, "0:init", event.seq);
      }
      const ops = Array.isArray(payload)
        ? payload
        : Array.isArray(payload?.ops)
          ? payload.ops
          : [payload];
      /** @type {Array<{ t0: number, t1: number }>} */
      const touched = [];
      for (let i = 0; i < ops.length; i += 1) {
        if (!isReplay) {
          touched.push(...applyEditOpInPlace(state, ops[i], event, i));
        } else {
          const opItem = ops[i];
          const isStructuralOp =
            opItem &&
            typeof opItem === "object" &&
            (opItem.op === "promote_range_to_segments" ||
              opItem.op === "split_segment" ||
              opItem.op === "move_segment_boundary" ||
              opItem.op === "merge_segments" ||
              opItem.op === "set_segment_keep" ||
              opItem.op === "set_segment_regime" ||
              opItem.op === "set_range_keep" ||
              opItem.op === "set_range_free" ||
              opItem.op === "upsert_span" ||
              opItem.op === "remove_span");
          const savedSegments = isStructuralOp ? state.segments.slice() : null;
          const savedSpans = isStructuralOp ? state.spans.slice() : null;
          const savedSegMutLen = state.segmentMutations.length;
          const savedRevs =
            isStructuralOp
              ? new Map(
                  [...state.segments.map((s) => s.id), ...state.spans.map((s) => s.id)].map(
                    (id) => [id, state.entityRevisions.get(id)],
                  ),
                )
              : null;
          try {
            touched.push(...applyEditOpInPlace(state, opItem, event, i));
          } catch (err) {
            if (savedSegments && savedSpans && savedRevs) {
              for (const seg of state.segments) state.entityRevisions.delete(seg.id);
              for (const sp of state.spans) state.entityRevisions.delete(sp.id);
              state.segments = savedSegments;
              state.spans = savedSpans;
              state.segmentMutations.length = savedSegMutLen;
              for (const [id, prevRev] of savedRevs) {
                if (prevRev !== undefined) state.entityRevisions.set(id, prevRev);
                else state.entityRevisions.delete(id);
              }
            }
            if (isReplayOrphanPreconditionError(err, opItem)) {
              state.foldWarnings.push({
                seq: event.seq,
                eventId: event.id,
                code: "ORPHANED_DEPENDENT_EDIT",
                message: err instanceof Error ? err.message : String(err),
              });
            } else {
              throw err;
            }
          }
        }
      }
      clearOverlappingSuppressions(state, touched);
      refreshOfferStatuses(state);
      break;
    }

    case "signal": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_PAYLOAD", "signal payload must be an object");
      }
      if (payload.scope !== "song") {
        assertRange(payload.scope);
      }
      if (typeof payload.reason !== "string") {
        throw new EnsomiError("INVALID_PAYLOAD", "signal payload requires string reason");
      }
      if (
        typeof payload.generation !== "number" ||
        !Number.isInteger(payload.generation) ||
        payload.generation < 0
      ) {
        throw new EnsomiError(
          "INVALID_PAYLOAD",
          "signal payload requires non-negative integer generation",
        );
      }
      state.lastSignal = {
        layer: "heavy_recompute",
        scope: payload.scope === "song" ? "song" : { ...payload.scope },
        reason: payload.reason,
        generation: payload.generation,
      };
      if (payload.generation > state.generation) {
        state.generation = payload.generation;
      }
      break;
    }

    case "offer_batch": {
      if (!payload || typeof payload !== "object" || !Array.isArray(payload.offers)) {
        throw new EnsomiError("INVALID_PAYLOAD", "offer_batch payload requires an offers array");
      }
      if (typeof payload.waitSec === "number" && Number.isFinite(payload.waitSec) && payload.waitSec > 0) {
        state.effort.waitSec += payload.waitSec;
      }

      /** @type {Map<string, number>} */
      const batchOptionCounts = new Map();
      for (const rawOffer of payload.offers) {
        const bId = rawOffer?.batchId ?? payload.batchId;
        if (typeof bId === "string") {
          batchOptionCounts.set(bId, (batchOptionCounts.get(bId) ?? 0) + 1);
        }
      }

      /** @type {import("../index.d.ts").PatchOffer[]} */
      const normalizedIncoming = [];
      const seenIncomingIds = new Set();

      for (const rawOffer of payload.offers) {
        const bId = rawOffer?.batchId ?? payload.batchId;
        const siblingCount = typeof bId === "string" ? (batchOptionCounts.get(bId) ?? 1) : 1;
        const expectedRevisions =
          rawOffer?.expectedRevisions && typeof rawOffer.expectedRevisions === "object"
            ? { ...rawOffer.expectedRevisions }
            : {};
        const ops = rawOffer?.ops ? structuredClone(rawOffer.ops) : {};
        const candidate = {
          ...rawOffer,
          batchId: bId,
          expectedRevisions,
          ops,
          ...(siblingCount > 1 && rawOffer.optionCount === undefined
            ? { optionCount: siblingCount }
            : {}),
          advisoryNotes: Array.isArray(rawOffer?.advisoryNotes)
            ? rawOffer.advisoryNotes.slice()
            : [],
          providerAdvisories: Array.isArray(rawOffer?.providerAdvisories)
            ? rawOffer.providerAdvisories.slice()
            : Array.isArray(rawOffer?.advisoryNotes)
              ? rawOffer.advisoryNotes.slice()
              : [],
          conflicts: [],
          status: rawOffer?.status ?? "valid",
        };
        assertOfferShape(candidate);
        if (seenIncomingIds.has(candidate.id) || state.offers.has(candidate.id)) {
          throw new EnsomiError(
            "DUPLICATE_OFFER_ID",
            `Duplicate offer id "${candidate.id}" in offer_batch`,
          );
        }
        seenIncomingIds.add(candidate.id);

        candidate.writeScopes = candidate.writeScopes.map((ws) => ({
          segmentId: ws.segmentId,
          ...assertRange(ws),
        }));
        if (candidate.readScope !== undefined && candidate.readScope !== "song") {
          candidate.readScope = assertRange(candidate.readScope);
        }
        candidate.semanticKey =
          candidate.semanticKey ||
          computeSemanticKey(candidate.writeScopes, candidate.ops, candidate.expectedRevisions);
        candidate.consequences =
          candidate.consequences ?? computeConsequences(state, candidate.ops);
        candidate.consequenceSummary =
          typeof candidate.consequenceSummary === "string" &&
          candidate.consequenceSummary.trim().length > 0
            ? candidate.consequenceSummary
            : formatConsequenceSummary(state, candidate.ops);

        if (candidate.generation > state.generation) {
          state.generation = candidate.generation;
        }
        normalizedIncoming.push(candidate);
      }

      const constraints = compileConstraints(state);
      /** @type {import("../index.d.ts").PatchOffer[]} */
      const acceptedIntoState = [];

      for (const offer of normalizedIncoming) {
        if (isOfferSuppressed(state, offer)) {
          state.suppressedOffers.push(offer);
          continue;
        }
        const check = validatePatchOffer(state, offer, constraints);
        acceptedIntoState.push({
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          advisoryNotes: check.advisoryNotes,
        });
      }

      // Mark older valid offers stale when overshadowed by a newer generation overlapping their writeScopes
      for (const [existingId, existing] of state.offers.entries()) {
        if (existing.status !== "valid") continue;
        const overshadowed = acceptedIntoState.some(
          (inc) =>
            inc.generation > existing.generation &&
            inc.writeScopes.some((iw) =>
              existing.writeScopes.some((ew) => rangesOverlap(iw, ew)),
            ),
        );
        if (overshadowed) {
          state.offers.set(existingId, {
            ...existing,
            status: "stale",
            conflicts:
              existing.conflicts?.length > 0
                ? existing.conflicts
                : [
                    {
                      kind: "version_mismatch",
                      message: "Superseded by a newer suggestion.",
                    },
                  ],
          });
        }
      }

      for (const offer of acceptedIntoState) {
        state.offers.set(offer.id, offer);
      }
      break;
    }

    case "offer_accept": {
      const offerId = payload?.offerId;
      if (typeof offerId !== "string" || offerId.length === 0) {
        throw new EnsomiError("INVALID_PAYLOAD", "offer_accept payload requires offerId");
      }
      const offer = state.offers.get(offerId);
      if (!offer) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_OFFER",
          message: `offer_accept skipped unknown offer "${offerId}"`,
        });
        break;
      }

      if (offer.status !== "valid") {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "OFFER_NOT_VALID",
          message: `offer_accept ignored for offer "${offerId}" with non-valid status "${offer.status}"`,
        });
        break;
      }

      const check = validatePatchOffer(state, offer);
      if (!check.valid) {
        state.offers.set(offerId, {
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          advisoryNotes: check.advisoryNotes,
        });
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "OFFER_CONFLICT",
          message: `offer_accept blocked for "${offerId}" due to ${check.conflicts.length} conflict(s)`,
        });
        break;
      }

      const ops = offer.ops ?? {};
      /** @type {Array<{ t0: number, t1: number }>} */
      const touched = [...offer.writeScopes.map((ws) => ({ t0: ws.t0, t1: ws.t1 }))];

      // Pre-normalize beat & sound updates before mutating state (O(|ops|), zero full-Map copy)
      const removedBeatIdSet = new Set(ops.removeBeatIds ?? []);
      /** @type {import("../index.d.ts").Beat[]} */
      const preparedBeats = [];
      for (const rawBeat of ops.upsertBeats ?? []) {
        const existing = removedBeatIdSet.has(rawBeat.id)
          ? undefined
          : state.beats.get(rawBeat.id);
        const nextRev = existing ? existing.revision + 1 : rawBeat.revision ?? 1;
        preparedBeats.push(
          normalizeBeat(
            {
              ...rawBeat,
              source: rawBeat.source ?? "offer",
              revision: nextRev,
            },
            nextRev,
          ),
        );
      }

      const removedSoundIdSet = new Set(ops.removeSoundIds ?? []);
      /** @type {import("../index.d.ts").SoundAnchor[]} */
      const preparedSounds = [];
      for (const rawSound of ops.upsertSounds ?? []) {
        const existing = removedSoundIdSet.has(rawSound.id)
          ? undefined
          : state.sounds.get(rawSound.id);
        const nextRev = existing ? existing.revision + 1 : rawSound.revision ?? 1;
        preparedSounds.push(
          normalizeSoundAnchor(
            {
              ...rawSound,
              source: rawSound.source ?? "offer",
              revision: nextRev,
            },
            nextRev,
          ),
        );
      }

      const splitList = ops.splitSegments ?? [];
      const moveList = ops.moveSegmentBoundaries ?? [];
      const regList = ops.setSegmentRegimes ?? [];
      const hasSegOps = splitList.length > 0 || moveList.length > 0 || regList.length > 0;
      const savedSegments = isReplay && hasSegOps ? state.segments.slice() : null;
      const savedSpans = isReplay && hasSegOps ? state.spans.slice() : null;
      const savedSegMutLen = state.segmentMutations.length;
      const savedSegRevs =
        isReplay && hasSegOps
          ? new Map(
              [...state.segments.map((s) => s.id), ...state.spans.map((s) => s.id)].map((id) => [
                id,
                state.entityRevisions.get(id),
              ]),
            )
          : null;

      try {
        for (let i = 0; i < splitList.length; i += 1) {
          const s = splitList[i];
          splitSegmentInPlace(
            state,
            s.segmentId,
            s.newSegmentId,
            s.timeSec,
            "offer",
            event,
            `oa_sp${i}`,
          );
        }
        for (let i = 0; i < moveList.length; i += 1) {
          const m = moveList[i];
          moveSegmentBoundaryInPlace(
            state,
            m.leftSegmentId,
            m.rightSegmentId,
            m.timeSec,
            "offer",
            event,
            `oa_mv${i}`,
          );
        }
        for (let i = 0; i < regList.length; i += 1) {
          const r = regList[i];
          const idx = state.segments.findIndex((seg) => seg.id === r.segmentId);
          if (idx >= 0) {
            const seg = state.segments[idx];
            const nextSeg = normalizeSegment(
              {
                ...seg,
                regime: r.regime,
                source: "offer",
                revision: seg.revision + 1,
              },
              seg.revision + 1,
            );
            state.segments[idx] = nextSeg;
            state.entityRevisions.set(nextSeg.id, rev);
            if (nextSeg.regime === "free") {
              normalizeChildSpansForSegment(state, nextSeg, rev, `oa_rg${i}`);
            }
          }
        }
      } catch (err) {
        if (savedSegments && savedSpans && savedSegRevs) {
          for (const seg of state.segments) state.entityRevisions.delete(seg.id);
          for (const sp of state.spans) state.entityRevisions.delete(sp.id);
          state.segments = savedSegments;
          state.spans = savedSpans;
          state.segmentMutations.length = savedSegMutLen;
          for (const [id, prevRev] of savedSegRevs) {
            if (prevRev !== undefined) state.entityRevisions.set(id, prevRev);
            else state.entityRevisions.delete(id);
          }
        }
        if (isReplay && err instanceof EnsomiError) {
          state.foldWarnings.push({
            seq: event.seq,
            eventId: event.id,
            code: "ORPHANED_DEPENDENT_EDIT",
            message: err.message,
          });
          break;
        }
        throw err;
      }

      for (const id of ops.removeBeatIds ?? []) {
        if (state.beats.delete(id)) {
          state.entityRevisions.delete(id);
        }
      }
      for (const beat of preparedBeats) {
        state.beats.set(beat.id, beat);
        state.entityRevisions.set(beat.id, rev);
      }
      for (const id of ops.removeSoundIds ?? []) {
        if (state.sounds.delete(id)) {
          state.entityRevisions.delete(id);
        }
      }
      for (const sound of preparedSounds) {
        state.sounds.set(sound.id, sound);
        state.entityRevisions.set(sound.id, rev);
      }

      state.offers.set(offerId, {
        ...offer,
        status: "accepted",
        conflicts: [],
      });

      // Supersede mutually exclusive sibling options in the same batch cleanly (human-safe copy, zero IDs)
      for (const [otherId, other] of state.offers.entries()) {
        if (otherId === offerId) continue;
        if (
          other.batchId === offer.batchId &&
          other.status !== "accepted" &&
          other.status !== "rejected" &&
          other.status !== "superseded"
        ) {
          state.offers.set(otherId, {
            ...other,
            status: "superseded",
            conflicts: [
              {
                kind: "sibling_conflict",
                message: "Another option in this set was already applied.",
              },
            ],
          });
        }
      }

      clearOverlappingSuppressions(state, touched);
      refreshOfferStatuses(state);
      break;
    }

    case "offer_reject": {
      const offerId = payload?.offerId;
      if (typeof offerId !== "string" || offerId.length === 0) {
        throw new EnsomiError("INVALID_PAYLOAD", "offer_reject payload requires offerId");
      }
      const offer = state.offers.get(offerId);
      if (!offer) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_OFFER",
          message: `offer_reject skipped unknown offer "${offerId}"`,
        });
        break;
      }
      if (offer.status === "accepted" || offer.status === "rejected" || offer.status === "superseded") {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "OFFER_NOT_REJECTABLE",
          message: `offer_reject ignored for "${offerId}" with terminal status "${offer.status}"`,
        });
        break;
      }

      state.offers.set(offerId, {
        ...offer,
        status: "rejected",
      });

      const scopeJson = canonicalJson(offer.writeScopes);
      const alreadySuppressed = state.suppressions.some(
        (s) => s.semanticKey === offer.semanticKey && canonicalJson(s.writeScopes) === scopeJson,
      );
      if (!alreadySuppressed) {
        state.suppressions.push({
          semanticKey: offer.semanticKey,
          writeScopes: offer.writeScopes.map((ws) => ({ ...ws })),
          rejectedAtSeq: event.seq,
          offerId: offer.id,
        });
      }
      break;
    }

    case "listen_effort": {
      if (payload && typeof payload === "object") {
        if (typeof payload.listenSec === "number" && Number.isFinite(payload.listenSec) && payload.listenSec > 0) {
          state.effort.listenSec += payload.listenSec;
        }
        if (typeof payload.waitSec === "number" && Number.isFinite(payload.waitSec) && payload.waitSec > 0) {
          state.effort.waitSec += payload.waitSec;
        }
        const rawRange = payload.range ?? payload.span;
        if (rawRange) {
          state.visitedRanges.push(assertRange(rawRange));
        }
      }
      break;
    }

    case "ready": {
      state.ready = payload?.ready !== false;
      state.readyAtSeq = state.ready ? event.seq : null;
      break;
    }

    default:
      throw new EnsomiError("INVALID_EVENT", `Unhandled event type: ${event.type}`);
  }
}

/**
 * Apply a single TraceEvent to `SessionState`.
 * - Normal events apply incrementally in O(1)/shallow-container time.
 * - `"retract"` events perform a clean full re-fold from event 0 so selective undo/redo
 *   never suffers inverse-delta clobbering and maintains 100% deterministic equivalence.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").TraceEvent} event
 * @returns {import("../index.d.ts").SessionState}
 */
export function applyEvent(state, event) {
  assertTraceEventEnvelope(event);
  if (state.events.length > 0 && event.seq <= state.lastSeq) {
    throw new EnsomiError(
      "NON_MONOTONIC_SEQ",
      `Event seq ${event.seq} must be strictly greater than lastSeq ${state.lastSeq}`,
    );
  }
  if (state.eventIds.has(event.id)) {
    throw new EnsomiError("DUPLICATE_EVENT_ID", `Duplicate trace event id "${event.id}"`);
  }

  const clonedEvent = cloneAndFreezeEvent(event);

  if (clonedEvent.type === "retract") {
    return foldTrace([...state.events, clonedEvent], {
      durationSec: state.initialDurationSec,
    });
  }

  const next = cloneStateShallow(state);
  next.events.push(clonedEvent);
  next.eventIds.add(clonedEvent.id);
  next.lastSeq = clonedEvent.seq;
  applyActiveEventInPlace(next, clonedEvent, false);
  return next;
}

/**
 * Fold a full sequence of TraceEvents from event 0 into a materialized `SessionState`.
 * Guaranteed identical to `events.reduce(applyEvent, createInitialState(options))` on non-retract traces.
 *
 * @param {import("../index.d.ts").TraceEvent[]} events
 * @param {{ durationSec?: number }} [options]
 * @returns {import("../index.d.ts").SessionState}
 */
export function foldTrace(events, options = {}) {
  if (!Array.isArray(events)) {
    throw new EnsomiError("INVALID_TRACE", "events must be an array");
  }

  let prevSeq = -1;
  const seenIds = new Set();
  /** @type {import("../index.d.ts").TraceEvent[]} */
  const clonedEvents = [];
  /** @type {Map<string, import("../index.d.ts").TraceEvent>} */
  const byId = new Map();
  /** @type {Map<number, import("../index.d.ts").TraceEvent>} */
  const bySeq = new Map();

  for (const ev of events) {
    assertTraceEventEnvelope(ev);
    if (prevSeq >= 0 && ev.seq <= prevSeq) {
      throw new EnsomiError(
        "NON_MONOTONIC_SEQ",
        `Event seq ${ev.seq} must be strictly greater than previous seq ${prevSeq}`,
      );
    }
    if (seenIds.has(ev.id)) {
      throw new EnsomiError("DUPLICATE_EVENT_ID", `Duplicate trace event id "${ev.id}"`);
    }
    seenIds.add(ev.id);
    prevSeq = ev.seq;
    const cloned = cloneAndFreezeEvent(ev);
    clonedEvents.push(cloned);
    byId.set(cloned.id, cloned);
    bySeq.set(cloned.seq, cloned);
  }

  const retractedIds = resolveActiveRetractions(clonedEvents);
  let effectiveDurationSec = options?.durationSec;
  if (effectiveDurationSec === undefined) {
    const firstSeed = clonedEvents[0]?.seedDurationSec;
    if (typeof firstSeed === "number" && Number.isFinite(firstSeed) && firstSeed > 0) {
      effectiveDurationSec = firstSeed;
    } else {
      for (const ev of clonedEvents) {
        if (ev.type === "audio_bound" && !retractedIds.has(ev.id)) {
          break;
        }
        if (
          typeof ev.seedDurationSec === "number" &&
          Number.isFinite(ev.seedDurationSec) &&
          ev.seedDurationSec > 0
        ) {
          effectiveDurationSec = ev.seedDurationSec;
          break;
        }
        const basisDur = /** @type {any} */ (ev.payload)?.basisDurationSec;
        if (typeof basisDur === "number" && Number.isFinite(basisDur) && basisDur > 0) {
          effectiveDurationSec = basisDur;
          break;
        }
      }
    }
  }

  const state = createInitialState(
    effectiveDurationSec !== undefined ? { ...options, durationSec: effectiveDurationSec } : options,
  );
  state.retractedEventIds = retractedIds;

  for (const ev of clonedEvents) {
    state.events.push(ev);
    state.eventIds.add(ev.id);
    state.lastSeq = ev.seq;

    if (ev.type === "retract") {
      if (!retractedIds.has(ev.id)) {
        if (ev.author?.kind === "user") {
          state.effort.actionCount += 1;
        }
        resolveRetractTarget(ev, byId, bySeq);
        refreshOfferStatuses(state);
      }
      continue;
    }

    if (retractedIds.has(ev.id)) {
      continue;
    }

    applyActiveEventInPlace(state, ev, true);
  }

  return state;
}

/**
 * Serialize a TraceEvent array to append-only NDJSON (.jsonl).
 * @param {readonly import("../index.d.ts").TraceEvent[]} events
 * @returns {string}
 */
export function serializeTraceNdjson(events) {
  if (!Array.isArray(events)) {
    throw new EnsomiError("INVALID_TRACE", "events must be an array");
  }
  if (events.length === 0) return "";
  return `${events.map((ev) => JSON.stringify(ev)).join("\n")}\n`;
}

/**
 * Parse an NDJSON (.jsonl) trace string into validated TraceEvents.
 * When `discardTruncatedTail: true`, discards ONLY an incomplete/corrupt trailing line;
 * interior line corruption ALWAYS throws `EnsomiError("CORRUPT_NDJSON")`.
 *
 * @param {string} text
 * @param {{ discardTruncatedTail?: boolean }} [options]
 * @returns {import("../index.d.ts").TraceEvent[]}
 */
export function parseTraceNdjson(text, options = {}) {
  if (typeof text !== "string") {
    throw new EnsomiError("INVALID_NDJSON", "NDJSON input must be a string");
  }
  const discardTruncatedTail = Boolean(options.discardTruncatedTail);
  if (text.length === 0) return [];

  const rawLines = text.split(/\r?\n/);
  if (rawLines.length > 0 && rawLines[rawLines.length - 1] === "") {
    rawLines.pop();
  }
  if (rawLines.length === 0) return [];

  /** @type {import("../index.d.ts").TraceEvent[]} */
  const events = [];
  for (let i = 0; i < rawLines.length; i += 1) {
    const line = rawLines[i];
    const isLastLine = i === rawLines.length - 1;
    const lineNum = i + 1;

    if (line.trim().length === 0) {
      throw new EnsomiError("CORRUPT_NDJSON", `Empty or whitespace-only line at line ${lineNum}`, {
        line: lineNum,
      });
    }

    let parsed;
    try {
      parsed = JSON.parse(line);
      assertTraceEventEnvelope(parsed);
    } catch (err) {
      if (isLastLine && discardTruncatedTail) {
        break;
      }
      throw new EnsomiError(
        "CORRUPT_NDJSON",
        `Corrupt NDJSON at line ${lineNum}: ${err instanceof Error ? err.message : String(err)}`,
        { line: lineNum },
      );
    }

    events.push(/** @type {import("../index.d.ts").TraceEvent} */ (parsed));
  }

  return events;
}

/**
 * Convert materialized `SessionState` into a canonical, deeply cloned JSON-serializable object.
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").SerializedSessionState}
 */
export function serializeState(state) {
  const revEntries =
    state.entityRevisions instanceof Map
      ? Array.from(state.entityRevisions.entries()).sort(([a], [b]) => a.localeCompare(b))
      : [];

  return structuredClone({
    audio: state.audio,
    durationSec: state.durationSec,
    segments: state.segments.slice(),
    spans: state.spans
      .slice()
      .sort(
        (a, b) =>
          a.t0 - b.t0 ||
          a.t1 - b.t1 ||
          a.kind.localeCompare(b.kind) ||
          a.id.localeCompare(b.id),
      ),
    beats: Array.from(state.beats.values()).sort(
      (a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id),
    ),
    sounds: Array.from(state.sounds.values()).sort(
      (a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id),
    ),
    unbeats: state.unbeats
      .slice()
      .sort((a, b) => a.timeSec - b.timeSec || a.id.localeCompare(b.id)),
    offers: Array.from(state.offers.values()).sort((a, b) => a.id.localeCompare(b.id)),
    entityRevisions: Object.fromEntries(revEntries),
    suppressions: state.suppressions
      .slice()
      .sort(
        (a, b) =>
          a.rejectedAtSeq - b.rejectedAtSeq || a.semanticKey.localeCompare(b.semanticKey),
      ),
    lastSignal: state.lastSignal,
    generation: state.generation,
    effort: { ...state.effort },
    visitedRanges: state.visitedRanges.slice(),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    retractedEventIds: Array.from(state.retractedEventIds).sort(),
    lastSeq: state.lastSeq,
  });
}

/**
 * Create a verifiable deterministic checkpoint from materialized `SessionState`.
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").TraceCheckpoint}
 */
export function createCheckpoint(state) {
  const snapshot = serializeState(state);
  const stateHash = `sha256:${sha256Hex(canonicalJson(snapshot))}`;
  return {
    schemaVersion: TRACE_SCHEMA_VERSION,
    lastSeq: state.lastSeq,
    eventCount: state.events.length,
    ...(state.initialDurationSec > 0 ? { initialDurationSec: state.initialDurationSec } : {}),
    stateHash,
    snapshot,
  };
}

/**
 * Verify a checkpoint against the trace prefix that produced it.
 * @param {import("../index.d.ts").TraceCheckpoint} checkpoint
 * @param {import("../index.d.ts").TraceEvent[]} prefixEvents
 * @param {{ durationSec?: number }} [options]
 * @returns {import("../index.d.ts").CheckpointVerification}
 */
export function verifyCheckpoint(checkpoint, prefixEvents, options = {}) {
  const durationSec =
    options?.durationSec !== undefined
      ? options.durationSec
      : typeof checkpoint?.initialDurationSec === "number"
        ? checkpoint.initialDurationSec
        : undefined;
  const refState = foldTrace(prefixEvents, { durationSec });
  const refCheckpoint = createCheckpoint(refState);

  if (!checkpoint || typeof checkpoint !== "object" || Array.isArray(checkpoint)) {
    return {
      valid: false,
      reason: "checkpoint must be a plain object",
      expectedHash: refCheckpoint.stateHash,
      actualHash: "",
    };
  }
  if (checkpoint.schemaVersion !== TRACE_SCHEMA_VERSION) {
    return {
      valid: false,
      reason: `schemaVersion mismatch: checkpoint=${checkpoint.schemaVersion}, expected=${TRACE_SCHEMA_VERSION}`,
      expectedHash: refCheckpoint.stateHash,
      actualHash: typeof checkpoint.stateHash === "string" ? checkpoint.stateHash : "",
    };
  }
  if (
    !checkpoint.snapshot ||
    typeof checkpoint.snapshot !== "object" ||
    Array.isArray(checkpoint.snapshot)
  ) {
    return {
      valid: false,
      reason: "checkpoint snapshot must be a plain object",
      expectedHash: refCheckpoint.stateHash,
      actualHash: typeof checkpoint.stateHash === "string" ? checkpoint.stateHash : "",
    };
  }
  if (typeof checkpoint.stateHash !== "string" || checkpoint.stateHash.length === 0) {
    return {
      valid: false,
      reason: "checkpoint stateHash must be a non-empty string",
      expectedHash: refCheckpoint.stateHash,
      actualHash: "",
    };
  }
  if (checkpoint.lastSeq !== refCheckpoint.lastSeq) {
    return {
      valid: false,
      reason: `lastSeq mismatch: checkpoint=${checkpoint.lastSeq}, prefix=${refCheckpoint.lastSeq}`,
      expectedHash: refCheckpoint.stateHash,
      actualHash: checkpoint.stateHash,
    };
  }
  if (checkpoint.eventCount !== refCheckpoint.eventCount) {
    return {
      valid: false,
      reason: `eventCount mismatch: checkpoint=${checkpoint.eventCount}, prefix=${refCheckpoint.eventCount}`,
      expectedHash: refCheckpoint.stateHash,
      actualHash: checkpoint.stateHash,
    };
  }

  const snapshotHash = `sha256:${sha256Hex(canonicalJson(checkpoint.snapshot))}`;
  if (snapshotHash !== checkpoint.stateHash) {
    return {
      valid: false,
      reason: "checkpoint snapshot contents do not match checkpoint.stateHash",
      expectedHash: refCheckpoint.stateHash,
      actualHash: snapshotHash,
    };
  }
  if (checkpoint.stateHash !== refCheckpoint.stateHash) {
    return {
      valid: false,
      reason: "stateHash mismatch against re-folded prefix",
      expectedHash: refCheckpoint.stateHash,
      actualHash: checkpoint.stateHash,
    };
  }
  return {
    valid: true,
    expectedHash: refCheckpoint.stateHash,
    actualHash: snapshotHash,
  };
}
