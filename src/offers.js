/**
 * Soft-first PatchOffer semantics & human-safe validation:
 *  - Deterministic semantic keys independent of freshly minted ephemeral IDs
 *  - Hard invalidation ONLY on:
 *      1. `protected_keep`
 *      2. `version_mismatch`
 *      3. `range_changed`
 *      4. `out_of_scope`
 *      (+ `sibling_conflict` when an alternative in the same batch was already accepted)
 *  - Soft cues (`beat`, `unbeat`, `sound`, `free`) NEVER hard-block acceptance;
 *    disagreements surface as concise musical `advisoryNotes` while `status` stays `"valid"`.
 *  - Zero internal IDs, batchIds, or hashes in user-facing conflict/advisory/consequence strings.
 */

import { compileConstraints, DEFAULT_BEAT_GUIDE_TOL_SEC } from "./constraints.js";
import { canonicalJson, roundTime, sha256Hex } from "./hash.js";
import {
  assertRange,
  formatTimeCode,
  normalizeBeat,
  normalizeSoundAnchor,
  rangeContainsTime,
  rangesOverlap,
  SEGMENT_REGIMES,
} from "./types.js";

const SEGMENT_REGIME_SET = new Set(SEGMENT_REGIMES);

/**
 * Parse originating trace seq from an immutable entity revision token (`"${seq}:${eventId}"`).
 * @param {string | undefined} rev
 * @returns {number | null}
 */
export function parseRevisionSeq(rev) {
  if (typeof rev !== "string") return null;
  const colonIdx = rev.indexOf(":");
  if (colonIdx <= 0) return null;
  const seq = Number(rev.slice(0, colonIdx));
  return Number.isInteger(seq) && seq >= 0 ? seq : null;
}

/**
 * Strip `revision` counter for content-equality checks inside `Keep` locks.
 * @param {Record<string, unknown>} entity
 */
function stripRevision(entity) {
  const { revision: _ignored, ...rest } = entity;
  return rest;
}

/**
 * Normalize writeScopes array into validated `{ segmentId, t0, t1 }[]`.
 * @param {readonly import("../index.d.ts").WriteScope[]} writeScopes
 */
function normalizeWriteScopes(writeScopes) {
  if (!Array.isArray(writeScopes) || writeScopes.length === 0) {
    return [];
  }
  return writeScopes.map((ws) => {
    const r = assertRange(ws);
    return { segmentId: ws.segmentId, t0: r.t0, t1: r.t1 };
  });
}

/**
 * Compute a deterministic semantic key for a PatchOffer's `writeScopes` and `ops`.
 * Preserves stable IDs for existing/referenced entities while stripping ephemeral IDs
 * and revision counters from newly proposed entities.
 *
 * @param {readonly import("../index.d.ts").WriteScope[]} writeScopes
 * @param {import("../index.d.ts").PatchOp} [ops]
 * @param {Record<string, string>} [expectedRevisions]
 * @returns {string}
 */
export function computeSemanticKey(writeScopes, ops = {}, expectedRevisions = {}) {
  const scopes = normalizeWriteScopes(writeScopes)
    .map((ws) => ({
      segmentId: ws.segmentId,
      t0: roundTime(ws.t0, 4),
      t1: roundTime(ws.t1, 4),
    }))
    .sort(
      (a, b) =>
        a.t0 - b.t0 || a.t1 - b.t1 || a.segmentId.localeCompare(b.segmentId),
    );

  const stableIds = new Set([
    ...Object.keys(expectedRevisions ?? {}),
    ...(ops.removeBeatIds ?? []),
    ...(ops.removeSoundIds ?? []),
  ]);

  const canonicalBeats = (ops.upsertBeats ?? [])
    .map((b) => ({
      id: typeof b.id === "string" && stableIds.has(b.id) ? b.id : null,
      timeSec: roundTime(b.timeSec, 4),
      source: b.source ?? "offer",
      snapEventId: b.snapEventId ?? null,
    }))
    .sort(
      (a, b) =>
        a.timeSec - b.timeSec ||
        (a.id ?? "").localeCompare(b.id ?? "") ||
        a.source.localeCompare(b.source),
    );

  const canonicalSounds = (ops.upsertSounds ?? [])
    .map((s) => ({
      id: typeof s.id === "string" && stableIds.has(s.id) ? s.id : null,
      timeSec: roundTime(s.timeSec, 4),
      strength: s.strength !== undefined ? roundTime(s.strength, 4) : null,
      family: s.family ?? null,
      role: s.role ?? null,
    }))
    .sort(
      (a, b) =>
        a.timeSec - b.timeSec ||
        (a.id ?? "").localeCompare(b.id ?? ""),
    );

  const canonicalSplits = (ops.splitSegments ?? [])
    .map((s) => ({
      segmentId: s.segmentId,
      timeSec: roundTime(s.timeSec, 4),
    }))
    .sort((a, b) => a.timeSec - b.timeSec || a.segmentId.localeCompare(b.segmentId));

  const canonicalMoves = (ops.moveSegmentBoundaries ?? [])
    .map((m) => ({
      leftSegmentId: m.leftSegmentId,
      rightSegmentId: m.rightSegmentId,
      timeSec: roundTime(m.timeSec, 4),
    }))
    .sort((a, b) => a.timeSec - b.timeSec || a.leftSegmentId.localeCompare(b.leftSegmentId));

  const canonicalRegimes = (ops.setSegmentRegimes ?? [])
    .map((r) => ({ segmentId: r.segmentId, regime: r.regime }))
    .sort((a, b) => a.segmentId.localeCompare(b.segmentId));

  const canonical = {
    v: 3,
    writeScopes: scopes,
    upsertBeats: canonicalBeats,
    removeBeatIds: [...new Set(ops.removeBeatIds ?? [])].sort(),
    upsertSounds: canonicalSounds,
    removeSoundIds: [...new Set(ops.removeSoundIds ?? [])].sort(),
    splitSegments: canonicalSplits,
    moveSegmentBoundaries: canonicalMoves,
    setSegmentRegimes: canonicalRegimes,
  };

  return `sk:${sha256Hex(canonicalJson(canonical)).slice(0, 32)}`;
}

