/**
 * Orthogonal PatchOffer semantics:
 *  - Deterministic semantic keys independent of freshly generated ephemeral IDs
 *  - Conflict-first validation (version_mismatch, range_changed, constraint_violation, sibling_conflict)
 *  - Rejection suppression checks and range entity membership helpers
 */

import { canonicalJson, roundTime, sha256Hex } from "./hash.js";
import { compileConstraints, DEFAULT_POINT_TOL_SEC } from "./constraints.js";
import {
  assertSpan,
  normalizeBeat,
  normalizeBoundary,
  normalizeSoundAnchor,
  spanContainsTime,
  spansOverlap,
} from "./types.js";

/**
 * Strip version field from an entity for full-content equality checks.
 * @param {Record<string, unknown>} entity
 */
function stripVersion(entity) {
  const { version: _ignored, ...rest } = entity;
  return rest;
}

/**
 * Parse the originating trace seq from an entity revision token (`"${seq}:${eventId}"`).
 * @param {string | undefined} rev
 * @returns {number | null}
 */
function parseRevSeq(rev) {
  if (typeof rev !== "string") return null;
  const colonIdx = rev.indexOf(":");
  if (colonIdx <= 0) return null;
  const seq = Number(rev.slice(0, colonIdx));
  return Number.isInteger(seq) && seq >= 0 ? seq : null;
}

/**
 * Compute a deterministic canonical semantic key for a PatchOffer's writeScope and ops.
 * Preserves stable IDs for existing/updated entities (referenced by expectedVersions,
 * expectedRevisions, remove*Ids, or expectedRangeEntityIds) while stripping freshly
 * minted ephemeral IDs and versions from newly inserted entities.
 *
 * @param {{ t0: number, t1: number }} writeScope
 * @param {import("../index.d.ts").PatchOp} ops
 * @returns {string}
 */
export function computeSemanticKey(writeScope, ops = {}) {
  const scope = assertSpan(writeScope);
  const stableIds = new Set([
    ...Object.keys(ops.expectedVersions ?? {}),
    ...Object.keys(ops.expectedRevisions ?? {}),
    ...(ops.removeBeatIds ?? []),
    ...(ops.removeBoundaryIds ?? []),
    ...(ops.removeSoundIds ?? []),
    ...(ops.expectedRangeEntityIds ?? []),
  ]);

  const canonicalBeats = (ops.upsertBeats ?? [])
    .map((b) => ({
      id: typeof b.id === "string" && stableIds.has(b.id) ? b.id : null,
      t: roundTime(b.t, 4),
      source: b.source ?? "model",
    }))
    .sort(
      (a, b) =>
        a.t - b.t ||
        (a.id ?? "").localeCompare(b.id ?? "") ||
        a.source.localeCompare(b.source),
    );

  const canonicalBoundaries = (ops.upsertBoundaries ?? [])
    .map((b) => ({
      id: typeof b.id === "string" && stableIds.has(b.id) ? b.id : null,
      t: roundTime(b.t, 4),
      kind: b.kind,
      bpm: b.bpm !== undefined ? roundTime(b.bpm, 4) : null,
      source: b.source ?? "model",
    }))
    .sort(
      (a, b) =>
        a.t - b.t ||
        (a.id ?? "").localeCompare(b.id ?? "") ||
        a.kind.localeCompare(b.kind) ||
        (a.bpm ?? -1) - (b.bpm ?? -1) ||
        a.source.localeCompare(b.source),
    );

  const canonicalSounds = (ops.upsertSounds ?? [])
    .map((s) => ({
      id: typeof s.id === "string" && stableIds.has(s.id) ? s.id : null,
      t: roundTime(s.t, 4),
      emphasis: roundTime(s.emphasis, 4),
      source: s.source ?? "model",
    }))
    .sort(
      (a, b) =>
        a.t - b.t ||
        (a.id ?? "").localeCompare(b.id ?? "") ||
        a.emphasis - b.emphasis ||
        a.source.localeCompare(b.source),
    );

  const canonical = {
    v: 2,
    writeScope: { t0: roundTime(scope.t0, 4), t1: roundTime(scope.t1, 4) },
    upsertBeats: canonicalBeats,
    removeBeatIds: [...new Set(ops.removeBeatIds ?? [])].sort(),
    upsertBoundaries: canonicalBoundaries,
    removeBoundaryIds: [...new Set(ops.removeBoundaryIds ?? [])].sort(),
    upsertSounds: canonicalSounds,
    removeSoundIds: [...new Set(ops.removeSoundIds ?? [])].sort(),
  };

  return `sk:${sha256Hex(canonicalJson(canonical)).slice(0, 32)}`;
}

