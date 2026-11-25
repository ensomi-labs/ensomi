/**
 * Unified `Offer` engine (`0.1.0`):
 *  - Host-bound `computeOfferDependencies` & `computeOfferBasisDigest` (covering `writeRanges`
 *    segments, boundaries, spans, marks including empty-range membership, AND `derivedFrom`!)
 *  - `validateOffer(snapshot, offer, options?)` running the EXACT pure `executeTransaction`
 *    interpreter on scratch state + Full Influence-Footprint `Approval` (`keep`) check +
 *    `protected_verified` & `OfferChallenge` check + `alternativeGroup` sibling supersession
 *  - `previewOffer(snapshot, offer, options?)` -> `{ validation, nextSnapshot, diff, previewTempoMap, consequences }`
 *  - `buildOffer` / `normalizeHostOffer` for binding provider proposals to `capturedBasis`
 */

import { compileConstraints } from "./constraints.js";
import { executeTransaction, getVerdictEntity } from "./interpreter.js";
import {
  assertPlainObject,
  canonicalJson,
  compareCodeUnits,
  EnsomiError,
  jcsSha256Hex,
} from "./jcs.js";
import { toScratchSessionState, toSnapshot } from "./state.js";
import {
  assertRangeUs,
  formatTimeUs,
  mergeRangesUs,
  rangeContainsUs,
  rangesOverlapUs,
} from "./time.js";
import {
  assertNonEmptyId,
  assertOfferShape,
  assertPositiveRevision,
  assertUnitInterval,
  computeEntityValueDigest,
  deepFreeze,
  isOfferOpKind,
  normalizeOptionalIdPair,
  normalizeStructuredNote,
  OFFER_KINDS,
  VERDICT_TARGET_KINDS,
} from "./types.js";
import { computeIntervalMusicalDigest, projectTempoMap } from "./views.js";

const OFFER_KIND_SET = new Set(OFFER_KINDS);
const VERDICT_TARGET_KIND_SET = new Set(VERDICT_TARGET_KINDS);

/**
 * Look up any active entity in `state` by ID for `derivedFrom.supportingIds`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {string} id
 * @returns {{ kind: import("../index.d.ts").EntityKind, revision: number, valueDigest: string } | null}
 */
function inspectSupportingEntity(state, id) {
  const b = state.beats.get(id);
  if (b) {
    return {
      kind: "beat",
      revision: b.revision,
      valueDigest: computeEntityValueDigest("beat", b),
    };
  }
  const u = state.unbeats.get(id);
  if (u) {
    return {
      kind: "unbeat",
      revision: u.revision,
      valueDigest: computeEntityValueDigest("unbeat", u),
    };
  }
  const s = state.sounds.get(id);
  if (s) {
    return {
      kind: "sound",
      revision: s.revision,
      valueDigest: computeEntityValueDigest("sound", s, s.verdict?.facet ?? "occurrence"),
    };
  }
  const sc = state.soundClasses.get(id);
  if (sc) {
    return {
      kind: "sound_class",
      revision: sc.revision,
      valueDigest: computeEntityValueDigest("sound_class", sc),
    };
  }
  const bnd = state.boundaries.get(id);
  if (bnd) {
    return {
      kind: "boundary",
      revision: bnd.revision,
      valueDigest: computeEntityValueDigest("boundary", bnd),
    };
  }
  const seg = state.segments.find((x) => x.id === id);
  if (seg) {
    return {
      kind: "segment",
      revision: seg.revision,
      valueDigest: jcsSha256Hex({
        id: seg.id,
        startUs: seg.startUs,
        endUs: seg.endUs,
        regime: seg.regime,
        kinematics: seg.kinematics ?? null,
        approvalId: seg.approval?.id ?? null,
      }),
    };
  }
  const sp = state.spans.find((x) => x.id === id);
  if (sp) {
    return {
      kind: "span",
      revision: sp.revision,
      valueDigest: jcsSha256Hex({
        id: sp.id,
        segmentId: sp.segmentId,
        startUs: sp.startUs,
        endUs: sp.endUs,
        kind: sp.kind,
        approvalId: sp.approval?.id ?? null,
        approvalDigest: sp.approval?.contentDigest ?? null,
        modifiedSinceApproval: Boolean(sp.approval?.modifiedSinceApproval),
      }),
    };
  }
  const app = state.approvals.get(id);
  if (app) {
    return {
      kind: "approval",
      revision: 1,
      valueDigest: jcsSha256Hex({
        id: app.id,
        eventId: app.eventId,
        contentDigest: app.contentDigest,
        modifiedSinceApproval: Boolean(app.modifiedSinceApproval),
      }),
    };
  }
  return null;
}

/**
 * Compute deterministic `sourceDigest` for `derivedFrom.range`, combining the musical interval
 * digest with per-entity epistemic verdict digests (`verdict ? verdict.valueDigest + ":" + verdict.kind : "unverified"`)
 * and active approvals so `clear_verdicts` or retracting `confirm_marks` in the source region
 * marks pending derived offers `"stale"` (`source_changed`)!
 *
 * @param {import("../index.d.ts").SessionState} state
 * @param {number} startUs
 * @param {number} endUs
 * @returns {string}
 */