/**
 * Collect sorted beat IDs currently inside `writeScopes`.
 * @param {import("../index.d.ts").SessionState} state
 * @param {readonly import("../index.d.ts").WriteScope[]} writeScopes
 * @returns {string[]}
 */
export function getScopeBeatIds(state, writeScopes) {
  const scopes = normalizeWriteScopes(writeScopes);
  if (scopes.length === 0) return [];
  /** @type {string[]} */
  const ids = [];
  for (const beat of state.beats.values()) {
    if (scopes.some((ws) => rangeContainsTime(ws, beat.timeSec))) {
      ids.push(beat.id);
    }
  }
  ids.sort();
  return ids;
}

/**
 * Compute numeric consequences of applying `ops` to `state`.
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOp} [ops]
 * @returns {import("../index.d.ts").OfferConsequences}
 */
export function computeConsequences(state, ops = {}) {
  let beatsAdded = 0;
  let beatsMoved = 0;
  let beatsRemoved = 0;
  let maxShiftMs = 0;

  const removedSet = new Set(ops.removeBeatIds ?? []);
  const upsertedIds = new Set((ops.upsertBeats ?? []).map((b) => b.id));

  for (const id of removedSet) {
    if (state.beats.has(id) && !upsertedIds.has(id)) {
      beatsRemoved += 1;
    }
  }

  for (const beat of ops.upsertBeats ?? []) {
    const existing = state.beats.get(beat.id);
    if (!existing) {
      beatsAdded += 1;
    } else if (existing.timeSec !== beat.timeSec) {
      beatsMoved += 1;
      const shiftMs = Math.abs(beat.timeSec - existing.timeSec) * 1000;
      if (shiftMs > maxShiftMs) maxShiftMs = shiftMs;
    }
  }

  const segmentChanges =
    (ops.splitSegments?.length ?? 0) +
    (ops.moveSegmentBoundaries?.length ?? 0) +
    (ops.setSegmentRegimes?.length ?? 0);

  return {
    beatsAdded,
    beatsMoved,
    beatsRemoved,
    maxShiftMs: roundTime(maxShiftMs, 2),
    segmentChanges,
  };
}

/**
 * Generate a concise, human-safe musical summary of offer consequences (zero internal IDs).
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOp} [ops]
 * @returns {string}
 */
export function formatConsequenceSummary(state, ops = {}) {
  const c = computeConsequences(state, ops);
  /** @type {string[]} */
  const parts = [];
  if (c.beatsMoved > 0) {
    const shiftNote = c.maxShiftMs >= 1 ? ` (up to ${Math.round(c.maxShiftMs)} ms)` : "";
    parts.push(`Nudges ${c.beatsMoved} beat${c.beatsMoved === 1 ? "" : "s"}${shiftNote}`);
  }
  if (c.beatsAdded > 0 && c.beatsRemoved > 0) {
    parts.push(
      `replaces ${c.beatsRemoved} beat${c.beatsRemoved === 1 ? "" : "s"} with ${c.beatsAdded}`,
    );
  } else if (c.beatsAdded > 0) {
    parts.push(`adds ${c.beatsAdded} beat${c.beatsAdded === 1 ? "" : "s"}`);
  } else if (c.beatsRemoved > 0) {
    parts.push(`removes ${c.beatsRemoved} beat${c.beatsRemoved === 1 ? "" : "s"}`);
  }
  if (c.segmentChanges > 0) {
    parts.push(`updates ${c.segmentChanges} section boundary${c.segmentChanges === 1 ? "" : " changes"}`);
  }
  if (parts.length === 0) {
    return "No beat timing changes";
  }
  const joined = parts.join(" · ");
  return joined.charAt(0).toUpperCase() + joined.slice(1);
}

/**
 * Detect mutually exclusive sibling offers within a batch (human-safe metadata, zero ID leaks).
 * Note: sibling alternatives remain `status: "valid"` until one sibling is accepted, at which
 * point other siblings in the batch transition cleanly to `status: "superseded"`.
 *
 * @param {readonly import("../index.d.ts").PatchOffer[]} offers
 * @returns {Map<string, number[]>} Map from offer.id -> conflicting sibling optionIndices
 */
export function detectSiblingConflicts(offers) {
  /** @type {Map<string, number[]>} */
  const map = new Map();
  for (let i = 0; i < offers.length; i += 1) {
    const a = offers[i];
    for (let j = i + 1; j < offers.length; j += 1) {
      const b = offers[j];
      if (a.id === b.id || a.batchId !== b.batchId) continue;
      if (!map.has(a.id)) map.set(a.id, []);
      if (!map.has(b.id)) map.set(b.id, []);
      map.get(a.id).push(b.optionIndex ?? j);
      map.get(b.id).push(a.optionIndex ?? i);
    }
  }
  return map;
}

