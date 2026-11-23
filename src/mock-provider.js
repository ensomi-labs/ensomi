/**
 * Deterministic synthetic `TimingProvider` (`ScriptedMockProvider`) and synthetic
 * song draft generator for conformance tests and offline development.
 *
 * Uses clean musical copy across all titles, summaries, advisories, and snap labels
 * (zero internal IDs, batchIds, hashes, or developer jargon in user-facing strings).
 */

import { canonicalJson, roundTime, sha256Hex } from "./hash.js";
import {
  computeConsequences,
  computeSemanticKey,
  formatConsequenceSummary,
  getScopeBeatIds,
} from "./offers.js";
import {
  decomposeRangeToSpans,
  EnsomiError,
  formatTimeCode,
  normalizeSegmentPartition,
  rangeContainsTime,
  SNAP_FAMILIES,
  SNAP_ROLES,
} from "./types.js";

const SYNTHETIC_SOUND_PROFILES = Object.freeze([
  Object.freeze({ family: "percussion", role: "transient_attack", label: "Kick attack", sigmaMs: 3 }),
  Object.freeze({ family: "percussion", role: "transient_attack", label: "Snare transient", sigmaMs: 3 }),
  Object.freeze({ family: "piano_keys", role: "p_center", label: "Chord onset", sigmaMs: 5 }),
  Object.freeze({ family: "bass_synth", role: "transient_attack", label: "Bass attack", sigmaMs: 4 }),
  Object.freeze({ family: "vocal", role: "p_center", label: "Vocal phrasing onset", sigmaMs: 8 }),
  Object.freeze({ family: "strings_winds", role: "harmonic_change", label: "Harmonic change", sigmaMs: 10 }),
]);

const FAMILY_LABELS = Object.freeze({
  percussion: "Percussion transient",
  piano_keys: "Keyboard attack",
  strings_winds: "Ensemble entry",
  vocal: "Vocal onset",
  bass_synth: "Bass transient",
  other: "Acoustic onset",
});

/**
 * Deterministic linear congruential generator for reproducible synthetic drafts.
 * @param {number} [seed=42]
 */
function createPrng(seed = 42) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(1664525, state) + 1013904223) >>> 0;
    return state / 0x100000000;
  };
}

/**
 * Generate a deterministic synthetic song draft (`segments`, `beats`, `sounds`, `spans`).
 *
 * @param {import("../index.d.ts").SyntheticDraftOptions} [options]
 * @returns {import("../index.d.ts").SyntheticDraft}
 */
