/**
 * Shared in-memory `SessionState` <-> plain JSON `Snapshot` conversion helpers.
 * Keeps `src/offers.js` and `src/trace.js` strictly acyclic and 100% consistent.
 */

import { compileConstraints } from "./constraints.js";
import { compareCodeUnits, jcsSha256Hex } from "./jcs.js";
import { assertNonNegSafeIntUs } from "./time.js";
import {
  deepFreeze,
  normalizeApproval,
  normalizeBeat,
  normalizeBoundary,
  normalizeSegment,
  normalizeSound,
  normalizeSoundClass,
  normalizeSpan,
  normalizeUnbeat,
  TRACE_SCHEMA_VERSION,
} from "./types.js";

/**
 * Create a clean initial `SessionState`.
 * When `options.durationUs > 0` is provided (for standalone reducer tests prior to `audio_bound`),
 * initializes a single `init_seg_0` segment covering `[0, durationUs)`.
 *
 * @param {{ durationUs?: number }} [options]
 * @returns {import("../index.d.ts").SessionState}
 */
export function createInitialState(options = {}) {
  const durationUs =
    options?.durationUs !== undefined
      ? assertNonNegSafeIntUs(options.durationUs, "options.durationUs", "INVALID_DURATION")
      : 0;

  /** @type {Map<string, import("../index.d.ts").EntityIdRecord>} */
  const idRegistry = new Map();
  /** @type {import("../index.d.ts").Segment[]} */
  const segments = [];

  if (durationUs > 0) {
    const initSeg = normalizeSegment({
      id: "init_seg_0",
      startUs: 0,
      endUs: durationUs,
      startBoundaryId: null,
      endBoundaryId: null,
      regime: "auto",
      origin: "rule",
      createdByEventId: "init",
      revision: 1,
    });
    segments.push(initSeg);
    idRegistry.set(
      initSeg.id,
      deepFreeze({
        id: initSeg.id,
        kind: "segment",
        active: true,
        createdByEventId: "init",
      }),
    );
  }

  return {
    audio: null,
    durationUs,
    segments,
    boundaries: new Map(),
    spans: [],
    beats: new Map(),
    unbeats: new Map(),
    sounds: new Map(),
    soundClasses: new Map(),
    approvals: new Map(),
    idRegistry,
    offers: new Map(),
    suppressedOffers: [],
    suppressions: [],
    orphanedTransactions: [],
    lastSignal: null,
    generation: 0,
    effort: {
      listenUs: 0,
      waitUs: 0,
      actionCount: 0,
    },
    visitedRanges: [],
    ready: false,
    readyAtSeq: null,
    events: [],
    eventIds: new Set(),
    retractedEventIds: new Set(),
    lastSeq: -1,
  };
}

/**
 * Shallow-clone `SessionState` containers. Since all entity values inside `SessionState`
 * are recursively frozen on creation/update (`deepFreeze`), shallow container copies are
 * O(N_ptrs) native C++ copies with zero per-entity object allocation overhead.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").SessionState}
 */
export function cloneSessionStateShallow(state) {
  return {
    audio: state.audio,
    durationUs: state.durationUs,
    segments: state.segments.slice(),
    boundaries: new Map(state.boundaries),
    spans: state.spans.slice(),
    beats: new Map(state.beats),
    unbeats: new Map(state.unbeats),
    sounds: new Map(state.sounds),
    soundClasses: new Map(state.soundClasses),
    approvals: new Map(state.approvals),
    idRegistry: new Map(state.idRegistry),
    offers: new Map(state.offers),
    suppressedOffers: state.suppressedOffers.slice(),
    suppressions: state.suppressions.slice(),
    orphanedTransactions: state.orphanedTransactions.slice(),
    lastSignal: state.lastSignal,
    generation: state.generation,
    effort: { ...state.effort },
    visitedRanges: state.visitedRanges.slice(),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    events: state.events.slice(),
    eventIds: new Set(state.eventIds),
    retractedEventIds: new Set(state.retractedEventIds),
    lastSeq: state.lastSeq,
  };
}

/**
 * Reconstruct a mutable scratch `SessionState` from either a live `SessionState` or a
 * plain JSON `Snapshot` (preserving both active and retired entity IDs in `idRegistry`).
 *
 * @param {import("../index.d.ts").SessionState | import("../index.d.ts").Snapshot} stateOrSnapshot
 * @returns {import("../index.d.ts").SessionState}
 */
