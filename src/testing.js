/**
 * Deterministic test helpers, synthetic draft generator, and `ScriptedMockProvider`
 * exported exclusively via `ensomi/testing` (zero mock bloat in production `ensomi`!).
 */

import { compareCodeUnits, EnsomiError, jcsSha256Hex } from "./jcs.js";
import {
  assertNonNegSafeIntUs,
  formatTimeUs,
  rangeContainsUs,
  secToUs,
} from "./time.js";
import {
  computeEntityValueDigest,
  decomposeRangeToSegments,
  deepFreeze,
  SNAP_ROLES,
  WELL_KNOWN_SOUND_FAMILIES,
} from "./types.js";

const SYNTHETIC_SOUND_PROFILES = Object.freeze([
  Object.freeze({
    family: "kick",
    register: "low",
    role: "transient_attack",
    labelCode: "kick_attack",
    label: "Kick attack",
    sigmaUs: 2_500,
    durUs: 90_000,
  }),
  Object.freeze({
    family: "snare",
    register: "mid",
    role: "transient_attack",
    labelCode: "snare_transient",
    label: "Snare transient",
    sigmaUs: 2_500,
    durUs: 120_000,
  }),
  Object.freeze({
    family: "piano_keys",
    register: "mid",
    role: "p_center",
    labelCode: "chord_onset",
    label: "Chord onset",
    sigmaUs: 5_000,
    durUs: 260_000,
  }),
  Object.freeze({
    family: "bass_synth",
    register: "sub",
    role: "transient_attack",
    labelCode: "bass_attack",
    label: "Bass attack",
    sigmaUs: 4_000,
    durUs: 220_000,
  }),
  Object.freeze({
    family: "vocal",
    register: "mid",
    role: "p_center",
    labelCode: "vocal_onset",
    label: "Vocal phrasing onset",
    sigmaUs: 8_000,
    durUs: 320_000,
  }),
  Object.freeze({
    family: "strings_winds",
    register: "high",
    role: "harmonic_change",
    labelCode: "harmonic_change",
    label: "Harmonic change",
    sigmaUs: 10_000,
    durUs: 400_000,
  }),
]);

/**
 * Deterministic 32-bit LCG PRNG returning numbers in `[0, 1)`.
 *
 * @param {number} [seed=1337]
 * @returns {() => number}
 */
export function createDeterministicPrng(seed = 1337) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(1664525, state) + 1013904223) >>> 0;
    return state / 0x100000000;
  };
}

/**
 * Generate a deterministic microsecond `AnalysisDraft` (`segments`, `boundaries`, `beats`, `sounds`, `spans`).
 *
 * @param {import("../testing.d.ts").SyntheticDraftOptions} [options]
 * @returns {import("../testing.d.ts").SyntheticDraft}
 */
