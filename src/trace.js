/**
 * Append-only NDJSON trace (`schemaVersion: "0.3"`), deterministic reducer (`applyEvent`, `foldTrace`),
 * event-atomic selective `retract` (`OrphanedTransaction` tracking), `computeUndoRedoTargets`,
 * and RFC 8785 (JCS) checkpoints.
 */

import { deriveChildEntityId, executeTransaction, getVerdictEntity } from "./interpreter.js";
import { canonicalJson, compareCodeUnits, EnsomiError, jcsSha256Hex } from "./jcs.js";
import {
  computeOfferSemanticKey,
  evaluateOfferInternal,
  isOfferSuppressed,
  validateOffer,
} from "./offers.js";
import {
  cloneSessionStateShallow,
  computeSnapshotDigest,
  createInitialState,
  createProviderView,
  hasActiveHumanTruth,
  serializeState,
  toSnapshot,
} from "./state.js";
import { assertNonNegSafeIntUs, assertRangeUs, rangesOverlapUs } from "./time.js";
import {
  assertNonEmptyId,
  assertOfferShape,
  assertTraceEventEnvelope,
  authorKindToOrigin,
  CHALLENGE_DISPOSITION_SET,
  computeEntityValueDigest,
  DEFAULT_BEAT_TOL_US,
  deepFreeze,
  normalizeAudioBinding,
  normalizeBeat,
  normalizeBoundary,
  normalizeSegment,
  normalizeSound,
  normalizeSoundClass,
  normalizeSpan,
  normalizeUnbeat,
  TRACE_SCHEMA_VERSION,
} from "./types.js";

const UNDOABLE_EVENT_TYPES = new Set([
  "reset_document",
  "edit",
  "offer_accept",
  "offer_reject",
  "ready",
]);

const ORPHANABLE_ERROR_CODES = new Set([
  "AUDIO_NOT_BOUND",
  "OUT_OF_BOUNDS",
  "MISSING_ENTITY",
  "REVISION_MISMATCH",
  "DIGEST_MISMATCH",
  "DUPLICATE_ENTITY_ID",
  "INVALID_SEGMENT_EDIT",
  "INVALID_SPAN",
  "INVALID_RANGE",
  "OFFER_NOT_ACCEPTABLE",
  "CHALLENGE_CONFIRM_REQUIRED",
  "MISSING_OFFER",
  "OFFER_NOT_REJECTABLE",
]);

const FROZEN_EVENTS = new WeakSet();

/**
 * Fast plain-JSON clone + freeze for TraceEvents.
 *
 * @template T
 * @param {T} value
 * @returns {T}
 */
function cloneAndFreezePlain(value) {
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) {
    const out = new Array(value.length);
    for (let i = 0; i < value.length; i += 1) {
      out[i] = cloneAndFreezePlain(value[i]);
    }
    return /** @type {T} */ (Object.freeze(out));
  }
  const proto = Object.getPrototypeOf(value);
  if (proto !== Object.prototype && proto !== null) {
    return deepFreeze(structuredClone(value));
  }
  /** @type {Record<string, unknown>} */
  const out = {};
  for (const k in /** @type {Record<string, unknown>} */ (value)) {
    if (Object.hasOwn(value, k)) {
      out[k] = cloneAndFreezePlain(/** @type {Record<string, unknown>} */ (value)[k]);
    }
  }
  return /** @type {T} */ (Object.freeze(out));
}

function cloneAndFreezeEvent(ev) {
  if (FROZEN_EVENTS.has(ev)) return ev;
  const cloned = cloneAndFreezePlain(ev);
  FROZEN_EVENTS.add(cloned);
  return cloned;
}

/**
 * Retire all currently active entities in `state` (preserving every ID in `state.idRegistry`
 * as `active: false` so IDs can NEVER be reused after `reset_document` or `draft_loaded(replace: true)`!).
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} eventId
 */
function retireAllActiveEntities(state, eventId) {
  for (const [id, rec] of state.idRegistry) {
    if (rec.active) {
      state.idRegistry.set(
        id,
        deepFreeze({
          ...rec,
          active: false,
          retiredByEventId: eventId,
        }),
      );
    }
  }
  state.segments = [];
  state.boundaries.clear();
  state.spans = [];
  state.beats.clear();
  state.unbeats.clear();
  state.sounds.clear();
  state.soundClasses.clear();
  state.approvals.clear();
}

/**
 * Initialize a single contiguous `[0, durationUs)` segment with a deterministic fresh ID.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {number} durationUs
 * @param {string} segId
 * @param {import("../index.d.ts").Origin} origin
 * @param {string} eventId
 */
function initializeSingleSegmentPartition(state, durationUs, segId, origin, eventId) {
  if (durationUs <= 0) return;
  if (state.idRegistry.has(segId)) {
    throw new EnsomiError("DUPLICATE_ENTITY_ID", `Entity id "${segId}" already exists`);
  }
  const seg = normalizeSegment({
    id: segId,
    startUs: 0,
    endUs: durationUs,
    startBoundaryId: null,
    endBoundaryId: null,
    regime: "auto",
    origin,
    createdByEventId: eventId,
    revision: 1,
  });
  state.segments = [seg];
  state.idRegistry.set(
    seg.id,
    deepFreeze({
      id: seg.id,
      kind: "segment",
      active: true,
      createdByEventId: eventId,
    }),
  );
}

/**
 * Compute exact half-open `[startUs, endUs)` intervals touched by `ops` for surgical
 * `RejectionSuppression` invalidation (so editing at 2s never lifts a suppression at 12s!).
 *
 * @param {import("../index.d.ts").SessionState} beforeState
 * @param {readonly import("../index.d.ts").TransactionOp[]} ops
 * @param {number} durationUs
 * @returns {import("../index.d.ts").RangeUs[]}
 */