function computeDerivedSourceDigest(state, startUs, endUs) {
  const musicalDigest = computeIntervalMusicalDigest(state, startUs, endUs);
  const verdictTag = (v) => (v ? `${v.valueDigest}:${v.kind}` : "unverified");

  const beatVerdicts = Array.from(state.beats.values())
    .filter((b) => b.atUs >= startUs && b.atUs < endUs)
    .map((b) => ({ id: b.id, verdict: verdictTag(b.verdict) }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  const unbeatVerdicts = Array.from(state.unbeats.values())
    .filter((u) => u.atUs >= startUs && u.atUs < endUs)
    .map((u) => ({ id: u.id, verdict: verdictTag(u.verdict) }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  const overlappingSounds = Array.from(state.sounds.values()).filter(
    (s) => s.startUs < endUs && s.endUs > startUs,
  );
  const soundVerdicts = overlappingSounds
    .map((s) => ({ id: s.id, verdict: verdictTag(s.verdict) }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  const classIdsInSource = new Set();
  for (const s of overlappingSounds) {
    if (s.classId) classIdsInSource.add(s.classId);
  }
  const soundClassVerdicts = Array.from(state.soundClasses.values())
    .filter((sc) => classIdsInSource.has(sc.id))
    .map((sc) => ({ id: sc.id, verdict: verdictTag(sc.verdict) }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  const boundaryVerdicts = Array.from(state.boundaries.values())
    .filter((bnd) => bnd.atUs >= startUs && bnd.atUs <= endUs)
    .map((bnd) => ({ id: bnd.id, verdict: verdictTag(bnd.verdict) }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  const approvalsInSource = [];
  for (const seg of state.segments) {
    if (seg.approval && seg.startUs < endUs && seg.endUs > startUs) {
      approvalsInSource.push({
        id: seg.approval.id,
        contentDigest: seg.approval.contentDigest,
        modifiedSinceApproval: Boolean(seg.approval.modifiedSinceApproval),
      });
    }
  }
  for (const sp of state.spans) {
    if (sp.approval && sp.startUs < endUs && sp.endUs > startUs) {
      approvalsInSource.push({
        id: sp.approval.id,
        contentDigest: sp.approval.contentDigest,
        modifiedSinceApproval: Boolean(sp.approval.modifiedSinceApproval),
      });
    }
  }
  approvalsInSource.sort((a, b) => compareCodeUnits(a.id, b.id));

  return jcsSha256Hex({
    v: 1,
    musicalDigest,
    verdicts: {
      beats: beatVerdicts,
      unbeats: unbeatVerdicts,
      sounds: soundVerdicts,
      soundClasses: soundClassVerdicts,
      boundaries: boundaryVerdicts,
    },
    approvals: approvalsInSource,
  });
}

/**
 * Compute deterministic `OfferDependencies` over `writeRanges` (and optional `derivedFrom` / `ops`)
 * from `snapshotOrState`. Captures empty-range membership and scopes `soundClasses` strictly to
 * classes referenced by sounds in `writeRanges`, `derivedFrom`, or `ops`.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshotOrState
 * @param {readonly import("../index.d.ts").RangeUs[]} writeRanges
 * @param {{ range: import("../index.d.ts").RangeUs, supportingIds?: readonly string[] }} [derivedFrom]
 * @param {readonly import("../index.d.ts").OfferOpInput[]} [ops]
 * @returns {import("../index.d.ts").OfferDependencies}
 */
export function computeOfferDependencies(snapshotOrState, writeRanges, derivedFrom, ops = []) {
  const state = toScratchSessionState(snapshotOrState);
  const durationUs = state.durationUs;
  if (!Array.isArray(writeRanges) || writeRanges.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "Offer writeRanges must be a non-empty RangeUs[]");
  }
  const mergedRanges = mergeRangesUs(
    writeRanges.map((r) => assertRangeUs(r, durationUs > 0 ? durationUs : undefined)),
  );

  const overlapsWrite = (startUs, endUs) =>
    mergedRanges.some((wr) => startUs < wr.endUs && endUs > wr.startUs);
  const pointInWrite = (atUs) =>
    mergedRanges.some((wr) => atUs >= wr.startUs && atUs < wr.endUs);
  const boundaryTouchesWrite = (atUs) =>
    mergedRanges.some((wr) => atUs >= wr.startUs && atUs <= wr.endUs);

  /** @type {Set<string>} */
  const referencedSegmentIds = new Set();
  /** @type {Set<string>} */
  const referencedUnbeatIds = new Set();
  if (Array.isArray(ops)) {
    for (const op of ops) {
      if (!op || typeof op !== "object") continue;
      if (
        (op.op === "split_segment" ||
          op.op === "set_segment_regime" ||
          op.op === "set_segment_kinematics") &&
        typeof op.segmentId === "string"
      ) {
        referencedSegmentIds.add(op.segmentId);
      } else if (op.op === "merge_segments") {
        if (typeof op.leftSegmentId === "string") referencedSegmentIds.add(op.leftSegmentId);
        if (typeof op.rightSegmentId === "string") referencedSegmentIds.add(op.rightSegmentId);
      } else if (op.op === "move_boundary" && typeof op.boundaryId === "string") {
        for (const s of state.segments) {
          if (s.startBoundaryId === op.boundaryId || s.endBoundaryId === op.boundaryId) {
            referencedSegmentIds.add(s.id);
          }
        }
      } else if (op.op === "move_unbeat" && typeof op.id === "string") {
        referencedUnbeatIds.add(op.id);
      }
    }
  }

  const segments = state.segments
    .filter((s) => overlapsWrite(s.startUs, s.endUs) || referencedSegmentIds.has(s.id))
    .map((s) => ({
      id: s.id,
      startUs: s.startUs,
      endUs: s.endUs,
      regime: s.regime,
      kinematics: s.kinematics ?? null,
      revision: s.revision,
      approved: Boolean(s.approval),
    }))
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  const boundaries = Array.from(state.boundaries.values())
    .filter((b) => boundaryTouchesWrite(b.atUs))
    .map((b) => ({ id: b.id, atUs: b.atUs, revision: b.revision }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  const spans = state.spans
    .filter((sp) => overlapsWrite(sp.startUs, sp.endUs))
    .map((sp) => ({
      id: sp.id,
      segmentId: sp.segmentId,
      startUs: sp.startUs,
      endUs: sp.endUs,
      kind: sp.kind,
      revision: sp.revision,
      approved: Boolean(sp.approval),
    }))
    .sort(
      (a, b) =>
        a.startUs - b.startUs ||
        a.endUs - b.endUs ||
        compareCodeUnits(a.kind, b.kind) ||
        compareCodeUnits(a.id, b.id),
    );

  const beats = Array.from(state.beats.values())
    .filter((b) => pointInWrite(b.atUs))
    .map((b) => ({ id: b.id, atUs: b.atUs, revision: b.revision }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  const unbeats = Array.from(state.unbeats.values())
    .filter((u) => pointInWrite(u.atUs) || referencedUnbeatIds.has(u.id))
    .map((u) => ({ id: u.id, atUs: u.atUs, revision: u.revision }))
    .sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  /** @type {Set<string>} */
  const referencedClassIds = new Set();
  const overlappingWriteSounds = Array.from(state.sounds.values()).filter((s) =>
    overlapsWrite(s.startUs, s.endUs),
  );
  for (const s of overlappingWriteSounds) {
    if (s.classId) referencedClassIds.add(s.classId);
  }

  const sounds = overlappingWriteSounds
    .map((s) => ({ id: s.id, startUs: s.startUs, endUs: s.endUs, revision: s.revision }))
    .sort((a, b) => a.startUs - b.startUs || a.endUs - b.endUs || compareCodeUnits(a.id, b.id));

  if (Array.isArray(ops)) {
    for (const op of ops) {
      if (!op || typeof op !== "object") continue;
      if (op.op === "upsert_sound_class" && typeof op.soundClass?.id === "string") {
        referencedClassIds.add(op.soundClass.id);
      } else if (op.op === "remove_sound_classes" && Array.isArray(op.ids)) {
        for (const cid of op.ids) {
          if (typeof cid === "string") referencedClassIds.add(cid);
        }
      } else if (op.op === "upsert_sound" && typeof op.sound?.classId === "string") {
        referencedClassIds.add(op.sound.classId);
      } else if (op.op === "update_sound" && typeof op.patch?.classId === "string") {
        referencedClassIds.add(op.patch.classId);
      }
    }
  }

  /** @type {import("../index.d.ts").OfferDependencies["derivedFrom"]} */
  let derivedDeps;
  if (derivedFrom) {
    const srcRange = assertRangeUs(
      derivedFrom.range,
      durationUs > 0 ? durationUs : undefined,
    );
    for (const s of state.sounds.values()) {
      if (s.startUs < srcRange.endUs && s.endUs > srcRange.startUs && s.classId) {
        referencedClassIds.add(s.classId);
      }
    }
    const sourceDigest = computeDerivedSourceDigest(state, srcRange.startUs, srcRange.endUs);
    const uniqueSupportingIds = [...new Set(derivedFrom.supportingIds ?? [])].sort(
      compareCodeUnits,
    );
    /** @type {Array<{ id: string, kind: import("../index.d.ts").EntityKind, revision: number, valueDigest: string }>} */
    const supportingRevisions = [];
    for (const sid of uniqueSupportingIds) {
      if (state.soundClasses.has(sid)) referencedClassIds.add(sid);
      const supSound = state.sounds.get(sid);
      if (supSound?.classId) referencedClassIds.add(supSound.classId);
      const info = inspectSupportingEntity(state, sid);
      if (info) {
        const epistemicSuffix =
          info.kind === "beat" ||
          info.kind === "unbeat" ||
          info.kind === "sound" ||
          info.kind === "sound_class" ||
          info.kind === "boundary"
            ? getVerdictEntity(state, info.kind, sid)
            : null;
        void epistemicSuffix;
        supportingRevisions.push({
          id: sid,
          kind: info.kind,
          revision: info.revision,
          valueDigest: info.valueDigest,
        });
      } else {
        supportingRevisions.push({
          id: sid,
          kind: "beat",
          revision: 0,
          valueDigest: "missing",
        });
      }
    }
    derivedDeps = {
      range: srcRange,
      sourceDigest,
      supportingRevisions,
    };
  }

  const soundClasses = Array.from(state.soundClasses.values())
    .filter((sc) => referencedClassIds.has(sc.id))
    .map((sc) => ({ id: sc.id, revision: sc.revision }))
    .sort((a, b) => compareCodeUnits(a.id, b.id));

  return deepFreeze({
    audioId: state.audio?.audioId ?? null,
    durationUs,
    writeRanges: mergedRanges,
    segments,
    boundaries,
    spans,
    beats,
    unbeats,
    sounds,
    soundClasses,
    ...(derivedDeps ? { derivedFrom: derivedDeps } : {}),
  });
}

/**
 * Compute RFC 8785 (JCS) SHA-256 `basisDigest` from `OfferDependencies`.
 *
 * @param {import("../index.d.ts").OfferDependencies} dependencies
 * @returns {string}
 */
export function computeOfferBasisDigest(dependencies) {
  return jcsSha256Hex(dependencies);
}

/**
 * Compute a deterministic semantic key for rejection suppression across recompute runs
 * (strips ephemeral newly-minted mark IDs while preserving target timestamps, roles, and ranges).
 *
 * @param {{ kind?: string, writeRanges: readonly import("../index.d.ts").RangeUs[], ops: readonly import("../index.d.ts").OfferOp[] }} offer
 * @returns {string}
 */
export function computeOfferSemanticKey(offer) {
  const ranges = mergeRangesUs(offer.writeRanges ?? []);
  const canonicalOps = (offer.ops ?? []).map((op) => {
    switch (op.op) {
      case "upsert_beat":
        return {
          op: "upsert_beat",
          atUs: op.beat.atUs,
          boundTo: op.beat.boundTo ?? null,
          structural: op.beat.structural ?? null,
        };
      case "upsert_sound":
        return {
          op: "upsert_sound",
          startUs: op.sound.startUs,
          endUs: op.sound.endUs,
          anchors: (op.sound.anchors ?? []).map((a) => ({ role: a.role, atUs: a.atUs })),
          classId: op.sound.classId ?? null,
          voiceGroupId: op.sound.voiceGroupId ?? null,
        };
      case "split_segment":
        return {
          op: "split_segment",
          segmentId: op.segmentId,
          atUs: op.atUs,
          rightRegime: op.rightRegime ?? null,
        };
      default:
        return op;
    }
  });
  return `sk:${jcsSha256Hex({
    v: 1,
    kind: offer.kind ?? "pulse",
    writeRanges: ranges,
    ops: canonicalOps,
  }).slice(0, 32)}`;
}

/**
 * Check whether an offer is suppressed by a prior user dismissal (`offer_reject`) whose
 * `writeRanges` have not yet been touched by a subsequent user edit.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} stateOrSnapshot
 * @param {{ semanticKey?: string, kind?: string, writeRanges: readonly import("../index.d.ts").RangeUs[], ops: readonly import("../index.d.ts").OfferOp[] }} offer
 * @returns {boolean}
 */
export function isOfferSuppressed(stateOrSnapshot, offer) {
  const suppressions = stateOrSnapshot?.suppressions;
  if (!Array.isArray(suppressions) || suppressions.length === 0) return false;
  const key = offer.semanticKey || computeOfferSemanticKey(offer);
  return suppressions.some(
    (entry) =>
      entry.semanticKey === key &&
      entry.writeRanges.some((er) => offer.writeRanges.some((or) => rangesOverlapUs(er, or))),
  );
}

/**
 * Compute structured diff and numeric `OfferConsequences` between `beforeState` and `afterState`.
 *
 * @param {import("../index.d.ts").SessionState} beforeState
 * @param {import("../index.d.ts").SessionState} afterState
 * @returns {{ diff: import("../index.d.ts").OfferPreviewDiff, consequences: import("../index.d.ts").OfferConsequences }}
 */
export function computeOfferDiffAndConsequences(beforeState, afterState) {
  /** @type {import("../index.d.ts").Beat[]} */
  const beatsAdded = [];
  /** @type {Array<{ before: import("../index.d.ts").Beat, after: import("../index.d.ts").Beat, deltaUs: number }>} */
  const beatsMoved = [];
  /** @type {import("../index.d.ts").Beat[]} */
  const beatsRemoved = [];
  let maxShiftUs = 0;

  for (const [id, bBefore] of beforeState.beats) {
    const bAfter = afterState.beats.get(id);
    if (!bAfter) {
      beatsRemoved.push(bBefore);
    } else if (bAfter.atUs !== bBefore.atUs) {
      const deltaUs = bAfter.atUs - bBefore.atUs;
      const absShift = Math.abs(deltaUs);
      if (absShift > maxShiftUs) maxShiftUs = absShift;
      beatsMoved.push({ before: bBefore, after: bAfter, deltaUs });
    }
  }
  for (const [id, bAfter] of afterState.beats) {
    if (!beforeState.beats.has(id)) {
      beatsAdded.push(bAfter);
    }
  }
  beatsAdded.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  beatsMoved.sort((a, b) => a.after.atUs - b.after.atUs || compareCodeUnits(a.after.id, b.after.id));
  beatsRemoved.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  /** @type {import("../index.d.ts").Unbeat[]} */
  const unbeatsAdded = [];
  /** @type {Array<{ before: import("../index.d.ts").Unbeat, after: import("../index.d.ts").Unbeat, deltaUs: number }>} */
  const unbeatsMoved = [];
  /** @type {import("../index.d.ts").Unbeat[]} */
  const unbeatsRemoved = [];
  for (const [id, uBefore] of beforeState.unbeats) {
    const uAfter = afterState.unbeats.get(id);
    if (!uAfter) {
      unbeatsRemoved.push(uBefore);
    } else if (uAfter.revision !== uBefore.revision) {
      const deltaUs = uAfter.atUs - uBefore.atUs;
      const absShift = Math.abs(deltaUs);
      if (absShift > maxShiftUs) maxShiftUs = absShift;
      unbeatsMoved.push({ before: uBefore, after: uAfter, deltaUs });
    }
  }
  for (const [id, uAfter] of afterState.unbeats) {
    if (!beforeState.unbeats.has(id)) unbeatsAdded.push(uAfter);
  }
  unbeatsAdded.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  unbeatsMoved.sort((a, b) => a.after.atUs - b.after.atUs || compareCodeUnits(a.after.id, b.after.id));
  unbeatsRemoved.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  /** @type {import("../index.d.ts").Sound[]} */
  const soundsAdded = [];
  /** @type {Array<{ before: import("../index.d.ts").Sound, after: import("../index.d.ts").Sound }>} */
  const soundsUpdated = [];
  /** @type {import("../index.d.ts").Sound[]} */
  const soundsRemoved = [];
  for (const [id, sBefore] of beforeState.sounds) {
    const sAfter = afterState.sounds.get(id);
    if (!sAfter) {
      soundsRemoved.push(sBefore);
    } else if (sAfter.revision !== sBefore.revision) {
      soundsUpdated.push({ before: sBefore, after: sAfter });
    }
  }
  for (const [id, sAfter] of afterState.sounds) {
    if (!beforeState.sounds.has(id)) soundsAdded.push(sAfter);
  }
  soundsAdded.sort((a, b) => a.startUs - b.startUs || compareCodeUnits(a.id, b.id));
  soundsUpdated.sort((a, b) => a.after.startUs - b.after.startUs || compareCodeUnits(a.after.id, b.after.id));
  soundsRemoved.sort((a, b) => a.startUs - b.startUs || compareCodeUnits(a.id, b.id));

  /** @type {import("../index.d.ts").Boundary[]} */
  const boundariesAdded = [];
  /** @type {Array<{ before: import("../index.d.ts").Boundary, after: import("../index.d.ts").Boundary, deltaUs: number }>} */
  const boundariesMoved = [];
  /** @type {import("../index.d.ts").Boundary[]} */
  const boundariesRemoved = [];
  for (const [id, bndBefore] of beforeState.boundaries) {
    const bndAfter = afterState.boundaries.get(id);
    if (!bndAfter) {
      boundariesRemoved.push(bndBefore);
    } else if (bndAfter.atUs !== bndBefore.atUs) {
      boundariesMoved.push({
        before: bndBefore,
        after: bndAfter,
        deltaUs: bndAfter.atUs - bndBefore.atUs,
      });
    }
  }
  for (const [id, bndAfter] of afterState.boundaries) {
    if (!beforeState.boundaries.has(id)) boundariesAdded.push(bndAfter);
  }
  boundariesAdded.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));
  boundariesMoved.sort((a, b) => a.after.atUs - b.after.atUs || compareCodeUnits(a.after.id, b.after.id));
  boundariesRemoved.sort((a, b) => a.atUs - b.atUs || compareCodeUnits(a.id, b.id));

  const beforeSegMap = new Map(beforeState.segments.map((s) => [s.id, s]));
  const afterSegMap = new Map(afterState.segments.map((s) => [s.id, s]));
  /** @type {import("../index.d.ts").Segment[]} */
  const segmentsAdded = [];
  /** @type {Array<{ before: import("../index.d.ts").Segment, after: import("../index.d.ts").Segment }>} */
  const segmentsUpdated = [];
  /** @type {import("../index.d.ts").Segment[]} */
  const segmentsRemoved = [];
  for (const [id, segBefore] of beforeSegMap) {
    const segAfter = afterSegMap.get(id);
    if (!segAfter) segmentsRemoved.push(segBefore);
    else if (segAfter.revision !== segBefore.revision) {
      segmentsUpdated.push({ before: segBefore, after: segAfter });
    }
  }
  for (const [id, segAfter] of afterSegMap) {
    if (!beforeSegMap.has(id)) segmentsAdded.push(segAfter);
  }

  const diff = deepFreeze({
    beats: { added: beatsAdded, moved: beatsMoved, removed: beatsRemoved },
    unbeats: { added: unbeatsAdded, moved: unbeatsMoved, removed: unbeatsRemoved },
    sounds: { added: soundsAdded, updated: soundsUpdated, removed: soundsRemoved },
    boundaries: { added: boundariesAdded, moved: boundariesMoved, removed: boundariesRemoved },
    segments: { added: segmentsAdded, updated: segmentsUpdated, removed: segmentsRemoved },
  });

  const consequences = deepFreeze({
    beatsAdded: beatsAdded.length,
    beatsMoved: beatsMoved.length,
    beatsRemoved: beatsRemoved.length,
    unbeatsMoved: unbeatsMoved.length,
    maxShiftUs,
    soundsAdded: soundsAdded.length,
    soundsUpdated: soundsUpdated.length,
    soundsRemoved: soundsRemoved.length,
    boundaryChanges:
      boundariesAdded.length + boundariesMoved.length + boundariesRemoved.length,
    segmentChanges: segmentsAdded.length + segmentsUpdated.length + segmentsRemoved.length,
  });

  return { diff, consequences };
}

/**
 * Format a concise human-safe summary string from `OfferConsequences` (zero internal IDs).
 *
 * @param {import("../index.d.ts").OfferConsequences} c
 * @returns {string}
 */
export function formatConsequenceSummary(c) {
  /** @type {string[]} */
  const parts = [];
  if (c.beatsMoved > 0) {
    const maxMs = Math.round(c.maxShiftUs / 1000);
    const shiftNote = maxMs >= 1 ? ` (up to ${maxMs} ms)` : "";
    parts.push(`Nudges ${c.beatsMoved} beat${c.beatsMoved === 1 ? "" : "s"}${shiftNote}`);
  }
  if (c.unbeatsMoved > 0) {
    parts.push(`nudges ${c.unbeatsMoved} unbeat${c.unbeatsMoved === 1 ? "" : "s"}`);
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
  if (c.soundsAdded > 0 || c.soundsUpdated > 0 || c.soundsRemoved > 0) {
    const totalSounds = c.soundsAdded + c.soundsUpdated + c.soundsRemoved;
    parts.push(`matches ${totalSounds} sound${totalSounds === 1 ? "" : "s"}`);
  }
  if (c.boundaryChanges > 0 || c.segmentChanges > 0) {
    const count = Math.max(c.boundaryChanges, c.segmentChanges);
    parts.push(`updates ${count} section${count === 1 ? "" : "s"}`);
  }
  if (parts.length === 0) return "No timing changes";
  const joined = parts.join(" · ");
  return joined.charAt(0).toUpperCase() + joined.slice(1);
}

/**
 * Detect mutually exclusive sibling offers sharing the same non-null `alternativeGroup`.
 * Offers in the same `batchId` WITHOUT a shared `alternativeGroup` are independent!
 *
 * @param {readonly import("../index.d.ts").Offer[]} offers
 * @returns {Map<string, number[]>}
 */
export function detectSiblingConflicts(offers) {
  /** @type {Map<string, number[]>} */
  const map = new Map();
  for (let i = 0; i < offers.length; i += 1) {
    const a = offers[i];
    if (!a.alternativeGroup) continue;
    for (let j = i + 1; j < offers.length; j += 1) {
      const b = offers[j];
      if (a.id === b.id || b.alternativeGroup !== a.alternativeGroup) continue;
      if (!map.has(a.id)) map.set(a.id, []);
      if (!map.has(b.id)) map.set(b.id, []);
      map.get(a.id).push(b.optionIndex ?? j);
      map.get(b.id).push(a.optionIndex ?? i);
    }
  }
  return map;
}

/**
 * Compute canonical merged effective `(regime, kinematics)` intervals across `[0, durationUs)`.
 *
 * @param {import("../index.d.ts").SessionState} state
 * @returns {Array<{ startUs: number, endUs: number, regime: string, kinematics: string | null }>}
 */
function computeEffectivePolicySlices(state) {
  /** @type {Array<{ startUs: number, endUs: number, regime: string, kinematics: string | null }>} */
  const slices = [];
  const push = (s, e, regime, kinematics) => {
    if (e <= s) return;
    const prev = slices[slices.length - 1];
    if (prev && prev.endUs === s && prev.regime === regime && prev.kinematics === kinematics) {
      prev.endUs = e;
    } else {
      slices.push({ startUs: s, endUs: e, regime, kinematics });
    }
  };

  for (let i = 0; i < state.segments.length; i += 1) {
    const seg = state.segments[i];
    const kin = seg.kinematics ?? null;
    if (seg.regime !== "auto") {
      push(seg.startUs, seg.endUs, seg.regime, kin);
      continue;
    }
    const freeRanges = mergeRangesUs(
      state.spans
        .filter((sp) => sp.segmentId === seg.id && sp.kind === "free")
        .map((sp) => ({
          startUs: Math.max(seg.startUs, sp.startUs),
          endUs: Math.min(seg.endUs, sp.endUs),
        }))
        .filter((r) => r.endUs > r.startUs),
    );
    if (freeRanges.length === 0) {
      push(seg.startUs, seg.endUs, "auto", kin);
    } else {
      let cursor = seg.startUs;
      for (const fr of freeRanges) {
        if (fr.startUs > cursor) push(cursor, fr.startUs, "auto", kin);
        push(fr.startUs, fr.endUs, "free", kin);
        cursor = fr.endUs;
      }
      if (cursor < seg.endUs) push(cursor, seg.endUs, "auto", kin);
    }
  }
  return slices;
}

/**
 * Validate a single `OfferOp` against `stepState` (either `beforeState` or live mid-transaction
 * state!) and `mergedWriteRanges`. Catches intra-transaction created entities & split/merge tails!
 *
 * @param {import("../index.d.ts").SessionState} stepState
 * @param {any} op
 * @param {readonly import("../index.d.ts").RangeUs[]} mergedWriteRanges
 * @returns {import("../index.d.ts").OfferConflict[]}
 */
function checkSingleOpWithinWriteRanges(stepState, op, mergedWriteRanges) {
  /** @type {import("../index.d.ts").OfferConflict[]} */
  const conflicts = [];
  const pointInWrite = (atUs) =>
    typeof atUs === "number" &&
    mergedWriteRanges.some((wr) => atUs >= wr.startUs && atUs < wr.endUs);
  const intervalInWrite = (startUs, endUs) =>
    typeof startUs === "number" &&
    typeof endUs === "number" &&
    mergedWriteRanges.some((wr) => startUs >= wr.startUs && endUs <= wr.endUs);

  if (!op || typeof op !== "object") {
    conflicts.push({
      kind: "invalid_op",
      message: "Suggestion contains a malformed operation.",
    });
    return conflicts;
  }
  if (!isOfferOpKind(op.op)) {
    conflicts.push({
      kind: "authority_violation",
      message: `Suggestion attempts user-only operation "${String(op.op)}".`,
    });
    return conflicts;
  }

  switch (op.op) {
    case "upsert_beat": {
      const atUs = op.beat?.atUs;
      const existing =
        typeof op.beat?.id === "string" ? stepState.beats.get(op.beat.id) : undefined;
      if (!pointInWrite(atUs) || (existing && !pointInWrite(existing.atUs))) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion modifies a beat near ${formatTimeUs(atUs ?? 0)} outside its target range.`,
        });
      }
      break;
    }
    case "move_beat":
    case "snap_beat": {
      const existing = stepState.beats.get(op.id);
      if (!pointInWrite(op.atUs) || (existing && !pointInWrite(existing.atUs))) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion moves a beat near ${formatTimeUs(op.atUs)} outside its target range.`,
        });
      }
      break;
    }
    case "shift_beats": {
      if (
        !intervalInWrite(op.startUs, op.endUs) ||
        !intervalInWrite(op.startUs + op.deltaUs, op.endUs + op.deltaUs)
      ) {
        conflicts.push({
          kind: "out_of_scope",
          message: "Suggestion shifts beats outside its target range.",
        });
      }
      break;
    }
    case "remove_beats": {
      for (const id of op.ids ?? []) {
        const existing = stepState.beats.get(id);
        if (existing && !pointInWrite(existing.atUs)) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion removes a beat near ${formatTimeUs(existing.atUs)} outside its target range.`,
          });
        }
      }
      break;
    }
    case "move_unbeat": {
      const existing = stepState.unbeats.get(op.id);
      if (!pointInWrite(op.atUs) || (existing && !pointInWrite(existing.atUs))) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion moves an unbeat near ${formatTimeUs(op.atUs ?? 0)} outside its target range.`,
        });
      }
      break;
    }
    case "remove_unbeats": {
      for (const id of op.ids ?? []) {
        const existing = stepState.unbeats.get(id);
        if (existing && !pointInWrite(existing.atUs)) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion removes an unbeat near ${formatTimeUs(existing.atUs)} outside its target range.`,
          });
        }
      }
      break;
    }
    case "upsert_sound": {
      const s = op.sound;
      const existing = typeof s?.id === "string" ? stepState.sounds.get(s.id) : undefined;
      if (
        !intervalInWrite(s?.startUs, s?.endUs) ||
        (existing && !intervalInWrite(existing.startUs, existing.endUs))
      ) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion modifies a sound near ${formatTimeUs(s?.startUs ?? 0)} outside its target range.`,
        });
      }
      break;
    }
    case "update_sound": {
      const existing = stepState.sounds.get(op.id);
      const nextStart = op.patch?.startUs ?? existing?.startUs;
      const nextEnd = op.patch?.endUs ?? existing?.endUs;
      if (
        (existing && !intervalInWrite(existing.startUs, existing.endUs)) ||
        !intervalInWrite(nextStart, nextEnd)
      ) {
        conflicts.push({
          kind: "out_of_scope",
          message: "Suggestion updates a sound outside its target range.",
        });
      }
      break;
    }
    case "remove_sounds": {
      for (const id of op.ids ?? []) {
        const existing = stepState.sounds.get(id);
        if (existing && !intervalInWrite(existing.startUs, existing.endUs)) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion removes a sound near ${formatTimeUs(existing.startUs)} outside its target range.`,
          });
        }
      }
      break;
    }
    case "split_segment": {
      const inside = mergedWriteRanges.some(
        (wr) => op.atUs > wr.startUs && op.atUs < wr.endUs,
      );
      if (!inside) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion splits a section at ${formatTimeUs(op.atUs)} outside its target range.`,
        });
        break;
      }
      const seg = stepState.segments.find((s) => s.id === op.segmentId);
      if (seg && op.atUs > seg.startUs && op.atUs < seg.endUs) {
        const changesRightRegime =
          op.rightRegime !== undefined && op.rightRegime !== seg.regime;
        const changesRightKinematics =
          op.rightKinematics !== undefined &&
          (op.rightKinematics ?? null) !== (seg.kinematics ?? null);
        if ((changesRightRegime || changesRightKinematics) && !intervalInWrite(op.atUs, seg.endUs)) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion changes section policy over ${formatTimeUs(op.atUs)}–${formatTimeUs(seg.endUs)} outside its target range.`,
          });
        }
      }
      break;
    }
    case "move_boundary": {
      const bnd = stepState.boundaries.get(op.boundaryId);
      if (bnd) {
        const lo = Math.min(bnd.atUs, op.atUs);
        const hi = Math.max(bnd.atUs, op.atUs);
        const covered = mergedWriteRanges.some(
          (wr) =>
            wr.startUs <= lo &&
            wr.endUs >= hi &&
            (lo < hi || (op.atUs > wr.startUs && op.atUs < wr.endUs)),
        );
        if (!covered) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion moves a boundary to ${formatTimeUs(op.atUs)} outside its target range.`,
          });
        }
      } else {
        const destInside = mergedWriteRanges.some(
          (wr) => typeof op.atUs === "number" && op.atUs > wr.startUs && op.atUs < wr.endUs,
        );
        if (!destInside) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion moves a boundary to ${formatTimeUs(op.atUs ?? 0)} outside its target range.`,
          });
        }
      }
      break;
    }
    case "merge_segments": {
      const leftIdx = stepState.segments.findIndex((s) => s.id === op.leftSegmentId);
      const left = leftIdx >= 0 ? stepState.segments[leftIdx] : undefined;
      const right =
        leftIdx >= 0 &&
        leftIdx + 1 < stepState.segments.length &&
        stepState.segments[leftIdx + 1].id === op.rightSegmentId
          ? stepState.segments[leftIdx + 1]
          : undefined;
      if (left) {
        const covered = mergedWriteRanges.some(
          (wr) => left.endUs > wr.startUs && left.endUs < wr.endUs,
        );
        if (!covered) {
          conflicts.push({
            kind: "out_of_scope",
            message: `Suggestion merges sections at ${formatTimeUs(left.endUs)} outside its target range.`,
          });
          break;
        }
        if (right) {
          const resolvedRegime =
            op.mergedRegime ??
            (left.regime === right.regime
              ? left.regime
              : left.regime === "auto" || right.regime === "auto"
                ? "auto"
                : "free");
          const resolvedKinematics =
            op.mergedKinematics === null
              ? null
              : op.mergedKinematics !== undefined
                ? op.mergedKinematics
                : (left.kinematics ?? null) === (right.kinematics ?? null)
                  ? (left.kinematics ?? null)
                  : null;
          // Note: merging `"free"` + `"auto"` into `"auto"` preserves the `"free"` half via child Span,
          // so effective regime only changes on a half if `break` is involved or `resolvedRegime === "free"` on an `"auto"` half!
          const leftRegimeChanged =
            left.regime !== resolvedRegime &&
            !(left.regime === "free" && resolvedRegime === "auto");
          const rightRegimeChanged =
            right.regime !== resolvedRegime &&
            !(right.regime === "free" && resolvedRegime === "auto");
          const leftKinChanged = (left.kinematics ?? null) !== resolvedKinematics;
          const rightKinChanged = (right.kinematics ?? null) !== resolvedKinematics;

          if ((leftRegimeChanged || leftKinChanged) && !intervalInWrite(left.startUs, left.endUs)) {
            conflicts.push({
              kind: "out_of_scope",
              message: `Suggestion changes section policy over ${formatTimeUs(left.startUs)}–${formatTimeUs(left.endUs)} outside its target range.`,
            });
          } else if (
            (rightRegimeChanged || rightKinChanged) &&
            !intervalInWrite(right.startUs, right.endUs)
          ) {
            conflicts.push({
              kind: "out_of_scope",
              message: `Suggestion changes section policy over ${formatTimeUs(right.startUs)}–${formatTimeUs(right.endUs)} outside its target range.`,
            });
          }
        }
      }
      break;
    }
    case "promote_range_to_segments":
    case "set_range_regime": {
      if (!intervalInWrite(op.startUs, op.endUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: "Suggestion modifies section range outside its target range.",
        });
      }
      break;
    }
    case "set_segment_regime":
    case "set_segment_kinematics": {
      const seg = stepState.segments.find((s) => s.id === op.segmentId);
      if (seg && !intervalInWrite(seg.startUs, seg.endUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: "Suggestion changes section mode outside its target range.",
        });
      }
      break;
    }
    default:
      break;
  }
  return conflicts;
}

/**
 * Check that every op in `offer.ops` targets coordinates inside `mergedWriteRanges`.
 *
 * @param {import("../index.d.ts").SessionState} beforeState
 * @param {readonly import("../index.d.ts").OfferOp[]} ops
 * @param {readonly import("../index.d.ts").RangeUs[]} mergedWriteRanges
 * @returns {import("../index.d.ts").OfferConflict[]}
 */
function checkOpsWithinWriteRanges(beforeState, ops, mergedWriteRanges) {
  /** @type {import("../index.d.ts").OfferConflict[]} */
  const conflicts = [];
  for (let i = 0; i < ops.length; i += 1) {
    conflicts.push(...checkSingleOpWithinWriteRanges(beforeState, ops[i], mergedWriteRanges));
  }
  return conflicts;
}

/**
 * Compare full `beforeState` vs `afterState` footprint to verify zero entity mutations or
 * effective `(regime, kinematics)` sub-interval changes escaped `mergedWriteRanges`.
 *
 * @param {import("../index.d.ts").SessionState} beforeState
 * @param {import("../index.d.ts").SessionState} afterState
 * @param {readonly import("../index.d.ts").RangeUs[]} mergedWriteRanges
 * @returns {import("../index.d.ts").OfferConflict[]}
 */
function checkStateDiffWithinWriteRanges(beforeState, afterState, mergedWriteRanges) {
  /** @type {import("../index.d.ts").OfferConflict[]} */
  const conflicts = [];
  const pointInWrite = (atUs) =>
    mergedWriteRanges.some((wr) => atUs >= wr.startUs && atUs < wr.endUs);
  const intervalInWrite = (startUs, endUs) =>
    mergedWriteRanges.some((wr) => startUs >= wr.startUs && endUs <= wr.endUs);

  for (const [id, bBefore] of beforeState.beats) {
    const bAfter = afterState.beats.get(id);
    if (!bAfter) {
      if (!pointInWrite(bBefore.atUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion removes a beat near ${formatTimeUs(bBefore.atUs)} outside its target range.`,
        });
      }
    } else if (
      bAfter.revision !== bBefore.revision &&
      (!pointInWrite(bBefore.atUs) || !pointInWrite(bAfter.atUs))
    ) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion modifies a beat near ${formatTimeUs(bAfter.atUs)} outside its target range.`,
      });
    }
  }
  for (const [id, bAfter] of afterState.beats) {
    if (!beforeState.beats.has(id) && !pointInWrite(bAfter.atUs)) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion adds a beat near ${formatTimeUs(bAfter.atUs)} outside its target range.`,
      });
    }
  }

  for (const [id, uBefore] of beforeState.unbeats) {
    const uAfter = afterState.unbeats.get(id);
    if (!uAfter) {
      if (!pointInWrite(uBefore.atUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion removes an unbeat near ${formatTimeUs(uBefore.atUs)} outside its target range.`,
        });
      }
    } else if (
      uAfter.revision !== uBefore.revision &&
      (!pointInWrite(uBefore.atUs) || !pointInWrite(uAfter.atUs))
    ) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion modifies an unbeat near ${formatTimeUs(uAfter.atUs)} outside its target range.`,
      });
    }
  }
  for (const [id, uAfter] of afterState.unbeats) {
    if (!beforeState.unbeats.has(id) && !pointInWrite(uAfter.atUs)) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion adds an unbeat near ${formatTimeUs(uAfter.atUs)} outside its target range.`,
      });
    }
  }

  for (const [id, sBefore] of beforeState.sounds) {
    const sAfter = afterState.sounds.get(id);
    if (!sAfter) {
      if (!intervalInWrite(sBefore.startUs, sBefore.endUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion removes a sound near ${formatTimeUs(sBefore.startUs)} outside its target range.`,
        });
      }
    } else if (
      sAfter.revision !== sBefore.revision &&
      (!intervalInWrite(sBefore.startUs, sBefore.endUs) ||
        !intervalInWrite(sAfter.startUs, sAfter.endUs))
    ) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion modifies a sound near ${formatTimeUs(sAfter.startUs)} outside its target range.`,
      });
    }
  }
  for (const [id, sAfter] of afterState.sounds) {
    if (!beforeState.sounds.has(id) && !intervalInWrite(sAfter.startUs, sAfter.endUs)) {
      conflicts.push({
        kind: "out_of_scope",
        message: `Suggestion adds a sound near ${formatTimeUs(sAfter.startUs)} outside its target range.`,
      });
    }
  }

  for (const [id, bndBefore] of beforeState.boundaries) {
    const bndAfter = afterState.boundaries.get(id);
    if (!bndAfter) {
      const covered = mergedWriteRanges.some(
        (wr) => bndBefore.atUs > wr.startUs && bndBefore.atUs < wr.endUs,
      );
      if (!covered) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion removes a section boundary at ${formatTimeUs(bndBefore.atUs)} outside its target range.`,
        });
      }
    } else if (bndAfter.atUs !== bndBefore.atUs) {
      const lo = Math.min(bndBefore.atUs, bndAfter.atUs);
      const hi = Math.max(bndBefore.atUs, bndAfter.atUs);
      const covered = mergedWriteRanges.some((wr) => wr.startUs <= lo && wr.endUs >= hi);
      if (!covered) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion sweeps a section boundary across ${formatTimeUs(lo)}–${formatTimeUs(hi)} outside its target range.`,
        });
      }
    }
  }
  for (const [id, bndAfter] of afterState.boundaries) {
    if (!beforeState.boundaries.has(id)) {
      const covered = mergedWriteRanges.some(
        (wr) => bndAfter.atUs >= wr.startUs && bndAfter.atUs <= wr.endUs,
      );
      if (!covered) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion creates a section boundary at ${formatTimeUs(bndAfter.atUs)} outside its target range.`,
        });
      }
    }
  }

  // Compare effective `(regime, kinematics)` step functions across `[0, durationUs)`
  const beforePolicy = computeEffectivePolicySlices(beforeState);
  const afterPolicy = computeEffectivePolicySlices(afterState);
  if (beforePolicy.length > 0 || afterPolicy.length > 0) {
    const cutSet = new Set([0, beforeState.durationUs]);
    for (const s of beforePolicy) {
      cutSet.add(s.startUs);
      cutSet.add(s.endUs);
    }
    for (const s of afterPolicy) {
      cutSet.add(s.startUs);
      cutSet.add(s.endUs);
    }
    const cuts = Array.from(cutSet).sort((a, b) => a - b);
    const lookupPolicy = (slices, midUs) => {
      for (let i = 0; i < slices.length; i += 1) {
        if (midUs >= slices[i].startUs && midUs < slices[i].endUs) return slices[i];
      }
      return null;
    };
    /** @type {Array<{ startUs: number, endUs: number }>} */
    const rawChanged = [];
    for (let i = 0; i + 1 < cuts.length; i += 1) {
      const a = cuts[i];
      const b = cuts[i + 1];
      if (b <= a) continue;
      const pb = lookupPolicy(beforePolicy, a);
      const pa = lookupPolicy(afterPolicy, a);
      if ((pb?.regime ?? "auto") !== (pa?.regime ?? "auto") || (pb?.kinematics ?? null) !== (pa?.kinematics ?? null)) {
        rawChanged.push({ startUs: a, endUs: b });
      }
    }
    const changedIntervals = mergeRangesUs(rawChanged);
    for (const ch of changedIntervals) {
      if (!intervalInWrite(ch.startUs, ch.endUs)) {
        conflicts.push({
          kind: "out_of_scope",
          message: `Suggestion alters section policy over ${formatTimeUs(ch.startUs)}–${formatTimeUs(ch.endUs)} outside its target range.`,
        });
      }
    }
  }

  return conflicts;
}