/**
 * Collect sorted IDs of entities currently inside a half-open span [t0, t1).
 * By default includes beats and boundaries; pass `{ includeSounds: true }` to include SoundAnchors,
 * or `{ kinds: ("beat" | "boundary" | "sound")[] }` to select entity kinds explicitly.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {{ t0: number, t1: number }} span
 * @param {{ includeSounds?: boolean, kinds?: Array<"beat" | "boundary" | "sound"> }} [options]
 * @returns {string[]}
 */
export function getRangeEntityIds(state, span, options = {}) {
  const s = assertSpan(span);
  const hasKinds = Array.isArray(options.kinds);
  const kindSet = hasKinds ? new Set(options.kinds) : null;
  const includeBeats = hasKinds ? kindSet.has("beat") : true;
  const includeBoundaries = hasKinds ? kindSet.has("boundary") : true;
  const includeSounds = hasKinds ? kindSet.has("sound") : Boolean(options.includeSounds);

  /** @type {string[]} */
  const ids = [];
  if (includeBeats) {
    for (const beat of state.beats.values()) {
      if (spanContainsTime(s, beat.t)) ids.push(beat.id);
    }
  }
  if (includeBoundaries) {
    for (const boundary of state.boundaries.values()) {
      if (spanContainsTime(s, boundary.t)) ids.push(boundary.id);
    }
  }
  if (includeSounds) {
    for (const sound of state.sounds.values()) {
      if (spanContainsTime(s, sound.t)) ids.push(sound.id);
    }
  }
  ids.sort();
  return ids;
}

/**
 * Look up any entity (beat, boundary, or sound) by ID in state.
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} id
 */
function findEntity(state, id) {
  if (state.beats.has(id)) return { kind: "beat", entity: state.beats.get(id) };
  if (state.boundaries.has(id)) return { kind: "boundary", entity: state.boundaries.get(id) };
  if (state.sounds.has(id)) return { kind: "sound", entity: state.sounds.get(id) };
  return null;
}

/**
 * Compute summary consequences of applying a PatchOp to the current state.
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOp} ops
 */
export function computeConsequences(state, ops = {}) {
  let beatsAdded = 0;
  let beatsMoved = 0;
  const removedSet = new Set(ops.removeBeatIds ?? []);
  let beatsRemoved = 0;
  for (const id of removedSet) {
    if (state.beats.has(id)) beatsRemoved += 1;
  }
  for (const beat of ops.upsertBeats ?? []) {
    const existing = state.beats.get(beat.id);
    if (!existing) {
      beatsAdded += 1;
    } else if (existing.t !== beat.t) {
      beatsMoved += 1;
    }
  }
  return { beatsAdded, beatsMoved, beatsRemoved };
}

/**
 * Detect overlapping sibling offers within a single batchId.
 * Returns a Map from offerId -> informational sibling_conflict records.
 *
 * @param {import("../index.d.ts").PatchOffer[]} offers
 * @returns {Map<string, import("../index.d.ts").Conflict[]>}
 */
