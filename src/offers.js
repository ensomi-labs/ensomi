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
} from "./types.js";

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
      if (a.batchId !== b.batchId) continue;
      const overlaps = a.writeScopes.some((wa) =>
        b.writeScopes.some((wb) => rangesOverlap(wa, wb)),
      );
      if (!overlaps) continue;
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

  // 2. out_of_scope (plus duplicate entity IDs in upsert arrays)
  for (const list of [ops.upsertBeats ?? [], ops.upsertSounds ?? []]) {
    const seen = new Set();
    for (const item of list) {
      if (!item || typeof item.id !== "string") continue;
      if (seen.has(item.id)) {
        pushConflict({
          kind: "out_of_scope",
          message: "Suggestion contains conflicting duplicate timing entries.",
        });
        break;
      }
      seen.add(item.id);
    }
  }

  for (const beat of ops.upsertBeats ?? []) {
    const existing = state.beats.get(beat.id);
    if (!inAnyScope(scopes, beat.timeSec) || (existing && !inAnyScope(scopes, existing.timeSec))) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies a beat near ${formatTimeCode(beat.timeSec)} outside its target range.`,
      });
    }
  }
  for (const id of ops.removeBeatIds ?? []) {
    const existing = state.beats.get(id);
    if (existing && !inAnyScope(scopes, existing.timeSec)) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion removes a beat near ${formatTimeCode(existing.timeSec)} outside its target range.`,
      });
    }
  }
  for (const sound of ops.upsertSounds ?? []) {
    const existing = state.sounds.get(sound.id);
    if (!inAnyScope(scopes, sound.timeSec) || (existing && !inAnyScope(scopes, existing.timeSec))) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion modifies an onset near ${formatTimeCode(sound.timeSec)} outside its target range.`,
      });
    }
  }
  for (const id of ops.removeSoundIds ?? []) {
    const existing = state.sounds.get(id);
    if (existing && !inAnyScope(scopes, existing.timeSec)) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion removes an onset near ${formatTimeCode(existing.timeSec)} outside its target range.`,
      });
    }
  }
  for (const split of ops.splitSegments ?? []) {
    const matchesScope = scopes.some(
      (ws) => ws.segmentId === split.segmentId && split.timeSec > ws.t0 && split.timeSec < ws.t1,
    );
    if (!matchesScope) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion splits a section at ${formatTimeCode(split.timeSec)} outside its target range.`,
      });
    }
  }
  for (const move of ops.moveSegmentBoundaries ?? []) {
    const left = segById.get(move.leftSegmentId);
    const leftCovered = scopes.some((ws) => ws.segmentId === move.leftSegmentId);
    const rightCovered = scopes.some((ws) => ws.segmentId === move.rightSegmentId);
    const minScopeT0 = Math.min(...scopes.map((ws) => ws.t0));
    const maxScopeT1 = Math.max(...scopes.map((ws) => ws.t1));
    if (
      !leftCovered ||
      !rightCovered ||
      move.timeSec <= minScopeT0 ||
      move.timeSec >= maxScopeT1 ||
      (left && (left.t1 < minScopeT0 || left.t1 > maxScopeT1))
    ) {
      pushConflict({
        kind: "out_of_scope",
        message: `Suggestion moves a section boundary to ${formatTimeCode(move.timeSec)} outside its target range.`,
      });
    }
  }
  for (const reg of ops.setSegmentRegimes ?? []) {
    const seg = segById.get(reg.segmentId);
    const covered =
      seg &&
      scopes.some(
        (ws) => ws.segmentId === reg.segmentId && ws.t0 <= seg.t0 + 1e-9 && ws.t1 >= seg.t1 - 1e-9,
      );
    if (!covered) {
      pushConflict({
        kind: "out_of_scope",
        message: "Suggestion changes section pulse mode outside its target range.",
      });
    }
  }

  // 3. version_mismatch: expectedRevisions vs state.entityRevisions + required preconditions + post-basisSeq beat edits
  const hasExpectedRev = (id) => Object.prototype.hasOwnProperty.call(expectedRevisions, id);

  for (const beat of ops.upsertBeats ?? []) {
    const kind = findEntityKind(state, beat.id);
    if (!kind) continue;
    if (kind !== "beat" || !hasExpectedRev(beat.id)) {
      pushConflict({
        kind: "version_mismatch",
        message: `Timing near ${formatTimeCode(beat.timeSec)} changed since this suggestion was prepared.`,
      });
    }
  }
  for (const id of ops.removeBeatIds ?? []) {
    if (!hasExpectedRev(id)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Beats in this range changed since this suggestion was prepared.",
      });
    }
  }
  for (const sound of ops.upsertSounds ?? []) {
    const kind = findEntityKind(state, sound.id);
    if (!kind) continue;
    if (kind !== "sound" || !hasExpectedRev(sound.id)) {
      pushConflict({
        kind: "version_mismatch",
        message: `Onsets near ${formatTimeCode(sound.timeSec)} changed since this suggestion was prepared.`,
      });
    }
  }
  for (const id of ops.removeSoundIds ?? []) {
    if (!hasExpectedRev(id)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Onsets in this range changed since this suggestion was prepared.",
      });
    }
  }
  for (const split of ops.splitSegments ?? []) {
    if (!hasExpectedRev(split.segmentId)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Section state changed since this suggestion was prepared.",
      });
    }
  }
  for (const move of ops.moveSegmentBoundaries ?? []) {
    if (!hasExpectedRev(move.leftSegmentId) || !hasExpectedRev(move.rightSegmentId)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Adjacent section state changed since this suggestion was prepared.",
      });
    }
  }
  for (const reg of ops.setSegmentRegimes ?? []) {
    if (!hasExpectedRev(reg.segmentId)) {
      pushConflict({
        kind: "version_mismatch",
        message: "Section state changed since this suggestion was prepared.",
      });
    }
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

  // 4. Simulate final beat state & check `protected_keep`
  const finalBeats = new Map(state.beats);
  const touchedBeatIds = new Set();
  for (const id of ops.removeBeatIds ?? []) {
    touchedBeatIds.add(id);
    finalBeats.delete(id);
  }
  for (const rawBeat of ops.upsertBeats ?? []) {
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
  }

  const keepLocks = constraints.keepLocks;
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

    for (const split of ops.splitSegments ?? []) {
      const lock = findIntersectingKeepLock(keepLocks, split.timeSec, split.timeSec);
      const seg = segById.get(split.segmentId);
      if (seg?.kept || lock) {
        const ref = lock ?? (seg ? { t0: seg.t0, t1: seg.t1 } : { t0: split.timeSec, t1: split.timeSec });
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(ref.t0)}–${formatTimeCode(ref.t1)} prevents splitting — unlock Keep to apply.`,
          t0: ref.t0,
          t1: ref.t1,
        });
      }
    }

    for (const move of ops.moveSegmentBoundaries ?? []) {
      const left = segById.get(move.leftSegmentId);
      const right = segById.get(move.rightSegmentId);
      const oldT = left ? left.t1 : move.timeSec;
      const lock = findIntersectingKeepLock(keepLocks, oldT, move.timeSec);
      if (left?.kept || right?.kept || lock) {
        const ref =
          lock ??
          (left?.kept
            ? { t0: left.t0, t1: left.t1 }
            : right
              ? { t0: right.t0, t1: right.t1 }
              : { t0: oldT, t1: move.timeSec });
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(ref.t0)}–${formatTimeCode(ref.t1)} prevents moving section boundary — unlock Keep to apply.`,
          t0: ref.t0,
          t1: ref.t1,
        });
      }
    }

    for (const reg of ops.setSegmentRegimes ?? []) {
      const seg = segById.get(reg.segmentId);
      if (!seg || seg.regime === reg.regime) continue;
      const lock = seg.kept ? { t0: seg.t0, t1: seg.t1 } : findIntersectingKeepLock(keepLocks, seg.t0, seg.t1);
      if (lock) {
        pushConflict({
          kind: "protected_keep",
          message: `Locked Keep passage at ${formatTimeCode(lock.t0)}–${formatTimeCode(lock.t1)} prevents changing section mode — unlock Keep to apply.`,
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

  // 5b. Beat placed inside an UnbeatCue repulsion zone
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