export function toScratchSessionState(stateOrSnapshot) {
  if (stateOrSnapshot && /** @type {any} */ (stateOrSnapshot).beats instanceof Map) {
    return cloneSessionStateShallow(
      /** @type {import("../index.d.ts").SessionState} */ (stateOrSnapshot),
    );
  }
  const snap = /** @type {import("../index.d.ts").Snapshot} */ (stateOrSnapshot);
  const state = createInitialState();
  state.audio = snap.audio ? deepFreeze({ ...snap.audio }) : null;
  state.durationUs = snap.durationUs ?? snap.audio?.durationUs ?? 0;

  state.idRegistry.clear();

  for (const retired of snap.retiredEntityIds ?? []) {
    state.idRegistry.set(
      retired.id,
      deepFreeze({
        id: retired.id,
        kind: retired.kind,
        active: false,
        createdByEventId: retired.createdByEventId,
        retiredByEventId: retired.retiredByEventId,
      }),
    );
  }

  state.segments = (snap.segments ?? []).map((s) => {
    const norm = normalizeSegment(s);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "segment",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
    if (norm.approval) {
      state.approvals.set(norm.approval.id, norm.approval);
      state.idRegistry.set(
        norm.approval.id,
        deepFreeze({
          id: norm.approval.id,
          kind: "approval",
          active: true,
          createdByEventId: norm.approval.eventId,
        }),
      );
    }
    return norm;
  });

  for (const b of snap.boundaries ?? []) {
    const norm = normalizeBoundary(b);
    state.boundaries.set(norm.id, norm);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "boundary",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
  }

  state.spans = (snap.spans ?? []).map((sp) => {
    const norm = normalizeSpan(sp);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "span",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
    if (norm.approval) {
      state.approvals.set(norm.approval.id, norm.approval);
      state.idRegistry.set(
        norm.approval.id,
        deepFreeze({
          id: norm.approval.id,
          kind: "approval",
          active: true,
          createdByEventId: norm.approval.eventId,
        }),
      );
    }
    return norm;
  });

  for (const b of snap.beats ?? []) {
    const norm = normalizeBeat(b);
    state.beats.set(norm.id, norm);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "beat",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
  }

  for (const u of snap.unbeats ?? []) {
    const norm = normalizeUnbeat(u);
    state.unbeats.set(norm.id, norm);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "unbeat",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
  }

  for (const s of snap.sounds ?? []) {
    const norm = normalizeSound(s);
    state.sounds.set(norm.id, norm);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "sound",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
  }

  for (const sc of snap.soundClasses ?? []) {
    const norm = normalizeSoundClass(sc);
    state.soundClasses.set(norm.id, norm);
    state.idRegistry.set(
      norm.id,
      deepFreeze({
        id: norm.id,
        kind: "sound_class",
        active: true,
        createdByEventId: norm.createdByEventId,
      }),
    );
  }

  for (const app of snap.approvals ?? []) {
    if (!state.approvals.has(app.id)) {
      const norm = normalizeApproval(app);
      state.approvals.set(norm.id, norm);
      state.idRegistry.set(
        norm.id,
        deepFreeze({
          id: norm.id,
          kind: "approval",
          active: true,
          createdByEventId: norm.eventId,
        }),
      );
    }
  }

  for (const off of snap.offers ?? []) {
    state.offers.set(off.id, deepFreeze(structuredClone(off)));
  }

  state.suppressions = (snap.suppressions ?? []).map((s) => deepFreeze(structuredClone(s)));
  state.orphanedTransactions = (snap.orphanedTransactions ?? []).map((o) =>
    deepFreeze(structuredClone(o)),
  );
  state.lastSignal = snap.lastSignal ? deepFreeze(structuredClone(snap.lastSignal)) : null;
  state.generation = snap.generation ?? 0;
  state.effort = snap.effort
    ? { ...snap.effort }
    : { listenUs: 0, waitUs: 0, actionCount: 0 };
  state.visitedRanges = (snap.visitedRanges ?? []).map((r) => ({ ...r }));
  state.ready = Boolean(snap.ready);
  state.readyAtSeq = snap.readyAtSeq ?? null;
  state.retractedEventIds = new Set(snap.retractedEventIds ?? []);
  state.lastSeq = snap.lastSeq ?? -1;

  return state;
}

/**
 * Materialize a canonical, JCS-ready plain JSON `Snapshot` from `SessionState` or `Snapshot`.
 * All entity arrays and map exports are deterministically sorted using `compareCodeUnits`.
 *
 * @param {import("../index.d.ts").SessionState | import("../index.d.ts").Snapshot} stateOrSnapshot
 * @returns {import("../index.d.ts").Snapshot}
 */