/**
 * Evaluate `offer` against `snapshotOrState`, returning both validation and scratch state
 * (shared by `validateOffer`, `previewOffer`, and `acceptOffer`!).
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshotOrState
 * @param {import("../index.d.ts").Offer} offer
 * @param {{ ignoreTerminalStatus?: boolean }} [options]
 */
export function evaluateOfferInternal(snapshotOrState, offer, options = {}) {
  const beforeState = toScratchSessionState(snapshotOrState);
  /** @type {import("../index.d.ts").StructuredNote[]} */
  const notes = Array.isArray(offer?.notes) ? offer.notes.map((n) => normalizeStructuredNote(n)) : [];
  const pushNote = (code, message, params) => {
    if (!notes.some((n) => n.code === code && n.message === message)) {
      notes.push(deepFreeze({ code, message, ...(params ? { params } : {}) }));
    }
  };

  if (!options.ignoreTerminalStatus) {
    if (offer.status === "accepted") {
      return {
        validation: deepFreeze({
          valid: false,
          status: "accepted",
          conflicts: [
            { kind: "version_mismatch", message: "This suggestion has already been applied." },
          ],
          requiredChallenges: [],
          notes,
        }),
        beforeState,
        afterState: beforeState,
        executedOk: false,
      };
    }
    if (offer.status === "rejected") {
      return {
        validation: deepFreeze({
          valid: false,
          status: "rejected",
          conflicts: [{ kind: "version_mismatch", message: "This suggestion was dismissed." }],
          requiredChallenges: [],
          notes,
        }),
        beforeState,
        afterState: beforeState,
        executedOk: false,
      };
    }
    if (offer.status === "superseded") {
      return {
        validation: deepFreeze({
          valid: false,
          status: "superseded",
          conflicts: [
            { kind: "sibling_conflict", message: "Another option in this group was applied." },
          ],
          requiredChallenges: [],
          notes,
        }),
        beforeState,
        afterState: beforeState,
        executedOk: false,
      };
    }
  }

  // Mutual exclusion ONLY across identical non-null `alternativeGroup`!
  if (offer.alternativeGroup) {
    for (const other of beforeState.offers.values()) {
      if (
        other.id !== offer.id &&
        other.alternativeGroup === offer.alternativeGroup &&
        other.status === "accepted"
      ) {
        return {
          validation: deepFreeze({
            valid: false,
            status: "superseded",
            conflicts: [
              {
                kind: "sibling_conflict",
                message: "Another alternative for this passage was already applied.",
              },
            ],
            requiredChallenges: [],
            notes,
          }),
          beforeState,
          afterState: beforeState,
          executedOk: false,
        };
      }
    }
  }

  /** @type {import("../index.d.ts").OfferConflict[]} */
  const conflicts = [];
  const pushConflict = (c) => {
    if (
      !conflicts.some(
        (x) =>
          x.kind === c.kind &&
          (x.message === c.message ||
            (c.kind === "protected_keep" && x.startUs === c.startUs && x.endUs === c.endUs)),
      )
    ) {
      conflicts.push(deepFreeze(c));
    }
  };

  let mergedWriteRanges = [];
  try {
    if (!Array.isArray(offer.writeRanges) || offer.writeRanges.length === 0) {
      throw new EnsomiError("INVALID_RANGE", "Missing writeRanges");
    }
    mergedWriteRanges = mergeRangesUs(
      offer.writeRanges.map((r) =>
        assertRangeUs(r, beforeState.durationUs > 0 ? beforeState.durationUs : undefined),
      ),
    );
  } catch {
    return {
      validation: deepFreeze({
        valid: false,
        status: "invalid",
        conflicts: [
          { kind: "out_of_scope", message: "Suggestion target range is outside track bounds." },
        ],
        requiredChallenges: [],
        notes,
      }),
      beforeState,
      afterState: beforeState,
      executedOk: false,
    };
  }

  if (beforeState.audio && offer.audioId !== beforeState.audio.audioId) {
    pushConflict({
      kind: "range_changed",
      message: "Bound audio changed since this suggestion was prepared.",
    });
  }

  // 1. Scope & forbidden-op pre-check against `beforeState`
  for (const sc of checkOpsWithinWriteRanges(beforeState, offer.ops ?? [], mergedWriteRanges)) {
    pushConflict(sc);
  }

  // 2. Compare `offer.dependencies` & `offer.basisDigest` against current state!
  if (offer.dependencies && typeof offer.dependencies === "object") {
    const currentDeps = computeOfferDependencies(
      beforeState,
      offer.writeRanges,
      offer.derivedFrom
        ? {
            range: offer.derivedFrom.range,
            supportingIds: offer.derivedFrom.supportingIds,
          }
        : undefined,
      offer.ops ?? [],
    );
    const currentDigest = computeOfferBasisDigest(currentDeps);

    if (currentDigest !== offer.basisDigest) {
      // Classify staleness precisely: `source_changed`, `range_changed`, `policy_changed`, `version_mismatch`
      if (offer.dependencies.derivedFrom || offer.derivedFrom) {
        const prevDerived = offer.dependencies.derivedFrom;
        const currDerived = currentDeps.derivedFrom;
        if (
          !prevDerived ||
          !currDerived ||
          canonicalJson(prevDerived) !== canonicalJson(currDerived) ||
          (offer.derivedFrom && currDerived.sourceDigest !== offer.derivedFrom.sourceDigest)
        ) {
          pushConflict({
            kind: "source_changed",
            message: "Source passage used to derive this suggestion was edited or retracted.",
            ...(offer.derivedFrom?.range
              ? { startUs: offer.derivedFrom.range.startUs, endUs: offer.derivedFrom.range.endUs }
              : {}),
          });
        }
      }

      const prevTopology = {
        boundaries: offer.dependencies.boundaries,
        segBounds: (offer.dependencies.segments ?? []).map((s) => ({
          id: s.id,
          startUs: s.startUs,
          endUs: s.endUs,
        })),
      };
      const currTopology = {
        boundaries: currentDeps.boundaries,
        segBounds: currentDeps.segments.map((s) => ({
          id: s.id,
          startUs: s.startUs,
          endUs: s.endUs,
        })),
      };
      if (canonicalJson(prevTopology) !== canonicalJson(currTopology)) {
        pushConflict({
          kind: "range_changed",
          message: "Section boundaries in this passage changed since the suggestion was prepared.",
        });
      }

      const prevPolicy = {
        segPolicies: (offer.dependencies.segments ?? []).map((s) => ({
          id: s.id,
          regime: s.regime,
          kinematics: s.kinematics,
        })),
        freeSpans: (offer.dependencies.spans ?? []).filter((sp) => sp.kind === "free"),
      };
      const currPolicy = {
        segPolicies: currentDeps.segments.map((s) => ({
          id: s.id,
          regime: s.regime,
          kinematics: s.kinematics,
        })),
        freeSpans: currentDeps.spans.filter((sp) => sp.kind === "free"),
      };
      if (canonicalJson(prevPolicy) !== canonicalJson(currPolicy)) {
        pushConflict({
          kind: "policy_changed",
          message: "Section timing mode changed since this suggestion was prepared.",
        });
      }

      const prevMarks = {
        beats: offer.dependencies.beats,
        unbeats: offer.dependencies.unbeats,
        sounds: offer.dependencies.sounds,
        soundClasses: offer.dependencies.soundClasses,
      };
      const currMarks = {
        beats: currentDeps.beats,
        unbeats: currentDeps.unbeats,
        sounds: currentDeps.sounds,
        soundClasses: currentDeps.soundClasses,
      };
      if (canonicalJson(prevMarks) !== canonicalJson(currMarks)) {
        pushConflict({
          kind: "version_mismatch",
          message: "Marks in this passage changed since the suggestion was prepared.",
        });
      }

      // If only `approved` changed (user newly locked Keep!), the Keep check below emits `protected_keep`.
      // Catch any remaining digest difference not covered above:
      const newlyKept =
        currentDeps.segments.some((s) => s.approved) ||
        currentDeps.spans.some((sp) => sp.kind === "keep" && sp.approved);
      if (conflicts.length === 0 && !newlyKept) {
        pushConflict({
          kind: "version_mismatch",
          message: "Document state changed since this suggestion was prepared.",
        });
      }
    }
  }

  // 3. Execute `offer.ops` on scratch state using the SINGLE pure transaction interpreter,
  //    checking per-step live scope AND post-execution full state-diff scope!
  const afterState = toScratchSessionState(beforeState);
  const actorOrigin = offer.providerId?.includes("rule") ? "rule" : "model";
  let executedOk = false;

  if ((offer.ops ?? []).length > 0) {
    try {
      executeTransaction(afterState, offer.ops, {
        txId: `offer:${offer.id}`,
        eventId: `offer:${offer.id}`,
        actorMode: "offer",
        actorOrigin,
        onBeforeOp(liveState, rawOp) {
          for (const sc of checkSingleOpWithinWriteRanges(liveState, rawOp, mergedWriteRanges)) {
            pushConflict(sc);
          }
        },
      });
      executedOk = true;
    } catch (err) {
      const code = err instanceof EnsomiError ? err.code : "INVALID_EDIT";
      if (code === "AUTHORITY_VIOLATION") {
        pushConflict({
          kind: "authority_violation",
          message: err.message,
        });
      } else if (code === "OUT_OF_BOUNDS") {
        pushConflict({
          kind: "out_of_scope",
          message: err.message,
        });
      } else if (
        (code === "MISSING_ENTITY" ||
          code === "SOUND_NOT_FOUND" ||
          code === "INVALID_SEGMENT_EDIT") &&
        conflicts.some((c) => c.kind === "range_changed" || c.kind === "version_mismatch")
      ) {
        pushConflict({
          kind: "range_changed",
          message: "Target section or mark no longer matches current document structure.",
        });
      } else {
        pushConflict({
          kind: "invalid_op",
          message: err instanceof Error ? err.message : "Invalid suggestion operation.",
        });
      }
    }
  } else {
    executedOk = true;
  }

  if (executedOk) {
    for (const sc of checkStateDiffWithinWriteRanges(beforeState, afterState, mergedWriteRanges)) {
      pushConflict(sc);
    }
  }

  // 4. Full Influence-Footprint `Approval` (`keep`) check!
  //    Every active Approval in `beforeState` (both `Segment.approval` and `Span.approval`)
  //    must survive on its exact `[startUs, endUs)` interval with `postDigest === preDigest`
  //    AND zero ops directly touching entities inside `[startUs, endUs)`!
  const beforeKeepIntervals = [];
  for (const seg of beforeState.segments) {
    if (seg.approval) {
      beforeKeepIntervals.push({
        approvalId: seg.approval.id,
        kind: "segment",
        ownerId: seg.id,
        startUs: seg.startUs,
        endUs: seg.endUs,
      });
    }
  }
  for (const sp of beforeState.spans) {
    if (sp.kind === "keep" && sp.approval) {
      beforeKeepIntervals.push({
        approvalId: sp.approval.id,
        kind: "span",
        ownerId: sp.id,
        startUs: sp.startUs,
        endUs: sp.endUs,
      });
    }
  }

  if (beforeKeepIntervals.length > 0) {
    for (const kp of beforeKeepIntervals) {
      // Check direct op writes into `[kp.startUs, kp.endUs)` even if executedOk was false
      for (const op of offer.ops ?? []) {
        if (!op || typeof op !== "object") continue;
        let touchesKeep = false;
        if (op.op === "upsert_beat") {
          const ex = typeof op.beat?.id === "string" ? beforeState.beats.get(op.beat.id) : undefined;
          if (rangeContainsUs(kp, op.beat?.atUs) || (ex && rangeContainsUs(kp, ex.atUs))) {
            touchesKeep = true;
          }
        } else if (op.op === "move_beat" || op.op === "snap_beat") {
          const ex = beforeState.beats.get(op.id);
          if (rangeContainsUs(kp, op.atUs) || (ex && rangeContainsUs(kp, ex.atUs))) {
            touchesKeep = true;
          }
        } else if (op.op === "remove_beats") {
          for (const bid of op.ids ?? []) {
            const ex = beforeState.beats.get(bid);
            if (ex && rangeContainsUs(kp, ex.atUs)) touchesKeep = true;
          }
        } else if (op.op === "move_unbeat") {
          const ex = beforeState.unbeats.get(op.id);
          if (rangeContainsUs(kp, op.atUs) || (ex && rangeContainsUs(kp, ex.atUs))) {
            touchesKeep = true;
          }
        } else if (op.op === "remove_unbeats") {
          for (const uid of op.ids ?? []) {
            const ex = beforeState.unbeats.get(uid);
            if (ex && rangeContainsUs(kp, ex.atUs)) touchesKeep = true;
          }
        } else if (op.op === "shift_beats") {
          if (
            typeof op.startUs === "number" &&
            typeof op.endUs === "number" &&
            typeof op.deltaUs === "number" &&
            (rangesOverlapUs(kp, { startUs: op.startUs, endUs: op.endUs }) ||
              rangesOverlapUs(kp, {
                startUs: op.startUs + op.deltaUs,
                endUs: op.endUs + op.deltaUs,
              }))
          ) {
            touchesKeep = true;
          }
        } else if (
          op.op === "split_segment" &&
          (op.segmentId === kp.ownerId || (op.atUs > kp.startUs && op.atUs < kp.endUs))
        ) {
          touchesKeep = true;
        } else if (op.op === "set_segment_regime" && op.segmentId === kp.ownerId) {
          touchesKeep = true;
        } else if (op.op === "set_segment_kinematics" && op.segmentId === kp.ownerId) {
          touchesKeep = true;
        } else if (
          (op.op === "promote_range_to_segments" || op.op === "set_range_regime") &&
          typeof op.startUs === "number" &&
          typeof op.endUs === "number" &&
          rangesOverlapUs(kp, { startUs: op.startUs, endUs: op.endUs })
        ) {
          touchesKeep = true;
        } else if (op.op === "upsert_sound" && op.sound) {
          const ex = typeof op.sound.id === "string" ? beforeState.sounds.get(op.sound.id) : undefined;
          if (
            rangesOverlapUs(kp, { startUs: op.sound.startUs, endUs: op.sound.endUs }) ||
            (ex && rangesOverlapUs(kp, ex))
          ) {
            touchesKeep = true;
          }
        } else if (op.op === "update_sound") {
          const ex = beforeState.sounds.get(op.id);
          const nextStart = op.patch?.startUs ?? ex?.startUs;
          const nextEnd = op.patch?.endUs ?? ex?.endUs;
          if (
            (ex && rangesOverlapUs(kp, ex)) ||
            (typeof nextStart === "number" &&
              typeof nextEnd === "number" &&
              nextEnd > nextStart &&
              rangesOverlapUs(kp, { startUs: nextStart, endUs: nextEnd }))
          ) {
            touchesKeep = true;
          }
        } else if (op.op === "remove_sounds") {
          for (const sid of op.ids ?? []) {
            const ex = beforeState.sounds.get(sid);
            if (ex && rangesOverlapUs(kp, ex)) touchesKeep = true;
          }
        }

        if (touchesKeep) {
          pushConflict({
            kind: "protected_keep",
            message: `Keep-approved passage at ${formatTimeUs(kp.startUs)}–${formatTimeUs(kp.endUs)} blocks automated changes.`,
            startUs: kp.startUs,
            endUs: kp.endUs,
          });
        }
      }

      if (executedOk) {
        const survivesOwner =
          kp.kind === "segment"
            ? afterState.segments.some(
                (s) =>
                  s.id === kp.ownerId &&
                  s.startUs === kp.startUs &&
                  s.endUs === kp.endUs &&
                  s.approval?.id === kp.approvalId,
              )
            : afterState.spans.some(
                (sp) =>
                  sp.id === kp.ownerId &&
                  sp.startUs === kp.startUs &&
                  sp.endUs === kp.endUs &&
                  sp.approval?.id === kp.approvalId,
              );

        const preDigest = computeIntervalMusicalDigest(beforeState, kp.startUs, kp.endUs);
        const postDigest = computeIntervalMusicalDigest(afterState, kp.startUs, kp.endUs);

        if (!survivesOwner || postDigest !== preDigest) {
          pushConflict({
            kind: "protected_keep",
            message: `Keep-approved passage at ${formatTimeUs(kp.startUs)}–${formatTimeUs(kp.endUs)} blocks changes to its musical projection.`,
            startUs: kp.startUs,
            endUs: kp.endUs,
          });
        }
      }
    }
  }

  // 5. `protected_verified` & `OfferChallenge` check!
  const declaredChallenges = Array.isArray(offer.challenges) ? offer.challenges : [];
  const seenChallengeIds = new Set();
  const seenChallengeTargets = new Set();
  const seenChallengeReplacements = new Set();
  for (const ch of declaredChallenges) {
    if (!ch || typeof ch !== "object" || Array.isArray(ch)) {
      pushConflict({
        kind: "invalid_op",
        message: "Suggestion contains a malformed challenge entry.",
      });
      continue;
    }
    if (typeof ch.challengeId !== "string" || ch.challengeId.length === 0) {
      pushConflict({
        kind: "invalid_op",
        message: "Suggestion challenge is missing challengeId.",
      });
      continue;
    }
    if (seenChallengeIds.has(ch.challengeId)) {
      pushConflict({
        kind: "invalid_op",
        message: `Suggestion reuses duplicate challengeId "${ch.challengeId}".`,
      });
      continue;
    }
    seenChallengeIds.add(ch.challengeId);
    const targetKey = `${String(ch.targetKind)}:${String(ch.targetId)}`;
    if (seenChallengeTargets.has(targetKey)) {
      pushConflict({
        kind: "invalid_op",
        message: `Suggestion declares duplicate challenges targeting "${targetKey}".`,
      });
      continue;
    }
    seenChallengeTargets.add(targetKey);

    if (ch.replacementId !== undefined) {
      if (typeof ch.replacementId !== "string" || ch.replacementId.length === 0) {
        pushConflict({
          kind: "invalid_op",
          message: `Suggestion challenge "${ch.challengeId}" has an invalid replacementId.`,
        });
        continue;
      }
      const repKey = `${String(ch.targetKind)}:${ch.replacementId}`;
      if (seenChallengeReplacements.has(repKey)) {
        pushConflict({
          kind: "invalid_op",
          message: `Suggestion declares duplicate challenges claiming replacement "${repKey}".`,
        });
        continue;
      }
      seenChallengeReplacements.add(repKey);
    }

    const entity = getVerdictEntity(beforeState, ch.targetKind, ch.targetId);
    if (!entity || !entity.verdict) {
      pushConflict({
        kind: "version_mismatch",
        message: "Verified mark targeted by challenge changed since the suggestion was prepared.",
      });
      continue;
    }
    if (
      entity.revision !== ch.expectedRevision ||
      entity.verdict.valueDigest !== ch.expectedValueDigest
    ) {
      pushConflict({
        kind: "version_mismatch",
        message: "Verified mark targeted by challenge was edited after the suggestion was prepared.",
      });
    }
  }

  /** @type {import("../index.d.ts").OfferChallenge[]} */
  const requiredChallenges = [];

  if (executedOk) {
    const postVerdictTargets = new Set();
    for (const ch of declaredChallenges) {
      if (!ch || typeof ch !== "object" || Array.isArray(ch)) continue;
      if (typeof ch.targetKind !== "string" || typeof ch.targetId !== "string") continue;
      const afterTarget = getVerdictEntity(afterState, ch.targetKind, ch.targetId);
      if (ch.replacementId !== undefined) {
        if (typeof ch.replacementId !== "string" || ch.replacementId.length === 0) continue;
        const repEntity = getVerdictEntity(afterState, ch.targetKind, ch.replacementId);
        if (!repEntity) {
          pushConflict({
            kind: "invalid_op",
            message: `Challenge replacement "${ch.replacementId}" (${ch.targetKind}) does not exist in post-offer state.`,
          });
          continue;
        }
        if (afterTarget && ch.replacementId !== ch.targetId) {
          pushConflict({
            kind: "invalid_op",
            message: `Challenge "${ch.challengeId}" specifies replacementId "${ch.replacementId}" while target "${ch.targetId}" was not removed.`,
          });
          continue;
        }
      }
      const effectiveAfterId = afterTarget ? ch.targetId : ch.replacementId;
      if (effectiveAfterId !== undefined) {
        const postKey = `${ch.targetKind}:${effectiveAfterId}`;
        if (postVerdictTargets.has(postKey)) {
          pushConflict({
            kind: "invalid_op",
            message: `Multiple challenges map to the same post-offer mark "${postKey}".`,
          });
        } else {
          postVerdictTargets.add(postKey);
        }
      }
    }
    /**
     * Record a human-verdict violation and match against `declaredChallenges`.
     * @param {"beat" | "unbeat" | "sound" | "sound_class" | "boundary"} kind
     * @param {any} beforeEntity
     * @param {string} description
     */
    const handleVerifiedViolation = (kind, beforeEntity, description) => {
      const match = declaredChallenges.find(
        (ch) =>
          ch &&
          ch.targetKind === kind &&
          ch.targetId === beforeEntity.id &&
          ch.expectedRevision === beforeEntity.revision &&
          ch.expectedValueDigest === beforeEntity.verdict.valueDigest,
      );
      if (match) {
        if (!requiredChallenges.some((c) => c.challengeId === match.challengeId)) {
          requiredChallenges.push(deepFreeze({ ...match }));
        }
      } else {
        pushConflict({
          kind: "protected_verified",
          targetKind: kind,
          targetId: beforeEntity.id,
          message: description,
        });
      }
    };

    for (const [id, bBefore] of beforeState.beats) {
      if (!bBefore.verdict) continue;
      const bAfter = afterState.beats.get(id);
      if (!bAfter) {
        handleVerifiedViolation(
          "beat",
          bBefore,
          `Verified beat near ${formatTimeUs(bBefore.atUs)} cannot be removed without confirmation.`,
        );
        continue;
      }
      if (computeEntityValueDigest("beat", bAfter) !== bBefore.verdict.valueDigest) {
        handleVerifiedViolation(
          "beat",
          bBefore,
          `Verified beat near ${formatTimeUs(bBefore.atUs)} cannot be altered without confirmation.`,
        );
      }
    }

    for (const [id, uBefore] of beforeState.unbeats) {
      if (!uBefore.verdict) continue;
      const uAfter = afterState.unbeats.get(id);
      if (!uAfter || computeEntityValueDigest("unbeat", uAfter) !== uBefore.verdict.valueDigest) {
        handleVerifiedViolation(
          "unbeat",
          uBefore,
          `Verified unbeat near ${formatTimeUs(uBefore.atUs)} cannot be removed or altered without confirmation.`,
        );
      }
    }

    for (const [id, bndBefore] of beforeState.boundaries) {
      if (!bndBefore.verdict) continue;
      const bndAfter = afterState.boundaries.get(id);
      if (
        !bndAfter ||
        computeEntityValueDigest("boundary", bndAfter) !== bndBefore.verdict.valueDigest
      ) {
        handleVerifiedViolation(
          "boundary",
          bndBefore,
          `Verified section boundary near ${formatTimeUs(bndBefore.atUs)} cannot be removed or moved without confirmation.`,
        );
      }
    }

    for (const [id, sBefore] of beforeState.sounds) {
      if (!sBefore.verdict) continue;
      const sAfter = afterState.sounds.get(id);
      if (!sAfter) {
        handleVerifiedViolation(
          "sound",
          sBefore,
          `Verified sound near ${formatTimeUs(sBefore.startUs)} cannot be removed without confirmation.`,
        );
        continue;
      }
      const facet = sBefore.verdict.facet ?? "occurrence";
      const afterFacetDigest = computeEntityValueDigest("sound", sAfter, facet);
      if (afterFacetDigest !== sBefore.verdict.valueDigest) {
        handleVerifiedViolation(
          "sound",
          sBefore,
          `Verified sound ${facet} near ${formatTimeUs(sBefore.startUs)} cannot be overwritten without confirmation.`,
        );
      }
    }

    for (const [id, scBefore] of beforeState.soundClasses) {
      if (!scBefore.verdict) continue;
      const scAfter = afterState.soundClasses.get(id);
      if (
        !scAfter ||
        computeEntityValueDigest("sound_class", scAfter) !== scBefore.verdict.valueDigest
      ) {
        handleVerifiedViolation(
          "sound_class",
          scBefore,
          "Verified sound class cannot be altered without confirmation.",
        );
      }
    }

    // Soft unbeat repulsion advisory note (if offer places a new beat inside an Unbeat repulsion zone)
    const constraints = compileConstraints(beforeState);
    for (const rep of constraints.unbeatRepulsions) {
      for (const [id, bAfter] of afterState.beats) {
        const bBefore = beforeState.beats.get(id);
        if (bBefore && bBefore.atUs === bAfter.atUs) continue;
        if (Math.abs(bAfter.atUs - rep.atUs) <= rep.repulsionRadiusUs) {
          pushNote(
            "near_unbeat_repulsion",
            `Places a beat near your unbeat cue at ${formatTimeUs(rep.atUs)}`,
            { unbeatAtUs: rep.atUs, beatAtUs: bAfter.atUs },
          );
          break;
        }
      }
    }
  }

  if (conflicts.length > 0) {
    const hardInvalid = conflicts.some(
      (c) =>
        c.kind === "protected_keep" ||
        c.kind === "protected_verified" ||
        c.kind === "authority_violation" ||
        c.kind === "out_of_scope" ||
        c.kind === "invalid_op",
    );
    return {
      validation: deepFreeze({
        valid: false,
        status: hardInvalid ? "invalid" : "stale",
        conflicts,
        requiredChallenges: [],
        notes,
      }),
      beforeState,
      afterState: executedOk ? afterState : beforeState,
      executedOk,
    };
  }

  if (requiredChallenges.length > 0) {
    return {
      validation: deepFreeze({
        valid: false,
        status: "requires_challenge_confirm",
        conflicts: [],
        requiredChallenges,
        notes,
      }),
      beforeState,
      afterState,
      executedOk,
    };
  }

  return {
    validation: deepFreeze({
      valid: true,
      status: "valid",
      conflicts: [],
      requiredChallenges: [],
      notes,
    }),
    beforeState,
    afterState,
    executedOk,
  };
}