export function generateSyntheticDraft(options = {}) {
  const durationUs =
    options.durationUs !== undefined
      ? assertNonNegSafeIntUs(options.durationUs, "durationUs", "INVALID_OPTIONS")
      : options.durationSec !== undefined
        ? secToUs(options.durationSec)
        : 32_000_000;
  const bpm = options.bpm ?? 120;
  const jitterUs =
    options.jitterUs !== undefined
      ? assertNonNegSafeIntUs(options.jitterUs, "jitterUs", "INVALID_OPTIONS")
      : 0;
  const startUs =
    options.startUs !== undefined
      ? assertNonNegSafeIntUs(options.startUs, "startUs", "INVALID_OPTIONS")
      : 500_000;
  const segmentCount = options.segmentCount ?? 1;

  if (typeof bpm !== "number" || !Number.isFinite(bpm) || bpm <= 0) {
    throw new EnsomiError("INVALID_OPTIONS", `bpm must be > 0, got ${String(bpm)}`);
  }
  if (!Number.isInteger(segmentCount) || segmentCount < 1) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `segmentCount must be integer >= 1, got ${String(segmentCount)}`,
    );
  }
  if (
    options.soundCount !== undefined &&
    (!Number.isInteger(options.soundCount) || options.soundCount < 0)
  ) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `soundCount must be integer >= 0, got ${String(options.soundCount)}`,
    );
  }

  if (durationUs === 0) {
    return { segments: [], boundaries: [], spans: [], beats: [], sounds: [] };
  }

  const rand = createDeterministicPrng(options.seed ?? 1337);

  /** @type {import("../index.d.ts").BoundaryInput[]} */
  const boundaries = [];
  /** @type {import("../index.d.ts").SegmentInput[]} */
  const segments = [];

  const cuts = [0];
  for (let i = 1; i < segmentCount; i += 1) {
    const cutUs = Math.round((i * durationUs) / segmentCount);
    if (cutUs > cuts[cuts.length - 1] && cutUs < durationUs) {
      cuts.push(cutUs);
      boundaries.push({
        id: `bnd_${boundaries.length}`,
        atUs: cutUs,
      });
    }
  }
  cuts.push(durationUs);

  for (let i = 0; i < cuts.length - 1; i += 1) {
    segments.push({
      id: `seg_${i}`,
      startUs: cuts[i],
      endUs: cuts[i + 1],
      regime: "auto",
    });
  }

  const periodUs = Math.max(1, Math.round(60_000_000 / bpm));
  /** @type {import("../index.d.ts").BeatInput[]} */
  const beats = [];
  let beatIdx = 0;
  for (let t = startUs; t < durationUs; t += periodUs) {
    const jitter = jitterUs > 0 ? Math.round((rand() * 2 - 1) * jitterUs) : 0;
    const atUs = Math.min(durationUs - 1, Math.max(0, t + jitter));
    beats.push({
      id: `beat_${beatIdx}`,
      atUs,
      confidence: Math.round((0.85 + rand() * 0.14) * 1000) / 1000,
    });
    beatIdx += 1;
  }

  const targetSoundCount = options.soundCount ?? Math.min(beats.length, 64);
  /** @type {import("../index.d.ts").SoundInput[]} */
  const sounds = [];
  if (beats.length > 0 && targetSoundCount > 0) {
    for (let i = 0; i < targetSoundCount; i += 1) {
      const bIdx = Math.min(
        beats.length - 1,
        Math.floor((i * beats.length) / targetSoundCount),
      );
      const baseAtUs = beats[bIdx].atUs;
      const subOffsetUs = i % 3 === 1 ? Math.round(periodUs / 2) : (i % 7) * 1_000;
      const anchorAtUs = Math.min(durationUs - 2, Math.max(0, baseAtUs + subOffsetUs));
      const profile = SYNTHETIC_SOUND_PROFILES[i % SYNTHETIC_SOUND_PROFILES.length];
      const sStartUs = anchorAtUs;
      const sEndUs = Math.min(durationUs, Math.max(sStartUs + 1_000, sStartUs + profile.durUs));
      const anchorId = `anc_${i}_0`;
      sounds.push({
        id: `snd_${i}`,
        startUs: sStartUs,
        endUs: sEndUs,
        endKind: sEndUs === durationUs ? "right_censored" : "estimated",
        anchors: [
          {
            id: anchorId,
            role: /** @type {import("../index.d.ts").SnapRole} */ (profile.role),
            atUs: anchorAtUs,
            sigmaUs: profile.sigmaUs,
          },
        ],
        primaryAnchorId: anchorId,
        salience: Math.round((0.4 + rand() * 0.58) * 1000) / 1000,
        characteristics: {
          family: profile.family,
          register: /** @type {import("../index.d.ts").SoundRegister} */ (profile.register),
          confidence: 0.9,
        },
        voiceGroupId: `voice_${profile.family}`,
        blendStatus: i % 4 === 0 ? "isolated" : "blended",
      });
    }
  }

  return deepFreeze({
    segments,
    boundaries,
    spans: [],
    beats,
    sounds,
  });
}

