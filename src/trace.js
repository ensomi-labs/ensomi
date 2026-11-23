/**
 * Append-only NDJSON trace, sans-IO state reducer, selective retraction (re-fold),
 * crash-safe NDJSON parser, and verifiable state checkpoints.
 */

import { canonicalJson, sha256Hex } from "./hash.js";
import {
  computeConsequences,
  computeSemanticKey,
  detectSiblingConflicts,
  isOfferSuppressed,
  validatePatchOffer,
} from "./offers.js";
import {
  assertOfferShape,
  assertSpan,
  assertTraceEventEnvelope,
  EnsomiError,
  normalizeAudioDescriptor,
  normalizeBeat,
  normalizeBoundary,
  normalizeSoundAnchor,
  normalizeStatement,
  spanContainsTime,
  spansOverlap,
  TRACE_SCHEMA_VERSION,
} from "./types.js";

const USER_ACTION_TYPES = new Set([
  "edit",
  "statement",
  "signal",
  "offer_accept",
  "offer_reject",
]);

/**
 * Create a clean initial sans-IO SessionState.
 * @returns {import("../index.d.ts").SessionState}
 */
export function createInitialState() {
  return {
    audio: null,
    beats: new Map(),
    boundaries: new Map(),
    sounds: new Map(),
    statements: new Map(),
    offers: new Map(),
    suppressedOffers: [],
    suppressions: [],
    lastSignal: null,
    generation: 0,
    effort: {
      listenSec: 0,
      actionCount: 0,
      waitSec: 0,
    },
    visitedSpans: [],
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
 * Shallow-clone state containers so incremental applyEvent never mutates the caller's state.
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").SessionState}
 */
function cloneStateShallow(state) {
  return {
    audio: state.audio ? { ...state.audio } : null,
    beats: new Map(state.beats),
    boundaries: new Map(state.boundaries),
    sounds: new Map(state.sounds),
    statements: new Map(state.statements),
    offers: new Map(state.offers),
    suppressedOffers: state.suppressedOffers.slice(),
    suppressions: state.suppressions.slice(),
    lastSignal: state.lastSignal ? structuredClone(state.lastSignal) : null,
    generation: state.generation,
    effort: { ...state.effort },
    visitedSpans: state.visitedSpans.slice(),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    events: state.events.slice(),
    eventIds: new Set(state.eventIds),
    retractedEventIds: new Set(state.retractedEventIds),
    lastSeq: state.lastSeq,
    foldWarnings: state.foldWarnings.slice(),
  };
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
 * Retracting a retraction cancels the earlier retraction (redo semantics).
 *
 * @param {import("../index.d.ts").TraceEvent[]} events
 * @returns {Set<string>} Set of retracted event IDs
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
    // Always validate backward reference even if this retract is itself retracted.
    const target = resolveRetractTarget(ev, byId, bySeq);
    if (retractedIds.has(ev.id)) {
      // This retraction was itself retracted by a newer retraction (redo).
      continue;
    }
    retractedIds.add(target.id);
  }

  return retractedIds;
}

/**
 * Clear any rejection suppressions whose writeScope overlaps any of the touched spans.
 * @param {import("../index.d.ts").SessionState} state
 * @param {Array<{ t0: number, t1: number }>} touchedSpans
 */
function clearOverlappingSuppressions(state, touchedSpans) {
  if (state.suppressions.length === 0 || touchedSpans.length === 0) return;
  state.suppressions = state.suppressions.filter(
    (entry) => !touchedSpans.some((span) => spansOverlap(entry.writeScope, span)),
  );
}

/**
 * Re-evaluate conflicts on all currently pending offers.
 * When `markStaleOnDataConflict` is true (after an edit or accepted offer),
 * offers with version_mismatch or range_changed transition to status "stale".
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {boolean} markStaleOnDataConflict
 */
function refreshPendingOfferConflicts(state, markStaleOnDataConflict) {
  for (const [id, offer] of state.offers.entries()) {
    if (offer.status !== "pending") continue;
    const conflicts = validatePatchOffer(state, offer);
    const hasDataConflict = conflicts.some(
      (c) => c.kind === "version_mismatch" || c.kind === "range_changed",
    );
    const nextStatus = markStaleOnDataConflict && hasDataConflict ? "stale" : offer.status;
    if (nextStatus !== offer.status || conflicts.length !== offer.conflicts.length) {
      state.offers.set(id, {
        ...offer,
        status: nextStatus,
        conflicts,
      });
    } else if (conflicts.length > 0) {
      state.offers.set(id, {
        ...offer,
        conflicts,
      });
    }
  }
}

/**
 * Apply a single direct EditOp in place on a cloned draft state.
 * Returns touched spans for rejection-suppression invalidation.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").EditOp} op
 * @param {import("../index.d.ts").TraceEvent} event
 * @returns {Array<{ t0: number, t1: number }>}
 */
function applyEditOpInPlace(state, op, event) {
  if (!op || typeof op !== "object") {
    throw new EnsomiError("INVALID_EDIT", "EditOp must be an object");
  }
  /** @type {Array<{ t0: number, t1: number }>} */
  const touched = [];

  switch (op.op) {
    case "upsert_beat": {
      const raw = op.beat;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_beat requires a beat object");
      }
      const existing = state.beats.get(raw.id);
      const nextVersion = existing ? existing.version + 1 : raw.version ?? 1;
      const beat = normalizeBeat({ ...raw, version: nextVersion }, nextVersion);
      if (existing) {
        touched.push({ t0: existing.t, t1: existing.t });
      }
      touched.push({ t0: beat.t, t1: beat.t });
      state.beats.set(beat.id, beat);
      break;
    }

    case "move_beat": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "move_beat requires non-empty id");
      }
      if (typeof op.t !== "number" || !Number.isFinite(op.t)) {
        throw new EnsomiError("INVALID_EDIT", `move_beat requires finite t, got ${op.t}`);
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
      touched.push({ t0: existing.t, t1: existing.t });
      touched.push({ t0: op.t, t1: op.t });
      const updated = normalizeBeat(
        {
          ...existing,
          t: op.t,
          source: op.source ?? existing.source,
          ...(op.conf !== undefined ? { conf: op.conf } : {}),
          ...(op.meta !== undefined ? { meta: op.meta } : {}),
          version: existing.version + 1,
        },
        existing.version + 1,
      );
      state.beats.set(updated.id, updated);
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
      touched.push({ t0: existing.t, t1: existing.t });
      state.beats.delete(op.id);
      break;
    }

    case "shift_run_offset": {
      if (typeof op.deltaSec !== "number" || !Number.isFinite(op.deltaSec)) {
        throw new EnsomiError(
          "INVALID_EDIT",
          `shift_run_offset requires finite deltaSec, got ${op.deltaSec}`,
        );
      }
      const span = op.span ? assertSpan(op.span) : null;
      const beatIdSet = Array.isArray(op.beatIds) ? new Set(op.beatIds) : null;
      if (!span && !beatIdSet) {
        throw new EnsomiError("INVALID_EDIT", "shift_run_offset requires span or beatIds");
      }
      if (span) {
        touched.push(span);
        touched.push({
          t0: Math.min(span.t0, span.t0 + op.deltaSec),
          t1: Math.max(span.t1, span.t1 + op.deltaSec),
        });
      }
      for (const [id, existing] of state.beats.entries()) {
        const matchId = beatIdSet ? beatIdSet.has(id) : true;
        const matchSpan = span ? spanContainsTime(span, existing.t) : true;
        if (matchId && matchSpan) {
          const nextT = existing.t + op.deltaSec;
          touched.push({ t0: existing.t, t1: existing.t });
          touched.push({ t0: nextT, t1: nextT });
          state.beats.set(
            id,
            normalizeBeat(
              {
                ...existing,
                t: nextT,
                source: op.source ?? existing.source,
                version: existing.version + 1,
              },
              existing.version + 1,
            ),
          );
        }
      }
      if (op.includeBoundaries && span) {
        for (const [id, existing] of state.boundaries.entries()) {
          if (spanContainsTime(span, existing.t)) {
            const nextT = existing.t + op.deltaSec;
            state.boundaries.set(
              id,
              normalizeBoundary(
                {
                  ...existing,
                  t: nextT,
                  source: op.source ?? existing.source,
                  version: existing.version + 1,
                },
                existing.version + 1,
              ),
            );
          }
        }
      }
      break;
    }

    case "upsert_boundary": {
      const raw = op.boundary;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_boundary requires a boundary object");
      }
      const existing = state.boundaries.get(raw.id);
      const nextVersion = existing ? existing.version + 1 : raw.version ?? 1;
      const boundary = normalizeBoundary({ ...raw, version: nextVersion }, nextVersion);
      if (existing) {
        touched.push({ t0: existing.t, t1: existing.t });
      }
      touched.push({ t0: boundary.t, t1: boundary.t });
      state.boundaries.set(boundary.id, boundary);
      break;
    }

    case "remove_boundary": {
      if (typeof op.id !== "string" || op.id.length === 0) {
        throw new EnsomiError("INVALID_EDIT", "remove_boundary requires non-empty id");
      }
      const existing = state.boundaries.get(op.id);
      if (!existing) {
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "MISSING_ENTITY",
          message: `remove_boundary skipped missing boundary "${op.id}"`,
        });
        break;
      }
      touched.push({ t0: existing.t, t1: existing.t });
      state.boundaries.delete(op.id);
      break;
    }

    case "upsert_sound": {
      const raw = op.sound;
      if (!raw || typeof raw !== "object") {
        throw new EnsomiError("INVALID_EDIT", "upsert_sound requires a sound object");
      }
      const existing = state.sounds.get(raw.id);
      const nextVersion = existing ? existing.version + 1 : raw.version ?? 1;
      const sound = normalizeSoundAnchor({ ...raw, version: nextVersion }, nextVersion);
      if (existing) {
        touched.push({ t0: existing.t, t1: existing.t });
      }
      touched.push({ t0: sound.t, t1: sound.t });
      state.sounds.set(sound.id, sound);
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
      touched.push({ t0: existing.t, t1: existing.t });
      state.sounds.delete(op.id);
      break;
    }

    default:
      throw new EnsomiError("INVALID_EDIT", `Unknown edit op: ${/** @type {any} */ (op).op}`);
  }

  return touched;
}