function computeTouchedRangesForOps(beforeState, ops, durationUs) {
  /** @type {import("../index.d.ts").RangeUs[]} */
  const ranges = [];
  const pushPoint = (atUs) => {
    if (typeof atUs === "number" && atUs >= 0 && atUs < durationUs) {
      ranges.push({ startUs: atUs, endUs: Math.min(durationUs, atUs + 1) });
    }
  };
  const pushSpan = (s, e) => {
    if (typeof s === "number" && typeof e === "number" && e > s) {
      ranges.push({ startUs: Math.max(0, s), endUs: Math.min(durationUs, e) });
    }
  };

  for (let i = 0; i < ops.length; i += 1) {
    const op = ops[i];
    switch (op.op) {
      case "upsert_beat": {
        const prev = beforeState.beats.get(op.beat.id);
        if (prev) pushPoint(prev.atUs);
        pushPoint(op.beat.atUs);
        break;
      }
      case "move_beat":
      case "snap_beat": {
        const prev = beforeState.beats.get(op.id);
        if (prev) pushPoint(prev.atUs);
        pushPoint(op.atUs);
        break;
      }
      case "shift_beats":
        pushSpan(op.startUs, op.endUs);
        pushSpan(op.startUs + op.deltaUs, op.endUs + op.deltaUs);
        break;
      case "remove_beats":
        for (const id of op.ids) {
          const prev = beforeState.beats.get(id);
          if (prev) pushPoint(prev.atUs);
        }
        break;
      case "place_unbeat":
        pushSpan(
          op.atUs - (op.captureUnverifiedRadiusUs ?? 0),
          op.atUs + (op.captureUnverifiedRadiusUs ?? 0) + 1,
        );
        break;
      case "remove_unbeats":
        for (const id of op.ids) {
          const prev = beforeState.unbeats.get(id);
          if (prev) pushPoint(prev.atUs);
        }
        break;
      case "beat_to_unbeat":
        for (const c of op.conversions) {
          const prev = beforeState.beats.get(c.beatId);
          if (prev) pushPoint(prev.atUs);
        }
        break;
      case "upsert_sound": {
        const prev = beforeState.sounds.get(op.sound.id);
        if (prev) pushSpan(prev.startUs, prev.endUs);
        pushSpan(op.sound.startUs, op.sound.endUs);
        break;
      }
      case "update_sound": {
        const prev = beforeState.sounds.get(op.id);
        if (prev) pushSpan(prev.startUs, prev.endUs);
        if (op.patch.startUs !== undefined || op.patch.endUs !== undefined) {
          pushSpan(
            op.patch.startUs ?? prev?.startUs ?? 0,
            op.patch.endUs ?? prev?.endUs ?? 0,
          );
        }
        break;
      }
      case "remove_sounds":
        for (const id of op.ids) {
          const prev = beforeState.sounds.get(id);
          if (prev) pushSpan(prev.startUs, prev.endUs);
        }
        break;
      case "split_segment":
        pushPoint(op.atUs);
        break;
      case "move_boundary": {
        const bnd = beforeState.boundaries.get(op.boundaryId);
        if (bnd) {
          pushSpan(Math.min(bnd.atUs, op.atUs), Math.max(bnd.atUs, op.atUs) + 1);
        }
        break;
      }
      case "merge_segments": {
        const left = beforeState.segments.find((s) => s.id === op.leftSegmentId);
        const right = beforeState.segments.find((s) => s.id === op.rightSegmentId);
        if (left && right) pushSpan(left.startUs, right.endUs);
        break;
      }
      case "promote_range_to_segments":
      case "set_range_regime":
      case "set_range_keep":
        pushSpan(op.startUs, op.endUs);
        break;
      case "set_segment_regime":
      case "set_segment_kinematics":
      case "set_segment_keep": {
        const seg = beforeState.segments.find((s) => s.id === op.segmentId);
        if (seg) pushSpan(seg.startUs, seg.endUs);
        break;
      }
      case "confirm_marks":
      case "clear_verdicts":
        for (const t of op.targets) {
          const ent = getVerdictEntity(beforeState, t.kind, t.id);
          if (!ent) continue;
          if ("atUs" in ent) pushPoint(ent.atUs);
          else if ("startUs" in ent && "endUs" in ent) pushSpan(ent.startUs, ent.endUs);
        }
        break;
      default:
        pushSpan(0, durationUs);
        break;
    }
  }
  return ranges;
}

/**
 * Re-evaluate pending (`valid` / `requires_challenge_confirm` / `stale`) offers after state mutations,
 * and drop `suppressions` whose `writeRanges` were edited by the user.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {readonly import("../index.d.ts").RangeUs[]} [userEditedRanges]
 */
function refreshOffersAndSuppressions(state, userEditedRanges = []) {
  if (userEditedRanges.length > 0 && state.suppressions.length > 0) {
    state.suppressions = state.suppressions.filter(
      (sup) =>
        !sup.writeRanges.some((sr) => userEditedRanges.some((ur) => rangesOverlapUs(sr, ur))),
    );
  }
  if (state.offers.size === 0) return;
  for (const [id, offer] of state.offers) {
    if (
      offer.status === "accepted" ||
      offer.status === "rejected" ||
      offer.status === "superseded"
    ) {
      continue;
    }
    const check = validateOffer(state, offer);
    if (
      check.status !== offer.status ||
      canonicalJson(check.conflicts) !== canonicalJson(offer.conflicts) ||
      canonicalJson(check.notes) !== canonicalJson(offer.notes)
    ) {
      state.offers.set(
        id,
        deepFreeze({
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          notes: check.notes,
        }),
      );
    }
  }
}

/**
 * Register a newly created entity in `state.idRegistry` (throwing `DUPLICATE_ENTITY_ID` if active or retired).
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} id
 * @param {import("../index.d.ts").EntityKind} kind
 * @param {string} eventId
 */
function registerFreshId(state, id, kind, eventId) {
  const existing = state.idRegistry.get(id);
  if (existing) {
    throw new EnsomiError(
      "DUPLICATE_ENTITY_ID",
      existing.active
        ? `Entity id "${id}" is already active as "${existing.kind}"`
        : `Entity id "${id}" was previously retired (${existing.kind}) and cannot be reused`,
      { id, existingKind: existing.kind, requestedKind: kind },
    );
  }
  state.idRegistry.set(
    id,
    deepFreeze({
      id,
      kind,
      active: true,
      createdByEventId: eventId,
    }),
  );
}