export function detectSiblingConflicts(offers) {
  /** @type {Map<string, import("../index.d.ts").Conflict[]>} */
  const map = new Map();
  for (let i = 0; i < offers.length; i += 1) {
    const a = offers[i];
    for (let j = i + 1; j < offers.length; j += 1) {
      const b = offers[j];
      if (a.batchId !== b.batchId) continue;
      if (!spansOverlap(a.writeScope, b.writeScope)) continue;

      const conflictForA = {
        id: `conflict:sibling:${a.id}:${b.id}`,
        kind: /** @type {const} */ ("sibling_conflict"),
        message: `Mutually conflicting sibling option with offer "${b.id}" in batch "${a.batchId}"`,
        entityIds: [b.id],
        span: {
          t0: Math.max(a.writeScope.t0, b.writeScope.t0),
          t1: Math.min(a.writeScope.t1, b.writeScope.t1),
        },
      };
      const conflictForB = {
        id: `conflict:sibling:${b.id}:${a.id}`,
        kind: /** @type {const} */ ("sibling_conflict"),
        message: `Mutually conflicting sibling option with offer "${a.id}" in batch "${a.batchId}"`,
        entityIds: [a.id],
        span: {
          t0: Math.max(a.writeScope.t0, b.writeScope.t0),
          t1: Math.min(a.writeScope.t1, b.writeScope.t1),
        },
      };

      if (!map.has(a.id)) map.set(a.id, []);
      if (!map.has(b.id)) map.set(b.id, []);
      map.get(a.id).push(conflictForA);
      map.get(b.id).push(conflictForB);
    }
  }
  return map;
}

/**
 * Check whether an offer is currently suppressed by a prior rejection whose
 * writeScope has not yet been invalidated by an overlapping edit or statement.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {{ semanticKey?: string, writeScope: { t0: number, t1: number }, ops?: import("../index.d.ts").PatchOp }} offer
 * @returns {boolean}
 */
export function isOfferSuppressed(state, offer) {
  if (!state || !state.suppressions || state.suppressions.length === 0) return false;
  const key = offer.semanticKey || computeSemanticKey(offer.writeScope, offer.ops ?? {});
  return state.suppressions.some(
    (entry) => entry.semanticKey === key && spansOverlap(entry.writeScope, offer.writeScope),
  );
}

/**
 * Validate a PatchOffer against current state without mutating state.
 * Checks:
 *  1. Offer status & sibling_conflict (non-pending status or accepted sibling in batchId)
 *  2. Duplicate entity IDs within PatchOp upsert arrays
 *  3. Write-scope containment for every old and new entity position
 *  4. Complete mutation preconditions, version_mismatch (expectedVersions & basisSeq), and expectedRevisions
 *  5. range_changed (against ops.expectedRangeEntityIds in writeScope with explicit kind selection)
 *  6. constraint_violation (against active freeze/forbid/clamp constraints on the simulated final state)
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOffer} offer
 * @returns {import("../index.d.ts").Conflict[]}
 */