/**
 * Validate an `Offer` against `snapshotOrState` without mutating caller state.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshotOrState
 * @param {import("../index.d.ts").Offer} offer
 * @param {{ ignoreTerminalStatus?: boolean }} [options]
 * @returns {import("../index.d.ts").OfferValidationResult}
 */
export function validateOffer(snapshotOrState, offer, options = {}) {
  return evaluateOfferInternal(snapshotOrState, offer, options).validation;
}

/**
 * Preview applying `offer` onto `snapshotOrState` using the unified transaction interpreter!
 * Powers full `Alt` A/B comparison across beats, segments/boundaries, sounds, and `TempoMap`.
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} snapshotOrState
 * @param {import("../index.d.ts").Offer} offer
 * @param {import("../index.d.ts").TempoMapOptions} [tempoOptions]
 * @returns {import("../index.d.ts").OfferPreviewResult}
 */
export function previewOffer(snapshotOrState, offer, tempoOptions) {
  const { validation, beforeState, afterState, executedOk } = evaluateOfferInternal(
    snapshotOrState,
    offer,
  );
  const effectiveAfter = executedOk ? toScratchSessionState(afterState) : beforeState;
  if (executedOk) {
    // Ensure preview snapshot never carries a dangling `anchor:<removedId>` verdict facet
    // when previewing a challenged sound anchor replacement prior to acceptance disposition!
    for (const [sid, s] of effectiveAfter.sounds) {
      if (
        s.verdict?.facet?.startsWith("anchor:") &&
        !s.anchors.some((a) => a.id === s.verdict.facet.slice(7))
      ) {
        const { verdict: _v, ...rest } = s;
        effectiveAfter.sounds.set(sid, deepFreeze(rest));
      }
    }
  }
  const nextSnapshot = toSnapshot(effectiveAfter);
  const { diff, consequences } = computeOfferDiffAndConsequences(beforeState, effectiveAfter);
  const previewTempoMap = projectTempoMap(nextSnapshot, tempoOptions);

  return deepFreeze({
    validation,
    nextSnapshot,
    diff,
    previewTempoMap,
    consequences,
  });
}

