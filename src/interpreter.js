/**
 * Unified Pure Transaction Interpreter (`executeTransaction`) with:
 *  - Single document-wide Global ID Registry (`beat`, `unbeat`, `sound`, `sound_class`,
 *    `boundary`, `segment`, `span`, `approval`) — both active AND retired IDs block reuse!
 *  - Deterministic child ID derivation `deriveChildEntityId(txId, opIndex, childSlot)`
 *    (NEVER derived from mutable `event.seq`!)
 *  - Semantic no-op rule (zero writes / revision bumps when post-value equals pre-value)
 *  - O(ops) savepoint rollback journal for event-atomic rollback on error / selective retract
 *  - Orthogonal `Origin` + `Verdict` + `Approval` (`modifiedSinceApproval` tracking)
 */

import { canonicalJson, compareCodeUnits, EnsomiError, sha256Hex } from "./jcs.js";
import {
  assertInteriorPointUs,
  assertIntervalUs,
  assertNonNegSafeIntUs,
  assertPointUs,
  assertRangeUs,
  assertSafeIntUs,
  mergeRangesUs,
  subtractRangeUs,
} from "./time.js";
import {
  assertNonEmptyId,
  assertPositiveRevision,
  computeEntityValueDigest,
  DEFAULT_BEAT_TOL_US,
  DEFAULT_BOUNDARY_TOL_US,
  DEFAULT_SNAP_BEAT_TOL_US,
  DEFAULT_SOUND_TOL_US,
  DEFAULT_UNBEAT_TOL_US,
  deepFreeze,
  isOfferOpKind,
  isTransactionOpKind,
  normalizeApproval,
  normalizeBeat,
  normalizeBeatBinding,
  normalizeBeatStructural,
  normalizeBoundary,
  normalizeSegment,
  normalizeSound,
  normalizeSoundClass,
  normalizeSpan,
  normalizeUnbeat,
  SEGMENT_KINEMATICS,
  SEGMENT_REGIMES,
  validateSoundFacet,
  VERDICT_TARGET_KINDS,
} from "./types.js";
import { computeIntervalMusicalDigest } from "./views.js";

const SEGMENT_REGIME_SET = new Set(SEGMENT_REGIMES);
const SEGMENT_KINEMATICS_SET = new Set(SEGMENT_KINEMATICS);
const VERDICT_TARGET_KIND_SET = new Set(VERDICT_TARGET_KINDS);

/**
 * Derive a deterministic child entity ID purely from `(txId, opIndex, childSlot)` —
 * NEVER from mutable `event.seq`.
 *
 * @param {string} txId
 * @param {number} opIndex
 * @param {string} childSlot
 * @returns {string}
 */
export function deriveChildEntityId(txId, opIndex, childSlot) {
  const digest = sha256Hex(`${txId}:${opIndex}:${childSlot}`).slice(0, 12);
  return `${childSlot}_${digest}`;
}

/**
 * Strip `revision` so semantic equality checks ignore revision counter.
 * @param {Record<string, unknown>} entity
 */
function withoutRevision(entity) {
  const { revision: _rev, ...rest } = entity;
  return rest;
}

function entitiesSemanticallyEqual(a, b) {
  if (a === b) return true;
  if (!a || !b) return false;
  return canonicalJson(withoutRevision(a)) === canonicalJson(withoutRevision(b));
}

/**
 * Create an O(ops) rollback journal over `state` so failed transactions leave `state`
 * 100% untouched without O(N) whole-map copies on every event during `foldTrace`.
 *
 * @param {import("../index.d.ts").SessionState} state
 */
function createJournal(state) {
  /** @type {Map<string, any> | null} */ let beatsUndo = null;
  /** @type {Map<string, any> | null} */ let unbeatsUndo = null;
  /** @type {Map<string, any> | null} */ let soundsUndo = null;
  /** @type {Map<string, any> | null} */ let soundClassesUndo = null;
  /** @type {Map<string, any> | null} */ let boundariesUndo = null;
  /** @type {Map<string, any> | null} */ let approvalsUndo = null;
  /** @type {Map<string, any> | null} */ let idRegistryUndo = null;
  /** @type {import("../index.d.ts").Segment[] | null} */
  let savedSegments = null;
  /** @type {import("../index.d.ts").Span[] | null} */
  let savedSpans = null;
  let writeCount = 0;
  /** @type {Set<string> | null} */
  let touchedApprovalIds = null;

  const setMap = (table, map, key, val) => {
    if (!table.has(key)) {
      table.set(key, map.has(key) ? map.get(key) : undefined);
    }
    map.set(key, val);
    writeCount += 1;
  };

  const deleteMap = (table, map, key) => {
    if (!map.has(key)) return;
    if (!table.has(key)) {
      table.set(key, map.get(key));
    }
    map.delete(key);
    writeCount += 1;
  };

  return {
    get writeCount() {
      return writeCount;
    },
    hasTouchedApproval(id) {
      return touchedApprovalIds !== null && touchedApprovalIds.has(id);
    },
    ensureSegmentsMutable() {
      if (savedSegments === null) {
        savedSegments = state.segments.slice();
      }
      writeCount += 1;
    },
    ensureSpansMutable() {
      if (savedSpans === null) {
        savedSpans = state.spans.slice();
      }
      writeCount += 1;
    },
    registerId(id, kind, eventId) {
      assertNonEmptyId(id, `${kind}.id`, "INVALID_ID");
      const existing = state.idRegistry.get(id);
      if (existing) {
        throw new EnsomiError(
          "DUPLICATE_ENTITY_ID",
          existing.active
            ? `Entity id "${id}" is already active as "${existing.kind}"`
            : `Entity id "${id}" was previously retired (${existing.kind}) and cannot be reused`,
          { id, existingKind: existing.kind, requestedKind: kind, active: existing.active },
        );
      }
      if (idRegistryUndo === null) idRegistryUndo = new Map();
      setMap(
        idRegistryUndo,
        state.idRegistry,
        id,
        deepFreeze({
          id,
          kind,
          active: true,
          createdByEventId: eventId,
        }),
      );
    },
    assertUpsertableId(id, kind, eventId) {
      assertNonEmptyId(id, `${kind}.id`, "INVALID_ID");
      const existing = state.idRegistry.get(id);
      if (!existing) {
        this.registerId(id, kind, eventId);
        return false; // created
      }
      if (existing.kind !== kind) {
        throw new EnsomiError(
          "DUPLICATE_ENTITY_ID",
          `Entity id "${id}" belongs to kind "${existing.kind}", cannot upsert as "${kind}"`,
          { id, existingKind: existing.kind, requestedKind: kind },
        );
      }
      if (!existing.active) {
        throw new EnsomiError(
          "DUPLICATE_ENTITY_ID",
          `Entity id "${id}" (${kind}) was previously retired and cannot be reused`,
          { id, kind },
        );
      }
      return true; // existing active
    },
    retireId(id, eventId) {
      const existing = state.idRegistry.get(id);
      if (!existing || !existing.active) return;
      if (idRegistryUndo === null) idRegistryUndo = new Map();
      setMap(
        idRegistryUndo,
        state.idRegistry,
        id,
        deepFreeze({
          ...existing,
          active: false,
          retiredByEventId: eventId,
        }),
      );
    },
    setBeat(id, beat) {
      if (beatsUndo === null) beatsUndo = new Map();
      setMap(beatsUndo, state.beats, id, beat);
    },
    deleteBeat(id, eventId) {
      if (beatsUndo === null) beatsUndo = new Map();
      deleteMap(beatsUndo, state.beats, id);
      this.retireId(id, eventId);
    },
    setUnbeat(id, unbeat) {
      if (unbeatsUndo === null) unbeatsUndo = new Map();
      setMap(unbeatsUndo, state.unbeats, id, unbeat);
    },
    deleteUnbeat(id, eventId) {
      if (unbeatsUndo === null) unbeatsUndo = new Map();
      deleteMap(unbeatsUndo, state.unbeats, id);
      this.retireId(id, eventId);
    },
    setSound(id, sound) {
      if (soundsUndo === null) soundsUndo = new Map();
      setMap(soundsUndo, state.sounds, id, sound);
    },
    deleteSound(id, eventId) {
      if (soundsUndo === null) soundsUndo = new Map();
      deleteMap(soundsUndo, state.sounds, id);
      this.retireId(id, eventId);
    },
    setSoundClass(id, sc) {
      if (soundClassesUndo === null) soundClassesUndo = new Map();
      setMap(soundClassesUndo, state.soundClasses, id, sc);
    },
    deleteSoundClass(id, eventId) {
      if (soundClassesUndo === null) soundClassesUndo = new Map();
      deleteMap(soundClassesUndo, state.soundClasses, id);
      this.retireId(id, eventId);
    },
    setBoundary(id, bnd) {
      if (boundariesUndo === null) boundariesUndo = new Map();
      setMap(boundariesUndo, state.boundaries, id, bnd);
    },
    deleteBoundary(id, eventId) {
      if (boundariesUndo === null) boundariesUndo = new Map();
      deleteMap(boundariesUndo, state.boundaries, id);
      this.retireId(id, eventId);
    },
    setApproval(id, app) {
      if (approvalsUndo === null) approvalsUndo = new Map();
      if (touchedApprovalIds === null) touchedApprovalIds = new Set();
      setMap(approvalsUndo, state.approvals, id, app);
      touchedApprovalIds.add(id);
    },
    deleteApproval(id, eventId) {
      if (approvalsUndo === null) approvalsUndo = new Map();
      if (touchedApprovalIds === null) touchedApprovalIds = new Set();
      deleteMap(approvalsUndo, state.approvals, id);
      this.retireId(id, eventId);
      touchedApprovalIds.add(id);
    },
    rollback() {
      const restoreMap = (saved, target) => {
        if (saved === null) return;
        for (const [k, v] of saved) {
          if (v === undefined) target.delete(k);
          else target.set(k, v);
        }
      };
      restoreMap(beatsUndo, state.beats);
      restoreMap(unbeatsUndo, state.unbeats);
      restoreMap(soundsUndo, state.sounds);
      restoreMap(soundClassesUndo, state.soundClasses);
      restoreMap(boundariesUndo, state.boundaries);
      restoreMap(approvalsUndo, state.approvals);
      restoreMap(idRegistryUndo, state.idRegistry);
      if (savedSegments !== null) state.segments = savedSegments;
      if (savedSpans !== null) state.spans = savedSpans;
    },
  };
}

function assertAudioBoundDuration(state) {
  if (!state.durationUs || state.durationUs <= 0) {
    throw new EnsomiError(
      "AUDIO_NOT_BOUND",
      "Document has durationUs === 0; bind audio before applying mark or segment edits",
    );
  }
  return state.durationUs;
}

function assertNoNonUserAuthorityFields(raw, actorMode, label) {
  if (actorMode === "user") return;
  if (!raw || typeof raw !== "object") return;
  const r = /** @type {Record<string, unknown>} */ (raw);
  if (r.origin === "user") {
    throw new EnsomiError(
      "AUTHORITY_VIOLATION",
      `Non-user transaction cannot set origin: "user" on ${label}`,
    );
  }
  if (r.verdict !== undefined) {
    throw new EnsomiError(
      "AUTHORITY_VIOLATION",
      `Non-user transaction cannot inject verdict on ${label}`,
    );
  }
  if (r.approval !== undefined) {
    throw new EnsomiError(
      "AUTHORITY_VIOLATION",
      `Non-user transaction cannot inject approval on ${label}`,
    );
  }
}