export function toSnapshot(stateOrSnapshot) {
  if (
    stateOrSnapshot &&
    !(/** @type {any} */ (stateOrSnapshot).beats instanceof Map) &&
    /** @type {any} */ (stateOrSnapshot).schemaVersion === TRACE_SCHEMA_VERSION &&
    Array.isArray(/** @type {any} */ (stateOrSnapshot).beats)
  ) {
    return deepFreeze(structuredClone(/** @type {import("../index.d.ts").Snapshot} */ (stateOrSnapshot)));
  }

  const state = /** @type {import("../index.d.ts").SessionState} */ (stateOrSnapshot);

  /** @type {import("../index.d.ts").RetiredEntityIdRecord[]} */
  const retiredEntityIds = [];
  for (const rec of state.idRegistry.values()) {
    if (!rec.active) {
      retiredEntityIds.push({
        id: rec.id,
        kind: rec.kind,
        createdByEventId: rec.createdByEventId,
        retiredByEventId: rec.retiredByEventId ?? rec.createdByEventId,
      });
    }
  }
  retiredEntityIds.sort((a, b) => compareCodeUnits(a.id, b.id));

  const segments = state.segments.slice();
  const boundaries = Array.from(state.boundaries.values()).sort(
    (a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id),
  );
  const spans = state.spans
    .slice()
    .sort(
      (a, b) =>
        a.startUs - b.startUs ||
        a.endUs - b.endUs ||
        compareCodeUnits(a.kind, b.kind) ||
        compareCodeUnits(a.id, b.id),
    );
  const beats = Array.from(state.beats.values()).sort(
    (a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id),
  );
  const unbeats = Array.from(state.unbeats.values()).sort(
    (a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id),
  );
  const sounds = Array.from(state.sounds.values()).sort(
    (a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id),
  );
  const soundClasses = Array.from(state.soundClasses.values()).sort((a, b) =>
    compareCodeUnits(a.id, b.id),
  );
  const approvals = Array.from(state.approvals.values()).sort((a, b) =>
    compareCodeUnits(a.id, b.id),
  );
  const offers = Array.from(state.offers.values()).sort((a, b) => compareCodeUnits(a.id, b.id));
  const suppressions = state.suppressions
    .slice()
    .sort(
      (a, b) =>
        a.rejectedAtSeq - b.rejectedAtSeq || compareCodeUnits(a.semanticKey, b.semanticKey),
    );
  const orphanedTransactions = state.orphanedTransactions.slice();
  const retractedEventIds = Array.from(state.retractedEventIds).sort(compareCodeUnits);

  return deepFreeze({
    schemaVersion: TRACE_SCHEMA_VERSION,
    audio: state.audio,
    durationUs: state.durationUs,
    segments,
    boundaries,
    spans,
    beats,
    unbeats,
    sounds,
    soundClasses,
    approvals,
    retiredEntityIds,
    offers,
    suppressions,
    orphanedTransactions,
    lastSignal: state.lastSignal,
    generation: state.generation,
    effort: { ...state.effort },
    visitedRanges: state.visitedRanges.map((r) => ({ ...r })),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    retractedEventIds,
    lastSeq: state.lastSeq,
  });
}

/**
 * Alias of `toSnapshot` for callers expecting `serializeState`.
 */
export const serializeState = toSnapshot;

/**
 * Compute RFC 8785 (JCS) SHA-256 digest of a `Snapshot` (or `SessionState` materialized via `toSnapshot`).
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} stateOrSnapshot
 * @returns {string}
 */
export function computeSnapshotDigest(stateOrSnapshot) {
  return jcsSha256Hex(toSnapshot(stateOrSnapshot));
}

/**
 * Build a JSON-safe `ProviderView` (`{ snapshot, constraints }`) for passing across
 * JS / Python-daemon / Rust / Wasm provider boundaries.
 *
 * @param {import("../index.d.ts").SessionState | import("../index.d.ts").Snapshot} stateOrSnapshot
 * @returns {import("../index.d.ts").ProviderView}
 */
export function createProviderView(stateOrSnapshot) {
  const snapshot = toSnapshot(stateOrSnapshot);
  const constraints = compileConstraints(snapshot);
  return deepFreeze({
    snapshot,
    constraints,
  });
}

/**
 * Check whether `state` currently holds any active human `Verdict` or `Approval` (`keep`).
 *
 * @param {import("../index.d.ts").SessionState} state
 * @returns {boolean}
 */
export function hasActiveHumanTruth(state) {
  if (state.approvals.size > 0) return true;
  for (const seg of state.segments) {
    if (seg.approval) return true;
  }
  for (const sp of state.spans) {
    if (sp.approval) return true;
  }
  for (const b of state.beats.values()) {
    if (b.verdict) return true;
  }
  for (const u of state.unbeats.values()) {
    if (u.verdict) return true;
  }
  for (const s of state.sounds.values()) {
    if (s.verdict) return true;
  }
  for (const sc of state.soundClasses.values()) {
    if (sc.verdict) return true;
  }
  for (const bnd of state.boundaries.values()) {
    if (bnd.verdict) return true;
  }
  return false;
}