/**
 * Heal/validate a draft `segments` + `boundaries` list into a contiguous `[0, durationUs)`
 * partition with explicit interior `Boundary` records.
 */
function loadDraftPartition(state, rawSegments, rawBoundaries, durationUs, origin, eventId) {
  if (!Array.isArray(rawSegments) || rawSegments.length === 0) {
    const segId = deriveChildEntityId(eventId, 0, "seg_draft");
    initializeSingleSegmentPartition(state, durationUs, segId, origin, eventId);
    return;
  }

  /** @type {Array<Record<string, unknown>>} */
  const sortedRaw = rawSegments
    .filter((s) => s && typeof s === "object")
    .slice()
    .sort(
      (a, b) =>
        Number(a.startUs ?? 0) - Number(b.startUs ?? 0) ||
        Number(a.endUs ?? 0) - Number(b.endUs ?? 0) ||
        compareCodeUnits(String(a.id ?? ""), String(b.id ?? "")),
    );

  // Forbid authority injection (`origin: "user"` or `approval` unless author is user)
  for (const s of sortedRaw) {
    if (origin !== "user" && (s.origin === "user" || s.approval !== undefined)) {
      throw new EnsomiError(
        "AUTHORITY_VIOLATION",
        "Non-user draft_loaded cannot inject origin: \"user\" or approval on segments",
      );
    }
  }

  /** @type {Array<{ id: string, startUs: number, endUs: number, regime: any, kinematics?: any }>} */
  const slices = [];
  let cursor = 0;
  let fillerIdx = 0;
  for (const item of sortedRaw) {
    const s0 = Math.max(cursor, assertNonNegSafeIntUs(item.startUs ?? 0, "draft.segment.startUs"));
    const s1 = Math.min(
      durationUs,
      assertNonNegSafeIntUs(item.endUs ?? durationUs, "draft.segment.endUs"),
    );
    if (s0 > cursor) {
      slices.push({
        id: deriveChildEntityId(eventId, 0, `seg_fill_${fillerIdx++}`),
        startUs: cursor,
        endUs: s0,
        regime: "auto",
      });
      cursor = s0;
    }
    if (s1 <= cursor) continue;
    const id =
      typeof item.id === "string" && item.id.length > 0
        ? item.id
        : deriveChildEntityId(eventId, 0, `seg_d_${fillerIdx++}`);
    slices.push({
      id,
      startUs: cursor,
      endUs: s1,
      regime: item.regime ?? "auto",
      kinematics: item.kinematics,
    });
    cursor = s1;
  }
  if (cursor < durationUs) {
    slices.push({
      id: deriveChildEntityId(eventId, 0, `seg_fill_${fillerIdx++}`),
      startUs: cursor,
      endUs: durationUs,
      regime: "auto",
    });
  }
  if (slices.length === 0) {
    const segId = deriveChildEntityId(eventId, 0, "seg_draft");
    initializeSingleSegmentPartition(state, durationUs, segId, origin, eventId);
    return;
  }

  const rawBndByAtUs = new Map();
  if (Array.isArray(rawBoundaries)) {
    for (const rb of rawBoundaries) {
      if (!rb || typeof rb !== "object") continue;
      if (origin !== "user" && (rb.origin === "user" || rb.verdict !== undefined)) {
        throw new EnsomiError(
          "AUTHORITY_VIOLATION",
          "Non-user draft_loaded cannot inject origin: \"user\" or verdict on boundaries",
        );
      }
      rawBndByAtUs.set(rb.atUs, rb);
    }
  }

  /** @type {import("../index.d.ts").Boundary[]} */
  const boundaries = [];
  for (let i = 0; i < slices.length - 1; i += 1) {
    const cutUs = slices[i].endUs;
    const provided = rawBndByAtUs.get(cutUs);
    const bndId =
      typeof provided?.id === "string" && provided.id.length > 0
        ? provided.id
        : deriveChildEntityId(eventId, 0, `bnd_d_${i}`);
    registerFreshId(state, bndId, "boundary", eventId);
    const bnd = normalizeBoundary(
      {
        id: bndId,
        atUs: cutUs,
        origin,
        createdByEventId: eventId,
        revision: 1,
      },
      { durationUs },
    );
    state.boundaries.set(bndId, bnd);
    boundaries.push(bnd);
  }

  /** @type {import("../index.d.ts").Segment[]} */
  const segments = [];
  for (let i = 0; i < slices.length; i += 1) {
    const sl = slices[i];
    registerFreshId(state, sl.id, "segment", eventId);
    const seg = normalizeSegment(
      {
        id: sl.id,
        startUs: sl.startUs,
        endUs: sl.endUs,
        startBoundaryId: i === 0 ? null : boundaries[i - 1].id,
        endBoundaryId: i === slices.length - 1 ? null : boundaries[i].id,
        regime: sl.regime,
        kinematics: sl.kinematics,
        origin,
        createdByEventId: eventId,
        revision: 1,
      },
      { durationUs },
    );
    segments.push(seg);
  }
  state.segments = segments;
}

/**
 * Apply a single non-retracted `TraceEvent` onto `state`.
 * All entity mutations are atomic (rollback on failure).
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").TraceEvent} event
 */