/**
 * Bind and normalize a raw provider `OfferInput` against a host-captured basis `Snapshot` / `SessionState`.
 * Computes `dependencies` and `basisDigest` strictly from `capturedBasis` (never blindly trusting
 * provider-supplied revisions or digests!).
 *
 * @param {import("../index.d.ts").Snapshot | import("../index.d.ts").SessionState} capturedBasis
 * @param {import("../index.d.ts").OfferInput} rawOffer
 * @param {{
 *   defaultBatchId?: string,
 *   defaultProviderId?: string,
 *   defaultModelRevision?: string,
 *   defaultAudioId?: string,
 *   defaultBasisSeq?: number
 * }} [defaults]
 * @returns {import("../index.d.ts").Offer}
 */
export function bindOfferToBasis(capturedBasis, rawOffer, defaults = {}) {
  if (!rawOffer || typeof rawOffer !== "object") {
    throw new EnsomiError("INVALID_OFFER", "OfferInput must be an object");
  }
  const basisState = toScratchSessionState(capturedBasis);
  const id = assertNonEmptyId(rawOffer.id, "offer.id", "INVALID_OFFER");
  const batchId = assertNonEmptyId(
    rawOffer.batchId ?? defaults.defaultBatchId ?? `batch_${id}`,
    "offer.batchId",
    "INVALID_OFFER",
  );
  const kind = rawOffer.kind ?? "pulse";
  if (!OFFER_KIND_SET.has(kind)) {
    throw new EnsomiError("INVALID_OFFER", `Invalid offer.kind: ${String(kind)}`);
  }
  const providerId = assertNonEmptyId(
    rawOffer.providerId ?? defaults.defaultProviderId ?? "provider",
    "offer.providerId",
    "INVALID_OFFER",
  );
  const audioId = assertNonEmptyId(
    rawOffer.audioId ?? defaults.defaultAudioId ?? basisState.audio?.audioId ?? "unknown_audio",
    "offer.audioId",
    "INVALID_OFFER",
  );
  const basisSeq =
    rawOffer.basisSeq ?? defaults.defaultBasisSeq ?? basisState.lastSeq;
  if (!Array.isArray(rawOffer.writeRanges) || rawOffer.writeRanges.length === 0) {
    throw new EnsomiError("INVALID_OFFER", "OfferInput.writeRanges must be non-empty");
  }
  const writeRanges = mergeRangesUs(
    rawOffer.writeRanges.map((r) =>
      assertRangeUs(r, basisState.durationUs > 0 ? basisState.durationUs : undefined),
    ),
  );

  /** @type {import("../index.d.ts").OfferDerivedFrom | undefined} */
  let derivedFrom;
  if (rawOffer.derivedFrom) {
    const srcRange = assertRangeUs(
      rawOffer.derivedFrom.range,
      basisState.durationUs > 0 ? basisState.durationUs : undefined,
    );
    const supportingIds = [...new Set(rawOffer.derivedFrom.supportingIds ?? [])].sort(
      compareCodeUnits,
    );
    const sourceDigest = computeDerivedSourceDigest(
      basisState,
      srcRange.startUs,
      srcRange.endUs,
    );
    derivedFrom = {
      range: srcRange,
      sourceDigest,
      supportingIds,
    };
  }

  const rawOps = Array.isArray(rawOffer.ops) ? structuredClone(rawOffer.ops) : [];
  const ops = rawOps.map((op) => {
    if (op && typeof op === "object" && op.op === "promote_range_to_segments") {
      const nextOp = { ...op };
      if (op.newBoundaryIds !== undefined) {
        nextOp.newBoundaryIds = normalizeOptionalIdPair(
          op.newBoundaryIds,
          "offer.ops.promote_range_to_segments.newBoundaryIds",
        );
      }
      if (op.newSegmentIds !== undefined) {
        nextOp.newSegmentIds = normalizeOptionalIdPair(
          op.newSegmentIds,
          "offer.ops.promote_range_to_segments.newSegmentIds",
        );
      }
      return nextOp;
    }
    return op;
  });
  const dependencies = computeOfferDependencies(basisState, writeRanges, derivedFrom, ops);
  const basisDigest = computeOfferBasisDigest(dependencies);
  const notes = (rawOffer.notes ?? []).map((n) => normalizeStructuredNote(n));
  const semanticKey = rawOffer.semanticKey ?? computeOfferSemanticKey({ kind, writeRanges, ops });
  const validatedScore =
    rawOffer.score !== undefined
      ? assertUnitInterval(rawOffer.score, "offer.score", "INVALID_FIELD")
      : undefined;

  /** @type {import("../index.d.ts").OfferChallenge[] | undefined} */
  let challenges;
  if (rawOffer.challenges && rawOffer.challenges.length > 0) {
    challenges = rawOffer.challenges.map((ch) => {
      const c = assertPlainObject(ch, "OfferChallenge", "INVALID_OFFER");
      assertNonEmptyId(c.challengeId, "challenge.challengeId", "INVALID_OFFER");
      if (!VERDICT_TARGET_KIND_SET.has(/** @type {any} */ (c.targetKind))) {
        throw new EnsomiError("INVALID_OFFER", `Invalid challenge.targetKind: ${String(c.targetKind)}`);
      }
      assertNonEmptyId(c.targetId, "challenge.targetId", "INVALID_OFFER");
      assertPositiveRevision(c.expectedRevision, "OfferChallenge");
      assertNonEmptyId(c.expectedValueDigest, "challenge.expectedValueDigest", "INVALID_OFFER");
      assertNonEmptyId(c.reasonCode, "challenge.reasonCode", "INVALID_OFFER");
      if (c.replacementId !== undefined) {
        assertNonEmptyId(c.replacementId, "challenge.replacementId", "INVALID_OFFER");
      }
      if (c.message !== undefined) {
        assertNonEmptyId(c.message, "challenge.message", "INVALID_OFFER");
      }
      return {
        challengeId: /** @type {string} */ (c.challengeId),
        targetKind: /** @type {import("../index.d.ts").VerdictTargetKind} */ (c.targetKind),
        targetId: /** @type {string} */ (c.targetId),
        expectedRevision: /** @type {number} */ (c.expectedRevision),
        expectedValueDigest: /** @type {string} */ (c.expectedValueDigest),
        reasonCode: /** @type {string} */ (c.reasonCode),
        ...(c.replacementId ? { replacementId: /** @type {string} */ (c.replacementId) } : {}),
        ...(c.message ? { message: /** @type {string} */ (c.message) } : {}),
      };
    });
  }

  /** @type {import("../index.d.ts").Offer} */
  const offer = {
    id,
    batchId,
    ...(rawOffer.alternativeGroup ? { alternativeGroup: rawOffer.alternativeGroup } : {}),
    kind,
    providerId,
    ...(rawOffer.modelRevision ?? defaults.defaultModelRevision
      ? { modelRevision: rawOffer.modelRevision ?? defaults.defaultModelRevision }
      : {}),
    audioId,
    basisSeq,
    writeRanges,
    dependencies,
    basisDigest,
    ops,
    ...(challenges ? { challenges } : {}),
    ...(derivedFrom ? { derivedFrom } : {}),
    notes,
    ...(rawOffer.title ? { title: rawOffer.title } : {}),
    ...(rawOffer.optionIndex !== undefined ? { optionIndex: rawOffer.optionIndex } : {}),
    ...(rawOffer.optionCount !== undefined ? { optionCount: rawOffer.optionCount } : {}),
    ...(validatedScore !== undefined ? { score: validatedScore } : {}),
    semanticKey,
    status: "valid",
    conflicts: [],
  };

  assertOfferShape(offer);
  const check = validateOffer(basisState, offer, { ignoreTerminalStatus: true });
  return deepFreeze({
    ...offer,
    status: check.status,
    conflicts: check.conflicts,
    notes: check.notes,
  });
}