function sortSpansInPlace(spans) {
  spans.sort(
    (a, b) =>
      a.startUs - b.startUs ||
      a.endUs - b.endUs ||
      compareCodeUnits(a.kind, b.kind) ||
      compareCodeUnits(a.id, b.id),
  );
}

/**
 * Evaluate whether a shifted beat's `boundTo` anchor alignment has drifted beyond tolerance.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {import("../index.d.ts").BeatBinding} boundTo
 * @param {number} nextAtUs
 * @param {number} deltaUs
 * @param {number | undefined} verdictTolUs
 * @returns {import("../index.d.ts").BeatBinding}
 */
function updateBeatBindingAfterShift(state, boundTo, nextAtUs, deltaUs, verdictTolUs) {
  const sound = boundTo.soundId ? state.sounds.get(boundTo.soundId) : undefined;
  let anchor = null;
  if (sound) {
    anchor =
      (boundTo.anchorId ? sound.anchors.find((a) => a.id === boundTo.anchorId) : null) ??
      sound.anchors.find((a) => a.role === boundTo.anchorRole) ??
      sound.anchors.find((a) => a.id === sound.primaryAnchorId) ??
      sound.anchors[0];
  }
  const tolUs = verdictTolUs ?? anchor?.sigmaUs ?? DEFAULT_SOUND_TOL_US;
  const isStale = anchor
    ? Math.abs(nextAtUs - anchor.atUs) > tolUs
    : Math.abs(deltaUs) > tolUs || Boolean(boundTo.alignmentStale);

  return normalizeBeatBinding({
    soundId: boundTo.soundId,
    candidateEventId: boundTo.candidateEventId,
    anchorId: boundTo.anchorId,
    anchorRole: boundTo.anchorRole,
    alignmentStale: isStale ? true : undefined,
  });
}

/**
 * Look up a verdict-target entity (`beat | unbeat | sound | sound_class | boundary`) in `state`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} kind
 * @param {string} id
 * @returns {any}
 */
export function getVerdictEntity(state, kind, id) {
  switch (kind) {
    case "beat":
      return state.beats.get(id);
    case "unbeat":
      return state.unbeats.get(id);
    case "sound":
      return state.sounds.get(id);
    case "sound_class":
      return state.soundClasses.get(id);
    case "boundary":
      return state.boundaries.get(id);
    default:
      return undefined;
  }
}

function setVerdictEntityInJournal(journal, kind, id, entity) {
  switch (kind) {
    case "beat":
      journal.setBeat(id, entity);
      break;
    case "unbeat":
      journal.setUnbeat(id, entity);
      break;
    case "sound":
      journal.setSound(id, entity);
      break;
    case "sound_class":
      journal.setSoundClass(id, entity);
      break;
    case "boundary":
      journal.setBoundary(id, entity);
      break;
    default:
      throw new EnsomiError("INVALID_ENTITY_KIND", `Unsupported kind: ${kind}`);
  }
}

/**
 * Split a single segment at `atUs` (`seg.startUs < atUs < seg.endUs`), maintaining `Boundary`,
 * `Segment` partition, and `Span ⊆ Segment` invariants with deterministic child IDs.
 */
function applySplitSegmentInternal(
  state,
  journal,
  segIdx,
  atUs,
  boundaryId,
  rightSegmentId,
  rightRegime,
  rightKinematics,
  ctx,
  opIndex,
  slotPrefix = "",
) {
  const seg = state.segments[segIdx];
  if (atUs <= seg.startUs || atUs >= seg.endUs) {
    throw new EnsomiError(
      "INVALID_SEGMENT_EDIT",
      `split_segment atUs (${atUs}) must lie strictly inside segment "${seg.id}" (${seg.startUs}..${seg.endUs})`,
    );
  }
  journal.registerId(boundaryId, "boundary", ctx.eventId);
  journal.registerId(rightSegmentId, "segment", ctx.eventId);

  /** @type {import("../index.d.ts").Boundary} */
  let boundary = normalizeBoundary({
    id: boundaryId,
    atUs,
    origin: ctx.actorOrigin,
    createdByEventId: ctx.eventId,
    revision: 1,
  });
  if (ctx.actorMode === "user") {
    const vd = computeEntityValueDigest("boundary", boundary);
    boundary = normalizeBoundary({
      ...boundary,
      verdict: {
        kind: "placed",
        eventId: ctx.eventId,
        valueDigest: vd,
        tolUs: DEFAULT_BOUNDARY_TOL_US,
        via: "tap",
      },
    });
  }
  journal.setBoundary(boundaryId, boundary);

  /** @type {import("../index.d.ts").Approval | undefined} */
  let leftApproval = seg.approval;
  /** @type {import("../index.d.ts").Approval | undefined} */
  let rightApproval;
  if (seg.approval) {
    leftApproval = normalizeApproval({
      ...seg.approval,
      modifiedSinceApproval: true,
    });
    journal.setApproval(leftApproval.id, leftApproval);

    const rightAppId = deriveChildEntityId(ctx.txId, opIndex, `${slotPrefix}app_split`);
    journal.registerId(rightAppId, "approval", ctx.eventId);
    rightApproval = normalizeApproval({
      id: rightAppId,
      eventId: ctx.eventId,
      contentDigest: seg.approval.contentDigest,
      modifiedSinceApproval: true,
    });
    journal.setApproval(rightAppId, rightApproval);
  }

  const leftSeg = normalizeSegment({
    ...seg,
    endUs: atUs,
    endBoundaryId: boundaryId,
    revision: seg.revision + 1,
    approval: leftApproval,
  });

  const resolvedRightKinematics =
    rightKinematics === undefined ? seg.kinematics : rightKinematics ?? undefined;

  const rightSeg = normalizeSegment({
    id: rightSegmentId,
    startUs: atUs,
    endUs: seg.endUs,
    startBoundaryId: boundaryId,
    endBoundaryId: seg.endBoundaryId,
    regime: rightRegime ?? seg.regime,
    kinematics: resolvedRightKinematics,
    origin: ctx.actorOrigin,
    createdByEventId: ctx.eventId,
    revision: 1,
    approval: rightApproval,
  });

  journal.ensureSegmentsMutable();
  state.segments.splice(segIdx, 1, leftSeg, rightSeg);

  // Maintain Span ⊆ Segment across split
  const segSpans = state.spans.filter((sp) => sp.segmentId === seg.id);
  if (segSpans.length > 0) {
    journal.ensureSpansMutable();
    /** @type {import("../index.d.ts").Span[]} */
    const nextSpans = state.spans.filter((sp) => sp.segmentId !== seg.id);
    let splitIdx = 0;
    for (const sp of segSpans) {
      if (sp.endUs <= atUs) {
        nextSpans.push(sp);
      } else if (sp.startUs >= atUs) {
        if (sp.kind === "free" && rightSeg.regime === "free") {
          journal.retireId(sp.id, ctx.eventId);
        } else {
          nextSpans.push(
            normalizeSpan({
              ...sp,
              segmentId: rightSeg.id,
              revision: sp.revision + 1,
            }),
          );
        }
      } else {
        // Straddles split `atUs`
        let leftSpanApproval = sp.approval;
        if (sp.approval) {
          leftSpanApproval = normalizeApproval({ ...sp.approval, modifiedSinceApproval: true });
          journal.setApproval(leftSpanApproval.id, leftSpanApproval);
        }
        nextSpans.push(
          normalizeSpan({
            ...sp,
            endUs: atUs,
            revision: sp.revision + 1,
            approval: leftSpanApproval,
          }),
        );

        if (!(sp.kind === "free" && rightSeg.regime === "free")) {
          const rSpanId = deriveChildEntityId(
            ctx.txId,
            opIndex,
            `${slotPrefix}span_split_${splitIdx}`,
          );
          journal.registerId(rSpanId, "span", ctx.eventId);
          let rSpanApproval;
          if (sp.approval) {
            const rAppId = deriveChildEntityId(
              ctx.txId,
              opIndex,
              `${slotPrefix}span_app_${splitIdx}`,
            );
            journal.registerId(rAppId, "approval", ctx.eventId);
            rSpanApproval = normalizeApproval({
              id: rAppId,
              eventId: ctx.eventId,
              contentDigest: sp.approval.contentDigest,
              modifiedSinceApproval: true,
            });
            journal.setApproval(rAppId, rSpanApproval);
          }
          nextSpans.push(
            normalizeSpan({
              id: rSpanId,
              segmentId: rightSeg.id,
              startUs: atUs,
              endUs: sp.endUs,
              kind: sp.kind,
              origin: sp.origin,
              createdByEventId: ctx.eventId,
              revision: 1,
              approval: rSpanApproval,
            }),
          );
        }
        splitIdx += 1;
      }
    }
    sortSpansInPlace(nextSpans);
    state.spans = nextSpans;
  }
}

/**
 * Apply `keep` or `free` range policy over `[clipStartUs, clipEndUs)` inside `state.segments[segIdx]`.
 */