function applyActiveEventAtomic(state, event) {
  const authorKind = event.author.kind;
  const actorOrigin = authorKindToOrigin(authorKind);
  const payload = /** @type {any} */ (event.payload);

  switch (event.type) {
    case "audio_bound": {
      const audio = normalizeAudioBinding(payload?.audio ?? payload);
      const prevAudio = state.audio;
      const changingTrackOrShrinking =
        prevAudio !== null &&
        (prevAudio.audioId !== audio.audioId || audio.durationUs < prevAudio.durationUs);

      if (changingTrackOrShrinking && hasActiveHumanTruth(state)) {
        throw new EnsomiError(
          "PROTECTED_HUMAN_TRUTH",
          "Cannot rebind audio over active human verdicts or approvals; commit reset_document first",
        );
      }

      if (prevAudio === null) {
        // Replace initial placeholder partition if untouched
        if (state.segments.length === 1 && state.segments[0].id === "init_seg_0") {
          retireAllActiveEntities(state, event.id);
        }
        state.audio = audio;
        state.durationUs = audio.durationUs;
        if (state.segments.length === 0) {
          initializeSingleSegmentPartition(
            state,
            audio.durationUs,
            deriveChildEntityId(event.id, 0, "seg_audio"),
            "rule",
            event.id,
          );
        }
      } else if (prevAudio.audioId !== audio.audioId || prevAudio.durationUs !== audio.durationUs) {
        // Unverified document with changed audioId or durationUs: reset cleanly while keeping retired IDs!
        retireAllActiveEntities(state, event.id);
        state.offers.clear();
        state.suppressions = [];
        state.audio = audio;
        state.durationUs = audio.durationUs;
        initializeSingleSegmentPartition(
          state,
          audio.durationUs,
          deriveChildEntityId(event.id, 0, "seg_audio"),
          "rule",
          event.id,
        );
      } else {
        // Same audioId and durationUs (e.g., attaching decodedPcmSha256 / sampleRateHz): preserve marks!
        state.audio = audio;
      }
      refreshOffersAndSuppressions(state);
      break;
    }

    case "reset_document": {
      if (authorKind !== "user" && authorKind !== "host") {
        throw new EnsomiError(
          "AUTHORITY_VIOLATION",
          "Only user or host may commit reset_document",
        );
      }
      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }
      const nextAudio = payload?.audio ? normalizeAudioBinding(payload.audio) : state.audio;
      retireAllActiveEntities(state, event.id);
      state.offers.clear();
      state.suppressedOffers = [];
      state.suppressions = [];
      state.ready = false;
      state.readyAtSeq = null;
      if (nextAudio) {
        state.audio = nextAudio;
        state.durationUs = nextAudio.durationUs;
        initializeSingleSegmentPartition(
          state,
          nextAudio.durationUs,
          deriveChildEntityId(event.id, 0, "seg_reset"),
          authorKind === "user" ? "user" : "rule",
          event.id,
        );
      } else if (state.durationUs > 0) {
        initializeSingleSegmentPartition(
          state,
          state.durationUs,
          deriveChildEntityId(event.id, 0, "seg_reset"),
          authorKind === "user" ? "user" : "rule",
          event.id,
        );
      }
      break;
    }

    case "draft_loaded": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_DRAFT", "draft_loaded payload must be an object");
      }
      if (state.durationUs <= 0) {
        throw new EnsomiError("AUDIO_NOT_BOUND", "audio_bound is required before draft_loaded");
      }
      const replace = payload.replace !== false;
      if (replace && hasActiveHumanTruth(state)) {
        throw new EnsomiError(
          "PROTECTED_HUMAN_TRUTH",
          "Cannot replace document with draft while human verdicts or approvals exist; use reset_document",
        );
      }

      // Run on a shallow copy first so any validation error in `draft_loaded` rolls back 100% atomically!
      const scratch = cloneSessionStateShallow(state);
      if (replace) {
        retireAllActiveEntities(scratch, event.id);
      }
      const draftOrigin = authorKind === "user" ? "import" : actorOrigin;
      if (replace || scratch.segments.length === 0) {
        loadDraftPartition(
          scratch,
          payload.segments,
          payload.boundaries,
          scratch.durationUs,
          draftOrigin,
          event.id,
        );
      }

      for (const rawSpan of payload.spans ?? []) {
        if (!rawSpan || typeof rawSpan !== "object") {
          throw new EnsomiError("INVALID_SPAN", "Draft span must be an object");
        }
        if (rawSpan.origin === "user" || rawSpan.approval !== undefined || rawSpan.kind === "keep") {
          throw new EnsomiError(
            "AUTHORITY_VIOLATION",
            "draft_loaded cannot inject origin: \"user\" or keep approval on spans",
          );
        }
        const seg = scratch.segments.find((s) => s.id === rawSpan.segmentId);
        if (!seg || rawSpan.startUs < seg.startUs || rawSpan.endUs > seg.endUs) {
          throw new EnsomiError(
            "INVALID_SPAN",
            `Draft span "${String(rawSpan.id)}" must lie inside parent segment "${String(rawSpan.segmentId)}"`,
          );
        }
        const spanId =
          typeof rawSpan.id === "string" && rawSpan.id.length > 0
            ? rawSpan.id
            : deriveChildEntityId(event.id, 0, `span_d_${scratch.spans.length}`);
        registerFreshId(scratch, spanId, "span", event.id);
        scratch.spans.push(
          normalizeSpan(
            {
              ...rawSpan,
              id: spanId,
              origin: draftOrigin,
              createdByEventId: event.id,
              revision: 1,
            },
            { durationUs: scratch.durationUs },
          ),
        );
      }
      scratch.spans.sort(
        (a, b) =>
          a.startUs - b.startUs ||
          a.endUs - b.endUs ||
          compareCodeUnits(a.kind, b.kind) ||
          compareCodeUnits(a.id, b.id),
      );

      for (const rawBeat of payload.beats ?? []) {
        if (!rawBeat || typeof rawBeat !== "object") {
          throw new EnsomiError("INVALID_BEAT", "Draft beat must be an object");
        }
        if (rawBeat.origin === "user" || rawBeat.verdict !== undefined) {
          throw new EnsomiError(
            "AUTHORITY_VIOLATION",
            "draft_loaded cannot inject origin: \"user\" or verdict on beats",
          );
        }
        registerFreshId(scratch, rawBeat.id, "beat", event.id);
        const beat = normalizeBeat(
          {
            ...rawBeat,
            origin: draftOrigin,
            createdByEventId: event.id,
            revision: 1,
          },
          { durationUs: scratch.durationUs },
        );
        scratch.beats.set(beat.id, beat);
      }

      for (const rawSound of payload.sounds ?? []) {
        if (!rawSound || typeof rawSound !== "object") {
          throw new EnsomiError("INVALID_SOUND", "Draft sound must be an object");
        }
        if (rawSound.origin === "user" || rawSound.verdict !== undefined) {
          throw new EnsomiError(
            "AUTHORITY_VIOLATION",
            "draft_loaded cannot inject origin: \"user\" or verdict on sounds",
          );
        }
        registerFreshId(scratch, rawSound.id, "sound", event.id);
        const sound = normalizeSound(
          {
            ...rawSound,
            origin: draftOrigin,
            createdByEventId: event.id,
            revision: 1,
          },
          { durationUs: scratch.durationUs },
        );
        scratch.sounds.set(sound.id, sound);
      }

      for (const rawSc of payload.soundClasses ?? []) {
        if (!rawSc || typeof rawSc !== "object") {
          throw new EnsomiError("INVALID_SOUND_CLASS", "Draft soundClass must be an object");
        }
        if (rawSc.origin === "user" || rawSc.verdict !== undefined) {
          throw new EnsomiError(
            "AUTHORITY_VIOLATION",
            "draft_loaded cannot inject origin: \"user\" or verdict on soundClasses",
          );
        }
        registerFreshId(scratch, rawSc.id, "sound_class", event.id);
        const sc = normalizeSoundClass({
          ...rawSc,
          origin: draftOrigin,
          createdByEventId: event.id,
          revision: 1,
        });
        scratch.soundClasses.set(sc.id, sc);
      }

      for (const rawUnbeat of payload.unbeats ?? []) {
        if (!rawUnbeat || typeof rawUnbeat !== "object") {
          throw new EnsomiError("INVALID_UNBEAT", "Draft unbeat must be an object");
        }
        if (rawUnbeat.origin === "user" || rawUnbeat.verdict !== undefined) {
          throw new EnsomiError(
            "AUTHORITY_VIOLATION",
            "draft_loaded cannot inject origin: \"user\" or verdict on unbeats",
          );
        }
        registerFreshId(scratch, rawUnbeat.id, "unbeat", event.id);
        const u = normalizeUnbeat(
          {
            ...rawUnbeat,
            origin: draftOrigin,
            createdByEventId: event.id,
            revision: 1,
            verdict: undefined,
          },
          { durationUs: scratch.durationUs },
        );
        scratch.unbeats.set(u.id, u);
      }

      // Commit scratch containers back to state
      state.segments = scratch.segments;
      state.boundaries = scratch.boundaries;
      state.spans = scratch.spans;
      state.beats = scratch.beats;
      state.unbeats = scratch.unbeats;
      state.sounds = scratch.sounds;
      state.soundClasses = scratch.soundClasses;
      state.approvals = scratch.approvals;
      state.idRegistry = scratch.idRegistry;
      refreshOffersAndSuppressions(state);
      break;
    }

    case "edit": {
      /** @type {import("../index.d.ts").TransactionOp[]} */
      let ops;
      if (Array.isArray(payload)) {
        ops = payload;
      } else if (payload && typeof payload === "object" && Array.isArray(payload.ops)) {
        ops = payload.ops;
      } else if (payload && typeof payload === "object" && typeof payload.op === "string") {
        ops = [payload];
      } else {
        throw new EnsomiError("INVALID_EDIT", "edit payload must be a TransactionOp or { ops }");
      }

      const preSuppressionRanges =
        authorKind === "user" && state.suppressions.length > 0 && state.durationUs > 0
          ? computeTouchedRangesForOps(state, ops, state.durationUs)
          : [];

      const actorMode = authorKind === "user" ? "user" : "offer";
      const { writeCount } = executeTransaction(state, ops, {
        txId: event.id,
        eventId: event.id,
        actorMode,
        actorOrigin,
      });

      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }

      // Only refresh pending offers/suppressions if offers/suppressions exist AND writes occurred!
      if (writeCount > 0 && (state.offers.size > 0 || state.suppressions.length > 0)) {
        refreshOffersAndSuppressions(state, preSuppressionRanges);
      }
      break;
    }

    case "signal": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_SIGNAL", "signal payload must be an object");
      }
      const scope =
        payload.scope === "song"
          ? "song"
          : assertRangeUs(
              payload.scope,
              state.durationUs > 0 ? state.durationUs : undefined,
            );
      const generation = assertNonNegSafeIntUs(
        payload.generation ?? state.generation + 1,
        "signal.generation",
        "INVALID_SIGNAL",
      );
      const reason =
        typeof payload.reason === "string" && payload.reason.length > 0
          ? payload.reason
          : "recompute";
      state.generation = Math.max(state.generation, generation);
      state.lastSignal = deepFreeze({
        layer: "heavy_recompute",
        scope,
        reason,
        generation,
      });
      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }
      break;
    }

    case "offer_batch": {
      if (!payload || typeof payload !== "object" || !Array.isArray(payload.offers)) {
        throw new EnsomiError("INVALID_OFFER", "offer_batch payload requires offers[]");
      }
      if (payload.waitUs !== undefined) {
        state.effort.waitUs += assertNonNegSafeIntUs(
          payload.waitUs,
          "offer_batch.waitUs",
          "INVALID_OFFER",
        );
      }
      for (const rawOffer of payload.offers) {
        assertOfferShape(rawOffer);
        if (state.offers.has(rawOffer.id)) {
          throw new EnsomiError("DUPLICATE_OFFER_ID", `Duplicate offer id "${rawOffer.id}"`);
        }
        const semanticKey = rawOffer.semanticKey ?? computeOfferSemanticKey(rawOffer);
        const normalized = deepFreeze({
          ...rawOffer,
          semanticKey,
        });
        if (isOfferSuppressed(state, normalized)) {
          state.suppressedOffers.push(normalized);
          continue;
        }
        const check = validateOffer(state, normalized, { ignoreTerminalStatus: true });
        state.offers.set(
          normalized.id,
          deepFreeze({
            ...normalized,
            status: check.status,
            conflicts: check.conflicts,
            notes: check.notes,
          }),
        );
      }
      break;
    }

    case "offer_accept": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_OFFER", "offer_accept payload must be an object");
      }
      /** @type {string[]} */
      const offerIds = Array.isArray(payload.offerIds)
        ? payload.offerIds
        : typeof payload.offerId === "string"
          ? [payload.offerId]
          : [];
      if (offerIds.length === 0) {
        throw new EnsomiError(
          "INVALID_OFFER",
          "offer_accept requires offerIds: string[] or offerId: string",
        );
      }
      const authorizedChallenges = Array.isArray(payload.authorizedChallenges)
        ? payload.authorizedChallenges
        : [];

      // Validate and apply all offers in `offerIds` atomically on a shallow copy first!
      const scratch = cloneSessionStateShallow(state);

      for (const offerId of offerIds) {
        const offer = scratch.offers.get(offerId);
        if (!offer) {
          throw new EnsomiError("MISSING_OFFER", `Offer "${offerId}" does not exist`);
        }
        const { validation, beforeState, afterState } = evaluateOfferInternal(scratch, offer);
        if (validation.status !== "valid" && validation.status !== "requires_challenge_confirm") {
          throw new EnsomiError(
            "OFFER_NOT_ACCEPTABLE",
            `Offer "${offerId}" cannot be accepted in status "${validation.status}"`,
            { offerId, status: validation.status, conflicts: validation.conflicts },
          );
        }

        // Verify all required challenges are explicitly authorized!
        if (validation.status === "requires_challenge_confirm") {
          for (const reqCh of validation.requiredChallenges) {
            const auth = authorizedChallenges.find(
              (a) =>
                a &&
                a.offerId === offer.id &&
                a.challengeId === reqCh.challengeId &&
                a.targetId === reqCh.targetId &&
                a.expectedValueDigest === reqCh.expectedValueDigest,
            );
            if (!auth || !CHALLENGE_DISPOSITION_SET.has(auth.disposition)) {
              throw new EnsomiError(
                "CHALLENGE_CONFIRM_REQUIRED",
                `Offer "${offerId}" modifies verified ${reqCh.targetKind} "${reqCh.targetId}" and requires challenge authorization`,
                { offerId, challengeId: reqCh.challengeId, targetId: reqCh.targetId },
              );
            }
          }
        }

        // Adopt post-transaction entity tables from `afterState`
        scratch.segments = afterState.segments;
        scratch.boundaries = afterState.boundaries;
        scratch.spans = afterState.spans;
        scratch.beats = afterState.beats;
        scratch.unbeats = afterState.unbeats;
        scratch.sounds = afterState.sounds;
        scratch.soundClasses = afterState.soundClasses;
        scratch.approvals = afterState.approvals;
        scratch.idRegistry = afterState.idRegistry;

        // Apply authorized challenge verdict dispositions (`confirm_replacement` | `clear_verdict`)
        for (const reqCh of validation.requiredChallenges) {
          const auth = authorizedChallenges.find(
            (a) => a.offerId === offer.id && a.challengeId === reqCh.challengeId,
          );
          if (!auth) continue;
          const beforeEntity = getVerdictEntity(beforeState, reqCh.targetKind, reqCh.targetId);
          const afterEntity = getVerdictEntity(scratch, reqCh.targetKind, reqCh.targetId);

          if (auth.disposition === "clear_verdict") {
            if (afterEntity && afterEntity.verdict) {
              const { verdict: _v, ...rest } = afterEntity;
              const cleared = deepFreeze({ ...rest });
              if (reqCh.targetKind === "beat") scratch.beats.set(reqCh.targetId, cleared);
              else if (reqCh.targetKind === "unbeat") scratch.unbeats.set(reqCh.targetId, cleared);
              else if (reqCh.targetKind === "sound") scratch.sounds.set(reqCh.targetId, cleared);
              else if (reqCh.targetKind === "sound_class")
                scratch.soundClasses.set(reqCh.targetId, cleared);
              else if (reqCh.targetKind === "boundary")
                scratch.boundaries.set(reqCh.targetId, cleared);
            }
          } else if (auth.disposition === "confirm_replacement") {
            let targetAfter = afterEntity;
            let targetIdAfter = reqCh.targetId;

            if (!targetAfter && reqCh.replacementId) {
              targetAfter = getVerdictEntity(scratch, reqCh.targetKind, reqCh.replacementId);
              targetIdAfter = reqCh.replacementId;
            } else if (!targetAfter && reqCh.targetKind === "beat" && beforeEntity) {
              // Find nearest newly inserted beat in `afterState` within writeRanges if unique/closest
              let bestBeat = null;
              let bestDist = Number.MAX_SAFE_INTEGER;
              for (const [bid, bCand] of scratch.beats) {
                if (beforeState.beats.has(bid)) continue;
                const d = Math.abs(bCand.atUs - beforeEntity.atUs);
                if (d < bestDist) {
                  bestDist = d;
                  bestBeat = bCand;
                }
              }
              if (bestBeat) {
                targetAfter = bestBeat;
                targetIdAfter = bestBeat.id;
              }
            }

            if (targetAfter) {
              const facet =
                reqCh.targetKind === "sound"
                  ? beforeEntity?.verdict?.facet ?? "occurrence"
                  : undefined;
              const tolUs = beforeEntity?.verdict?.tolUs ?? DEFAULT_BEAT_TOL_US;
              const vd = computeEntityValueDigest(reqCh.targetKind, targetAfter, facet);
              const confirmed = deepFreeze({
                ...targetAfter,
                verdict: deepFreeze({
                  kind: "confirmed",
                  eventId: event.id,
                  valueDigest: vd,
                  tolUs,
                  ...(facet ? { facet } : {}),
                  via: "challenge_accept",
                }),
              });
              if (reqCh.targetKind === "beat") scratch.beats.set(targetIdAfter, confirmed);
              else if (reqCh.targetKind === "unbeat") scratch.unbeats.set(targetIdAfter, confirmed);
              else if (reqCh.targetKind === "sound") scratch.sounds.set(targetIdAfter, confirmed);
              else if (reqCh.targetKind === "sound_class")
                scratch.soundClasses.set(targetIdAfter, confirmed);
              else if (reqCh.targetKind === "boundary")
                scratch.boundaries.set(targetIdAfter, confirmed);
            }
          }
        }

        // Mark offer accepted and supersede ONLY offers sharing the same non-null `alternativeGroup`!
        scratch.offers.set(
          offer.id,
          deepFreeze({
            ...offer,
            status: "accepted",
            conflicts: [],
          }),
        );
        if (offer.alternativeGroup) {
          for (const [sid, sib] of scratch.offers) {
            if (
              sid !== offer.id &&
              sib.alternativeGroup === offer.alternativeGroup &&
              sib.status !== "accepted" &&
              sib.status !== "rejected" &&
              sib.status !== "superseded"
            ) {
              scratch.offers.set(
                sid,
                deepFreeze({
                  ...sib,
                  status: "superseded",
                  conflicts: [
                    {
                      kind: "sibling_conflict",
                      message: "Another alternative for this passage was applied.",
                    },
                  ],
                }),
              );
            }
          }
        }

        refreshOffersAndSuppressions(scratch);
      }

      // Commit all changes atomically
      state.segments = scratch.segments;
      state.boundaries = scratch.boundaries;
      state.spans = scratch.spans;
      state.beats = scratch.beats;
      state.unbeats = scratch.unbeats;
      state.sounds = scratch.sounds;
      state.soundClasses = scratch.soundClasses;
      state.approvals = scratch.approvals;
      state.idRegistry = scratch.idRegistry;
      state.offers = scratch.offers;
      state.suppressions = scratch.suppressions;
      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }
      break;
    }

    case "offer_reject": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_OFFER", "offer_reject payload must be an object");
      }
      const offerId = assertNonEmptyId(payload.offerId, "offer_reject.offerId", "INVALID_OFFER");
      const offer = state.offers.get(offerId);
      if (!offer) {
        throw new EnsomiError("MISSING_OFFER", `Offer "${offerId}" does not exist`);
      }
      if (
        offer.status === "accepted" ||
        offer.status === "rejected" ||
        offer.status === "superseded"
      ) {
        throw new EnsomiError(
          "OFFER_NOT_REJECTABLE",
          `Offer "${offerId}" with status "${offer.status}" cannot be dismissed`,
        );
      }
      const semanticKey = offer.semanticKey ?? computeOfferSemanticKey(offer);
      state.offers.set(
        offerId,
        deepFreeze({
          ...offer,
          status: "rejected",
        }),
      );
      state.suppressions.push(
        deepFreeze({
          semanticKey,
          writeRanges: offer.writeRanges,
          rejectedAtSeq: event.seq,
          offerId,
        }),
      );
      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }
      break;
    }

    case "listen_effort": {
      if (!payload || typeof payload !== "object") {
        throw new EnsomiError("INVALID_EVENT", "listen_effort payload must be an object");
      }
      if (payload.listenUs !== undefined) {
        state.effort.listenUs += assertNonNegSafeIntUs(
          payload.listenUs,
          "listen_effort.listenUs",
          "INVALID_EVENT",
        );
      }
      if (payload.waitUs !== undefined) {
        state.effort.waitUs += assertNonNegSafeIntUs(
          payload.waitUs,
          "listen_effort.waitUs",
          "INVALID_EVENT",
        );
      }
      if (payload.range !== undefined) {
        state.visitedRanges.push(
          deepFreeze(
            assertRangeUs(
              payload.range,
              state.durationUs > 0 ? state.durationUs : undefined,
            ),
          ),
        );
      }
      break;
    }

    case "ready": {
      state.ready = payload?.ready !== false;
      state.readyAtSeq = state.ready ? event.seq : null;
      if (authorKind === "user") {
        state.effort.actionCount += 1;
      }
      break;
    }

    default:
      throw new EnsomiError("INVALID_EVENT", `Unhandled TraceEvent type: ${event.type}`);
  }
}