export function validatePatchOffer(state, offer) {
  /** @type {import("../index.d.ts").Conflict[]} */
  const conflicts = [];
  const ops = offer.ops ?? {};
  const writeScope = assertSpan(offer.writeScope);

  // 1. Offer status & sibling acceptance / supersession check
  if (offer.status === "superseded") {
    conflicts.push({
      id: `conflict:sibling:${offer.id}`,
      kind: "sibling_conflict",
      message: `Offer "${offer.id}" was superseded by an accepted sibling in batch "${offer.batchId}"`,
      span: { ...writeScope },
    });
  } else {
    if (offer.status !== undefined && offer.status !== "pending") {
      conflicts.push({
        id: `conflict:status:${offer.id}`,
        kind: "version_mismatch",
        message: `Offer "${offer.id}" has status "${offer.status}" and is no longer pending`,
        span: { ...writeScope },
      });
    }
    for (const other of state.offers.values()) {
      if (other.id !== offer.id && other.batchId === offer.batchId && other.status === "accepted") {
        conflicts.push({
          id: `conflict:sibling:${offer.id}:${other.id}`,
          kind: "sibling_conflict",
          message: `Sibling offer "${other.id}" in batch "${offer.batchId}" was already accepted`,
          entityIds: [other.id],
          span: { ...writeScope },
        });
      }
    }
  }

  // 2. Duplicate entity IDs within PatchOp upsert arrays
  /** @type {string[]} */
  const duplicateUpsertIds = [];
  for (const list of [ops.upsertBeats ?? [], ops.upsertBoundaries ?? [], ops.upsertSounds ?? []]) {
    const seen = new Set();
    for (const item of list) {
      if (!item || typeof item.id !== "string") continue;
      if (seen.has(item.id)) {
        duplicateUpsertIds.push(item.id);
      } else {
        seen.add(item.id);
      }
    }
  }
  if (duplicateUpsertIds.length > 0) {
    const uniqueDupes = [...new Set(duplicateUpsertIds)].sort();
    conflicts.push({
      id: `conflict:duplicate_upsert:${offer.id}`,
      kind: "constraint_violation",
      message: `PatchOp contains duplicate entity ID(s) in upsert array: [${uniqueDupes.join(", ")}]`,
      entityIds: uniqueDupes,
      span: { ...writeScope },
    });
  }

  // 3. Write-scope containment check for every old and new position
  /** @type {string[]} */
  const outOfScopeIds = [];
  for (const beat of ops.upsertBeats ?? []) {
    const existing = findEntity(state, beat.id);
    if (!spanContainsTime(writeScope, beat.t) || (existing && !spanContainsTime(writeScope, existing.entity.t))) {
      outOfScopeIds.push(beat.id);
    }
  }
  for (const id of ops.removeBeatIds ?? []) {
    const existing = state.beats.get(id);
    if (existing && !spanContainsTime(writeScope, existing.t)) {
      outOfScopeIds.push(id);
    }
  }
  for (const boundary of ops.upsertBoundaries ?? []) {
    const existing = findEntity(state, boundary.id);
    if (
      !spanContainsTime(writeScope, boundary.t) ||
      (existing && !spanContainsTime(writeScope, existing.entity.t))
    ) {
      outOfScopeIds.push(boundary.id);
    }
  }
  for (const id of ops.removeBoundaryIds ?? []) {
    const existing = state.boundaries.get(id);
    if (existing && !spanContainsTime(writeScope, existing.t)) {
      outOfScopeIds.push(id);
    }
  }
  for (const sound of ops.upsertSounds ?? []) {
    const existing = findEntity(state, sound.id);
    if (
      !spanContainsTime(writeScope, sound.t) ||
      (existing && !spanContainsTime(writeScope, existing.entity.t))
    ) {
      outOfScopeIds.push(sound.id);
    }
  }
  for (const id of ops.removeSoundIds ?? []) {
    const existing = state.sounds.get(id);
    if (existing && !spanContainsTime(writeScope, existing.t)) {
      outOfScopeIds.push(id);
    }
  }
  if (outOfScopeIds.length > 0) {
    const uniqueOutOfScope = [...new Set(outOfScopeIds)].sort();
    conflicts.push({
      id: `conflict:scope:${offer.id}`,
      kind: "constraint_violation",
      message: `Offer touches entity(ies) outside writeScope [${writeScope.t0}, ${writeScope.t1}): [${uniqueOutOfScope.join(", ")}]`,
      entityIds: uniqueOutOfScope,
      span: { ...writeScope },
    });
  }

  // 4. Version, revision, and explicit update/remove precondition checks
  const expectedVersions =
    ops.expectedVersions && typeof ops.expectedVersions === "object" ? ops.expectedVersions : {};
  const expectedRevisions =
    ops.expectedRevisions && typeof ops.expectedRevisions === "object" ? ops.expectedRevisions : {};
  const hasPrecondition = (id) =>
    Object.prototype.hasOwnProperty.call(expectedVersions, id) ||
    Object.prototype.hasOwnProperty.call(expectedRevisions, id);

  const versionConflictEntityIds = new Set();
  const pushVersionConflict = (entityId, message) => {
    if (versionConflictEntityIds.has(entityId)) return;
    versionConflictEntityIds.add(entityId);
    conflicts.push({
      id: `conflict:version:${offer.id}:${entityId}`,
      kind: "version_mismatch",
      message,
      entityIds: [entityId],
      span: { ...writeScope },
    });
  };

  // Every upsert targeting an existing entity ID and every remove MUST declare an explicit precondition.
  for (const [targetKind, list] of [
    ["beat", ops.upsertBeats ?? []],
    ["boundary", ops.upsertBoundaries ?? []],
    ["sound", ops.upsertSounds ?? []],
  ]) {
    for (const item of list) {
      const existing = findEntity(state, item.id);
      if (!existing) continue;
      if (existing.kind !== targetKind) {
        pushVersionConflict(
          item.id,
          `Entity "${item.id}" already exists as a ${existing.kind} and cannot be overwritten as a ${targetKind}`,
        );
      } else if (!hasPrecondition(item.id)) {
        pushVersionConflict(
          item.id,
          `Existing ${targetKind} "${item.id}" cannot be modified without an explicit expectedVersions or expectedRevisions precondition`,
        );
      }
    }
  }
  for (const [targetKind, ids] of [
    ["beat", ops.removeBeatIds ?? []],
    ["boundary", ops.removeBoundaryIds ?? []],
    ["sound", ops.removeSoundIds ?? []],
  ]) {
    for (const id of ids) {
      if (!hasPrecondition(id)) {
        pushVersionConflict(
          id,
          `Removing ${targetKind} "${id}" requires an explicit expectedVersions or expectedRevisions precondition`,
        );
      }
    }
  }

  const revMap = state.entityRevisions instanceof Map ? state.entityRevisions : null;

  for (const [entityId, expectedVersion] of Object.entries(expectedVersions)) {
    const found = findEntity(state, entityId);
    if (!found) {
      pushVersionConflict(
        entityId,
        `Expected entity "${entityId}" at version ${expectedVersion}, but it no longer exists`,
      );
    } else if (found.entity.version !== expectedVersion) {
      pushVersionConflict(
        entityId,
        `Entity "${entityId}" version mismatch: expected ${expectedVersion}, found ${found.entity.version}`,
      );
    } else if (revMap && typeof offer.basisSeq === "number" && Number.isInteger(offer.basisSeq)) {
      const actualRev = revMap.get(entityId);
      const revSeq = parseRevSeq(actualRev);
      if (revSeq !== null && revSeq > offer.basisSeq) {
        pushVersionConflict(
          entityId,
          `Entity "${entityId}" was modified at seq ${revSeq} after offer basisSeq ${offer.basisSeq}`,
        );
      }
    }
  }

  for (const [entityId, expectedRev] of Object.entries(expectedRevisions)) {
    const found = findEntity(state, entityId);
    if (!found) {
      pushVersionConflict(
        entityId,
        `Expected entity "${entityId}" at revision "${expectedRev}", but it no longer exists`,
      );
    } else if (revMap) {
      const actualRev = revMap.get(entityId);
      if (actualRev !== expectedRev) {
        pushVersionConflict(
          entityId,
          `Entity "${entityId}" revision mismatch: expected "${expectedRev}", found "${actualRev ?? "unknown"}"`,
        );
      }
    }
  }

  // 5. Range membership safety check
  if (Array.isArray(ops.expectedRangeEntityIds)) {
    let rangeOptions;
    if (Array.isArray(ops.expectedRangeKinds)) {
      rangeOptions = { kinds: ops.expectedRangeKinds };
    } else if (typeof ops.includeSounds === "boolean") {
      rangeOptions = { includeSounds: ops.includeSounds };
    } else {
      const includeSounds =
        (ops.upsertSounds && ops.upsertSounds.length > 0) ||
        (ops.removeSoundIds && ops.removeSoundIds.length > 0) ||
        ops.expectedRangeEntityIds.some((id) => state.sounds.has(id));
      rangeOptions = { includeSounds };
    }

    const currentIds = new Set(getRangeEntityIds(state, writeScope, rangeOptions));
    const expectedIds = new Set(ops.expectedRangeEntityIds);

    const added = [...currentIds].filter((id) => !expectedIds.has(id)).sort();
    const missing = [...expectedIds].filter((id) => !currentIds.has(id)).sort();

    if (added.length > 0 || missing.length > 0) {
      const changedIds = [...added, ...missing];
      conflicts.push({
        id: `conflict:range:${offer.id}`,
        kind: "range_changed",
        message: `Entities inside writeScope [${writeScope.t0}, ${writeScope.t1}) changed since offer generation (added: [${added.join(", ")}], removed: [${missing.join(", ")}])`,
        entityIds: changedIds,
        span: { ...writeScope },
      });
    }
  }

  // 6. Simulate final normalized entity state (matching offer_accept last-write-wins semantics)
  const finalBeats = new Map(state.beats);
  const touchedBeatIds = new Set();
  for (const id of ops.removeBeatIds ?? []) {
    touchedBeatIds.add(id);
    finalBeats.delete(id);
  }
  for (const rawBeat of ops.upsertBeats ?? []) {
    touchedBeatIds.add(rawBeat.id);
    const existing = finalBeats.get(rawBeat.id);
    const nextVersion = existing ? existing.version + 1 : rawBeat.version ?? 1;
    finalBeats.set(rawBeat.id, normalizeBeat({ ...rawBeat, version: nextVersion }, nextVersion));
  }

  const finalBoundaries = new Map(state.boundaries);
  const touchedBoundaryIds = new Set();
  for (const id of ops.removeBoundaryIds ?? []) {
    touchedBoundaryIds.add(id);
    finalBoundaries.delete(id);
  }
  for (const rawBoundary of ops.upsertBoundaries ?? []) {
    touchedBoundaryIds.add(rawBoundary.id);
    const existing = finalBoundaries.get(rawBoundary.id);
    const nextVersion = existing ? existing.version + 1 : rawBoundary.version ?? 1;
    finalBoundaries.set(
      rawBoundary.id,
      normalizeBoundary({ ...rawBoundary, version: nextVersion }, nextVersion),
    );
  }

  const finalSounds = new Map(state.sounds);
  const touchedSoundIds = new Set();
  for (const id of ops.removeSoundIds ?? []) {
    touchedSoundIds.add(id);
    finalSounds.delete(id);
  }
  for (const rawSound of ops.upsertSounds ?? []) {
    touchedSoundIds.add(rawSound.id);
    const existing = finalSounds.get(rawSound.id);
    const nextVersion = existing ? existing.version + 1 : rawSound.version ?? 1;
    finalSounds.set(
      rawSound.id,
      normalizeSoundAnchor({ ...rawSound, version: nextVersion }, nextVersion),
    );
  }

  // 7. Constraint violation check (freeze / forbid / clamp beat_at) against simulated final state
  const constraints = compileConstraints(state.statements);
  for (const constraint of constraints) {
    if (constraint.form === "freeze") {
      const scopeMode = String(constraint.params?.scope ?? "beats_and_boundaries");
      const paramKinds = Array.isArray(constraint.params?.kinds)
        ? new Set(constraint.params.kinds)
        : null;
      const frozenIds = new Set(
        Array.isArray(constraint.params?.entityIds) ? constraint.params.entityIds : [],
      );
      const checkBeats = paramKinds
        ? paramKinds.has("beat") || paramKinds.has("beats")
        : scopeMode === "beats" || scopeMode === "beats_and_boundaries" || scopeMode === "all";
      const checkBoundaries = paramKinds
        ? paramKinds.has("boundary") || paramKinds.has("boundaries")
        : scopeMode === "boundaries" || scopeMode === "beats_and_boundaries" || scopeMode === "all";
      const checkSounds = paramKinds
        ? paramKinds.has("sound") || paramKinds.has("sounds")
        : scopeMode === "sounds" || scopeMode === "all";

      /** @type {string[]} */
      const violatedIds = [];

      const checkKindFreeze = (touchedIds, beforeMap, afterMap) => {
        for (const id of touchedIds) {
          const before = beforeMap.get(id);
          const after = afterMap.get(id);
          const inFrozenSpan =
            (constraint.span && before && spanContainsTime(constraint.span, before.t)) ||
            (constraint.span && after && spanContainsTime(constraint.span, after.t));
          const inFrozenId = frozenIds.has(id);
          if (!inFrozenSpan && !inFrozenId) continue;
          const unchanged =
            before !== undefined &&
            after !== undefined &&
            canonicalJson(stripVersion(before)) === canonicalJson(stripVersion(after));
          if (!unchanged) {
            violatedIds.push(id);
          }
        }
      };

      if (checkBeats) checkKindFreeze(touchedBeatIds, state.beats, finalBeats);
      if (checkBoundaries) checkKindFreeze(touchedBoundaryIds, state.boundaries, finalBoundaries);
      if (checkSounds) checkKindFreeze(touchedSoundIds, state.sounds, finalSounds);

      if (violatedIds.length > 0) {
        conflicts.push({
          id: `conflict:constraint:${offer.id}:${constraint.statementId}`,
          kind: "constraint_violation",
          message: `Offer violates active "keep" (freeze) statement "${constraint.statementId}"`,
          entityIds: [...new Set(violatedIds)],
          statementIds: [constraint.statementId],
          ...(constraint.span ? { span: { ...constraint.span } } : {}),
        });
      }
    } else if (constraint.form === "forbid") {
      const tolSec =
        typeof constraint.params?.tolSec === "number"
          ? constraint.params.tolSec
          : DEFAULT_POINT_TOL_SEC;
      /** @type {string[]} */
      const violatedIds = [];

      for (const id of touchedBeatIds) {
        const after = finalBeats.get(id);
        if (!after) continue;
        const hitsSpan = constraint.span && spanContainsTime(constraint.span, after.t);
        const hitsPoint = constraint.t !== undefined && Math.abs(after.t - constraint.t) <= tolSec;
        if (hitsSpan || hitsPoint) {
          violatedIds.push(id);
        }
      }

      if (violatedIds.length > 0) {
        conflicts.push({
          id: `conflict:constraint:${offer.id}:${constraint.statementId}`,
          kind: "constraint_violation",
          message: `Offer places beat(s) violating "${constraint.statementKind}" (forbid) statement "${constraint.statementId}"`,
          entityIds: [...new Set(violatedIds)],
          statementIds: [constraint.statementId],
          ...(constraint.span ? { span: { ...constraint.span } } : {}),
        });
      }
    } else if (constraint.form === "clamp" && constraint.target === "beat" && constraint.t !== undefined) {
      const tolSec =
        typeof constraint.params?.tolSec === "number"
          ? constraint.params.tolSec
          : DEFAULT_POINT_TOL_SEC;
      /** @type {string[]} */
      const displacedIds = [];
      for (const id of touchedBeatIds) {
        const before = state.beats.get(id);
        const after = finalBeats.get(id);
        if (
          before &&
          Math.abs(before.t - constraint.t) <= tolSec &&
          (!after || Math.abs(after.t - constraint.t) > tolSec)
        ) {
          displacedIds.push(id);
        }
      }
      if (displacedIds.length > 0) {
        let hasReplacement = false;
        for (const beat of finalBeats.values()) {
          if (Math.abs(beat.t - constraint.t) <= tolSec) {
            hasReplacement = true;
            break;
          }
        }
        if (!hasReplacement) {
          conflicts.push({
            id: `conflict:constraint:${offer.id}:${constraint.statementId}`,
            kind: "constraint_violation",
            message: `Offer removes/moves clamped beat required by "beat_at" statement "${constraint.statementId}" at t=${constraint.t}`,
            entityIds: [...new Set(displacedIds)],
            statementIds: [constraint.statementId],
          });
        }
      }
    }
  }

  return conflicts;
}