function applySlicePolicyToSegment(
  state,
  journal,
  segIdx,
  clipStartUs,
  clipEndUs,
  kind,
  active,
  ctx,
  opIndex,
  sliceIdx,
) {
  const seg = state.segments[segIdx];
  const coversWhole = clipStartUs === seg.startUs && clipEndUs === seg.endUs;

  if (kind === "keep") {
    if (active) {
      const existingKeepSpans = state.spans.filter(
        (sp) => sp.segmentId === seg.id && sp.kind === "keep",
      );
      const merged = mergeRangesUs([
        ...(seg.approval ? [{ startUs: seg.startUs, endUs: seg.endUs }] : []),
        ...existingKeepSpans.map((sp) => ({ startUs: sp.startUs, endUs: sp.endUs })),
        { startUs: clipStartUs, endUs: clipEndUs },
      ]);
      const nowCoversWhole =
        coversWhole ||
        (merged.length === 1 && merged[0].startUs === seg.startUs && merged[0].endUs === seg.endUs);

      if (nowCoversWhole) {
        const digest = computeIntervalMusicalDigest(state, seg.startUs, seg.endUs);
        if (
          seg.approval &&
          !seg.approval.modifiedSinceApproval &&
          seg.approval.contentDigest === digest &&
          existingKeepSpans.length === 0
        ) {
          return; // semantic no-op
        }
        if (seg.approval) {
          journal.deleteApproval(seg.approval.id, ctx.eventId);
        }
        if (existingKeepSpans.length > 0) {
          journal.ensureSpansMutable();
          for (const sp of existingKeepSpans) {
            if (sp.approval) journal.deleteApproval(sp.approval.id, ctx.eventId);
            journal.retireId(sp.id, ctx.eventId);
          }
          state.spans = state.spans.filter(
            (sp) => !(sp.segmentId === seg.id && sp.kind === "keep"),
          );
        }
        const appId = deriveChildEntityId(ctx.txId, opIndex, `app_rk_${sliceIdx}`);
        journal.registerId(appId, "approval", ctx.eventId);
        const approval = normalizeApproval({
          id: appId,
          eventId: ctx.eventId,
          contentDigest: digest,
        });
        journal.setApproval(appId, approval);
        journal.ensureSegmentsMutable();
        state.segments[segIdx] = normalizeSegment({
          ...seg,
          revision: seg.revision + 1,
          approval,
        });
        return;
      }

      // Check if existing keep spans already cover [clipStartUs, clipEndUs) unchanged
      const alreadyCovered = existingKeepSpans.some(
        (sp) =>
          sp.startUs <= clipStartUs &&
          sp.endUs >= clipEndUs &&
          sp.approval &&
          !sp.approval.modifiedSinceApproval,
      );
      if (alreadyCovered) return;

      journal.ensureSpansMutable();
      for (const sp of existingKeepSpans) {
        if (sp.approval) journal.deleteApproval(sp.approval.id, ctx.eventId);
        journal.retireId(sp.id, ctx.eventId);
      }
      const nextSpans = state.spans.filter(
        (sp) => !(sp.segmentId === seg.id && sp.kind === "keep"),
      );
      for (let k = 0; k < merged.length; k += 1) {
        const iv = merged[k];
        const spanId = deriveChildEntityId(ctx.txId, opIndex, `span_rk_${sliceIdx}_${k}`);
        const appId = deriveChildEntityId(ctx.txId, opIndex, `app_rks_${sliceIdx}_${k}`);
        journal.registerId(spanId, "span", ctx.eventId);
        journal.registerId(appId, "approval", ctx.eventId);
        const digest = computeIntervalMusicalDigest(state, iv.startUs, iv.endUs);
        const approval = normalizeApproval({
          id: appId,
          eventId: ctx.eventId,
          contentDigest: digest,
        });
        journal.setApproval(appId, approval);
        nextSpans.push(
          normalizeSpan({
            id: spanId,
            segmentId: seg.id,
            startUs: iv.startUs,
            endUs: iv.endUs,
            kind: "keep",
            origin: ctx.actorOrigin,
            createdByEventId: ctx.eventId,
            revision: 1,
            approval,
          }),
        );
      }
      sortSpansInPlace(nextSpans);
      state.spans = nextSpans;
      return;
    }

    // active === false: unlock Keep over [clipStartUs, clipEndUs)
    const existingKeepSpans = state.spans.filter(
      (sp) => sp.segmentId === seg.id && sp.kind === "keep",
    );
    if (!seg.approval && existingKeepSpans.length === 0) return;

    const currentIntervals = seg.approval
      ? [{ startUs: seg.startUs, endUs: seg.endUs }]
      : existingKeepSpans.map((sp) => ({ startUs: sp.startUs, endUs: sp.endUs }));
    const remaining = subtractRangeUs(currentIntervals, clipStartUs, clipEndUs);

    if (
      !seg.approval &&
      remaining.length === existingKeepSpans.length &&
      remaining.every(
        (r, i) =>
          r.startUs === existingKeepSpans[i].startUs && r.endUs === existingKeepSpans[i].endUs,
      )
    ) {
      return; // no overlap with unlocked range -> semantic no-op
    }

    if (seg.approval) {
      journal.deleteApproval(seg.approval.id, ctx.eventId);
      journal.ensureSegmentsMutable();
      const { approval: _removed, ...restSeg } = seg;
      state.segments[segIdx] = normalizeSegment({
        ...restSeg,
        revision: seg.revision + 1,
      });
    }

    journal.ensureSpansMutable();
    for (const sp of existingKeepSpans) {
      if (sp.approval) journal.deleteApproval(sp.approval.id, ctx.eventId);
      journal.retireId(sp.id, ctx.eventId);
    }
    const nextSpans = state.spans.filter(
      (sp) => !(sp.segmentId === seg.id && sp.kind === "keep"),
    );
    for (let k = 0; k < remaining.length; k += 1) {
      const iv = remaining[k];
      const spanId = deriveChildEntityId(ctx.txId, opIndex, `span_rku_${sliceIdx}_${k}`);
      const appId = deriveChildEntityId(ctx.txId, opIndex, `app_rku_${sliceIdx}_${k}`);
      journal.registerId(spanId, "span", ctx.eventId);
      journal.registerId(appId, "approval", ctx.eventId);
      const digest = computeIntervalMusicalDigest(state, iv.startUs, iv.endUs);
      const approval = normalizeApproval({
        id: appId,
        eventId: ctx.eventId,
        contentDigest: digest,
      });
      journal.setApproval(appId, approval);
      nextSpans.push(
        normalizeSpan({
          id: spanId,
          segmentId: seg.id,
          startUs: iv.startUs,
          endUs: iv.endUs,
          kind: "keep",
          origin: ctx.actorOrigin,
          createdByEventId: ctx.eventId,
          revision: 1,
          approval,
        }),
      );
    }
    sortSpansInPlace(nextSpans);
    state.spans = nextSpans;
    return;
  }

  // kind === "free" (`set_range_regime` to `"free"` or `"auto"`)
  if (active) {
    if (seg.regime === "free") return;
    const existingFreeSpans = state.spans.filter(
      (sp) => sp.segmentId === seg.id && sp.kind === "free",
    );
    const merged = mergeRangesUs([
      ...existingFreeSpans.map((sp) => ({ startUs: sp.startUs, endUs: sp.endUs })),
      { startUs: clipStartUs, endUs: clipEndUs },
    ]);
    const nowCoversWhole =
      coversWhole ||
      (seg.regime === "auto" &&
        merged.length === 1 &&
        merged[0].startUs === seg.startUs &&
        merged[0].endUs === seg.endUs);

    if (nowCoversWhole && seg.regime !== "break") {
      journal.ensureSegmentsMutable();
      state.segments[segIdx] = normalizeSegment({
        ...seg,
        regime: "free",
        revision: seg.revision + 1,
      });
      if (existingFreeSpans.length > 0) {
        journal.ensureSpansMutable();
        for (const sp of existingFreeSpans) journal.retireId(sp.id, ctx.eventId);
        state.spans = state.spans.filter(
          (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
        );
      }
      return;
    }

    if (
      merged.length === existingFreeSpans.length &&
      merged.every(
        (r, i) =>
          r.startUs === existingFreeSpans[i].startUs && r.endUs === existingFreeSpans[i].endUs,
      )
    ) {
      return; // semantic no-op
    }

    journal.ensureSpansMutable();
    for (const sp of existingFreeSpans) journal.retireId(sp.id, ctx.eventId);
    const nextSpans = state.spans.filter(
      (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
    );
    for (let k = 0; k < merged.length; k += 1) {
      const iv = merged[k];
      const spanId = deriveChildEntityId(ctx.txId, opIndex, `span_rf_${sliceIdx}_${k}`);
      journal.registerId(spanId, "span", ctx.eventId);
      nextSpans.push(
        normalizeSpan({
          id: spanId,
          segmentId: seg.id,
          startUs: iv.startUs,
          endUs: iv.endUs,
          kind: "free",
          origin: ctx.actorOrigin,
          createdByEventId: ctx.eventId,
          revision: 1,
        }),
      );
    }
    sortSpansInPlace(nextSpans);
    state.spans = nextSpans;
    return;
  }

  // active === false -> setting range regime to "auto"
  const existingFreeSpans = state.spans.filter(
    (sp) => sp.segmentId === seg.id && sp.kind === "free",
  );
  if (seg.regime !== "free" && existingFreeSpans.length === 0) {
    if (coversWhole && seg.regime === "break") {
      journal.ensureSegmentsMutable();
      state.segments[segIdx] = normalizeSegment({
        ...seg,
        regime: "auto",
        revision: seg.revision + 1,
      });
    }
    return;
  }

  const currentIntervals =
    seg.regime === "free"
      ? [{ startUs: seg.startUs, endUs: seg.endUs }]
      : existingFreeSpans.map((sp) => ({ startUs: sp.startUs, endUs: sp.endUs }));
  const remaining = subtractRangeUs(currentIntervals, clipStartUs, clipEndUs);

  if (
    seg.regime !== "free" &&
    remaining.length === existingFreeSpans.length &&
    remaining.every(
      (r, i) =>
        r.startUs === existingFreeSpans[i].startUs && r.endUs === existingFreeSpans[i].endUs,
    )
  ) {
    return;
  }

  if (seg.regime === "free") {
    journal.ensureSegmentsMutable();
    state.segments[segIdx] = normalizeSegment({
      ...seg,
      regime: "auto",
      revision: seg.revision + 1,
    });
  }

  journal.ensureSpansMutable();
  for (const sp of existingFreeSpans) journal.retireId(sp.id, ctx.eventId);
  const nextSpans = state.spans.filter(
    (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
  );
  for (let k = 0; k < remaining.length; k += 1) {
    const iv = remaining[k];
    const spanId = deriveChildEntityId(ctx.txId, opIndex, `span_rfa_${sliceIdx}_${k}`);
    journal.registerId(spanId, "span", ctx.eventId);
    nextSpans.push(
      normalizeSpan({
        id: spanId,
        segmentId: seg.id,
        startUs: iv.startUs,
        endUs: iv.endUs,
        kind: "free",
        origin: ctx.actorOrigin,
        createdByEventId: ctx.eventId,
        revision: 1,
      }),
    );
  }
  sortSpansInPlace(nextSpans);
  state.spans = nextSpans;
}

/**
 * Execute an ordered list of `TransactionOp`s atomically against `state`.
 * If ANY op fails, the O(ops) savepoint journal rolls back all prior ops in the transaction
 * before rethrowing `EnsomiError`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {readonly import("../index.d.ts").TransactionOp[]} ops
 * @param {{
 *   txId: string,
 *   eventId: string,
 *   actorMode: "user" | "offer",
 *   actorOrigin: import("../index.d.ts").Origin
 * }} ctx
 * @returns {{ writeCount: number }}
 */
export function executeTransaction(state, ops, ctx) {
  if (!Array.isArray(ops) || ops.length === 0) {
    throw new EnsomiError("INVALID_EDIT", "Transaction requires a non-empty ops array");
  }

  // Capture active approvals BEFORE mutating state so user edits inside approved regions
  // can flag `modifiedSinceApproval = true` without trapping the user!
  /** @type {Array<{ id: string, startUs: number, endUs: number, preDigest: string }>} */
  const preApprovals = [];
  if (ctx.actorMode === "user") {
    for (const seg of state.segments) {
      if (seg.approval && !seg.approval.modifiedSinceApproval) {
        preApprovals.push({
          id: seg.approval.id,
          startUs: seg.startUs,
          endUs: seg.endUs,
          preDigest: seg.approval.contentDigest,
        });
      }
    }
    for (const sp of state.spans) {
      if (sp.kind === "keep" && sp.approval && !sp.approval.modifiedSinceApproval) {
        preApprovals.push({
          id: sp.approval.id,
          startUs: sp.startUs,
          endUs: sp.endUs,
          preDigest: sp.approval.contentDigest,
        });
      }
    }
  }

  const journal = createJournal(state);

  try {
    for (let opIndex = 0; opIndex < ops.length; opIndex += 1) {
      const rawOp = ops[opIndex];
      if (!rawOp || typeof rawOp !== "object") {
        throw new EnsomiError("INVALID_EDIT", `Transaction op[${opIndex}] must be an object`);
      }
      const opKind = /** @type {any} */ (rawOp).op;
      if (!isTransactionOpKind(opKind)) {
        throw new EnsomiError("INVALID_EDIT", `Unknown transaction op "${String(opKind)}"`);
      }
      if (ctx.actorMode === "offer" && !isOfferOpKind(opKind)) {
        throw new EnsomiError(
          "AUTHORITY_VIOLATION",
          `Operation "${opKind}" is forbidden inside automated offers`,
        );
      }

      const durationUs = assertAudioBoundDuration(state);

      switch (opKind) {
        case "upsert_beat": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "upsert_beat" }>} */ (rawOp);
          if (!op.beat || typeof op.beat !== "object") {
            throw new EnsomiError("INVALID_BEAT", "upsert_beat requires a beat object");
          }
          assertNoNonUserAuthorityFields(op.beat, ctx.actorMode, "upsert_beat.beat");
          const beatId = assertNonEmptyId(op.beat.id, "beat.id", "INVALID_BEAT");
          const existed = journal.assertUpsertableId(beatId, "beat", ctx.eventId);
          const existing = existed ? state.beats.get(beatId) : undefined;

          const origin = existing ? existing.origin : ctx.actorOrigin;
          const createdByEventId = existing ? existing.createdByEventId : ctx.eventId;
          const nextRev = existing ? existing.revision + 1 : 1;

          // Preserve boundTo on verified beats if an unchallenged offer omitted boundTo
          const resolvedBoundTo =
            op.beat.boundTo !== undefined
              ? op.beat.boundTo
              : ctx.actorMode === "offer" && existing?.verdict && existing.boundTo
                ? existing.boundTo
                : undefined;

          /** @type {import("../index.d.ts").BeatLineage | undefined} */
          let lineage = existing?.lineage;
          if (ctx.actorMode === "user" && existing && existing.origin !== "user" && !lineage) {
            lineage = {
              origin: existing.origin,
              atUs: existing.atUs,
              ...(existing.confidence !== undefined ? { confidence: existing.confidence } : {}),
            };
          }

          let draftBeat = normalizeBeat(
            {
              id: beatId,
              atUs: op.beat.atUs,
              origin,
              createdByEventId,
              revision: nextRev,
              confidence: op.beat.confidence ?? existing?.confidence,
              boundTo: resolvedBoundTo,
              structural:
                op.beat.structural !== undefined ? op.beat.structural : existing?.structural,
              lineage,
            },
            { durationUs },
          );

          if (ctx.actorMode === "user") {
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "upsert_beat.tolUs", "INVALID_VERDICT")
                : existing?.verdict?.tolUs ?? DEFAULT_BEAT_TOL_US;
            const vd = computeEntityValueDigest("beat", draftBeat);
            draftBeat = normalizeBeat(
              {
                ...draftBeat,
                verdict: {
                  kind: origin === "user" ? "placed" : "adjusted",
                  eventId: ctx.eventId,
                  valueDigest: vd,
                  tolUs,
                  via: op.via ?? "tap",
                },
              },
              { durationUs },
            );
          } else if (existing?.verdict) {
            // Unchallenged offer updating within tolUs NEVER erases human verdict!
            const vd = computeEntityValueDigest("beat", draftBeat);
            draftBeat = normalizeBeat(
              {
                ...draftBeat,
                verdict: {
                  ...existing.verdict,
                  valueDigest: vd,
                },
              },
              { durationUs },
            );
          }

          if (existing && entitiesSemanticallyEqual(existing, draftBeat)) {
            break;
          }
          journal.setBeat(beatId, draftBeat);
          break;
        }

        case "move_beat": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "move_beat" }>} */ (rawOp);
          const existing = state.beats.get(op.id);
          if (!existing) {
            throw new EnsomiError("MISSING_ENTITY", `Beat "${op.id}" does not exist`);
          }
          const atUs = assertPointUs(op.atUs, durationUs, "move_beat.atUs");
          const nextBoundTo =
            op.boundTo === null
              ? undefined
              : op.boundTo !== undefined
                ? normalizeBeatBinding(op.boundTo)
                : existing.boundTo
                  ? updateBeatBindingAfterShift(
                      state,
                      existing.boundTo,
                      atUs,
                      atUs - existing.atUs,
                      existing.verdict?.tolUs,
                    )
                  : undefined;
          const nextStructural =
            op.structural === null
              ? undefined
              : op.structural !== undefined
                ? normalizeBeatStructural(op.structural)
                : existing.structural;

          // Zero-delta check: accidental zero-delta move on unverified beat does NOT fabricate verdict
          if (
            atUs === existing.atUs &&
            canonicalJson(nextBoundTo ?? null) === canonicalJson(existing.boundTo ?? null) &&
            canonicalJson(nextStructural ?? null) === canonicalJson(existing.structural ?? null) &&
            (op.confidence === undefined || op.confidence === existing.confidence)
          ) {
            break;
          }

          /** @type {import("../index.d.ts").BeatLineage | undefined} */
          let lineage = existing.lineage;
          if (ctx.actorMode === "user" && existing.origin !== "user" && !lineage) {
            lineage = Object.freeze({
              origin: existing.origin,
              atUs: existing.atUs,
              ...(existing.confidence !== undefined ? { confidence: existing.confidence } : {}),
            });
          }

          const nextConfidence =
            op.confidence !== undefined ? op.confidence : existing.confidence;
          const vdTarget = {
            id: existing.id,
            atUs,
            boundTo: nextBoundTo,
            structural: nextStructural,
          };

          /** @type {import("../index.d.ts").Verdict | undefined} */
          let verdict;
          if (ctx.actorMode === "user") {
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "move_beat.tolUs", "INVALID_VERDICT")
                : existing.verdict?.tolUs ?? DEFAULT_BEAT_TOL_US;
            const vd = computeEntityValueDigest("beat", vdTarget);
            const via = op.via ?? "nudge";
            verdict = Object.freeze({
              kind: existing.origin === "user" ? "placed" : "adjusted",
              eventId: ctx.eventId,
              valueDigest: vd,
              tolUs,
              via,
            });
          } else if (existing.verdict) {
            const vd = computeEntityValueDigest("beat", vdTarget);
            verdict = Object.freeze({
              ...existing.verdict,
              valueDigest: vd,
            });
          }

          /** @type {import("../index.d.ts").Beat} */
          const nextBeat = Object.freeze({
            id: existing.id,
            atUs,
            origin: existing.origin,
            createdByEventId: existing.createdByEventId,
            revision: existing.revision + 1,
            ...(nextConfidence !== undefined ? { confidence: nextConfidence } : {}),
            ...(nextBoundTo ? { boundTo: nextBoundTo } : {}),
            ...(nextStructural && Object.keys(nextStructural).length > 0
              ? { structural: nextStructural }
              : {}),
            ...(lineage ? { lineage } : {}),
            ...(verdict ? { verdict } : {}),
          });

          if (existing.atUs === nextBeat.atUs && entitiesSemanticallyEqual(existing, nextBeat)) {
            break;
          }
          journal.setBeat(existing.id, nextBeat);
          break;
        }

        case "snap_beat": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "snap_beat" }>} */ (rawOp);
          const existing = state.beats.get(op.id);
          if (!existing) {
            throw new EnsomiError("MISSING_ENTITY", `Beat "${op.id}" does not exist`);
          }
          const atUs = assertPointUs(op.atUs, durationUs, "snap_beat.atUs");
          const boundTo = normalizeBeatBinding(op.boundTo);

          let lineage = existing.lineage;
          if (ctx.actorMode === "user" && existing.origin !== "user" && !lineage) {
            lineage = {
              origin: existing.origin,
              atUs: existing.atUs,
              ...(existing.confidence !== undefined ? { confidence: existing.confidence } : {}),
            };
          }

          let nextBeat = normalizeBeat(
            {
              id: existing.id,
              atUs,
              origin: existing.origin,
              createdByEventId: existing.createdByEventId,
              revision: existing.revision + 1,
              confidence: existing.confidence,
              boundTo,
              structural: existing.structural,
              lineage,
            },
            { durationUs },
          );

          if (ctx.actorMode === "user") {
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "snap_beat.tolUs", "INVALID_VERDICT")
                : DEFAULT_SNAP_BEAT_TOL_US;
            const vd = computeEntityValueDigest("beat", nextBeat);
            nextBeat = normalizeBeat(
              {
                ...nextBeat,
                verdict: {
                  kind: existing.origin === "user" ? "placed" : "adjusted",
                  eventId: ctx.eventId,
                  valueDigest: vd,
                  tolUs,
                  via: op.via ?? "snap",
                },
              },
              { durationUs },
            );
          } else if (existing.verdict) {
            const vd = computeEntityValueDigest("beat", nextBeat);
            nextBeat = normalizeBeat(
              {
                ...nextBeat,
                verdict: { ...existing.verdict, valueDigest: vd },
              },
              { durationUs },
            );
          }

          if (entitiesSemanticallyEqual(existing, nextBeat)) break;
          journal.setBeat(existing.id, nextBeat);
          break;
        }

        case "shift_beats": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "shift_beats" }>} */ (rawOp);
          const range = assertIntervalUs(op.startUs, op.endUs, durationUs, "shift_beats");
          const deltaUs = assertSafeIntUs(op.deltaUs, "shift_beats.deltaUs");
          const includeVerified = op.includeVerified !== false;
          if (deltaUs === 0) break; // semantic no-op

          /** @type {import("../index.d.ts").Beat[]} */
          const targets = [];
          for (const b of state.beats.values()) {
            if (b.atUs < range.startUs || b.atUs >= range.endUs) continue;
            if (b.verdict && !includeVerified) continue;
            const nextAtUs = b.atUs + deltaUs;
            if (nextAtUs < 0 || nextAtUs >= durationUs) {
              throw new EnsomiError(
                "OUT_OF_BOUNDS",
                `shift_beats pushes beat "${b.id}" from ${b.atUs} to ${nextAtUs} outside [0, ${durationUs})`,
                { beatId: b.id, atUs: b.atUs, nextAtUs, durationUs },
              );
            }
            targets.push(b);
          }

          for (let i = 0; i < targets.length; i += 1) {
            const b = targets[i];
            const nextAtUs = b.atUs + deltaUs;
            const nextBoundTo = b.boundTo
              ? updateBeatBindingAfterShift(
                  state,
                  b.boundTo,
                  nextAtUs,
                  deltaUs,
                  b.verdict?.tolUs,
                )
              : undefined;

            // Epistemic invariant (A1): NEVER manufacture verdicts on unverified beats!
            let lineage = b.lineage;
            if (b.verdict && b.origin !== "user" && !lineage) {
              lineage = {
                origin: b.origin,
                atUs: b.atUs,
                ...(b.confidence !== undefined ? { confidence: b.confidence } : {}),
              };
            }

            let shifted = normalizeBeat(
              {
                id: b.id,
                atUs: nextAtUs,
                origin: b.origin,
                createdByEventId: b.createdByEventId,
                revision: b.revision + 1,
                confidence: b.confidence,
                boundTo: nextBoundTo,
                structural: b.structural,
                lineage,
              },
              { durationUs },
            );

            if (b.verdict) {
              const vd = computeEntityValueDigest("beat", shifted);
              shifted = normalizeBeat(
                {
                  ...shifted,
                  verdict: {
                    kind: b.origin === "user" ? "placed" : "adjusted",
                    eventId: ctx.eventId,
                    valueDigest: vd,
                    tolUs: b.verdict.tolUs,
                    ...(b.verdict.via ? { via: b.verdict.via } : {}),
                  },
                },
                { durationUs },
              );
            }
            journal.setBeat(b.id, shifted);
          }
          break;
        }

        case "remove_beats": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "remove_beats" }>} */ (rawOp);
          if (!Array.isArray(op.ids) || op.ids.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "remove_beats requires non-empty ids[]");
          }
          for (const id of op.ids) {
            assertNonEmptyId(id, "remove_beats.id", "INVALID_BEAT");
            if (!state.beats.has(id)) {
              throw new EnsomiError("MISSING_ENTITY", `Beat "${id}" does not exist`);
            }
          }
          for (const id of op.ids) {
            journal.deleteBeat(id, ctx.eventId);
          }
          break;
        }

        case "place_unbeat": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "place_unbeat" }>} */ (rawOp);
          const id = assertNonEmptyId(op.id, "place_unbeat.id", "INVALID_UNBEAT");
          const atUs = assertPointUs(op.atUs, durationUs, "place_unbeat.atUs");
          const captureRadius =
            op.captureUnverifiedRadiusUs !== undefined
              ? assertNonNegSafeIntUs(
                  op.captureUnverifiedRadiusUs,
                  "place_unbeat.captureUnverifiedRadiusUs",
                  "INVALID_UNBEAT",
                )
              : 0;
          const explicitVerifiedIds = new Set(op.removeVerifiedBeatIds ?? []);
          for (const vid of explicitVerifiedIds) {
            if (!state.beats.has(vid)) {
              throw new EnsomiError(
                "MISSING_ENTITY",
                `removeVerifiedBeatIds references unknown beat "${vid}"`,
              );
            }
          }

          journal.registerId(id, "unbeat", ctx.eventId);

          /** @type {import("../index.d.ts").Beat | null} */
          let closestCaptured = null;
          let closestDist = Number.MAX_SAFE_INTEGER;
          /** @type {string[]} */
          const toDeleteBeatIds = [];

          for (const b of state.beats.values()) {
            if (explicitVerifiedIds.has(b.id)) {
              toDeleteBeatIds.push(b.id);
              const dist = Math.abs(b.atUs - atUs);
              if (dist < closestDist) {
                closestDist = dist;
                closestCaptured = b;
              }
              continue;
            }
            if (!b.verdict && captureRadius > 0) {
              const dist = Math.abs(b.atUs - atUs);
              if (dist <= captureRadius) {
                toDeleteBeatIds.push(b.id);
                if (dist < closestDist) {
                  closestDist = dist;
                  closestCaptured = b;
                }
              }
            }
          }

          for (const bid of toDeleteBeatIds) {
            journal.deleteBeat(bid, ctx.eventId);
          }

          const tolUs =
            op.tolUs !== undefined
              ? assertNonNegSafeIntUs(op.tolUs, "place_unbeat.tolUs", "INVALID_UNBEAT")
              : DEFAULT_UNBEAT_TOL_US;
          let unbeat = normalizeUnbeat(
            {
              id,
              atUs,
              origin: "user",
              createdByEventId: ctx.eventId,
              revision: 1,
              tolUs,
              pulseLayer: op.pulseLayer ?? "tactus",
              replaces: closestCaptured
                ? {
                    beatId: closestCaptured.id,
                    origin: closestCaptured.origin,
                    createdByEventId: closestCaptured.createdByEventId,
                    priorRevision: closestCaptured.revision,
                    atUs: closestCaptured.atUs,
                    ...(closestCaptured.confidence !== undefined
                      ? { confidence: closestCaptured.confidence }
                      : {}),
                  }
                : undefined,
            },
            { durationUs },
          );
          const vd = computeEntityValueDigest("unbeat", unbeat);
          unbeat = normalizeUnbeat(
            {
              ...unbeat,
              verdict: {
                kind: "placed",
                eventId: ctx.eventId,
                valueDigest: vd,
                tolUs,
                via: op.via ?? "tap",
              },
            },
            { durationUs },
          );
          journal.setUnbeat(id, unbeat);
          break;
        }

        case "remove_unbeats": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "remove_unbeats" }>} */ (rawOp);
          if (!Array.isArray(op.ids) || op.ids.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "remove_unbeats requires non-empty ids[]");
          }
          for (const id of op.ids) {
            assertNonEmptyId(id, "remove_unbeats.id", "INVALID_UNBEAT");
            if (!state.unbeats.has(id)) {
              throw new EnsomiError("MISSING_ENTITY", `Unbeat "${id}" does not exist`);
            }
          }
          for (const id of op.ids) {
            journal.deleteUnbeat(id, ctx.eventId);
          }
          break;
        }

        case "beat_to_unbeat": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "beat_to_unbeat" }>} */ (rawOp);
          if (!Array.isArray(op.conversions) || op.conversions.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "beat_to_unbeat requires non-empty conversions[]");
          }
          for (const conv of op.conversions) {
            if (!conv || typeof conv !== "object") {
              throw new EnsomiError("INVALID_EDIT", "Invalid beat_to_unbeat conversion entry");
            }
            const beat = state.beats.get(conv.beatId);
            if (!beat) {
              throw new EnsomiError("MISSING_ENTITY", `Beat "${conv.beatId}" does not exist`);
            }
            const expRev = assertPositiveRevision(conv.expectedRevision, "beat_to_unbeat");
            if (beat.revision !== expRev) {
              throw new EnsomiError(
                "REVISION_MISMATCH",
                `Beat "${conv.beatId}" revision mismatch: expected ${expRev}, actual ${beat.revision}`,
              );
            }
            const newId = assertNonEmptyId(conv.newUnbeatId, "newUnbeatId", "INVALID_UNBEAT");
            journal.registerId(newId, "unbeat", ctx.eventId);
            journal.deleteBeat(beat.id, ctx.eventId);

            const tolUs =
              conv.tolUs !== undefined
                ? assertNonNegSafeIntUs(conv.tolUs, "beat_to_unbeat.tolUs", "INVALID_UNBEAT")
                : DEFAULT_UNBEAT_TOL_US;
            let unbeat = normalizeUnbeat(
              {
                id: newId,
                atUs: beat.atUs,
                origin: "user",
                createdByEventId: ctx.eventId,
                revision: 1,
                tolUs,
                pulseLayer: conv.pulseLayer ?? "tactus",
                replaces: {
                  beatId: beat.id,
                  origin: beat.origin,
                  createdByEventId: beat.createdByEventId,
                  priorRevision: beat.revision,
                  atUs: beat.atUs,
                  ...(beat.confidence !== undefined ? { confidence: beat.confidence } : {}),
                },
              },
              { durationUs },
            );
            const vd = computeEntityValueDigest("unbeat", unbeat);
            unbeat = normalizeUnbeat(
              {
                ...unbeat,
                verdict: {
                  kind: "placed",
                  eventId: ctx.eventId,
                  valueDigest: vd,
                  tolUs,
                  via: "tap",
                },
              },
              { durationUs },
            );
            journal.setUnbeat(newId, unbeat);
          }
          break;
        }

        case "upsert_sound": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "upsert_sound" }>} */ (rawOp);
          if (!op.sound || typeof op.sound !== "object") {
            throw new EnsomiError("INVALID_SOUND", "upsert_sound requires a sound object");
          }
          assertNoNonUserAuthorityFields(op.sound, ctx.actorMode, "upsert_sound.sound");
          const soundId = assertNonEmptyId(op.sound.id, "sound.id", "INVALID_SOUND");
          const existed = journal.assertUpsertableId(soundId, "sound", ctx.eventId);
          const existing = existed ? state.sounds.get(soundId) : undefined;

          const origin = existing ? existing.origin : ctx.actorOrigin;
          const createdByEventId = existing ? existing.createdByEventId : ctx.eventId;
          const nextRev = existing ? existing.revision + 1 : 1;

          let nextSound = normalizeSound(
            {
              ...op.sound,
              id: soundId,
              origin,
              createdByEventId,
              revision: nextRev,
              verdict: undefined,
            },
            { durationUs },
          );

          if (ctx.actorMode === "user") {
            const facet = validateSoundFacet(op.facet ?? existing?.verdict?.facet, nextSound.anchors);
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "upsert_sound.tolUs", "INVALID_VERDICT")
                : existing?.verdict?.tolUs ?? DEFAULT_SOUND_TOL_US;
            const vd = computeEntityValueDigest("sound", nextSound, facet);
            nextSound = normalizeSound(
              {
                ...nextSound,
                verdict: {
                  kind: origin === "user" ? "placed" : "adjusted",
                  eventId: ctx.eventId,
                  valueDigest: vd,
                  tolUs,
                  facet,
                  via: op.via ?? "tap",
                },
              },
              { durationUs },
            );
          } else if (existing?.verdict) {
            const facet = existing.verdict.facet ?? "occurrence";
            const vd = computeEntityValueDigest("sound", nextSound, facet);
            nextSound = normalizeSound(
              {
                ...nextSound,
                verdict: {
                  ...existing.verdict,
                  valueDigest: vd,
                },
              },
              { durationUs },
            );
          }

          if (existing && entitiesSemanticallyEqual(existing, nextSound)) break;
          journal.setSound(soundId, nextSound);
          break;
        }

        case "update_sound": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "update_sound" }>} */ (rawOp);
          const existing = state.sounds.get(op.id);
          if (!existing) {
            throw new EnsomiError("MISSING_ENTITY", `Sound "${op.id}" does not exist`);
          }
          if (!op.patch || typeof op.patch !== "object") {
            throw new EnsomiError("INVALID_SOUND", "update_sound requires a patch object");
          }
          assertNoNonUserAuthorityFields(op.patch, ctx.actorMode, "update_sound.patch");

          let nextSound = normalizeSound(
            {
              ...existing,
              ...op.patch,
              id: existing.id,
              origin: existing.origin,
              createdByEventId: existing.createdByEventId,
              revision: existing.revision + 1,
              verdict: undefined,
            },
            { durationUs },
          );

          if (ctx.actorMode === "user") {
            const facet = validateSoundFacet(op.facet ?? existing.verdict?.facet, nextSound.anchors);
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "update_sound.tolUs", "INVALID_VERDICT")
                : existing.verdict?.tolUs ?? DEFAULT_SOUND_TOL_US;
            const vd = computeEntityValueDigest("sound", nextSound, facet);
            nextSound = normalizeSound(
              {
                ...nextSound,
                verdict: {
                  kind: existing.origin === "user" ? "placed" : "adjusted",
                  eventId: ctx.eventId,
                  valueDigest: vd,
                  tolUs,
                  facet,
                  via: op.via ?? "nudge",
                },
              },
              { durationUs },
            );
          } else if (existing.verdict) {
            const facet = existing.verdict.facet ?? "occurrence";
            const vd = computeEntityValueDigest("sound", nextSound, facet);
            nextSound = normalizeSound(
              {
                ...nextSound,
                verdict: {
                  ...existing.verdict,
                  valueDigest: vd,
                },
              },
              { durationUs },
            );
          }

          if (entitiesSemanticallyEqual(existing, nextSound)) break;
          journal.setSound(existing.id, nextSound);
          break;
        }

        case "remove_sounds": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "remove_sounds" }>} */ (rawOp);
          if (!Array.isArray(op.ids) || op.ids.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "remove_sounds requires non-empty ids[]");
          }
          for (const id of op.ids) {
            assertNonEmptyId(id, "remove_sounds.id", "INVALID_SOUND");
            if (!state.sounds.has(id)) {
              throw new EnsomiError("MISSING_ENTITY", `Sound "${id}" does not exist`);
            }
          }
          for (const id of op.ids) {
            journal.deleteSound(id, ctx.eventId);
          }
          break;
        }

        case "upsert_sound_class": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "upsert_sound_class" }>} */ (rawOp);
          if (!op.soundClass || typeof op.soundClass !== "object") {
            throw new EnsomiError("INVALID_SOUND_CLASS", "upsert_sound_class requires soundClass");
          }
          assertNoNonUserAuthorityFields(op.soundClass, ctx.actorMode, "soundClass");
          const scId = assertNonEmptyId(op.soundClass.id, "soundClass.id", "INVALID_SOUND_CLASS");
          const existed = journal.assertUpsertableId(scId, "sound_class", ctx.eventId);
          const existing = existed ? state.soundClasses.get(scId) : undefined;

          for (const exId of op.soundClass.exemplarSoundIds ?? []) {
            if (!state.sounds.has(exId)) {
              throw new EnsomiError(
                "MISSING_ENTITY",
                `SoundClass "${scId}" references missing exemplar Sound "${exId}"`,
              );
            }
          }

          const origin = existing ? existing.origin : ctx.actorOrigin;
          const createdByEventId = existing ? existing.createdByEventId : ctx.eventId;
          const nextRev = existing ? existing.revision + 1 : 1;

          let nextSc = normalizeSoundClass({
            ...op.soundClass,
            id: scId,
            origin,
            createdByEventId,
            revision: nextRev,
            verdict: undefined,
          });

          if (ctx.actorMode === "user") {
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "upsert_sound_class.tolUs", "INVALID_VERDICT")
                : existing?.verdict?.tolUs ?? 0;
            const vd = computeEntityValueDigest("sound_class", nextSc);
            nextSc = normalizeSoundClass({
              ...nextSc,
              verdict: {
                kind: origin === "user" ? "placed" : "adjusted",
                eventId: ctx.eventId,
                valueDigest: vd,
                tolUs,
                via: op.via ?? "review",
              },
            });
          } else if (existing?.verdict) {
            const vd = computeEntityValueDigest("sound_class", nextSc);
            nextSc = normalizeSoundClass({
              ...nextSc,
              verdict: { ...existing.verdict, valueDigest: vd },
            });
          }

          if (existing && entitiesSemanticallyEqual(existing, nextSc)) break;
          journal.setSoundClass(scId, nextSc);
          break;
        }

        case "remove_sound_classes": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "remove_sound_classes" }>} */ (rawOp);
          if (!Array.isArray(op.ids) || op.ids.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "remove_sound_classes requires non-empty ids[]");
          }
          for (const id of op.ids) {
            assertNonEmptyId(id, "remove_sound_classes.id", "INVALID_SOUND_CLASS");
            if (!state.soundClasses.has(id)) {
              throw new EnsomiError("MISSING_ENTITY", `SoundClass "${id}" does not exist`);
            }
          }
          for (const id of op.ids) {
            journal.deleteSoundClass(id, ctx.eventId);
          }
          break;
        }

        case "confirm_marks": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "confirm_marks" }>} */ (rawOp);
          if (!Array.isArray(op.targets) || op.targets.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "confirm_marks requires non-empty targets[]");
          }
          for (const target of op.targets) {
            if (!target || typeof target !== "object") {
              throw new EnsomiError("INVALID_EDIT", "confirm_marks target must be an object");
            }
            if (!VERDICT_TARGET_KIND_SET.has(target.kind)) {
              throw new EnsomiError(
                "INVALID_ENTITY_KIND",
                `Invalid confirm_marks target kind: ${String(target.kind)}`,
              );
            }
            const entity = getVerdictEntity(state, target.kind, target.id);
            if (!entity) {
              throw new EnsomiError(
                "MISSING_ENTITY",
                `${target.kind} "${target.id}" does not exist`,
              );
            }
            const expRev = assertPositiveRevision(target.expectedRevision, "confirm_marks");
            if (entity.revision !== expRev) {
              throw new EnsomiError(
                "REVISION_MISMATCH",
                `${target.kind} "${target.id}" revision mismatch: expected ${expRev}, actual ${entity.revision}`,
              );
            }
            const facet =
              target.kind === "sound"
                ? validateSoundFacet(target.facet, entity.anchors)
                : target.facet;
            const defaultTol =
              target.kind === "beat"
                ? DEFAULT_BEAT_TOL_US
                : target.kind === "unbeat"
                  ? entity.tolUs ?? DEFAULT_UNBEAT_TOL_US
                  : target.kind === "boundary"
                    ? DEFAULT_BOUNDARY_TOL_US
                    : target.kind === "sound"
                      ? DEFAULT_SOUND_TOL_US
                      : 0;
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "confirm_marks.tolUs", "INVALID_VERDICT")
                : entity.verdict?.tolUs ?? defaultTol;
            const vd = computeEntityValueDigest(target.kind, entity, facet);

            if (
              entity.verdict &&
              entity.verdict.kind === "confirmed" &&
              entity.verdict.valueDigest === vd &&
              entity.verdict.tolUs === tolUs &&
              (entity.verdict.facet ?? undefined) === (facet ?? undefined)
            ) {
              continue; // semantic no-op
            }

            const updated = deepFreeze({
              ...entity,
              revision: entity.revision + 1,
              verdict: deepFreeze({
                kind: "confirmed",
                eventId: ctx.eventId,
                valueDigest: vd,
                tolUs,
                ...(facet ? { facet } : {}),
                via: op.via ?? "review",
              }),
            });
            setVerdictEntityInJournal(journal, target.kind, target.id, updated);
          }
          break;
        }

        case "clear_verdicts": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "clear_verdicts" }>} */ (rawOp);
          if (!Array.isArray(op.targets) || op.targets.length === 0) {
            throw new EnsomiError("INVALID_EDIT", "clear_verdicts requires non-empty targets[]");
          }
          for (const target of op.targets) {
            if (!target || typeof target !== "object") {
              throw new EnsomiError("INVALID_EDIT", "clear_verdicts target must be an object");
            }
            if (!VERDICT_TARGET_KIND_SET.has(target.kind)) {
              throw new EnsomiError(
                "INVALID_ENTITY_KIND",
                `Invalid clear_verdicts target kind: ${String(target.kind)}`,
              );
            }
            const entity = getVerdictEntity(state, target.kind, target.id);
            if (!entity) {
              throw new EnsomiError(
                "MISSING_ENTITY",
                `${target.kind} "${target.id}" does not exist`,
              );
            }
            const expRev = assertPositiveRevision(target.expectedRevision, "clear_verdicts");
            if (entity.revision !== expRev) {
              throw new EnsomiError(
                "REVISION_MISMATCH",
                `${target.kind} "${target.id}" revision mismatch: expected ${expRev}, actual ${entity.revision}`,
              );
            }
            if (!entity.verdict) continue; // semantic no-op
            const { verdict: _cleared, ...rest } = entity;
            const updated = deepFreeze({
              ...rest,
              revision: entity.revision + 1,
            });
            setVerdictEntityInJournal(journal, target.kind, target.id, updated);
          }
          break;
        }

        case "split_segment": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "split_segment" }>} */ (rawOp);
          const segIdx = state.segments.findIndex((s) => s.id === op.segmentId);
          if (segIdx < 0) {
            throw new EnsomiError(
              "MISSING_ENTITY",
              `Segment "${op.segmentId}" does not exist`,
            );
          }
          const atUs = assertInteriorPointUs(op.atUs, durationUs, "split_segment.atUs");
          const boundaryId =
            op.newBoundaryId ?? deriveChildEntityId(ctx.txId, opIndex, "bnd_0");
          const rightSegmentId =
            op.newSegmentId ?? deriveChildEntityId(ctx.txId, opIndex, "seg_0");

          applySplitSegmentInternal(
            state,
            journal,
            segIdx,
            atUs,
            boundaryId,
            rightSegmentId,
            op.rightRegime,
            op.rightKinematics,
            ctx,
            opIndex,
          );
          break;
        }

        case "move_boundary": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "move_boundary" }>} */ (rawOp);
          const bnd = state.boundaries.get(op.boundaryId);
          if (!bnd) {
            throw new EnsomiError(
              "MISSING_ENTITY",
              `Boundary "${op.boundaryId}" does not exist`,
            );
          }
          const leftIdx = state.segments.findIndex((s) => s.endBoundaryId === op.boundaryId);
          if (
            leftIdx < 0 ||
            leftIdx + 1 >= state.segments.length ||
            state.segments[leftIdx + 1].startBoundaryId !== op.boundaryId
          ) {
            throw new EnsomiError(
              "INVALID_SEGMENT_EDIT",
              `Boundary "${op.boundaryId}" is not backed by adjacent segments`,
            );
          }
          const leftSeg = state.segments[leftIdx];
          const rightSeg = state.segments[leftIdx + 1];
          const atUs = assertInteriorPointUs(op.atUs, durationUs, "move_boundary.atUs");
          if (atUs <= leftSeg.startUs || atUs >= rightSeg.endUs) {
            throw new EnsomiError(
              "INVALID_SEGMENT_EDIT",
              `move_boundary atUs (${atUs}) must lie strictly inside (${leftSeg.startUs}, ${rightSeg.endUs})`,
            );
          }
          if (atUs === bnd.atUs) break; // semantic no-op

          const oldAtUs = bnd.atUs;
          let nextBnd = normalizeBoundary({
            ...bnd,
            atUs,
            revision: bnd.revision + 1,
          });
          if (ctx.actorMode === "user") {
            const tolUs =
              op.tolUs !== undefined
                ? assertNonNegSafeIntUs(op.tolUs, "move_boundary.tolUs", "INVALID_VERDICT")
                : bnd.verdict?.tolUs ?? DEFAULT_BOUNDARY_TOL_US;
            const vd = computeEntityValueDigest("boundary", nextBnd);
            nextBnd = normalizeBoundary({
              ...nextBnd,
              verdict: {
                kind: bnd.origin === "user" ? "placed" : "adjusted",
                eventId: ctx.eventId,
                valueDigest: vd,
                tolUs,
                via: op.via ?? "nudge",
              },
            });
          } else if (bnd.verdict) {
            const vd = computeEntityValueDigest("boundary", nextBnd);
            nextBnd = normalizeBoundary({
              ...nextBnd,
              verdict: { ...bnd.verdict, valueDigest: vd },
            });
          }
          journal.setBoundary(bnd.id, nextBnd);

          let leftApp = leftSeg.approval;
          if (leftApp) {
            leftApp = normalizeApproval({ ...leftApp, modifiedSinceApproval: true });
            journal.setApproval(leftApp.id, leftApp);
          }
          let rightApp = rightSeg.approval;
          if (rightApp) {
            rightApp = normalizeApproval({ ...rightApp, modifiedSinceApproval: true });
            journal.setApproval(rightApp.id, rightApp);
          }

          const nextLeft = normalizeSegment({
            ...leftSeg,
            endUs: atUs,
            revision: leftSeg.revision + 1,
            approval: leftApp,
          });
          const nextRight = normalizeSegment({
            ...rightSeg,
            startUs: atUs,
            revision: rightSeg.revision + 1,
            approval: rightApp,
          });
          journal.ensureSegmentsMutable();
          state.segments[leftIdx] = nextLeft;
          state.segments[leftIdx + 1] = nextRight;

          // Maintain Span ⊆ Segment across moved boundary
          const affectedSpans = state.spans.filter(
            (sp) => sp.segmentId === leftSeg.id || sp.segmentId === rightSeg.id,
          );
          if (affectedSpans.length > 0) {
            journal.ensureSpansMutable();
            const unaffected = state.spans.filter(
              (sp) => sp.segmentId !== leftSeg.id && sp.segmentId !== rightSeg.id,
            );
            let splitCounter = 0;
            for (const sp of affectedSpans) {
              if (sp.endUs <= atUs) {
                unaffected.push(
                  sp.segmentId === leftSeg.id
                    ? sp
                    : normalizeSpan({ ...sp, segmentId: leftSeg.id, revision: sp.revision + 1 }),
                );
              } else if (sp.startUs >= atUs) {
                unaffected.push(
                  sp.segmentId === rightSeg.id
                    ? sp
                    : normalizeSpan({ ...sp, segmentId: rightSeg.id, revision: sp.revision + 1 }),
                );
              } else {
                // Straddles new boundary `atUs` (swept across `[min(oldAtUs, atUs), max(oldAtUs, atUs)]`)
                void oldAtUs;
                let lApp = sp.approval;
                if (lApp) {
                  lApp = normalizeApproval({ ...lApp, modifiedSinceApproval: true });
                  journal.setApproval(lApp.id, lApp);
                }
                unaffected.push(
                  normalizeSpan({
                    ...sp,
                    segmentId: leftSeg.id,
                    endUs: atUs,
                    revision: sp.revision + 1,
                    approval: lApp,
                  }),
                );
                const rSpanId = deriveChildEntityId(
                  ctx.txId,
                  opIndex,
                  `span_mv_${splitCounter}`,
                );
                journal.registerId(rSpanId, "span", ctx.eventId);
                let rApp;
                if (sp.approval) {
                  const rAppId = deriveChildEntityId(
                    ctx.txId,
                    opIndex,
                    `app_mv_${splitCounter}`,
                  );
                  journal.registerId(rAppId, "approval", ctx.eventId);
                  rApp = normalizeApproval({
                    id: rAppId,
                    eventId: ctx.eventId,
                    contentDigest: sp.approval.contentDigest,
                    modifiedSinceApproval: true,
                  });
                  journal.setApproval(rAppId, rApp);
                }
                unaffected.push(
                  normalizeSpan({
                    id: rSpanId,
                    segmentId: rightSeg.id,
                    startUs: atUs,
                    endUs: sp.endUs,
                    kind: sp.kind,
                    origin: sp.origin,
                    createdByEventId: ctx.eventId,
                    revision: 1,
                    approval: rApp,
                  }),
                );
                splitCounter += 1;
              }
            }
            sortSpansInPlace(unaffected);
            state.spans = unaffected;
          }
          break;
        }

        case "merge_segments": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "merge_segments" }>} */ (rawOp);
          const leftIdx = state.segments.findIndex((s) => s.id === op.leftSegmentId);
          if (
            leftIdx < 0 ||
            leftIdx + 1 >= state.segments.length ||
            state.segments[leftIdx + 1].id !== op.rightSegmentId
          ) {
            throw new EnsomiError(
              "INVALID_SEGMENT_EDIT",
              `Segments "${op.leftSegmentId}" and "${op.rightSegmentId}" are not adjacent`,
            );
          }
          const leftSeg = state.segments[leftIdx];
          const rightSeg = state.segments[leftIdx + 1];
          const oldBoundaryId = leftSeg.endBoundaryId;
          const oldBoundaryUs = leftSeg.endUs;

          const targetRegime =
            op.mergedRegime ??
            (leftSeg.regime === rightSeg.regime
              ? leftSeg.regime
              : leftSeg.regime === "auto" || rightSeg.regime === "auto"
                ? "auto"
                : "free");
          if (!SEGMENT_REGIME_SET.has(targetRegime)) {
            throw new EnsomiError("INVALID_SEGMENT", `Invalid mergedRegime: ${String(targetRegime)}`);
          }
          const targetKinematics =
            op.mergedKinematics === null
              ? undefined
              : op.mergedKinematics !== undefined
                ? op.mergedKinematics
                : leftSeg.kinematics === rightSeg.kinematics
                  ? leftSeg.kinematics
                  : undefined;

          if (oldBoundaryId) {
            journal.deleteBoundary(oldBoundaryId, ctx.eventId);
          }
          journal.retireId(rightSeg.id, ctx.eventId);

          // Symmetrically preserve half-kept and half-free intervals as child Spans!
          const bothKept = Boolean(leftSeg.approval && rightSeg.approval);
          /** @type {import("../index.d.ts").Approval | undefined} */
          let mergedApproval;
          if (bothKept && leftSeg.approval && rightSeg.approval) {
            journal.deleteApproval(rightSeg.approval.id, ctx.eventId);
            mergedApproval = normalizeApproval({
              ...leftSeg.approval,
              modifiedSinceApproval: true,
            });
            journal.setApproval(mergedApproval.id, mergedApproval);
          }

          const mergedSeg = normalizeSegment({
            ...leftSeg,
            endUs: rightSeg.endUs,
            endBoundaryId: rightSeg.endBoundaryId,
            regime: targetRegime,
            kinematics: targetKinematics,
            revision: leftSeg.revision + 1,
            approval: mergedApproval,
          });

          journal.ensureSegmentsMutable();
          state.segments.splice(leftIdx, 2, mergedSeg);

          journal.ensureSpansMutable();
          /** @type {import("../index.d.ts").Span[]} */
          const nextSpans = state.spans.filter(
            (sp) => sp.segmentId !== leftSeg.id && sp.segmentId !== rightSeg.id,
          );
          const pairSpans = state.spans.filter(
            (sp) => sp.segmentId === leftSeg.id || sp.segmentId === rightSeg.id,
          );

          // Re-parent existing child spans
          for (const sp of pairSpans) {
            if ((sp.kind === "keep" && bothKept) || (sp.kind === "free" && targetRegime === "free")) {
              if (sp.approval) journal.deleteApproval(sp.approval.id, ctx.eventId);
              journal.retireId(sp.id, ctx.eventId);
              continue;
            }
            nextSpans.push(
              sp.segmentId === mergedSeg.id
                ? sp
                : normalizeSpan({ ...sp, segmentId: mergedSeg.id, revision: sp.revision + 1 }),
            );
          }

          // Half-kept segment -> preserve its Approval on a child Span!
          if (!bothKept && leftSeg.approval) {
            const spId = deriveChildEntityId(ctx.txId, opIndex, "span_mg_kleft");
            journal.registerId(spId, "span", ctx.eventId);
            nextSpans.push(
              normalizeSpan({
                id: spId,
                segmentId: mergedSeg.id,
                startUs: leftSeg.startUs,
                endUs: oldBoundaryUs,
                kind: "keep",
                origin: leftSeg.origin,
                createdByEventId: ctx.eventId,
                revision: 1,
                approval: leftSeg.approval,
              }),
            );
          } else if (!bothKept && rightSeg.approval) {
            const spId = deriveChildEntityId(ctx.txId, opIndex, "span_mg_kright");
            journal.registerId(spId, "span", ctx.eventId);
            nextSpans.push(
              normalizeSpan({
                id: spId,
                segmentId: mergedSeg.id,
                startUs: oldBoundaryUs,
                endUs: rightSeg.endUs,
                kind: "keep",
                origin: rightSeg.origin,
                createdByEventId: ctx.eventId,
                revision: 1,
                approval: rightSeg.approval,
              }),
            );
          }

          // Half-free segment when targetRegime !== "free" -> preserve free sub-interval as child Span!
          if (targetRegime !== "free") {
            if (leftSeg.regime === "free") {
              const spId = deriveChildEntityId(ctx.txId, opIndex, "span_mg_fleft");
              journal.registerId(spId, "span", ctx.eventId);
              nextSpans.push(
                normalizeSpan({
                  id: spId,
                  segmentId: mergedSeg.id,
                  startUs: leftSeg.startUs,
                  endUs: oldBoundaryUs,
                  kind: "free",
                  origin: leftSeg.origin,
                  createdByEventId: ctx.eventId,
                  revision: 1,
                }),
              );
            }
            if (rightSeg.regime === "free") {
              const spId = deriveChildEntityId(ctx.txId, opIndex, "span_mg_fright");
              journal.registerId(spId, "span", ctx.eventId);
              nextSpans.push(
                normalizeSpan({
                  id: spId,
                  segmentId: mergedSeg.id,
                  startUs: oldBoundaryUs,
                  endUs: rightSeg.endUs,
                  kind: "free",
                  origin: rightSeg.origin,
                  createdByEventId: ctx.eventId,
                  revision: 1,
                }),
              );
            }
          }

          sortSpansInPlace(nextSpans);
          state.spans = nextSpans;
          break;
        }

        case "promote_range_to_segments": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "promote_range_to_segments" }>} */ (rawOp);
          if (ctx.actorMode === "offer" && op.keep !== undefined) {
            throw new EnsomiError(
              "AUTHORITY_VIOLATION",
              "Automated offers cannot set keep on promote_range_to_segments",
            );
          }
          const range = assertIntervalUs(op.startUs, op.endUs, durationUs, "promote_range");
          if (range.startUs > 0 && !state.segments.some((s) => s.startUs === range.startUs)) {
            const idx = state.segments.findIndex(
              (s) => range.startUs > s.startUs && range.startUs < s.endUs,
            );
            if (idx >= 0) {
              const bId =
                op.newBoundaryIds?.[0] ?? deriveChildEntityId(ctx.txId, opIndex, "bnd_pr0");
              const sId =
                op.newSegmentIds?.[0] ?? deriveChildEntityId(ctx.txId, opIndex, "seg_pr0");
              applySplitSegmentInternal(
                state,
                journal,
                idx,
                range.startUs,
                bId,
                sId,
                undefined,
                undefined,
                ctx,
                opIndex,
                "pr0_",
              );
            }
          }
          if (
            range.endUs < durationUs &&
            !state.segments.some((s) => s.endUs === range.endUs)
          ) {
            const idx = state.segments.findIndex(
              (s) => range.endUs > s.startUs && range.endUs < s.endUs,
            );
            if (idx >= 0) {
              const bId =
                op.newBoundaryIds?.[1] ?? deriveChildEntityId(ctx.txId, opIndex, "bnd_pr1");
              const sId =
                op.newSegmentIds?.[1] ?? deriveChildEntityId(ctx.txId, opIndex, "seg_pr1");
              applySplitSegmentInternal(
                state,
                journal,
                idx,
                range.endUs,
                bId,
                sId,
                undefined,
                undefined,
                ctx,
                opIndex,
                "pr1_",
              );
            }
          }

          for (let sIdx = 0; sIdx < state.segments.length; sIdx += 1) {
            const seg = state.segments[sIdx];
            if (seg.startUs < range.startUs || seg.endUs > range.endUs) continue;
            if (op.regime !== undefined && seg.regime !== op.regime) {
              if (!SEGMENT_REGIME_SET.has(op.regime)) {
                throw new EnsomiError("INVALID_SEGMENT", `Invalid regime: ${String(op.regime)}`);
              }
              journal.ensureSegmentsMutable();
              state.segments[sIdx] = normalizeSegment({
                ...state.segments[sIdx],
                regime: op.regime,
                revision: state.segments[sIdx].revision + 1,
              });
              if (op.regime === "free") {
                const redFree = state.spans.filter(
                  (sp) => sp.segmentId === seg.id && sp.kind === "free",
                );
                if (redFree.length > 0) {
                  journal.ensureSpansMutable();
                  for (const sp of redFree) journal.retireId(sp.id, ctx.eventId);
                  state.spans = state.spans.filter(
                    (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
                  );
                }
              }
            }
            if (op.kinematics !== undefined) {
              if (!SEGMENT_KINEMATICS_SET.has(op.kinematics)) {
                throw new EnsomiError(
                  "INVALID_SEGMENT",
                  `Invalid kinematics: ${String(op.kinematics)}`,
                );
              }
              if (state.segments[sIdx].kinematics !== op.kinematics) {
                journal.ensureSegmentsMutable();
                state.segments[sIdx] = normalizeSegment({
                  ...state.segments[sIdx],
                  kinematics: op.kinematics,
                  revision: state.segments[sIdx].revision + 1,
                });
              }
            }
            if (op.keep !== undefined) {
              applySlicePolicyToSegment(
                state,
                journal,
                sIdx,
                seg.startUs,
                seg.endUs,
                "keep",
                Boolean(op.keep),
                ctx,
                opIndex,
                sIdx,
              );
            }
          }
          break;
        }

        case "set_segment_regime": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "set_segment_regime" }>} */ (rawOp);
          const segIdx = state.segments.findIndex((s) => s.id === op.segmentId);
          if (segIdx < 0) {
            throw new EnsomiError("MISSING_ENTITY", `Segment "${op.segmentId}" does not exist`);
          }
          if (!SEGMENT_REGIME_SET.has(op.regime)) {
            throw new EnsomiError("INVALID_SEGMENT", `Invalid regime: ${String(op.regime)}`);
          }
          const seg = state.segments[segIdx];
          const redundantFree =
            op.regime === "free"
              ? state.spans.filter((sp) => sp.segmentId === seg.id && sp.kind === "free")
              : [];
          if (seg.regime === op.regime && redundantFree.length === 0) {
            break; // semantic no-op (never bumps revision or alters origin!)
          }
          journal.ensureSegmentsMutable();
          state.segments[segIdx] = normalizeSegment({
            ...seg,
            regime: op.regime,
            revision: seg.revision + 1,
          });
          if (redundantFree.length > 0) {
            journal.ensureSpansMutable();
            for (const sp of redundantFree) journal.retireId(sp.id, ctx.eventId);
            state.spans = state.spans.filter(
              (sp) => !(sp.segmentId === seg.id && sp.kind === "free"),
            );
          }
          break;
        }

        case "set_segment_kinematics": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "set_segment_kinematics" }>} */ (rawOp);
          const segIdx = state.segments.findIndex((s) => s.id === op.segmentId);
          if (segIdx < 0) {
            throw new EnsomiError("MISSING_ENTITY", `Segment "${op.segmentId}" does not exist`);
          }
          if (op.kinematics !== null && !SEGMENT_KINEMATICS_SET.has(op.kinematics)) {
            throw new EnsomiError(
              "INVALID_SEGMENT",
              `Invalid kinematics: ${String(op.kinematics)}`,
            );
          }
          const seg = state.segments[segIdx];
          if ((seg.kinematics ?? null) === op.kinematics) {
            break; // semantic no-op
          }
          journal.ensureSegmentsMutable();
          const { kinematics: _oldK, ...rest } = seg;
          state.segments[segIdx] = normalizeSegment({
            ...rest,
            ...(op.kinematics !== null ? { kinematics: op.kinematics } : {}),
            revision: seg.revision + 1,
          });
          break;
        }

        case "set_segment_keep": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "set_segment_keep" }>} */ (rawOp);
          const segIdx = state.segments.findIndex((s) => s.id === op.segmentId);
          if (segIdx < 0) {
            throw new EnsomiError("MISSING_ENTITY", `Segment "${op.segmentId}" does not exist`);
          }
          if (typeof op.keep !== "boolean") {
            throw new EnsomiError("INVALID_EDIT", "set_segment_keep.keep must be boolean");
          }
          const seg = state.segments[segIdx];
          if (op.keep && op.approvalId !== undefined) {
            const digest = computeIntervalMusicalDigest(state, seg.startUs, seg.endUs);
            const childKeep = state.spans.filter(
              (sp) => sp.segmentId === seg.id && sp.kind === "keep",
            );
            if (
              seg.approval &&
              !seg.approval.modifiedSinceApproval &&
              seg.approval.contentDigest === digest &&
              childKeep.length === 0
            ) {
              break; // semantic no-op
            }
            if (seg.approval) journal.deleteApproval(seg.approval.id, ctx.eventId);
            if (childKeep.length > 0) {
              journal.ensureSpansMutable();
              for (const sp of childKeep) {
                if (sp.approval) journal.deleteApproval(sp.approval.id, ctx.eventId);
                journal.retireId(sp.id, ctx.eventId);
              }
              state.spans = state.spans.filter(
                (sp) => !(sp.segmentId === seg.id && sp.kind === "keep"),
              );
            }
            journal.registerId(op.approvalId, "approval", ctx.eventId);
            const approval = normalizeApproval({
              id: op.approvalId,
              eventId: ctx.eventId,
              contentDigest: digest,
            });
            journal.setApproval(op.approvalId, approval);
            journal.ensureSegmentsMutable();
            state.segments[segIdx] = normalizeSegment({
              ...seg,
              revision: seg.revision + 1,
              approval,
            });
          } else {
            applySlicePolicyToSegment(
              state,
              journal,
              segIdx,
              seg.startUs,
              seg.endUs,
              "keep",
              op.keep,
              ctx,
              opIndex,
              0,
            );
          }
          break;
        }

        case "set_range_regime": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "set_range_regime" }>} */ (rawOp);
          if (op.regime !== "auto" && op.regime !== "free") {
            throw new EnsomiError(
              "INVALID_EDIT",
              `set_range_regime regime must be "auto" or "free", got "${String(op.regime)}"`,
            );
          }
          const range = assertRangeUs({ startUs: op.startUs, endUs: op.endUs }, durationUs);
          for (let sIdx = 0; sIdx < state.segments.length; sIdx += 1) {
            const seg = state.segments[sIdx];
            const c0 = Math.max(seg.startUs, range.startUs);
            const c1 = Math.min(seg.endUs, range.endUs);
            if (c1 > c0) {
              applySlicePolicyToSegment(
                state,
                journal,
                sIdx,
                c0,
                c1,
                "free",
                op.regime === "free",
                ctx,
                opIndex,
                sIdx,
              );
            }
          }
          break;
        }

        case "set_range_keep": {
          const op = /** @type {Extract<import("../index.d.ts").TransactionOp, { op: "set_range_keep" }>} */ (rawOp);
          if (typeof op.keep !== "boolean") {
            throw new EnsomiError("INVALID_EDIT", "set_range_keep.keep must be boolean");
          }
          const range = assertRangeUs({ startUs: op.startUs, endUs: op.endUs }, durationUs);
          for (let sIdx = 0; sIdx < state.segments.length; sIdx += 1) {
            const seg = state.segments[sIdx];
            const c0 = Math.max(seg.startUs, range.startUs);
            const c1 = Math.min(seg.endUs, range.endUs);
            if (c1 > c0) {
              applySlicePolicyToSegment(
                state,
                journal,
                sIdx,
                c0,
                c1,
                "keep",
                op.keep,
                ctx,
                opIndex,
                sIdx,
              );
            }
          }
          break;
        }

        default:
          throw new EnsomiError("INVALID_EDIT", `Unhandled transaction op: ${String(opKind)}`);
      }
    }

    // For direct user edits, check if any pre-existing untouched `Approval` had its
    // musical content modified by the user (human is never trapped by their own `keep`,
    // and `approval.modifiedSinceApproval` flags the modification explicitly!).
    if (ctx.actorMode === "user" && journal.writeCount > 0 && preApprovals.length > 0) {
      for (const pre of preApprovals) {
        if (journal.hasTouchedApproval(pre.id)) continue;
        const segIdx = state.segments.findIndex((s) => s.approval?.id === pre.id);
        if (segIdx >= 0) {
          const seg = state.segments[segIdx];
          const postDigest = computeIntervalMusicalDigest(state, seg.startUs, seg.endUs);
          if (postDigest !== pre.preDigest && seg.approval) {
            const updatedApp = normalizeApproval({
              ...seg.approval,
              modifiedSinceApproval: true,
            });
            journal.setApproval(updatedApp.id, updatedApp);
            journal.ensureSegmentsMutable();
            state.segments[segIdx] = normalizeSegment({
              ...seg,
              approval: updatedApp,
            });
          }
          continue;
        }
        const spanIdx = state.spans.findIndex((sp) => sp.approval?.id === pre.id);
        if (spanIdx >= 0) {
          const sp = state.spans[spanIdx];
          const postDigest = computeIntervalMusicalDigest(state, sp.startUs, sp.endUs);
          if (postDigest !== pre.preDigest && sp.approval) {
            const updatedApp = normalizeApproval({
              ...sp.approval,
              modifiedSinceApproval: true,
            });
            journal.setApproval(updatedApp.id, updatedApp);
            journal.ensureSpansMutable();
            state.spans[spanIdx] = normalizeSpan({
              ...sp,
              approval: updatedApp,
            });
          }
        }
      }
    }

    return { writeCount: journal.writeCount };
  } catch (err) {
    journal.rollback();
    throw err;
  }
}