/**
 * Apply a single active (non-retracted, non-retract) event in place on `state`.
 * Used identically by both incremental applyEvent and full foldTrace.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").TraceEvent} event
 */
function applyActiveEventInPlace(state, event) {
  if (event.author?.kind === "user" && USER_ACTION_TYPES.has(event.type)) {
    state.effort.actionCount += 1;
  }

  const payload = /** @type {any} */ (event.payload);

  switch (event.type) {
    case "audio_bound": {
      const rawAudio = payload?.audio ?? payload;
      state.audio = normalizeAudioDescriptor(rawAudio);
      break;
    }

    case "draft_loaded": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_PAYLOAD", "draft_loaded payload must be an object");
      }
      const replace = payload.replace !== false;
      if (replace) {
        state.beats.clear();
        state.boundaries.clear();
        state.sounds.clear();
      }
      for (const rawBeat of payload.beats ?? []) {
        const beat = normalizeBeat(rawBeat, rawBeat.version ?? 1);
        state.beats.set(beat.id, beat);
      }
      for (const rawBoundary of payload.boundaries ?? []) {
        const boundary = normalizeBoundary(rawBoundary, rawBoundary.version ?? 1);
        state.boundaries.set(boundary.id, boundary);
      }
      for (const rawSound of payload.sounds ?? []) {
        const sound = normalizeSoundAnchor(rawSound, rawSound.version ?? 1);
        state.sounds.set(sound.id, sound);
      }
      for (const rawStatement of payload.statements ?? []) {
        const st = normalizeStatement(rawStatement, event.seq, rawStatement.version ?? 1);
        state.statements.set(st.id, st);
      }
      break;
    }

    case "edit": {
      const ops = Array.isArray(payload?.ops) ? payload.ops : [payload];
      /** @type {Array<{ t0: number, t1: number }>} */
      const touched = [];
      for (const op of ops) {
        touched.push(...applyEditOpInPlace(state, op, event));
      }
      clearOverlappingSuppressions(state, touched);
      refreshPendingOfferConflicts(state, true);
      break;
    }

    case "statement": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_PAYLOAD", "statement payload must be an object");
      }
      /** @type {Array<{ t0: number, t1: number }>} */
      const touched = [];

      if (payload.action === "deactivate" || payload.action === "remove") {
        const id = payload.id ?? payload.statement?.id;
        if (typeof id !== "string" || id.length === 0) {
          throw new EnsomiError("INVALID_STATEMENT", "statement deactivate requires id");
        }
        const existing = state.statements.get(id);
        if (existing) {
          if (existing.span) touched.push({ ...existing.span });
          if (existing.t !== undefined) touched.push({ t0: existing.t, t1: existing.t });
          state.statements.set(id, {
            ...existing,
            active: false,
            version: existing.version + 1,
          });
        }
      } else {
        const rawSt = payload.statement ?? payload;
        const existing = state.statements.get(rawSt.id);
        const nextVersion = existing ? existing.version + 1 : rawSt.version ?? 1;
        const createdAtSeq = existing ? existing.createdAtSeq : rawSt.createdAtSeq ?? event.seq;
        const st = normalizeStatement(
          { ...rawSt, version: nextVersion, createdAtSeq },
          createdAtSeq,
          nextVersion,
        );
        if (existing?.span) touched.push({ ...existing.span });
        if (existing?.t !== undefined) touched.push({ t0: existing.t, t1: existing.t });
        if (st.span) touched.push({ ...st.span });
        if (st.t !== undefined) touched.push({ t0: st.t, t1: st.t });
        state.statements.set(st.id, st);
      }

      clearOverlappingSuppressions(state, touched);
      refreshPendingOfferConflicts(state, false);
      break;
    }

    case "signal": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_PAYLOAD", "signal payload must be an object");
      }
      if (payload.scope !== "song") {
        assertSpan(payload.scope);
      }
      if (typeof payload.reason !== "string") {
        throw new EnsomiError("INVALID_PAYLOAD", "signal payload requires string reason");
      }
      if (
        typeof payload.generation !== "number" ||
        !Number.isInteger(payload.generation) ||
        payload.generation < 0
      ) {
        throw new EnsomiError("INVALID_PAYLOAD", "signal payload requires non-negative integer generation");
      }
      state.lastSignal = {
        scope: payload.scope === "song" ? "song" : { ...payload.scope },
        reason: payload.reason,
        generation: payload.generation,
        ...(Array.isArray(payload.statementIds)
          ? { statementIds: payload.statementIds.slice() }
          : {}),
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

      /** @type {import("../index.d.ts").PatchOffer[]} */
      const normalizedIncoming = [];
      for (const rawOffer of payload.offers) {
        const candidate = {
          ...rawOffer,
          batchId: rawOffer.batchId ?? payload.batchId,
          status: rawOffer.status ?? "pending",
          ops: rawOffer.ops ? structuredClone(rawOffer.ops) : {},
          conflicts: Array.isArray(rawOffer.conflicts) ? structuredClone(rawOffer.conflicts) : [],
        };
        assertOfferShape(candidate);
        candidate.writeScope = assertSpan(candidate.writeScope);
        if (candidate.readScope !== "song") {
          candidate.readScope = assertSpan(candidate.readScope);
        }
        candidate.semanticKey =
          candidate.semanticKey || computeSemanticKey(candidate.writeScope, candidate.ops);
        candidate.consequences =
          candidate.consequences ?? computeConsequences(state, candidate.ops);

        if (candidate.generation > state.generation) {
          state.generation = candidate.generation;
        }
        normalizedIncoming.push(candidate);
      }

      const siblingMap = detectSiblingConflicts(normalizedIncoming);

      /** @type {import("../index.d.ts").PatchOffer[]} */
      const acceptedIntoState = [];
      for (const offer of normalizedIncoming) {
        if (isOfferSuppressed(state, offer)) {
          state.suppressedOffers.push(offer);
          continue;
        }
        const liveConflicts = validatePatchOffer(state, offer);
        const siblingConflicts = siblingMap.get(offer.id) ?? [];
        const combined = [...liveConflicts];
        for (const sc of siblingConflicts) {
          if (!combined.some((c) => c.id === sc.id)) {
            combined.push(sc);
          }
        }
        const finalOffer = {
          ...offer,
          conflicts: combined,
        };
        acceptedIntoState.push(finalOffer);
      }

      // Mark older pending offers stale when superseded by a newer generation overlapping their writeScope
      for (const [existingId, existing] of state.offers.entries()) {
        if (existing.status !== "pending") continue;
        const overshadowed = acceptedIntoState.some(
          (inc) =>
            inc.generation > existing.generation &&
            spansOverlap(inc.writeScope, existing.writeScope),
        );
        if (overshadowed) {
          state.offers.set(existingId, { ...existing, status: "stale" });
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

      const conflicts = validatePatchOffer(state, offer);
      if (conflicts.length > 0) {
        state.offers.set(offerId, {
          ...offer,
          status: offer.status === "superseded" ? "superseded" : "stale",
          conflicts,
        });
        state.foldWarnings.push({
          seq: event.seq,
          eventId: event.id,
          code: "OFFER_CONFLICT",
          message: `offer_accept blocked for "${offerId}" due to ${conflicts.length} conflict(s)`,
        });
        break;
      }

      const ops = offer.ops ?? {};
      for (const id of ops.removeBeatIds ?? []) {
        state.beats.delete(id);
      }
      for (const rawBeat of ops.upsertBeats ?? []) {
        const existing = state.beats.get(rawBeat.id);
        const nextVersion = existing ? existing.version + 1 : rawBeat.version ?? 1;
        const beat = normalizeBeat({ ...rawBeat, version: nextVersion }, nextVersion);
        state.beats.set(beat.id, beat);
      }
      for (const id of ops.removeBoundaryIds ?? []) {
        state.boundaries.delete(id);
      }
      for (const rawBoundary of ops.upsertBoundaries ?? []) {
        const existing = state.boundaries.get(rawBoundary.id);
        const nextVersion = existing ? existing.version + 1 : rawBoundary.version ?? 1;
        const boundary = normalizeBoundary({ ...rawBoundary, version: nextVersion }, nextVersion);
        state.boundaries.set(boundary.id, boundary);
      }
      for (const id of ops.removeSoundIds ?? []) {
        state.sounds.delete(id);
      }
      for (const rawSound of ops.upsertSounds ?? []) {
        const existing = state.sounds.get(rawSound.id);
        const nextVersion = existing ? existing.version + 1 : rawSound.version ?? 1;
        const sound = normalizeSoundAnchor({ ...rawSound, version: nextVersion }, nextVersion);
        state.sounds.set(sound.id, sound);
      }

      state.offers.set(offerId, {
        ...offer,
        status: "accepted",
        conflicts: [],
      });

      // Supersede sibling pending offers in the same batchId
      for (const [otherId, other] of state.offers.entries()) {
        if (otherId === offerId) continue;
        if (other.batchId === offer.batchId && other.status === "pending") {
          state.offers.set(otherId, {
            ...other,
            status: "superseded",
            conflicts: [
              {
                id: `conflict:sibling:${otherId}:${offerId}`,
                kind: "sibling_conflict",
                message: `Superseded because sibling offer "${offerId}" in batch "${offer.batchId}" was accepted`,
                entityIds: [offerId],
                span: { ...other.writeScope },
              },
            ],
          });
        }
      }

      refreshPendingOfferConflicts(state, true);
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

      state.offers.set(offerId, {
        ...offer,
        status: "rejected",
      });

      const alreadySuppressed = state.suppressions.some(
        (s) =>
          s.semanticKey === offer.semanticKey &&
          s.writeScope.t0 === offer.writeScope.t0 &&
          s.writeScope.t1 === offer.writeScope.t1,
      );
      if (!alreadySuppressed) {
        state.suppressions.push({
          semanticKey: offer.semanticKey,
          writeScope: { ...offer.writeScope },
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
        if (payload.span) {
          state.visitedSpans.push(assertSpan(payload.span));
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
 * Apply a single TraceEvent to SessionState.
 * - Normal events apply incrementally (purely, returning a new SessionState).
 * - "retract" events validate backward-only target references and perform a clean
 *   full re-fold from event 0 so selective undo never clobbers later edits.
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

  if (event.type === "retract") {
    return foldTrace([...state.events, event]);
  }

  const next = cloneStateShallow(state);
  next.events.push(event);
  next.eventIds.add(event.id);
  next.lastSeq = event.seq;
  applyActiveEventInPlace(next, event);
  return next;
}

/**
 * Fold a full sequence of TraceEvents from event 0 into a materialized SessionState.
 * Guaranteed equivalent to `events.reduce(applyEvent, createInitialState())`.
 *
 * @param {import("../index.d.ts").TraceEvent[]} events
 * @returns {import("../index.d.ts").SessionState}
 */
export function foldTrace(events) {
  if (!Array.isArray(events)) {
    throw new EnsomiError("INVALID_TRACE", "events must be an array");
  }

  let prevSeq = -1;
  const seenIds = new Set();
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
  }

  const retractedIds = resolveActiveRetractions(events);
  const state = createInitialState();
  state.retractedEventIds = retractedIds;

  for (const ev of events) {
    state.events.push(ev);
    state.eventIds.add(ev.id);
    state.lastSeq = ev.seq;

    if (ev.type === "retract") {
      if (!retractedIds.has(ev.id) && ev.author?.kind === "user") {
        state.effort.actionCount += 1;
      }
      continue;
    }

    if (retractedIds.has(ev.id)) {
      continue;
    }

    applyActiveEventInPlace(state, ev);
  }

  return state;
}

/**
 * Serialize a TraceEvent array to append-only NDJSON (.jsonl).
 * @param {import("../index.d.ts").TraceEvent[]} events
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
 * When `discardTruncatedTail` is true, discards ONLY an incomplete/corrupt trailing line;
 * interior corruption ALWAYS throws.
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
  // Strip single trailing empty segment produced by a standard terminating newline.
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
 * Convert materialized SessionState into a canonical JSON-serializable object.
 * @param {import("../index.d.ts").SessionState} state
 */
export function serializeState(state) {
  return {
    audio: state.audio,
    beats: Array.from(state.beats.values()).sort((a, b) => a.t - b.t || a.id.localeCompare(b.id)),
    boundaries: Array.from(state.boundaries.values()).sort(
      (a, b) => a.t - b.t || a.id.localeCompare(b.id),
    ),
    sounds: Array.from(state.sounds.values()).sort((a, b) => a.t - b.t || a.id.localeCompare(b.id)),
    statements: Array.from(state.statements.values()).sort(
      (a, b) => a.createdAtSeq - b.createdAtSeq || a.id.localeCompare(b.id),
    ),
    offers: Array.from(state.offers.values()).sort((a, b) => a.id.localeCompare(b.id)),
    suppressions: state.suppressions
      .slice()
      .sort((a, b) => a.rejectedAtSeq - b.rejectedAtSeq || a.semanticKey.localeCompare(b.semanticKey)),
    lastSignal: state.lastSignal,
    generation: state.generation,
    effort: { ...state.effort },
    visitedSpans: state.visitedSpans.slice(),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    retractedEventIds: Array.from(state.retractedEventIds).sort(),
    lastSeq: state.lastSeq,
  };
}

/**
 * Create a verifiable deterministic checkpoint from materialized SessionState.
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
    stateHash,
    snapshot,
  };
}

/**
 * Verify a checkpoint against the trace prefix that produced it.
 * @param {import("../index.d.ts").TraceCheckpoint} checkpoint
 * @param {import("../index.d.ts").TraceEvent[]} prefixEvents
 */
export function verifyCheckpoint(checkpoint, prefixEvents) {
  const refState = foldTrace(prefixEvents);
  const refCheckpoint = createCheckpoint(refState);
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
    actualHash: checkpoint.stateHash,
  };
}