/**
 * Check whether an offer is currently suppressed by a prior user rejection whose
 * `writeScopes` have not yet been invalidated by an overlapping user edit.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {{ semanticKey?: string, writeScopes: readonly import("../index.d.ts").WriteScope[], ops?: import("../index.d.ts").PatchOp, expectedRevisions?: Record<string, string> }} offer
 * @returns {boolean}
 */
export function isOfferSuppressed(state, offer) {
  if (!state || !Array.isArray(state.suppressions) || state.suppressions.length === 0) {
    return false;
  }
  const key =
    offer.semanticKey ||
    computeSemanticKey(offer.writeScopes, offer.ops ?? {}, offer.expectedRevisions ?? {});
  return state.suppressions.some(
    (entry) =>
      entry.semanticKey === key &&
      entry.writeScopes.some((es) => offer.writeScopes.some((os) => rangesOverlap(es, os))),
  );
}

/**
 * Look up any revisioned entity in `SessionState` by ID.
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} id
 */
function findEntityKind(state, id) {
  if (state.beats.has(id)) return "beat";
  if (state.sounds.has(id)) return "sound";
  if (state.segments.some((s) => s.id === id)) return "segment";
  if (state.spans.some((s) => s.id === id)) return "span";
  if (state.unbeats.some((u) => u.id === id)) return "unbeat";
  return null;
}

function inAnyScope(scopes, timeSec) {
  return scopes.some((ws) => rangeContainsTime(ws, timeSec));
}

/**
 * Merge overlapping or touching `[t0, t1)` intervals into contiguous ranges.
 * @param {readonly Array<{ t0: number, t1: number }>} ranges
 * @returns {Array<{ t0: number, t1: number }>}
 */