function delayWithCancel(delayMs, signal, isCancelled, ignoreAbort) {
  return new Promise((resolve, reject) => {
    if (!ignoreAbort && (signal?.aborted || isCancelled())) {
      reject(signal?.reason ?? new EnsomiError("ABORTED", "Cancelled"));
      return;
    }
    const timer = setTimeout(() => {
      if (!ignoreAbort && signal) signal.removeEventListener("abort", onAbort);
      if (!ignoreAbort && isCancelled()) {
        reject(new EnsomiError("ABORTED", "Cancelled"));
        return;
      }
      resolve(undefined);
    }, delayMs);

    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new EnsomiError("ABORTED", "Cancelled"));
    };

    if (!ignoreAbort && signal) {
      signal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

export class ScriptedMockProvider {
  /**
   * @param {import("../testing.d.ts").ScriptedMockProviderOptions} [options]
   */
  constructor(options = {}) {
    this._id = options.id ?? "ensomi.scripted";
    this._version = options.version ?? "0.1.0";
    this._modelRevision = options.modelRevision ?? "mock-0.1.0";
    this._draft = options.draft ?? null;
    this._defaultScenario = options.defaultScenario ?? "local_rewrite";
    this._delayMs = options.delayMs ?? 0;
    this._analyzeDelayMs = options.analyzeDelayMs ?? 0;
    this._snapDelayMs = options.snapDelayMs ?? 0;
    this._ignoreAbort = Boolean(options.ignoreAbort);
    this._script = Array.isArray(options.script) ? options.script.slice() : [];
    this._snapScript = Array.isArray(options.snapScript) ? options.snapScript.slice() : [];
    this._soundOptionScript = Array.isArray(options.soundOptionScript)
      ? options.soundOptionScript.slice()
      : [];
    this._matchScript = Array.isArray(options.matchScript) ? options.matchScript.slice() : [];
    this._segmentationScript = Array.isArray(options.segmentationScript)
      ? options.segmentationScript.slice()
      : [];

    this._callIdx = 0;
    this._snapIdx = 0;
    /** @type {Set<string>} */
    this.cancelledRequestIds = new Set();

    /** @type {Array<{ audioId: string }>} */
    this.prepareCalls = [];
    /** @type {Array<{ requestId: string, generation: number, aborted: boolean }>} */
    this.analyzeCalls = [];
    /** @type {Array<{ requestId: string, generation: number, atUs: number, windowUs: number, aborted: boolean }>} */
    this.suggestSnapsCalls = [];
    /** @type {Array<{ requestId: string, generation: number, aborted: boolean }>} */
    this.suggestSoundOptionsCalls = [];
    /** @type {Array<{ requestId: string, generation: number, matchMode: string, aborted: boolean }>} */
    this.matchSoundsCalls = [];
    /** @type {Array<{ requestId: string, generation: number, aborted: boolean }>} */
    this.suggestSegmentationCalls = [];
    /** @type {Array<{ requestId: string, generation: number, reason: string, aborted: boolean }>} */
    this.recomputeCalls = [];
  }

  /** @returns {import("../index.d.ts").ProviderManifest} */
  manifest() {
    return {
      id: this._id,
      version: this._version,
      kind: "mock",
      modelRevision: this._modelRevision,
      capabilities: {
        draft: true,
        sounds: true,
        snapSuggestions: true,
        soundOptions: true,
        soundMatching: true,
        segmentation: true,
        constrainedRecompute: true,
        supportedSnapFamilies: [...WELL_KNOWN_SOUND_FAMILIES],
        supportedSnapRoles: [...SNAP_ROLES],
        alternatives: true,
        repeatPropagation: true,
      },
    };
  }

  /**
   * JSON-safe cancellation hook invoked by `SessionController` across process / Wasm boundaries.
   * @param {string} requestId
   */
  cancel(requestId) {
    this.cancelledRequestIds.add(requestId);
  }

  /**
   * @param {import("../index.d.ts").AudioBinding} audio
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").EvidenceRef>}
   */
  async prepare(audio, signal) {
    if (!this._ignoreAbort && signal?.aborted) {
      throw signal.reason ?? new EnsomiError("ABORTED", "Aborted");
    }
    this.prepareCalls.push({ audioId: audio.audioId });
    return {
      evidenceId: `ev_${audio.audioId}_${audio.durationUs}`,
      audioId: audio.audioId,
      durationUs: audio.durationUs,
    };
  }

  /**
   * @param {import("../index.d.ts").AnalyzeRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").AnalysisDraft>}
   */
  async analyze(request, signal) {
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      aborted: false,
    };
    this.analyzeCalls.push(record);
    const isCancelled = () => this.cancelledRequestIds.has(request.requestId);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }
    if (this._analyzeDelayMs > 0) {
      await delayWithCancel(this._analyzeDelayMs, signal, isCancelled, this._ignoreAbort);
    } else if (!this._ignoreAbort && (signal?.aborted || isCancelled())) {
      record.aborted = true;
      throw signal?.reason ?? new EnsomiError("ABORTED", "Aborted");
    }
    if (this._draft) {
      return structuredClone(this._draft);
    }
    return generateSyntheticDraft({ durationUs: request.audio.durationUs, bpm: 120 });
  }

  /**
   * @param {import("../index.d.ts").SuggestSnapsRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SnapCandidate[]>}
   */
  async suggestSnaps(request, signal) {
    this._snapIdx += 1;
    const callIdx = this._snapIdx;
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      atUs: request.atUs,
      windowUs: request.windowUs,
      aborted: false,
    };
    this.suggestSnapsCalls.push(record);
    const isCancelled = () => this.cancelledRequestIds.has(request.requestId);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }

    if (this._snapScript.length > 0) {
      const step = this._snapScript.shift();
      if (typeof step === "function") {
        return await step({ request, signal, callIdx });
      }
      return structuredClone(step);
    }

    if (this._snapDelayMs > 0) {
      await delayWithCancel(this._snapDelayMs, signal, isCancelled, this._ignoreAbort);
    } else if (!this._ignoreAbort && (signal?.aborted || isCancelled())) {
      record.aborted = true;
      throw signal?.reason ?? new EnsomiError("ABORTED", "Aborted");
    }

    const sounds = request.view.snapshot.sounds;
    /** @type {import("../index.d.ts").SnapCandidate[]} */
    const fromSounds = [];
    for (const s of sounds) {
      for (const a of s.anchors) {
        const deltaUs = a.atUs - request.atUs;
        if (Math.abs(deltaUs) <= request.windowUs) {
          fromSounds.push({
            candidateId: `snap_${s.id}_${a.id}`,
            atUs: a.atUs,
            deltaUs,
            anchorId: a.id,
            role: a.role,
            sigmaUs: a.sigmaUs ?? 3_000,
            salience: s.salience ?? 0.85,
            labelCode: s.characteristics?.family ?? "acoustic_onset",
            label: `${s.characteristics?.family ?? "Sound"} (${a.role})`,
            sound: {
              id: s.id,
              startUs: s.startUs,
              endUs: s.endUs,
              endKind: s.endKind,
              anchors: s.anchors,
              primaryAnchorId: s.primaryAnchorId,
              ...(s.characteristics ? { characteristics: s.characteristics } : {}),
              ...(s.voiceGroupId ? { voiceGroupId: s.voiceGroupId } : {}),
              ...(s.classId ? { classId: s.classId } : {}),
              ...(s.blendStatus ? { blendStatus: s.blendStatus } : {}),
            },
          });
        }
      }
    }
    if (fromSounds.length > 0) {
      return fromSounds
        .sort(
          (a, b) =>
            b.salience - a.salience ||
            Math.abs(a.deltaUs) - Math.abs(b.deltaUs) ||
            compareCodeUnits(a.candidateId, b.candidateId),
        )
        .slice(0, 6);
    }

    const dur = request.view.snapshot.durationUs;
    const t0 = Math.min(dur - 2_000, Math.max(0, request.atUs + Math.min(8_000, Math.round(request.windowUs / 4))));
    const t1 = Math.max(0, request.atUs - Math.min(12_000, Math.round(request.windowUs / 3)));
    const tag = jcsSha256Hex({ id: this._id, atUs: request.atUs }).slice(0, 8);

    return [
      {
        candidateId: `cand_${tag}_0`,
        atUs: t0,
        deltaUs: t0 - request.atUs,
        anchorId: `anc_${tag}_0`,
        role: "transient_attack",
        sigmaUs: 2_500,
        salience: 0.92,
        labelCode: "kick_attack",
        label: "Kick attack",
        sound: {
          startUs: t0,
          endUs: Math.min(dur, t0 + 90_000),
          endKind: "estimated",
          anchors: [{ id: `anc_${tag}_0`, role: "transient_attack", atUs: t0, sigmaUs: 2_500 }],
          primaryAnchorId: `anc_${tag}_0`,
          characteristics: { family: "kick", register: "low", confidence: 0.92 },
          voiceGroupId: "voice_kick",
          blendStatus: "blended",
        },
      },
      {
        candidateId: `cand_${tag}_1`,
        atUs: t1,
        deltaUs: t1 - request.atUs,
        anchorId: `anc_${tag}_1`,
        role: "p_center",
        sigmaUs: 5_000,
        salience: 0.78,
        labelCode: "chord_onset",
        label: "Chord onset",
        sound: {
          startUs: t1,
          endUs: Math.min(dur, t1 + 240_000),
          endKind: "estimated",
          anchors: [{ id: `anc_${tag}_1`, role: "p_center", atUs: t1, sigmaUs: 5_000 }],
          primaryAnchorId: `anc_${tag}_1`,
          characteristics: { family: "piano_keys", register: "mid", confidence: 0.84 },
          voiceGroupId: "voice_piano",
          blendStatus: "blended",
        },
      },
    ];
  }

  /**
   * @param {import("../index.d.ts").SuggestSoundOptionsRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SoundOptionCandidate[]>}
   */
  async suggestSoundOptions(request, signal) {
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      aborted: false,
    };
    this.suggestSoundOptionsCalls.push(record);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }
    if (this._soundOptionScript.length > 0) {
      const step = this._soundOptionScript.shift();
      return typeof step === "function" ? await step({ request, signal }) : structuredClone(step);
    }
    const dur = request.view.snapshot.durationUs;
    const pivotUs =
      request.atUs ?? (request.rangeUs ? request.rangeUs.startUs : 500_000);
    const startUs = Math.min(dur - 50_000, Math.max(0, pivotUs));
    return [
      {
        optionId: `opt_perc_${startUs}`,
        salience: 0.91,
        labelCode: "kick_layer",
        label: "Low kick transient",
        sound: {
          id: `snd_opt_perc_${startUs}`,
          startUs,
          endUs: Math.min(dur, startUs + 85_000),
          endKind: "observed",
          anchors: [{ id: "a_att", role: "transient_attack", atUs: startUs, sigmaUs: 2_000 }],
          primaryAnchorId: "a_att",
          characteristics: { family: "kick", register: "low", confidence: 0.93 },
          voiceGroupId: "voice_kick",
          blendStatus: "blended",
        },
      },
      {
        optionId: `opt_harm_${startUs}`,
        salience: 0.83,
        labelCode: "keys_layer",
        label: "Sustained chord",
        sound: {
          id: `snd_opt_harm_${startUs}`,
          startUs,
          endUs: Math.min(dur, startUs + 420_000),
          endKind: "right_censored",
          anchors: [
            { id: "a_att", role: "transient_attack", atUs: startUs, sigmaUs: 4_000 },
            { id: "a_pc", role: "p_center", atUs: Math.min(dur, startUs + 12_000), sigmaUs: 6_000 },
          ],
          primaryAnchorId: "a_pc",
          characteristics: { family: "piano_keys", register: "mid", f0Hz: 261.63, confidence: 0.88 },
          voiceGroupId: "voice_piano",
          blendStatus: "blended",
        },
      },
    ];
  }

  /**
   * @param {import("../index.d.ts").MatchSoundsRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").OfferInput[] | import("../index.d.ts").RecomputeResult>}
   */
  async matchSounds(request, signal) {
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      matchMode: request.matchMode,
      aborted: false,
    };
    this.matchSoundsCalls.push(record);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }
    if (this._matchScript.length > 0) {
      const step = this._matchScript.shift();
      return typeof step === "function" ? await step({ request, signal }) : structuredClone(step);
    }

    const snap = request.view.snapshot;
    const exemplar = snap.sounds.find((s) => s.id === request.exemplarSoundIds[0]);
    const dur = snap.durationUs;
    const writeRange =
      request.scope === "song" ? { startUs: 0, endUs: dur } : request.scope;
    const classId = request.classId ?? `cls_${request.matchMode}_${request.generation}`;
    const targetStart = Math.min(dur - 100_000, Math.max(writeRange.startUs, (exemplar?.startUs ?? 500_000) + 2_000_000));

    /** @type {import("../index.d.ts").OfferOp[]} */
    const ops = [];
    if (!snap.soundClasses.some((c) => c.id === classId)) {
      ops.push({
        op: "upsert_sound_class",
        soundClass: {
          id: classId,
          exemplarSoundIds: request.exemplarSoundIds,
          matchMode: request.matchMode,
          label: `Matched ${request.matchMode}`,
        },
      });
    }
    ops.push({
      op: "upsert_sound",
      sound: {
        id: `snd_match_g${request.generation}_0`,
        startUs: targetStart,
        endUs: Math.min(dur, targetStart + 120_000),
        endKind: "estimated",
        anchors: [
          {
            id: "anc_0",
            role: "transient_attack",
            atUs: targetStart,
            sigmaUs: 3_000,
          },
        ],
        primaryAnchorId: "anc_0",
        characteristics: exemplar?.characteristics ?? { family: "percussion" },
        voiceGroupId: exemplar?.voiceGroupId ?? "voice_matched",
        classId,
      },
    });

    return [
      {
        id: `offer_match_g${request.generation}`,
        batchId: `batch_match_g${request.generation}`,
        kind: "sound_match",
        writeRanges: [writeRange],
        ...(exemplar
          ? {
              derivedFrom: {
                range: { startUs: exemplar.startUs, endUs: exemplar.endUs },
                supportingIds: request.exemplarSoundIds,
              },
            }
          : {}),
        ops,
        notes: [
          {
            code: "sound_match_summary",
            message: `Matched recurring ${request.matchMode} across track`,
          },
        ],
      },
    ];
  }

  /**
   * @param {import("../index.d.ts").SuggestSegmentationRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").OfferInput[] | import("../index.d.ts").RecomputeResult>}
   */
  async suggestSegmentation(request, signal) {
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      aborted: false,
    };
    this.suggestSegmentationCalls.push(record);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }
    if (this._segmentationScript.length > 0) {
      const step = this._segmentationScript.shift();
      return typeof step === "function" ? await step({ request, signal }) : structuredClone(step);
    }

    const snap = request.view.snapshot;
    const firstSeg = snap.segments[0];
    if (!firstSeg || firstSeg.endUs - firstSeg.startUs < 2_000_000) return [];
    const midUs = Math.round((firstSeg.startUs + firstSeg.endUs) / 2);

    return [
      {
        id: `offer_seg_g${request.generation}`,
        batchId: `batch_seg_g${request.generation}`,
        kind: "segmentation",
        writeRanges: [{ startUs: firstSeg.startUs, endUs: firstSeg.endUs }],
        ops: [
          {
            op: "split_segment",
            segmentId: firstSeg.id,
            atUs: midUs,
            newBoundaryId: `bnd_seg_g${request.generation}`,
            newSegmentId: `seg_right_g${request.generation}`,
            rightRegime: "free",
          },
        ],
        notes: [
          {
            code: "phase_regime_split",
            message: `Splits rubato transition at ${formatTimeUs(midUs)}`,
          },
        ],
      },
    ];
  }

  /**
   * @param {import("../index.d.ts").RecomputeRequest} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").RecomputeResult>}
   */
  async recompute(request, signal) {
    this._callIdx += 1;
    const callIdx = this._callIdx;
    const record = {
      requestId: request.requestId,
      generation: request.generation,
      reason: request.reason,
      aborted: false,
    };
    this.recomputeCalls.push(record);
    const isCancelled = () => this.cancelledRequestIds.has(request.requestId);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }

    if (this._script.length > 0) {
      const step = this._script.shift();
      if (typeof step === "function") {
        return await step({ request, signal, callIdx });
      }
      return structuredClone(step);
    }

    const scenario = request.reason || this._defaultScenario;
    if (scenario === "slow_cancellable" || this._delayMs > 0) {
      await delayWithCancel(this._delayMs || 40, signal, isCancelled, this._ignoreAbort);
    } else if (!this._ignoreAbort && (signal?.aborted || isCancelled())) {
      record.aborted = true;
      throw signal?.reason ?? new EnsomiError("ABORTED", "Aborted");
    }

    const snap = request.view.snapshot;
    const dur = snap.durationUs;
    const baseRange =
      request.scope === "song" ? { startUs: 0, endUs: dur } : request.scope;
    const tag = `g${request.generation}_s${request.basisSeq}`;
    const batchId = `batch_${tag}`;

    if (scenario === "triplet_vs_reset") {
      const altGroup = `alt_${tag}`;
      const existingUnverified = snap.beats.filter(
        (b) => !b.verdict && rangeContainsUs(baseRange, b.atUs),
      );
      const spanUs = baseRange.endUs - baseRange.startUs;
      return {
        batchId,
        offers: [
          {
            id: `offer_${tag}_opt0`,
            batchId,
            alternativeGroup: altGroup,
            kind: "pulse",
            optionIndex: 0,
            optionCount: 2,
            writeRanges: [baseRange],
            ops: [
              ...(existingUnverified.length > 0
                ? [{ op: /** @type {const} */ ("remove_beats"), ids: existingUnverified.map((b) => b.id) }]
                : []),
              ...[0, 1, 2].map((k) => ({
                op: /** @type {const} */ ("upsert_beat"),
                beat: {
                  id: `trip_${tag}_${k}`,
                  atUs: baseRange.startUs + Math.round(((k + 0.25) * spanUs) / 3),
                  confidence: 0.9,
                },
              })),
            ],
            notes: [{ code: "triplet_pulse", message: "Fit triplet pulse" }],
          },
          {
            id: `offer_${tag}_opt1`,
            batchId,
            alternativeGroup: altGroup,
            kind: "pulse",
            optionIndex: 1,
            optionCount: 2,
            writeRanges: [baseRange],
            ops: [
              ...(existingUnverified.length > 0
                ? [{ op: /** @type {const} */ ("remove_beats"), ids: existingUnverified.map((b) => b.id) }]
                : []),
              {
                op: "upsert_beat",
                beat: {
                  id: `ph_${tag}_0`,
                  atUs: baseRange.startUs + Math.min(100_000, Math.round(spanUs * 0.2)),
                  confidence: 0.92,
                },
              },
            ],
            notes: [{ code: "phase_reset", message: "Realign downbeat phase" }],
          },
        ],
      };
    }

    if (scenario === "propagation" || scenario === "cross_region") {
      // Two independent propagation destinations in the SAME batchId (no alternativeGroup!),
      // placed after any active Keep locks so they remain independently valid!
      const width = Math.max(1_000_000, baseRange.endUs - baseRange.startUs);
      const maxKeepEnd = request.view.constraints.keepLocks.reduce(
        (m, lk) => Math.max(m, lk.endUs),
        baseRange.endUs,
      );
      const anchorStart = Math.max(baseRange.endUs, maxKeepEnd) + 1_000_000;
      const dest1Start = Math.min(dur - width * 2, anchorStart);
      const dest1 = { startUs: dest1Start, endUs: dest1Start + width };
      const dest2Start = Math.min(dur - width, dest1.endUs + 1_000_000);
      const dest2 = { startUs: dest2Start, endUs: dest2Start + width };
      const sourceBeats = snap.beats
        .filter((b) => b.verdict && rangeContainsUs(baseRange, b.atUs))
        .map((b) => b.id);

      return {
        batchId,
        offers: [
          {
            id: `offer_${tag}_prop0`,
            batchId,
            kind: "propagation",
            writeRanges: [dest1],
            derivedFrom: {
              range: baseRange,
              supportingIds: sourceBeats,
            },
            ops: [
              {
                op: "upsert_beat",
                beat: {
                  id: `prop0_${tag}_0`,
                  atUs: dest1.startUs + Math.round(width * 0.25),
                  confidence: 0.89,
                },
              },
            ],
            notes: [{ code: "repeat_match", message: `Propagate phrasing to ${formatTimeUs(dest1.startUs)}` }],
          },
          {
            id: `offer_${tag}_prop1`,
            batchId,
            kind: "propagation",
            writeRanges: [dest2],
            derivedFrom: {
              range: baseRange,
              supportingIds: sourceBeats,
            },
            ops: [
              {
                op: "upsert_beat",
                beat: {
                  id: `prop1_${tag}_0`,
                  atUs: dest2.startUs + Math.round(width * 0.25),
                  confidence: 0.88,
                },
              },
            ],
            notes: [{ code: "repeat_match", message: `Propagate phrasing to ${formatTimeUs(dest2.startUs)}` }],
          },
        ],
      };
    }

    if (scenario === "protected_keep_conflict") {
      const keepLock = request.view.constraints.keepLocks[0];
      const targetRange = keepLock
        ? { startUs: keepLock.startUs, endUs: keepLock.endUs }
        : baseRange;
      const midUs = Math.round((targetRange.startUs + targetRange.endUs) / 2);
      return {
        batchId,
        offers: [
          {
            id: `offer_${tag}_keep_conflict`,
            batchId,
            kind: "pulse",
            writeRanges: [targetRange],
            ops: [
              {
                op: "upsert_beat",
                beat: { id: `kp_${tag}_0`, atUs: midUs, confidence: 0.75 },
              },
            ],
            notes: [{ code: "keep_probe", message: "Attempt write into Keep lock" }],
          },
        ],
      };
    }

    if (scenario === "challenge_verified") {
      const verifiedBeat = snap.beats.find(
        (b) => b.verdict && rangeContainsUs(baseRange, b.atUs),
      );
      if (verifiedBeat && verifiedBeat.verdict) {
        const nextAtUs = Math.min(
          baseRange.endUs - 1,
          verifiedBeat.atUs + verifiedBeat.verdict.tolUs + 25_000,
        );
        return {
          batchId,
          offers: [
            {
              id: `offer_${tag}_challenge`,
              batchId,
              kind: "pulse",
              writeRanges: [baseRange],
              challenges: [
                {
                  challengeId: `ch_${tag}_0`,
                  targetKind: "beat",
                  targetId: verifiedBeat.id,
                  expectedRevision: verifiedBeat.revision,
                  expectedValueDigest: computeEntityValueDigest("beat", verifiedBeat),
                  reasonCode: "stronger_transient_nearby",
                  message: "Stronger kick attack detected +45 ms later",
                },
              ],
              ops: [
                {
                  op: "move_beat",
                  id: verifiedBeat.id,
                  atUs: nextAtUs,
                },
              ],
              notes: [{ code: "challenge_note", message: "Proposes shifting verified beat to stronger transient" }],
            },
          ],
        };
      }
    }

    // Default: `local_rewrite` (replaces only unverified beats inside `baseRange`)
    const slices = decomposeRangeToSegments(snap.segments, baseRange);
    const primary = slices[0] ?? baseRange;
    const unverifiedInRange = snap.beats.filter(
      (b) => !b.verdict && rangeContainsUs(primary, b.atUs),
    );
    const spanUs = primary.endUs - primary.startUs;

    return {
      batchId,
      offers: [
        {
          id: `offer_${tag}_local`,
          batchId,
          kind: "pulse",
          writeRanges: [{ startUs: primary.startUs, endUs: primary.endUs }],
          ops: [
            ...(unverifiedInRange.length > 0
              ? [{ op: /** @type {const} */ ("remove_beats"), ids: unverifiedInRange.map((b) => b.id) }]
              : []),
            {
              op: "upsert_beat",
              beat: {
                id: `loc_${tag}_0`,
                atUs: primary.startUs + Math.round(spanUs * 0.25),
                confidence: 0.94,
              },
            },
            {
              op: "upsert_beat",
              beat: {
                id: `loc_${tag}_1`,
                atUs: primary.startUs + Math.round(spanUs * 0.75),
                confidence: 0.94,
              },
            },
          ],
          notes: [
            {
              code: "local_grid_refine",
              message: `Refine pulse grid at ${formatTimeUs(primary.startUs)}–${formatTimeUs(primary.endUs)}`,
            },
          ],
        },
      ],
    };
  }
}