/**
 * Validate and resolve the backward-referenced target of a `retract` event.
 *
 * @param {import("../index.d.ts").TraceEvent} retractEvent
 * @param {Map<string, import("../index.d.ts").TraceEvent>} byId
 * @param {Map<number, import("../index.d.ts").TraceEvent>} bySeq
 * @returns {import("../index.d.ts").TraceEvent}
 */
function resolveRetractTarget(retractEvent, byId, bySeq) {
  const payload = /** @type {Record<string, unknown>} */ (retractEvent.payload);
  if (!payload || typeof payload !== "object") {
    throw new EnsomiError("INVALID_RETRACTION", "Retract payload must be an object");
  }
  let target = null;
  if (typeof payload.targetEventId === "string" && payload.targetEventId.length > 0) {
    target = byId.get(payload.targetEventId) ?? null;
  } else if (typeof payload.targetSeq === "number" && Number.isSafeInteger(payload.targetSeq)) {
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
 * Resolve active retractions newest-first (supporting nested retract-of-retract / redo to arbitrary depth).
 *
 * @param {readonly import("../index.d.ts").TraceEvent[]} events
 * @returns {Set<string>}
 */
export function resolveActiveRetractions(events) {
  /** @type {Map<string, import("../index.d.ts").TraceEvent>} */
  const byId = new Map();
  /** @type {Map<number, import("../index.d.ts").TraceEvent>} */
  const bySeq = new Map();
  for (let i = 0; i < events.length; i += 1) {
    const ev = events[i];
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
 * Deterministically reconstruct `undoStack` and `redoStack` directly from trace events.
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
  const activeRetractedIds = resolveActiveRetractions(events);

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
  let currentAudioId = null;

  for (let i = 0; i < events.length; i += 1) {
    const ev = events[i];
    byId.set(ev.id, ev);
    bySeq.set(ev.seq, ev);

    if (ev.type === "audio_bound" && !activeRetractedIds.has(ev.id)) {
      const rawAudio = /** @type {any} */ (ev.payload)?.audio ?? ev.payload;
      const nextAudioId = typeof rawAudio?.audioId === "string" ? rawAudio.audioId : null;
      if (currentAudioId !== null && nextAudioId !== null && nextAudioId !== currentAudioId) {
        undoableRoots.clear();
        undoStack.length = 0;
        redoStack.length = 0;
      }
      if (nextAudioId !== null) currentAudioId = nextAudioId;
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

      let kids = childrenOf.get(target.id);
      if (!kids) {
        kids = [];
        childrenOf.set(target.id, kids);
      }
      kids.push(ev.id);

      /** @type {string | undefined} */
      let curr = target.id;
      while (curr !== undefined) {
        const cKids = childrenOf.get(curr) ?? [];
        const nextEff = !cKids.some((cid) => effective.get(cid) === true);
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
          if (undoIdx >= 0) undoStack.splice(undoIdx, 1);
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
 * Apply a single `TraceEvent` to `SessionState`.
 *  - Live `applyEvent` throws `EnsomiError` on malformed input and appends nothing.
 *  - `"retract"` events delegate to `foldTrace` so selective undo/redo is event-atomic
 *    and 100% equivalent to full replay.
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
    return foldTrace([...state.events, clonedEvent]);
  }

  const next = cloneSessionStateShallow(state);
  next.events.push(clonedEvent);
  next.eventIds.add(clonedEvent.id);
  next.lastSeq = clonedEvent.seq;
  applyActiveEventAtomic(next, clonedEvent);
  return next;
}

/**
 * Replay a full sequence of `TraceEvent`s from event 0 into a materialized `SessionState`.
 *
 * Event-Atomic Selective `Retract`:
 *  - Resolves active retractions newest-first (`resolveActiveRetractions`).
 *  - Executes each non-retracted transaction atomically via rollback journal / scratch state.
 *  - If an earlier event before `ev` was retracted AND `ev` fails a precondition
 *    (`MISSING_ENTITY`, `REVISION_MISMATCH`, `DUPLICATE_ENTITY_ID`, `INVALID_SEGMENT_EDIT`,
 *    `AUDIO_NOT_BOUND`, `OUT_OF_BOUNDS`, `OFFER_NOT_ACCEPTABLE`, etc.), **aborts and skips
 *    the ENTIRE dependent transaction atomically** (zero partial ops!) and records an
 *    `OrphanedTransaction` entry with `code: "ORPHANED_BY_RETRACT"`.
 *  - When NO earlier event was retracted, malformed events throw `EnsomiError` directly!
 *
 * @param {readonly import("../index.d.ts").TraceEvent[]} events
 * @param {{ durationUs?: number }} [options]
 * @returns {import("../index.d.ts").SessionState}
 */
export function foldTrace(events, options = {}) {
  if (!Array.isArray(events)) {
    throw new EnsomiError("INVALID_TRACE", "events must be an array");
  }

  let prevSeq = -1;
  const seenIds = new Set();
  /** @type {import("../index.d.ts").TraceEvent[]} */
  const clonedEvents = new Array(events.length);
  /** @type {Map<string, import("../index.d.ts").TraceEvent>} */
  const byId = new Map();
  /** @type {Map<number, import("../index.d.ts").TraceEvent>} */
  const bySeq = new Map();

  for (let i = 0; i < events.length; i += 1) {
    const ev = events[i];
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
    clonedEvents[i] = cloned;
    byId.set(cloned.id, cloned);
    bySeq.set(cloned.seq, cloned);
  }

  const retractedIds = resolveActiveRetractions(clonedEvents);
  const state = createInitialState(options);
  state.retractedEventIds = retractedIds;

  let seenRetractedEarlier = false;

  for (let i = 0; i < clonedEvents.length; i += 1) {
    const ev = clonedEvents[i];
    state.events.push(ev);
    state.eventIds.add(ev.id);
    state.lastSeq = ev.seq;

    if (ev.type === "retract") {
      resolveRetractTarget(ev, byId, bySeq);
      if (!retractedIds.has(ev.id)) {
        if (ev.author.kind === "user") {
          state.effort.actionCount += 1;
        }
        refreshOffersAndSuppressions(state);
      }
      continue;
    }

    if (retractedIds.has(ev.id)) {
      seenRetractedEarlier = true;
      continue;
    }

    try {
      applyActiveEventAtomic(state, ev);
    } catch (err) {
      if (
        seenRetractedEarlier &&
        err instanceof EnsomiError &&
        ORPHANABLE_ERROR_CODES.has(err.code)
      ) {
        state.orphanedTransactions.push(
          deepFreeze({
            seq: ev.seq,
            eventId: ev.id,
            type: ev.type,
            code: "ORPHANED_BY_RETRACT",
            causeCode: err.code,
            message: err.message,
          }),
        );
        continue;
      }
      throw err;
    }
  }

  return state;
}

/**
 * Serialize `TraceEvent[]` to append-only NDJSON (`.jsonl`).
 *
 * @param {readonly import("../index.d.ts").TraceEvent[]} events
 * @returns {string}
 */
export function serializeTraceNdjson(events) {
  if (!Array.isArray(events)) {
    throw new EnsomiError("INVALID_TRACE", "events must be an array");
  }
  if (events.length === 0) return "";
  return `${events.map((ev) => canonicalJson(ev)).join("\n")}\n`;
}

/**
 * Parse an NDJSON (`.jsonl`) trace string into validated `TraceEvent`s (`schemaVersion: "0.3"`).
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

    events.push(parsed);
  }

  return events;
}

/**
 * Create a verifiable RFC 8785 (JCS) SHA-256 checkpoint from `SessionState`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").TraceCheckpoint}
 */
export function createCheckpoint(state) {
  const snapshot = toSnapshot(state);
  const stateHash = `sha256:${jcsSha256Hex(snapshot)}`;
  return deepFreeze({
    schemaVersion: TRACE_SCHEMA_VERSION,
    lastSeq: state.lastSeq,
    eventCount: state.events.length,
    stateHash,
    snapshot,
  });
}

/**
 * Verify a `TraceCheckpoint` against the `prefixEvents` that produced it.
 *
 * @param {import("../index.d.ts").TraceCheckpoint} checkpoint
 * @param {readonly import("../index.d.ts").TraceEvent[]} prefixEvents
 * @returns {import("../index.d.ts").CheckpointVerification}
 */
export function verifyCheckpoint(checkpoint, prefixEvents) {
  const refState = foldTrace(prefixEvents);
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
      reason: `schemaVersion mismatch: checkpoint=${String(checkpoint.schemaVersion)}, expected=${TRACE_SCHEMA_VERSION}`,
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

  const snapshotHash = `sha256:${jcsSha256Hex(checkpoint.snapshot)}`;
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

export {
  computeSnapshotDigest,
  createInitialState,
  createProviderView,
  hasActiveHumanTruth,
  serializeState,
  toSnapshot,
};