function mergeContiguousRanges(ranges) {
  const valid = ranges
    .filter((r) => r.t1 > r.t0 + 1e-12)
    .map((r) => ({ t0: r.t0, t1: r.t1 }))
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

function findOverlappingKeepLock(keepLocks, timeSec) {
  for (const lock of keepLocks) {
    if (rangeContainsTime(lock, timeSec)) return lock;
  }
  return null;
}

function findIntersectingKeepLock(keepLocks, t0, t1) {
  const lo = Math.min(t0, t1);
  const hi = Math.max(t0, t1);
  for (const lock of keepLocks) {
    if (lo === hi) {
      if (lo > lock.t0 && lo < lock.t1) return lock;
    } else if (rangesOverlap(lock, { t0: lo, t1: hi })) {
      return lock;
    }
  }
  return null;
}

/**
 * Validate a `PatchOffer` against current `SessionState` without mutating state.
 *
 * Hard-invalidates ONLY on:
 *  1. `protected_keep`
 *  2. `version_mismatch`
 *  3. `range_changed`
 *  4. `out_of_scope`
 *  (plus terminal/sibling supersession if already accepted/rejected/superseded)
 *
 * Soft-cue disagreements (`user` beat shifted/removed beyond ±20 ms, or beat placed inside
 * an `unbeat` repulsion zone) NEVER hard-block acceptance; they keep `status: "valid"` and
 * append concise musical `advisoryNotes`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOffer} offer
 * @param {import("../index.d.ts").CompiledConstraints} [precompiledConstraints]
 * @returns {import("../index.d.ts").OfferValidationResult}
 */
export function validatePatchOffer(state, offer, precompiledConstraints) {
  const constraints = precompiledConstraints ?? compileConstraints(state);
  const baseAdvisories = Array.isArray(offer.providerAdvisories)
    ? offer.providerAdvisories.slice()
    : Array.isArray(offer.advisoryNotes)
      ? offer.advisoryNotes.slice()
      : [];

  /** @type {string[]} */
  const advisoryNotes = [];
  const pushAdvisory = (note) => {
    if (typeof note === "string" && note.length > 0 && !advisoryNotes.includes(note)) {
      advisoryNotes.push(note);
    }
  };
  for (const note of baseAdvisories) pushAdvisory(note);

  if (offer.status === "accepted") {
    return {
      valid: false,
      status: "accepted",
      conflicts: [
        {
          kind: "version_mismatch",
          message: "This suggestion has already been applied.",
        },
      ],
      advisoryNotes,
    };
  }
  if (offer.status === "rejected") {
    return {
      valid: false,
      status: "rejected",
      conflicts: [
        {
          kind: "version_mismatch",
          message: "This suggestion was dismissed.",
        },
      ],
      advisoryNotes,
    };
  }
  if (offer.status === "superseded") {
    return {
      valid: false,
      status: "superseded",
      conflicts: [
        {
          kind: "sibling_conflict",
          message: "Another option in this set was already applied.",
        },
      ],
      advisoryNotes,
    };
  }

  for (const other of state.offers.values()) {
    if (other.id !== offer.id && other.batchId === offer.batchId && other.status === "accepted") {
      return {
        valid: false,
        status: "superseded",
        conflicts: [
          {
            kind: "sibling_conflict",
            message: "Another option in this set was already applied.",
          },
        ],
        advisoryNotes,
      };
    }
  }

  /** @type {import("../index.d.ts").OfferConflict[]} */
  const conflicts = [];
  const pushConflict = (conflict) => {
    if (!conflicts.some((c) => c.kind === conflict.kind && c.message === conflict.message)) {
      conflicts.push(conflict);
    }
  };

  let scopes;
  try {
    scopes = normalizeWriteScopes(offer.writeScopes);
  } catch {
    scopes = [];
  }
  if (scopes.length === 0) {
    return {
      valid: false,
      status: "invalid",
      conflicts: [
        {
          kind: "out_of_scope",
          message: "Suggestion is missing a valid target range.",
        },
      ],
      advisoryNotes,
    };
  }

  const segById = new Map(state.segments.map((s) => [s.id, s]));
  const ops = offer.ops ?? {};
  const expectedRevisions =
    offer.expectedRevisions && typeof offer.expectedRevisions === "object"
      ? offer.expectedRevisions
      : {};

  // 1. range_changed: check referenced segment existence/bounds & post-basisSeq topology changes
  for (const ws of scopes) {
    const seg = segById.get(ws.segmentId);
    if (!seg) {
      pushConflict({
        kind: "range_changed",
        message: `Section structure near ${formatTimeCode(ws.t0)}–${formatTimeCode(ws.t1)} changed since this suggestion was prepared.`,
        t0: ws.t0,
        t1: ws.t1,
      });
      continue;
    }
    if (ws.t0 < seg.t0 - 1e-9 || ws.t1 > seg.t1 + 1e-9) {
      pushConflict({
        kind: "range_changed",
        message: `Section boundaries near ${formatTimeCode(ws.t0)}–${formatTimeCode(ws.t1)} moved since this suggestion was prepared.`,
        t0: ws.t0,
        t1: ws.t1,
      });
    }
  }

  if (Array.isArray(state.segmentMutations) && typeof offer.basisSeq === "number") {
    for (const mut of state.segmentMutations) {
      if (mut.seq <= offer.basisSeq) continue;
      for (const ws of scopes) {
        const overlapsRange =
          mut.t0 === mut.t1
            ? mut.t0 >= ws.t0 && mut.t0 <= ws.t1
            : rangesOverlap({ t0: mut.t0, t1: mut.t1 }, ws);
        const touchedSegment = Array.isArray(mut.segmentIds) && mut.segmentIds.includes(ws.segmentId);
        if (overlapsRange || touchedSegment) {
          pushConflict({
            kind: "range_changed",
            message: `Section boundaries near ${formatTimeCode(ws.t0)}–${formatTimeCode(ws.t1)} were edited after this suggestion was prepared.`,
            t0: ws.t0,
            t1: ws.t1,
          });
        }
      }
    }
  }

  // 2. out_of_scope + entity kind / duplicate checks + sequential segment simulation
  const seenBeatUpsertIds = new Set();
  for (const item of ops.upsertBeats ?? []) {
    if (!item || typeof item.id !== "string" || item.id.length === 0) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid beat entry.",
      });
      continue;
    }
    if (seenBeatUpsertIds.has(item.id)) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains conflicting duplicate timing entries.",
      });
      break;
    }
    seenBeatUpsertIds.add(item.id);
  }

  const seenSoundUpsertIds = new Set();
  for (const item of ops.upsertSounds ?? []) {
    if (!item || typeof item.id !== "string" || item.id.length === 0) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid onset entry.",
      });
      continue;
    }
    if (seenSoundUpsertIds.has(item.id) || seenBeatUpsertIds.has(item.id)) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains conflicting duplicate timing entries.",
      });
      break;
    }
    seenSoundUpsertIds.add(item.id);
  }

  const hasExpectedRev = (id) => Object.prototype.hasOwnProperty.call(expectedRevisions, id);

  for (const beat of ops.upsertBeats ?? []) {
    if (!beat || typeof beat.id !== "string") continue;
    const existing = state.beats.get(beat.id);
    if (
      typeof beat.timeSec !== "number" ||
      !Number.isFinite(beat.timeSec) ||
      !inAnyScope(scopes, beat.timeSec) ||
      (existing && !inAnyScope(scopes, existing.timeSec))
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies a beat near ${formatTimeCode(beat.timeSec)} outside its target range.`,
      });
    }
    const kind = findEntityKind(state, beat.id);
    if (kind !== null && kind !== "beat") {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies a non-beat item near ${formatTimeCode(beat.timeSec)}.`,
      });
    } else if (kind === "beat" && !hasExpectedRev(beat.id)) {
      pushConflict({
        kind: "version_mismatch",
        message: `Timing near ${formatTimeCode(beat.timeSec)} changed since this suggestion was prepared.`,
      });
    }
  }

  for (const id of ops.removeBeatIds ?? []) {
    const kind = findEntityKind(state, id);
    if (kind !== null && kind !== "beat") {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion targets a non-beat item for beat removal.",
      });
      continue;
    }
    const existing = state.beats.get(id);
    if (existing && !inAnyScope(scopes, existing.timeSec)) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion removes a beat near ${formatTimeCode(existing.timeSec)} outside its target range.`,
      });
    }
    if (!hasExpectedRev(id)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Beats in this range changed since this suggestion was prepared.",
      });
    }
  }

  for (const sound of ops.upsertSounds ?? []) {
    if (!sound || typeof sound.id !== "string") continue;
    const existing = state.sounds.get(sound.id);
    if (
      typeof sound.timeSec !== "number" ||
      !Number.isFinite(sound.timeSec) ||
      !inAnyScope(scopes, sound.timeSec) ||
      (existing && !inAnyScope(scopes, existing.timeSec))
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies an onset near ${formatTimeCode(sound.timeSec)} outside its target range.`,
      });
    }
    const kind = findEntityKind(state, sound.id);
    if (kind !== null && kind !== "sound") {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies a non-onset item near ${formatTimeCode(sound.timeSec)}.`,
      });
    } else if (kind === "sound" && !hasExpectedRev(sound.id)) {
      pushConflict({
        kind: "version_mismatch",
        message: `Onsets near ${formatTimeCode(sound.timeSec)} changed since this suggestion was prepared.`,
      });
    }
  }

  for (const id of ops.removeSoundIds ?? []) {
    const kind = findEntityKind(state, id);
    if (kind !== null && kind !== "sound") {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion targets a non-onset item for onset removal.",
      });
      continue;
    }
    const existing = state.sounds.get(id);
    if (existing && !inAnyScope(scopes, existing.timeSec)) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion removes an onset near ${formatTimeCode(existing.timeSec)} outside its target range.`,
      });
    }
    if (!hasExpectedRev(id)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Onsets in this range changed since this suggestion was prepared.",
      });
    }
  }

  // 3. Simulate segment operations in exact `applyOfferOps` order:
  //    splitSegments -> moveSegmentBoundaries -> setSegmentRegimes
  const keepLocks = constraints.keepLocks;
  const simSegments = state.segments.map((s) => ({ ...s }));
  /** @type {Array<{ segmentId: string, t0: number, t1: number }>} */
  let simScopes = [];
  for (const ws of scopes) {
    const seg = segById.get(ws.segmentId);
    if (!seg) continue;
    const t0 = Math.max(seg.t0, ws.t0);
    const t1 = Math.min(seg.t1, ws.t1);
    if (t1 > t0 + 1e-12) {
      simScopes.push({ segmentId: seg.id, t0, t1 });
    }
  }

  const simKnownIds = new Set([
    ...state.segments.map((s) => s.id),
    ...state.entityRevisions.keys(),
    ...state.beats.keys(),
    ...state.sounds.keys(),
    ...state.spans.map((s) => s.id),
    ...state.unbeats.map((u) => u.id),
  ]);
  for (const id of ops.removeBeatIds ?? []) {
    if (state.beats.has(id)) simKnownIds.delete(id);
  }
  for (const b of ops.upsertBeats ?? []) {
    if (b && typeof b.id === "string" && b.id.length > 0) simKnownIds.add(b.id);
  }
  for (const id of ops.removeSoundIds ?? []) {
    if (state.sounds.has(id)) simKnownIds.delete(id);
  }
  for (const s of ops.upsertSounds ?? []) {
    if (s && typeof s.id === "string" && s.id.length > 0) simKnownIds.add(s.id);
  }

  const createdSegIds = new Set();

  for (const split of ops.splitSegments ?? []) {
    if (!split || typeof split !== "object") {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid section split.",
      });
      continue;
    }
    if (!createdSegIds.has(split.segmentId) && !hasExpectedRev(split.segmentId)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Section state changed since this suggestion was prepared.",
      });
    }
    if (
      typeof split.newSegmentId !== "string" ||
      split.newSegmentId.length === 0 ||
      simKnownIds.has(split.newSegmentId)
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains a conflicting section identifier.",
      });
      continue;
    }

    const idx = simSegments.findIndex((s) => s.id === split.segmentId);
    if (idx < 0) {
      pushConflict({
        kind: hasExpectedRev(split.segmentId) ? "range_changed" : "out_of_scope",
        message: `Suggestion splits a section at ${formatTimeCode(split.timeSec)} outside its target range.`,
      });
      continue;
    }
    const seg = simSegments[idx];
    if (
      typeof split.timeSec !== "number" ||
      !Number.isFinite(split.timeSec) ||
      split.timeSec <= seg.t0 ||
      split.timeSec >= seg.t1
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion splits a section at ${formatTimeCode(split.timeSec)} outside its target range.`,
      });
      continue;
    }

    const matchesScope = simScopes.some(
      (ws) => ws.segmentId === split.segmentId && split.timeSec > ws.t0 && split.timeSec < ws.t1,
    );
    if (!matchesScope) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion splits a section at ${formatTimeCode(split.timeSec)} outside its target range.`,
      });
    }

    if (keepLocks.length > 0) {
      const lock = findIntersectingKeepLock(keepLocks, split.timeSec, split.timeSec);
      if (seg.kept || lock) {
        const ref = lock ?? { t0: seg.t0, t1: seg.t1 };
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(ref.t0)}–${formatTimeCode(ref.t1)} prevents splitting — unlock Keep to apply.`,
          t0: ref.t0,
          t1: ref.t1,
        });
      }
    }

    simKnownIds.add(split.newSegmentId);
    createdSegIds.add(split.newSegmentId);
    const leftSeg = { ...seg, t1: split.timeSec };
    const rightSeg = { ...seg, id: split.newSegmentId, t0: split.timeSec, t1: seg.t1 };
    simSegments.splice(idx, 1, leftSeg, rightSeg);

    /** @type {Array<{ segmentId: string, t0: number, t1: number }>} */
    const nextSimScopes = [];
    for (const ws of simScopes) {
      if (ws.segmentId !== split.segmentId) {
        nextSimScopes.push(ws);
        continue;
      }
      if (ws.t1 <= split.timeSec + 1e-12) {
        nextSimScopes.push({ ...ws, t1: Math.min(ws.t1, split.timeSec) });
      } else if (ws.t0 >= split.timeSec - 1e-12) {
        nextSimScopes.push({
          segmentId: split.newSegmentId,
          t0: Math.max(ws.t0, split.timeSec),
          t1: ws.t1,
        });
      } else {
        nextSimScopes.push(
          { segmentId: split.segmentId, t0: ws.t0, t1: split.timeSec },
          { segmentId: split.newSegmentId, t0: split.timeSec, t1: ws.t1 },
        );
      }
    }
    simScopes = nextSimScopes;
  }

  for (const move of ops.moveSegmentBoundaries ?? []) {
    if (!move || typeof move !== "object") {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid section boundary move.",
      });
      continue;
    }
    if (
      (!createdSegIds.has(move.leftSegmentId) && !hasExpectedRev(move.leftSegmentId)) ||
      (!createdSegIds.has(move.rightSegmentId) && !hasExpectedRev(move.rightSegmentId))
    ) {
      pushConflict({
        kind: "version_mismatch",
        message: "Adjacent section state changed since this suggestion was prepared.",
      });
    }

    const idx = simSegments.findIndex((s) => s.id === move.leftSegmentId);
    const isAdjacent =
      idx >= 0 &&
      idx + 1 < simSegments.length &&
      simSegments[idx + 1].id === move.rightSegmentId;
    if (!isAdjacent) {
      const priorStale = conflicts.some(
        (c) => c.kind === "range_changed" || c.kind === "version_mismatch",
      );
      pushConflict({
        kind: priorStale ? "range_changed" : "out_of_scope",
        message: `Suggestion moves a section boundary to ${formatTimeCode(move.timeSec)} outside adjacent sections.`,
      });
      continue;
    }

    const left = simSegments[idx];
    const right = simSegments[idx + 1];
    if (
      typeof move.timeSec !== "number" ||
      !Number.isFinite(move.timeSec) ||
      move.timeSec <= left.t0 ||
      move.timeSec >= right.t1
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion moves a section boundary to ${formatTimeCode(move.timeSec)} outside its target range.`,
      });
      continue;
    }

    const oldBoundary = left.t1;
    const lo = Math.min(oldBoundary, move.timeSec);
    const hi = Math.max(oldBoundary, move.timeSec);
    const leftTouchesBoundary = simScopes.some(
      (ws) => ws.segmentId === left.id && ws.t1 >= oldBoundary - 1e-9,
    );
    const rightTouchesBoundary = simScopes.some(
      (ws) => ws.segmentId === right.id && ws.t0 <= oldBoundary + 1e-9,
    );
    const mergedPairScopes = mergeContiguousRanges(
      simScopes.filter((ws) => ws.segmentId === left.id || ws.segmentId === right.id),
    );
    const sweptCovered = mergedPairScopes.some(
      (iv) =>
        iv.t0 <= lo + 1e-9 &&
        iv.t1 >= hi - 1e-9 &&
        (lo < hi || (move.timeSec > iv.t0 + 1e-9 && move.timeSec < iv.t1 - 1e-9)),
    );

    if (!leftTouchesBoundary || !rightTouchesBoundary || !sweptCovered) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion moves a section boundary to ${formatTimeCode(move.timeSec)} outside its target range.`,
      });
    }

    if (keepLocks.length > 0) {
      const lock = findIntersectingKeepLock(keepLocks, oldBoundary, move.timeSec);
      if (left.kept || right.kept || lock) {
        const ref = lock ?? (left.kept ? { t0: left.t0, t1: left.t1 } : { t0: right.t0, t1: right.t1 });
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(ref.t0)}–${formatTimeCode(ref.t1)} prevents moving section boundary — unlock Keep to apply.`,
          t0: ref.t0,
          t1: ref.t1,
        });
      }
    }

    simSegments[idx] = { ...left, t1: move.timeSec };
    simSegments[idx + 1] = { ...right, t0: move.timeSec };

    /** @type {Array<{ segmentId: string, t0: number, t1: number }>} */
    const nextSimScopes = [];
    for (const ws of simScopes) {
      if (ws.segmentId !== left.id && ws.segmentId !== right.id) {
        nextSimScopes.push(ws);
        continue;
      }
      if (ws.t1 <= move.timeSec + 1e-12) {
        nextSimScopes.push({ segmentId: left.id, t0: ws.t0, t1: Math.min(ws.t1, move.timeSec) });
      } else if (ws.t0 >= move.timeSec - 1e-12) {
        nextSimScopes.push({ segmentId: right.id, t0: Math.max(ws.t0, move.timeSec), t1: ws.t1 });
      } else {
        nextSimScopes.push(
          { segmentId: left.id, t0: ws.t0, t1: move.timeSec },
          { segmentId: right.id, t0: move.timeSec, t1: ws.t1 },
        );
      }
    }
    simScopes = nextSimScopes;
  }

  for (const reg of ops.setSegmentRegimes ?? []) {
    if (!reg || typeof reg !== "object" || !SEGMENT_REGIME_SET.has(reg.regime)) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion specifies an unsupported section mode.",
      });
      continue;
    }
    if (!createdSegIds.has(reg.segmentId) && !hasExpectedRev(reg.segmentId)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Section state changed since this suggestion was prepared.",
      });
    }
    const idx = simSegments.findIndex((s) => s.id === reg.segmentId);
    if (idx < 0) {
      pushConflict({
        kind: hasExpectedRev(reg.segmentId) ? "range_changed" : "out_of_scope",
        message: "Suggestion changes section pulse mode outside its target range.",
      });
      continue;
    }
    const seg = simSegments[idx];
    const mergedSegScopes = mergeContiguousRanges(
      simScopes.filter((ws) => ws.segmentId === seg.id),
    );
    const covered = mergedSegScopes.some(
      (iv) => iv.t0 <= seg.t0 + 1e-9 && iv.t1 >= seg.t1 - 1e-9,
    );
    if (!covered) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion changes section pulse mode outside its target range.",
      });
    }

    if (keepLocks.length > 0 && seg.regime !== reg.regime) {
      const lock = seg.kept
        ? { t0: seg.t0, t1: seg.t1 }
        : findIntersectingKeepLock(keepLocks, seg.t0, seg.t1);
      if (lock) {
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(lock.t0)}–${formatTimeCode(lock.t1)} prevents changing section mode — unlock Keep to apply.`,
          t0: lock.t0,
          t1: lock.t1,
        });
      }
    }

    simSegments[idx] = { ...seg, regime: reg.regime };
  }

  for (const [entityId, expectedRev] of Object.entries(expectedRevisions)) {
    const actualRev = state.entityRevisions.get(entityId);
    if (actualRev !== expectedRev || findEntityKind(state, entityId) === null) {
      pushConflict({
        kind: "version_mismatch",
        message: "Local edits changed this passage since the suggestion was prepared.",
      });
      break;
    }
  }

  if (typeof offer.basisSeq === "number" && Number.isInteger(offer.basisSeq)) {
    for (const beat of state.beats.values()) {
      if (!inAnyScope(scopes, beat.timeSec)) continue;
      const revSeq = parseRevisionSeq(state.entityRevisions.get(beat.id));
      if (revSeq !== null && revSeq > offer.basisSeq) {
        pushConflict({
          kind: "version_mismatch",
          message: `Beats near ${formatTimeCode(beat.timeSec)} were edited while this suggestion was running.`,
        });
        break;
      }
    }
  }

  if (Array.isArray(offer.expectedScopeBeatIds)) {
    const currentIds = getScopeBeatIds(state, scopes);
    if (
      currentIds.length !== offer.expectedScopeBeatIds.length ||
      currentIds.some((id, idx) => id !== offer.expectedScopeBeatIds[idx])
    ) {
      pushConflict({
        kind: "version_mismatch",
        message: "Beats inside this range changed since the suggestion was prepared.",
      });
    }
  }

  // 4. Simulate final beat & sound state and check `protected_keep`
  const finalBeats = new Map(state.beats);
  const touchedBeatIds = new Set();
  for (const id of ops.removeBeatIds ?? []) {
    if (state.beats.has(id)) {
      touchedBeatIds.add(id);
      finalBeats.delete(id);
    }
  }
  for (const rawBeat of ops.upsertBeats ?? []) {
    if (!rawBeat || typeof rawBeat.id !== "string" || rawBeat.id.length === 0) continue;
    try {
      touchedBeatIds.add(rawBeat.id);
      const existing = finalBeats.get(rawBeat.id);
      const nextRev = existing ? existing.revision + 1 : rawBeat.revision ?? 1;
      finalBeats.set(
        rawBeat.id,
        normalizeBeat(
          {
            ...rawBeat,
            source: rawBeat.source ?? "offer",
            revision: nextRev,
          },
          nextRev,
        ),
      );
    } catch {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid beat entry.",
      });
    }
  }

  const finalSounds = new Map(state.sounds);
  const touchedSoundIds = new Set();
  for (const id of ops.removeSoundIds ?? []) {
    if (state.sounds.has(id)) {
      touchedSoundIds.add(id);
      finalSounds.delete(id);
    }
  }
  for (const rawSound of ops.upsertSounds ?? []) {
    if (!rawSound || typeof rawSound.id !== "string" || rawSound.id.length === 0) continue;
    try {
      touchedSoundIds.add(rawSound.id);
      const existing = finalSounds.get(rawSound.id);
      const nextRev = existing ? existing.revision + 1 : rawSound.revision ?? 1;
      finalSounds.set(
        rawSound.id,
        normalizeSoundAnchor(
          {
            ...rawSound,
            source: rawSound.source ?? "offer",
            revision: nextRev,
          },
          nextRev,
        ),
      );
    } catch {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion contains an invalid onset entry.",
      });
    }
  }

  if (keepLocks.length > 0) {
    for (const id of touchedBeatIds) {
      const before = state.beats.get(id);
      const after = finalBeats.get(id);
      const lockBefore = before ? findOverlappingKeepLock(keepLocks, before.timeSec) : null;
      const lockAfter = after ? findOverlappingKeepLock(keepLocks, after.timeSec) : null;
      const lock = lockBefore ?? lockAfter;
      if (!lock) continue;

      const unchanged =
        before !== undefined &&
        after !== undefined &&
        canonicalJson(stripRevision(before)) === canonicalJson(stripRevision(after));
      if (!unchanged) {
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(lock.t0)}–${formatTimeCode(lock.t1)} prevents changes — unlock Keep to apply.`,
          t0: lock.t0,
          t1: lock.t1,
        });
      }
    }

    for (const id of touchedSoundIds) {
      const before = state.sounds.get(id);
      const after = finalSounds.get(id);
      const lockBefore = before ? findOverlappingKeepLock(keepLocks, before.timeSec) : null;
      const lockAfter = after ? findOverlappingKeepLock(keepLocks, after.timeSec) : null;
      const lock = lockBefore ?? lockAfter;
      if (!lock) continue;

      const unchanged =
        before !== undefined &&
        after !== undefined &&
        canonicalJson(stripRevision(before)) === canonicalJson(stripRevision(after));
      if (!unchanged) {
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(lock.t0)}–${formatTimeCode(lock.t1)} prevents changes — unlock Keep to apply.`,
          t0: lock.t0,
          t1: lock.t1,
        });
      }
    }
  }

  // 5. Soft-cue disagreement advisories (NEVER block acceptance!)
  // 5a. User beat shifted/removed beyond ±20 ms
  for (const guide of constraints.beatGuides) {
    if (!touchedBeatIds.has(guide.beatId)) continue;
    const after = finalBeats.get(guide.beatId);
    if (after) {
      const diffSec = after.timeSec - guide.timeSec;
      if (Math.abs(diffSec) > DEFAULT_BEAT_GUIDE_TOL_SEC) {
        const deltaMs = Math.round(diffSec * 1000);
        const sign = deltaMs >= 0 ? "+" : "";
        pushAdvisory(`Moves your beat near ${formatTimeCode(guide.timeSec)} by ${sign}${deltaMs} ms`);
      }
      continue;
    }

    // Guide beat ID was removed — check if a replacement beat lands within ±20 ms or nearby
    let withinTol = false;
    /** @type {{ timeSec: number, absDiff: number } | null} */
    let nearestNew = null;
    for (const b of finalBeats.values()) {
      const absDiff = Math.abs(b.timeSec - guide.timeSec);
      if (absDiff <= DEFAULT_BEAT_GUIDE_TOL_SEC) {
        withinTol = true;
        break;
      }
    }
    if (!withinTol) {
      for (const rawBeat of ops.upsertBeats ?? []) {
        const absDiff = Math.abs(rawBeat.timeSec - guide.timeSec);
        if (absDiff <= 0.15 && (!nearestNew || absDiff < nearestNew.absDiff)) {
          nearestNew = { timeSec: rawBeat.timeSec, absDiff };
        }
      }
      if (nearestNew) {
        const deltaMs = Math.round((nearestNew.timeSec - guide.timeSec) * 1000);
        const sign = deltaMs >= 0 ? "+" : "";
        pushAdvisory(`Moves your beat near ${formatTimeCode(guide.timeSec)} by ${sign}${deltaMs} ms`);
      } else {
        pushAdvisory(`Removes your beat near ${formatTimeCode(guide.timeSec)}`);
      }
    }
  }

  // 5b. User SoundAnchor shifted/removed beyond ±20 ms
  for (const obs of constraints.soundObservations) {
    if (!touchedSoundIds.has(obs.soundId)) continue;
    const after = finalSounds.get(obs.soundId);
    if (after) {
      const diffSec = after.timeSec - obs.timeSec;
      if (Math.abs(diffSec) > DEFAULT_BEAT_GUIDE_TOL_SEC) {
        const deltaMs = Math.round(diffSec * 1000);
        const sign = deltaMs >= 0 ? "+" : "";
        pushAdvisory(
          `Adjusts your sound marker near ${formatTimeCode(obs.timeSec)} by ${sign}${deltaMs} ms`,
        );
      }
      continue;
    }

    let withinTol = false;
    /** @type {{ timeSec: number, absDiff: number } | null} */
    let nearestNew = null;
    for (const s of finalSounds.values()) {
      const absDiff = Math.abs(s.timeSec - obs.timeSec);
      if (absDiff <= DEFAULT_BEAT_GUIDE_TOL_SEC) {
        withinTol = true;
        break;
      }
    }
    if (!withinTol) {
      for (const rawSound of ops.upsertSounds ?? []) {
        const absDiff = Math.abs(rawSound.timeSec - obs.timeSec);
        if (absDiff <= 0.15 && (!nearestNew || absDiff < nearestNew.absDiff)) {
          nearestNew = { timeSec: rawSound.timeSec, absDiff };
        }
      }
      if (nearestNew) {
        const deltaMs = Math.round((nearestNew.timeSec - obs.timeSec) * 1000);
        const sign = deltaMs >= 0 ? "+" : "";
        pushAdvisory(
          `Adjusts your sound marker near ${formatTimeCode(obs.timeSec)} by ${sign}${deltaMs} ms`,
        );
      } else {
        pushAdvisory(`Removes your sound marker near ${formatTimeCode(obs.timeSec)}`);
      }
    }
  }

  // 5c. Beat placed inside an UnbeatCue repulsion zone
  for (const rep of constraints.unbeatRepulsions) {
    let placesNearUnbeat = false;
    for (const rawBeat of ops.upsertBeats ?? []) {
      const after = finalBeats.get(rawBeat.id);
      if (!after) continue;
      const before = state.beats.get(rawBeat.id);
      if (before && before.timeSec === after.timeSec) continue;
      if (Math.abs(after.timeSec - rep.timeSec) <= rep.repulsionRadiusSec) {
        placesNearUnbeat = true;
        break;
      }
    }
    if (placesNearUnbeat) {
      pushAdvisory(`Places a beat near your muted tick at ${formatTimeCode(rep.timeSec)}`);
    }
  }

  if (conflicts.length === 0) {
    const storedOffer = state?.offers instanceof Map ? state.offers.get(offer.id) : undefined;
    if (offer.status === "stale" || storedOffer?.status === "stale") {
      const staleConflicts =
        Array.isArray(offer.conflicts) && offer.conflicts.length > 0
          ? offer.conflicts.slice()
          : Array.isArray(storedOffer?.conflicts) && storedOffer.conflicts.length > 0
            ? storedOffer.conflicts.slice()
            : [
                {
                  kind: "version_mismatch",
                  message: "Superseded by a newer suggestion.",
                },
              ];
      return {
        valid: false,
        status: "stale",
        conflicts: staleConflicts,
        advisoryNotes,
      };
    }
    return {
      valid: true,
      status: "valid",
      conflicts: [],
      advisoryNotes,
    };
  }

  const hasHardInvalid = conflicts.some(
    (c) => c.kind === "protected_keep" || c.kind === "out_of_scope",
  );
  return {
    valid: false,
    status: hasHardInvalid ? "invalid" : "stale",
    conflicts,
    advisoryNotes,
  };
}
