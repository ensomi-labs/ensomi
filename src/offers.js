/**
 * Orthogonal PatchOffer semantics:
 *  - Deterministic semantic keys independent of freshly generated ephemeral IDs
 *  - Conflict-first validation (version_mismatch, range_changed, constraint_violation, sibling_conflict)
 *  - Rejection suppression checks and range entity membership helpers
 */

import { canonicalJson, roundTime, sha256Hex } from "./hash.js";
import { compileConstraints, DEFAULT_POINT_TOL_SEC } from "./constraints.js";
import { assertSpan, spanContainsTime, spansOverlap } from "./types.js";

/**
 * Compute a deterministic canonical semantic key for a PatchOffer's writeScope and ops.
 * Deliberately strips ephemeral entity IDs and versions from upserted entities so
 * rejection suppression works across recomputes that mint fresh IDs.
 *
 * @param {{ t0: number, t1: number }} writeScope
 * @param {import("../index.d.ts").PatchOp} ops
 * @returns {string}
 */
export function computeSemanticKey(writeScope, ops = {}) {
  const scope = assertSpan(writeScope);

  const canonicalBeats = (ops.upsertBeats ?? [])
    .map((b) => ({
      t: roundTime(b.t, 4),
      source: b.source ?? "model",
    }))
    .sort((a, b) => a.t - b.t || a.source.localeCompare(b.source));

  const canonicalBoundaries = (ops.upsertBoundaries ?? [])
    .map((b) => ({
      t: roundTime(b.t, 4),
      kind: b.kind,
      bpm: b.bpm !== undefined ? roundTime(b.bpm, 4) : null,
      source: b.source ?? "model",
    }))
    .sort(
      (a, b) =>
        a.t - b.t ||
        a.kind.localeCompare(b.kind) ||
        (a.bpm ?? -1) - (b.bpm ?? -1) ||
        a.source.localeCompare(b.source),
    );

  const canonicalSounds = (ops.upsertSounds ?? [])
    .map((s) => ({
      t: roundTime(s.t, 4),
      emphasis: roundTime(s.emphasis, 4),
      source: s.source ?? "model",
    }))
    .sort((a, b) => a.t - b.t || a.emphasis - b.emphasis || a.source.localeCompare(b.source));

  const canonical = {
    v: 1,
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
 * By default includes beats and boundaries; pass `{ includeSounds: true }` to include SoundAnchors.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {{ t0: number, t1: number }} span
 * @param {{ includeSounds?: boolean }} [options]
 * @returns {string[]}
 */
export function getRangeEntityIds(state, span, options = {}) {
  const s = assertSpan(span);
  /** @type {string[]} */
  const ids = [];
  for (const beat of state.beats.values()) {
    if (spanContainsTime(s, beat.t)) ids.push(beat.id);
  }
  for (const boundary of state.boundaries.values()) {
    if (spanContainsTime(s, boundary.t)) ids.push(boundary.id);
  }
  if (options.includeSounds) {
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
 *  1. sibling_conflict (if another offer in the same batchId was accepted or this offer was superseded)
 *  2. version_mismatch (against ops.expectedVersions)
 *  3. range_changed (against ops.expectedRangeEntityIds in writeScope)
 *  4. constraint_violation (against active freeze/forbid/clamp constraints)
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").PatchOffer} offer
 * @returns {import("../index.d.ts").Conflict[]}
 */
export function validatePatchOffer(state, offer) {
  /** @type {import("../index.d.ts").Conflict[]} */
  const conflicts = [];
  const ops = offer.ops ?? {};

  // 1. Sibling acceptance / supersession check
  if (offer.status === "superseded") {
    conflicts.push({
      id: `conflict:sibling:${offer.id}`,
      kind: "sibling_conflict",
      message: `Offer "${offer.id}" was superseded by an accepted sibling in batch "${offer.batchId}"`,
      span: { ...offer.writeScope },
    });
  } else {
    for (const other of state.offers.values()) {
      if (other.id !== offer.id && other.batchId === offer.batchId && other.status === "accepted") {
        conflicts.push({
          id: `conflict:sibling:${offer.id}:${other.id}`,
          kind: "sibling_conflict",
          message: `Sibling offer "${other.id}" in batch "${offer.batchId}" was already accepted`,
          entityIds: [other.id],
          span: { ...offer.writeScope },
        });
      }
    }
  }

  // 2. Version mismatch check
  if (ops.expectedVersions && typeof ops.expectedVersions === "object") {
    for (const [entityId, expectedVersion] of Object.entries(ops.expectedVersions)) {
      const found = findEntity(state, entityId);
      if (!found) {
        conflicts.push({
          id: `conflict:version:${offer.id}:${entityId}`,
          kind: "version_mismatch",
          message: `Expected entity "${entityId}" at version ${expectedVersion}, but it no longer exists`,
          entityIds: [entityId],
          span: { ...offer.writeScope },
        });
      } else if (found.entity.version !== expectedVersion) {
        conflicts.push({
          id: `conflict:version:${offer.id}:${entityId}`,
          kind: "version_mismatch",
          message: `Entity "${entityId}" version mismatch: expected ${expectedVersion}, found ${found.entity.version}`,
          entityIds: [entityId],
          span: { ...offer.writeScope },
        });
      }
    }
  }

  // 3. Range membership safety check
  if (Array.isArray(ops.expectedRangeEntityIds)) {
    const includeSounds =
      (ops.upsertSounds && ops.upsertSounds.length > 0) ||
      (ops.removeSoundIds && ops.removeSoundIds.length > 0) ||
      ops.expectedRangeEntityIds.some((id) => state.sounds.has(id));

    const currentIds = new Set(getRangeEntityIds(state, offer.writeScope, { includeSounds }));
    const expectedIds = new Set(ops.expectedRangeEntityIds);

    const added = [...currentIds].filter((id) => !expectedIds.has(id)).sort();
    const missing = [...expectedIds].filter((id) => !currentIds.has(id)).sort();

    if (added.length > 0 || missing.length > 0) {
      const changedIds = [...added, ...missing];
      conflicts.push({
        id: `conflict:range:${offer.id}`,
        kind: "range_changed",
        message: `Entities inside writeScope [${offer.writeScope.t0}, ${offer.writeScope.t1}) changed since offer generation (added: [${added.join(", ")}], removed: [${missing.join(", ")}])`,
        entityIds: changedIds,
        span: { ...offer.writeScope },
      });
    }
  }

  // 4. Constraint violation check (freeze / forbid / clamp beat_at)
  const constraints = compileConstraints(state.statements);
  for (const constraint of constraints) {
    if (constraint.form === "freeze") {
      const scopeMode = String(constraint.params?.scope ?? "beats_and_boundaries");
      const frozenIds = new Set(
        Array.isArray(constraint.params?.entityIds) ? constraint.params.entityIds : [],
      );
      const checkBeats = scopeMode !== "boundaries";
      const checkBoundaries = scopeMode !== "beats";
      const checkSounds = scopeMode === "all" || scopeMode === "sounds";

      /** @type {string[]} */
      const violatedIds = [];

      if (checkBeats) {
        for (const beat of ops.upsertBeats ?? []) {
          const existing = state.beats.get(beat.id);
          const inFrozenSpan =
            (constraint.span && spanContainsTime(constraint.span, beat.t)) ||
            (constraint.span && existing && spanContainsTime(constraint.span, existing.t));
          const inFrozenId = frozenIds.has(beat.id);
          const unchanged = existing && existing.t === beat.t && existing.source === beat.source;
          if ((inFrozenSpan || inFrozenId) && !unchanged) {
            violatedIds.push(beat.id);
          }
        }
        for (const id of ops.removeBeatIds ?? []) {
          const existing = state.beats.get(id);
          const inFrozenSpan = existing && constraint.span && spanContainsTime(constraint.span, existing.t);
          if (inFrozenSpan || frozenIds.has(id)) {
            violatedIds.push(id);
          }
        }
      }

      if (checkBoundaries) {
        for (const boundary of ops.upsertBoundaries ?? []) {
          const existing = state.boundaries.get(boundary.id);
          const inFrozenSpan =
            (constraint.span && spanContainsTime(constraint.span, boundary.t)) ||
            (constraint.span && existing && spanContainsTime(constraint.span, existing.t));
          const inFrozenId = frozenIds.has(boundary.id);
          const unchanged =
            existing &&
            existing.t === boundary.t &&
            existing.kind === boundary.kind &&
            existing.bpm === boundary.bpm;
          if ((inFrozenSpan || inFrozenId) && !unchanged) {
            violatedIds.push(boundary.id);
          }
        }
        for (const id of ops.removeBoundaryIds ?? []) {
          const existing = state.boundaries.get(id);
          const inFrozenSpan = existing && constraint.span && spanContainsTime(constraint.span, existing.t);
          if (inFrozenSpan || frozenIds.has(id)) {
            violatedIds.push(id);
          }
        }
      }

      if (checkSounds) {
        for (const sound of ops.upsertSounds ?? []) {
          const existing = state.sounds.get(sound.id);
          const inFrozenSpan =
            (constraint.span && spanContainsTime(constraint.span, sound.t)) ||
            (constraint.span && existing && spanContainsTime(constraint.span, existing.t));
          if (inFrozenSpan || frozenIds.has(sound.id)) {
            violatedIds.push(sound.id);
          }
        }
        for (const id of ops.removeSoundIds ?? []) {
          const existing = state.sounds.get(id);
          const inFrozenSpan = existing && constraint.span && spanContainsTime(constraint.span, existing.t);
          if (inFrozenSpan || frozenIds.has(id)) {
            violatedIds.push(id);
          }
        }
      }

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

      for (const beat of ops.upsertBeats ?? []) {
        const hitsSpan = constraint.span && spanContainsTime(constraint.span, beat.t);
        const hitsPoint = constraint.t !== undefined && Math.abs(beat.t - constraint.t) <= tolSec;
        if (hitsSpan || hitsPoint) {
          violatedIds.push(beat.id);
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
      const removedAtT = (ops.removeBeatIds ?? []).filter((id) => {
        const existing = state.beats.get(id);
        return existing && Math.abs(existing.t - constraint.t) <= tolSec;
      });
      const movedAwayAtT = (ops.upsertBeats ?? []).filter((b) => {
        const existing = state.beats.get(b.id);
        return (
          existing &&
          Math.abs(existing.t - constraint.t) <= tolSec &&
          Math.abs(b.t - constraint.t) > tolSec
        );
      });
      if (removedAtT.length > 0 || movedAwayAtT.length > 0) {
        // Check if any beat remains at constraint.t after patch
        const removedSet = new Set(ops.removeBeatIds ?? []);
        const upsertMap = new Map((ops.upsertBeats ?? []).map((b) => [b.id, b]));
        let hasReplacement = false;
        for (const b of ops.upsertBeats ?? []) {
          if (Math.abs(b.t - constraint.t) <= tolSec) {
            hasReplacement = true;
            break;
          }
        }
        if (!hasReplacement) {
          for (const existing of state.beats.values()) {
            if (removedSet.has(existing.id) || upsertMap.has(existing.id)) continue;
            if (Math.abs(existing.t - constraint.t) <= tolSec) {
              hasReplacement = true;
              break;
            }
          }
        }
        if (!hasReplacement) {
          conflicts.push({
            id: `conflict:constraint:${offer.id}:${constraint.statementId}`,
            kind: "constraint_violation",
            message: `Offer removes/moves clamped beat required by "beat_at" statement "${constraint.statementId}" at t=${constraint.t}`,
            entityIds: [...removedAtT, ...movedAwayAtT.map((b) => b.id)],
            statementIds: [constraint.statementId],
          });
        }
      }
    }
  }

  return conflicts;
}