export function generateSyntheticDraft(options = {}) {
  const durationSec = options.durationSec ?? 32;
  const bpm = options.bpm ?? 120;
  const jitterMs = options.jitterMs ?? 0;
  const startSec = options.startSec ?? 0.5;
  const segmentCount = options.segmentCount ?? 1;

  if (typeof durationSec !== "number" || !Number.isFinite(durationSec) || durationSec < 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft durationSec must be a non-negative finite number, got ${durationSec}`,
    );
  }
  if (typeof bpm !== "number" || !Number.isFinite(bpm) || bpm <= 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft bpm must be a positive finite number, got ${bpm}`,
    );
  }
  if (typeof startSec !== "number" || !Number.isFinite(startSec) || startSec < 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft startSec must be a non-negative finite number, got ${startSec}`,
    );
  }
  if (typeof jitterMs !== "number" || !Number.isFinite(jitterMs) || jitterMs < 0) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft jitterMs must be a non-negative finite number, got ${jitterMs}`,
    );
  }
  if (
    options.soundCount !== undefined &&
    (typeof options.soundCount !== "number" ||
      !Number.isInteger(options.soundCount) ||
      options.soundCount < 0)
  ) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft soundCount must be a non-negative integer, got ${options.soundCount}`,
    );
  }
  if (!Number.isInteger(segmentCount) || segmentCount < 1) {
    throw new EnsomiError(
      "INVALID_OPTIONS",
      `generateSyntheticDraft segmentCount must be an integer >= 1, got ${segmentCount}`,
    );
  }

  if (durationSec === 0) {
    return { segments: [], spans: [], beats: [], sounds: [] };
  }

  const rand = createPrng(options.seed ?? 1337);

  const rawSegments = [];
  for (let i = 0; i < segmentCount; i += 1) {
    const t0 = roundTime((i * durationSec) / segmentCount, 6);
    const t1 = i === segmentCount - 1 ? durationSec : roundTime(((i + 1) * durationSec) / segmentCount, 6);
    if (t1 > t0) {
      rawSegments.push({
        id: `seg_${i}`,
        t0,
        t1,
        regime: "auto",
        kept: false,
        source: "draft",
        revision: 1,
      });
    }
  }
  const segments = normalizeSegmentPartition(rawSegments, durationSec, "draft");

  const periodSec = 60 / bpm;
  /** @type {import("../index.d.ts").Beat[]} */
  const beats = [];
  let idx = 0;
  for (let t = startSec; t < durationSec; t += periodSec) {
    const jitterSec = jitterMs > 0 ? ((rand() * 2 - 1) * jitterMs) / 1000 : 0;
    const clampedT = Math.min(durationSec - 1e-6, Math.max(0, t + jitterSec));
    beats.push({
      id: `beat_${idx}`,
      timeSec: roundTime(clampedT, 6),
      source: "draft",
      confidence: roundTime(0.85 + rand() * 0.14, 3),
      revision: 1,
    });
    idx += 1;
  }

  const targetSoundCount = options.soundCount ?? Math.min(beats.length, 64);
  /** @type {import("../index.d.ts").SoundAnchor[]} */
  const sounds = [];
  if (beats.length > 0 && targetSoundCount > 0) {
    for (let i = 0; i < targetSoundCount; i += 1) {
      const beatIdx = Math.min(
        beats.length - 1,
        Math.floor((i * beats.length) / targetSoundCount),
      );
      const baseT = beats[beatIdx].timeSec;
      const subOffset = i % 3 === 1 ? periodSec / 2 : 0;
      const timeSec = roundTime(Math.min(durationSec - 0.005, baseT + subOffset), 6);
      const profile = SYNTHETIC_SOUND_PROFILES[i % SYNTHETIC_SOUND_PROFILES.length];
      sounds.push({
        id: `snd_${i}`,
        timeSec,
        strength: roundTime(0.35 + rand() * 0.65, 3),
        family: /** @type {import("../index.d.ts").SnapFamily} */ (profile.family),
        role: /** @type {import("../index.d.ts").SnapRole} */ (profile.role),
        sigmaMs: profile.sigmaMs,
        source: "draft",
        revision: 1,
      });
    }
  }

  return { segments, spans: [], beats, sounds };
}

function delay(delayMs, signal, ignoreAbort) {
  return new Promise((resolve, reject) => {
    if (!ignoreAbort && signal?.aborted) {
      reject(signal.reason ?? new Error("Aborted"));
      return;
    }
    const timer = setTimeout(() => {
      if (!ignoreAbort && signal) {
        signal.removeEventListener("abort", onAbort);
      }
      resolve(undefined);
    }, delayMs);

    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new Error("Aborted"));
    };

    if (!ignoreAbort && signal) {
      signal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

export class ScriptedMockProvider {
  /**
   * @param {import("../index.d.ts").ScriptedMockProviderOptions} [options]
   */
  constructor(options = {}) {
    this._id = options.id ?? "ensomi.scripted";
    this._version = options.version ?? "0.2.0";
    this._draft = options.draft ?? null;
    this._defaultScenario = options.defaultScenario ?? "local_rewrite";
    this._delayMs = options.delayMs ?? 0;
    this._analyzeDelayMs = options.analyzeDelayMs ?? 0;
    this._snapDelayMs = options.snapDelayMs ?? 0;
    this._ignoreAbort = Boolean(options.ignoreAbort);
    this._script = Array.isArray(options.script) ? options.script.slice() : [];
    this._snapScript = Array.isArray(options.snapScript) ? options.snapScript.slice() : [];
    this._callCount = 0;
    this._snapCallCount = 0;
    this._analyzeCallCount = 0;

    /** @type {Array<{ generation: number, reason: string, aborted: boolean }>} */
    this.recomputeCalls = [];
    /** @type {Array<{ generation: number, timeSec: number, windowSec: number, aborted: boolean }>} */
    this.suggestSnapsCalls = [];
    /** @type {Array<{ aborted: boolean }>} */
    this.analyzeCalls = [];
  }

  /** @returns {import("../index.d.ts").ProviderManifest} */
  manifest() {
    return {
      id: this._id,
      version: this._version,
      kind: "mock",
      capabilities: {
        draft: true,
        sounds: true,
        snapSuggestions: true,
        constrainedRecompute: true,
        supportedSnapFamilies: [...SNAP_FAMILIES],
        supportedSnapRoles: [...SNAP_ROLES],
        diagnostics: true,
        alternatives: true,
        repeatPropagation: true,
      },
    };
  }

  /**
   * @param {import("../index.d.ts").AudioDescriptor} audio
   * @param {AbortSignal} [signal]
   */
  async prepare(audio, signal) {
    if (!this._ignoreAbort && signal?.aborted) {
      throw signal.reason ?? new Error("Aborted");
    }
    return {
      songId: audio.songId,
      durationSec: audio.durationSec,
      clock: audio.clock,
      prepared: true,
    };
  }

  /**
   * @param {any} evidence
   * @param {AbortSignal} [signal]
   */
  async analyze(evidence, signal) {
    this._analyzeCallCount += 1;
    const record = { aborted: false };
    this.analyzeCalls.push(record);
    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }
    if (this._analyzeDelayMs > 0) {
      await delay(this._analyzeDelayMs, signal, this._ignoreAbort);
    } else if (!this._ignoreAbort && signal?.aborted) {
      throw signal.reason ?? new Error("Aborted");
    }
    if (this._draft) {
      return structuredClone(this._draft);
    }
    return generateSyntheticDraft({ durationSec: evidence?.durationSec ?? 16, bpm: 120 });
  }

  /**
   * Ephemeral `micro_assist` snap suggestions near `request.timeSec`.
   * @param {any} evidence
   * @param {import("../index.d.ts").SessionState} state
   * @param {{ timeSec: number, windowSec?: number, generation?: number }} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SnapCandidate[]>}
   */
  async suggestSnaps(evidence, state, request, signal) {
    this._snapCallCount += 1;
    const callIdx = this._snapCallCount;
    const windowSec = request.windowSec ?? 0.08;
    const record = {
      generation: request.generation ?? callIdx,
      timeSec: request.timeSec,
      windowSec,
      aborted: false,
    };
    this.suggestSnapsCalls.push(record);

    if (signal) {
      signal.addEventListener("abort", () => {
        record.aborted = true;
      }, { once: true });
    }

    if (this._snapScript.length > 0) {
      const step = this._snapScript.shift();
      if (typeof step === "function") {
        return await step({ evidence, state, request, signal, callIdx });
      }
      return structuredClone(step);
    }

    if (this._snapDelayMs > 0) {
      await delay(this._snapDelayMs, signal, this._ignoreAbort);
    } else if (!this._ignoreAbort && signal?.aborted) {
      throw signal.reason ?? new Error("Aborted");
    }

    const sounds = state?.sounds instanceof Map ? Array.from(state.sounds.values()) : [];
    const matching = sounds
      .filter((s) => Math.abs(s.timeSec - request.timeSec) <= windowSec)
      .sort((a, b) => Math.abs(a.timeSec - request.timeSec) - Math.abs(b.timeSec - request.timeSec));

    if (matching.length > 0) {
      return matching.slice(0, 6).map((s) => {
        const family = s.family ?? "percussion";
        const role = s.role ?? "transient_attack";
        return {
          eventId: `snap_${s.id}`,
          targetSec: s.timeSec,
          deltaMs: roundTime((s.timeSec - request.timeSec) * 1000, 4),
          family,
          role,
          ...(s.sigmaMs !== undefined ? { sigmaMs: s.sigmaMs } : { sigmaMs: 4 }),
          label: FAMILY_LABELS[family] ?? "Acoustic onset",
          salience: s.strength ?? 0.85,
        };
      });
    }

    const durationSec = evidence?.durationSec ?? state?.durationSec ?? 32;
    const primaryTarget = Math.min(
      Math.max(0, roundTime(request.timeSec + Math.min(0.008, windowSec * 0.25), 6)),
      Math.max(0, durationSec - 0.001),
    );
    const secondaryTarget = Math.max(
      0,
      roundTime(request.timeSec - Math.min(0.014, windowSec * 0.4), 6),
    );

    const tag = sha256Hex(`${this._id}:${roundTime(request.timeSec, 4)}`).slice(0, 8);
    return [
      {
        eventId: `evt_${tag}_0`,
        targetSec: primaryTarget,
        deltaMs: roundTime((primaryTarget - request.timeSec) * 1000, 4),
        family: "percussion",
        role: "transient_attack",
        sigmaMs: 3,
        label: "Kick attack",
        salience: 0.92,
      },
      {
        eventId: `evt_${tag}_1`,
        targetSec: secondaryTarget,
        deltaMs: roundTime((secondaryTarget - request.timeSec) * 1000, 4),
        family: "piano_keys",
        role: "p_center",
        sigmaMs: 5,
        label: "Chord onset",
        salience: 0.76,
      },
    ];
  }

  /**
   * @param {any} evidence
   * @param {import("../index.d.ts").SessionState} state
   * @param {import("../index.d.ts").RecomputeRequest} request
   * @param {AbortSignal} [signal]
   */
  async recompute(evidence, state, request, signal) {
    this._callCount += 1;
    const callIdx = this._callCount;
    const callRecord = {
      generation: request.generation,
      reason: request.reason,
      aborted: false,
    };
    this.recomputeCalls.push(callRecord);

    if (signal) {
      signal.addEventListener("abort", () => {
        callRecord.aborted = true;
      }, { once: true });
    }

    if (this._script.length > 0) {
      const nextStep = this._script.shift();
      if (typeof nextStep === "function") {
        return await nextStep({ evidence, state, request, signal, callIdx });
      }
      return structuredClone(nextStep);
    }

    const scenario = request.reason || this._defaultScenario;
    const durationSec =
      state.durationSec || evidence?.durationSec || state.audio?.durationSec || 16;
    const effectiveSegments =
      state.segments.length > 0
        ? state.segments
        : normalizeSegmentPartition([], Math.max(durationSec, 16), "draft");

    const baseRange =
      request.scope === "song"
        ? { t0: 0, t1: effectiveSegments[effectiveSegments.length - 1].t1 }
        : { ...request.scope };

    if (scenario === "slow_cancellable" || this._delayMs > 0) {
      await delay(this._delayMs || 40, signal, this._ignoreAbort);
    } else if (!this._ignoreAbort && signal?.aborted) {
      throw signal.reason ?? new Error("Aborted");
    }

    const idTag = this._durableTag(request, scenario, baseRange);
    const batchId = `batch_${idTag}`;

    if (scenario === "triplet_vs_reset") {
      return this._buildTripletVsReset(state, effectiveSegments, request, baseRange, batchId, idTag);
    }
    if (scenario === "forward" || scenario === "forward_continuation") {
      return this._buildForward(state, effectiveSegments, request, baseRange, batchId, idTag);
    }
    if (scenario === "cross_region") {
      return this._buildCrossRegion(state, effectiveSegments, request, baseRange, batchId, idTag);
    }
    if (scenario === "protected_keep_conflict" || scenario === "hard_constraint_conflict") {
      return this._buildProtectedKeepConflict(
        state,
        effectiveSegments,
        request,
        baseRange,
        batchId,
        idTag,
      );
    }
    return this._buildLocalRewrite(state, effectiveSegments, request, baseRange, batchId, idTag);
  }

  _durableTag(request, scenario, range) {
    const digest = sha256Hex(
      canonicalJson({
        providerId: this._id,
        generation: request.generation,
        basisSeq: request.basisSeq,
        scenario,
        range,
      }),
    ).slice(0, 8);
    return `g${request.generation}_s${request.basisSeq}_${digest}`;
  }

  _resolveWriteScopes(segments, range) {
    const slices = decomposeRangeToSpans(segments, range);
    if (slices.length > 0) return slices;
    const first = segments[0] ?? { id: "seg_0", t0: range.t0, t1: range.t1 };
    return [{ segmentId: first.id, t0: range.t0, t1: range.t1 }];
  }

  _captureRevisions(state, entities) {
    /** @type {Record<string, string>} */
    const expectedRevisions = {};
    for (const e of entities) {
      const rev = state.entityRevisions.get(e.id);
      if (rev) expectedRevisions[e.id] = rev;
    }
    return expectedRevisions;
  }

  _buildTripletVsReset(state, segments, request, baseRange, batchId, idTag) {
    const writeScopes = this._resolveWriteScopes(segments, baseRange);
    const primaryScope = writeScopes[0];
    const existingInScope = Array.from(state.beats.values())
      .filter((b) => writeScopes.some((ws) => rangeContainsTime(ws, b.timeSec)))
      .sort((a, b) => a.timeSec - b.timeSec);
    const expectedRevisions = this._captureRevisions(state, existingInScope);
    const expectedScopeBeatIds = getScopeBeatIds(state, writeScopes);

    const spanLen = primaryScope.t1 - primaryScope.t0;
    const tripletBeats = [0, 1, 2].map((k) => ({
      id: `trip_${idTag}_${k}`,
      timeSec: roundTime(primaryScope.t0 + ((k + 0.25) * spanLen) / 3, 6),
      source: /** @type {const} */ ("offer"),
      confidence: 0.89,
    }));

    const opsA = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats: tripletBeats,
    };

    const off0 = Math.min(0.1, spanLen * 0.15);
    const off1 = Math.min(0.6, spanLen * 0.65);
    const resetBeats = [
      {
        id: `ph_${idTag}_0`,
        timeSec: roundTime(primaryScope.t0 + off0, 6),
        source: /** @type {const} */ ("offer"),
        confidence: 0.91,
      },
      {
        id: `ph_${idTag}_1`,
        timeSec: roundTime(primaryScope.t0 + off1, 6),
        source: /** @type {const} */ ("offer"),
        confidence: 0.91,
      },
    ];
    const opsB = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats: resetBeats,
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${idTag}_opt0`,
          batchId,
          optionIndex: 0,
          optionCount: 2,
          scopeKind: "local",
          readScope: baseRange,
          writeScopes,
          expectedRevisions,
          expectedScopeBeatIds,
          ops: opsA,
          title: "Fit triplet pulse",
        }),
        this._makeOffer(state, request, {
          id: `offer_${idTag}_opt1`,
          batchId,
          optionIndex: 1,
          optionCount: 2,
          scopeKind: "local",
          readScope: baseRange,
          writeScopes,
          expectedRevisions,
          expectedScopeBeatIds,
          ops: opsB,
          title: "Realign downbeat phase",
        }),
      ],
    };
  }

  _buildLocalRewrite(state, segments, request, baseRange, batchId, idTag) {
    const writeScopes = this._resolveWriteScopes(segments, baseRange);
    const primaryScope = writeScopes[0];
    const existingInScope = Array.from(state.beats.values())
      .filter((b) => writeScopes.some((ws) => rangeContainsTime(ws, b.timeSec)))
      .sort((a, b) => a.timeSec - b.timeSec);
    const expectedRevisions = this._captureRevisions(state, existingInScope);
    const expectedScopeBeatIds = getScopeBeatIds(state, writeScopes);

    const spanLen = primaryScope.t1 - primaryScope.t0;
    const upsertBeats = [
      {
        id: `loc_${idTag}_0`,
        timeSec: roundTime(primaryScope.t0 + (spanLen >= 1 ? 0.25 : spanLen * 0.25), 6),
        source: /** @type {const} */ ("offer"),
        confidence: 0.94,
      },
      {
        id: `loc_${idTag}_1`,
        timeSec: roundTime(primaryScope.t0 + (spanLen >= 1 ? 0.75 : spanLen * 0.75), 6),
        source: /** @type {const} */ ("offer"),
        confidence: 0.94,
      },
    ];

    const ops = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats,
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${idTag}_local`,
          batchId,
          scopeKind: "local",
          readScope: baseRange,
          writeScopes,
          expectedRevisions,
          expectedScopeBeatIds,
          ops,
          title: `Align beat grid at ${formatTimeCode(primaryScope.t0)}–${formatTimeCode(primaryScope.t1)}`,
        }),
      ],
    };
  }

  _buildForward(state, segments, request, readRange, batchId, idTag) {
    const maxTrackT1 = segments[segments.length - 1].t1;
    const fwdT0 = Math.min(readRange.t1, maxTrackT1 - 0.5);
    const fwdT1 = Math.min(maxTrackT1, roundTime(fwdT0 + 2.0, 6));
    const writeScopes = this._resolveWriteScopes(segments, { t0: fwdT0, t1: fwdT1 });
    const primaryScope = writeScopes[0];
    const expectedScopeBeatIds = getScopeBeatIds(state, writeScopes);

    const spanLen = primaryScope.t1 - primaryScope.t0;
    const step = Math.min(0.5, spanLen / 4);
    const ops = {
      upsertBeats: [0, 1, 2, 3]
        .map((k) => roundTime(primaryScope.t0 + k * step, 6))
        .filter((t) => t < primaryScope.t1)
        .map((timeSec, k) => ({
          id: `fwd_${idTag}_${k}`,
          timeSec,
          source: /** @type {const} */ ("offer"),
          confidence: 0.9,
        })),
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${idTag}_fwd`,
          batchId,
          scopeKind: "forward",
          readScope: readRange,
          writeScopes,
          expectedRevisions: {},
          expectedScopeBeatIds,
          ops,
          title: `Continue steady pulse from ${formatTimeCode(primaryScope.t0)}`,
        }),
      ],
    };
  }

  _buildCrossRegion(state, segments, request, readRange, batchId, idTag) {
    const maxTrackT1 = segments[segments.length - 1].t1;
    const spanWidth = Math.max(0.5, readRange.t1 - readRange.t0);
    const targetT0 = Math.min(roundTime(readRange.t0 + 8.0, 6), Math.max(0, maxTrackT1 - spanWidth));
    const targetT1 = Math.min(maxTrackT1, roundTime(targetT0 + spanWidth, 6));
    const writeScopes = this._resolveWriteScopes(segments, { t0: targetT0, t1: targetT1 });
    const primaryScope = writeScopes[0];
    const expectedScopeBeatIds = getScopeBeatIds(state, writeScopes);

    const localLen = primaryScope.t1 - primaryScope.t0;
    const ops = {
      upsertBeats: [
        {
          id: `xreg_${idTag}_0`,
          timeSec: roundTime(primaryScope.t0 + (localLen >= 1 ? 0.25 : localLen * 0.25), 6),
          source: /** @type {const} */ ("offer"),
          confidence: 0.88,
        },
        {
          id: `xreg_${idTag}_1`,
          timeSec: roundTime(primaryScope.t0 + (localLen >= 1 ? 0.75 : localLen * 0.75), 6),
          source: /** @type {const} */ ("offer"),
          confidence: 0.88,
        },
      ],
    };

    // Passive non-mutating advisory if another section in the track is Keep-locked
    const advisoryNotes = [];
    const otherKeep = request.constraints.keepLocks.find(
      (lk) => lk.t1 <= primaryScope.t0 || lk.t0 >= primaryScope.t1,
    );
    if (otherKeep) {
      advisoryNotes.push(
        `Matches edited section (+14 ms) at ${formatTimeCode(otherKeep.t0)}–${formatTimeCode(otherKeep.t1)} — unlock Keep to apply`,
      );
    }

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${idTag}_prop`,
          batchId,
          scopeKind: "cross_region",
          readScope: readRange,
          writeScopes,
          expectedRevisions: {},
          expectedScopeBeatIds,
          ops,
          title: `Apply timing to repeated passage at ${formatTimeCode(primaryScope.t0)}`,
          advisoryNotes,
        }),
      ],
    };
  }

  _buildProtectedKeepConflict(state, segments, request, baseRange, batchId, idTag) {
    const keepLock = request.constraints.keepLocks[0];
    const targetRange = keepLock ? { t0: keepLock.t0, t1: keepLock.t1 } : baseRange;
    const writeScopes = this._resolveWriteScopes(segments, targetRange);
    const primaryScope = writeScopes[0];
    const targetTime = roundTime((primaryScope.t0 + primaryScope.t1) / 2, 6);

    const ops = {
      upsertBeats: [
        {
          id: `kp_${idTag}_0`,
          timeSec: targetTime,
          source: /** @type {const} */ ("offer"),
          confidence: 0.72,
        },
      ],
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${idTag}_keep_test`,
          batchId,
          scopeKind: "local",
          readScope: targetRange,
          writeScopes,
          expectedRevisions: {},
          expectedScopeBeatIds: getScopeBeatIds(state, writeScopes),
          ops,
          title: `Adjust timing near ${formatTimeCode(targetTime)}`,
        }),
      ],
    };
  }

  _makeOffer(state, request, spec) {
    const ops = spec.ops;
    const expectedRevisions = spec.expectedRevisions ?? {};
    return {
      id: spec.id,
      batchId: spec.batchId,
      ...(spec.optionIndex !== undefined ? { optionIndex: spec.optionIndex } : {}),
      ...(spec.optionCount !== undefined ? { optionCount: spec.optionCount } : {}),
      providerId: this._id,
      providerKind: /** @type {const} */ ("mock"),
      generation: request.generation,
      basisSeq: request.basisSeq,
      expectedRevisions,
      ...(spec.expectedScopeBeatIds ? { expectedScopeBeatIds: spec.expectedScopeBeatIds } : {}),
      scopeKind: spec.scopeKind,
      readScope: spec.readScope,
      writeScopes: spec.writeScopes,
      ops,
      title: spec.title,
      consequenceSummary: formatConsequenceSummary(state, ops),
      advisoryNotes: spec.advisoryNotes ?? [],
      consequences: computeConsequences(state, ops),
      conflicts: [],
      semanticKey: computeSemanticKey(spec.writeScopes, ops, expectedRevisions),
      status: /** @type {const} */ ("valid"),
    };
  }
}
