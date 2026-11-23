/**
 * Purely deterministic synthetic TimingProvider (`ScriptedMockProvider`) and
 * synthetic song draft generator for conformance tests and offline development.
 * Uses synthetic data ONLY — zero real audio, beatmaps, or checkpoints.
 */

import { roundTime } from "./hash.js";
import { computeConsequences, computeSemanticKey, getRangeEntityIds } from "./offers.js";
import { STATEMENT_KINDS } from "./types.js";

/**
 * Deterministic linear congruential generator for reproducible synthetic jitter/confidence.
 * @param {number} seed
 */
function createPrng(seed = 42) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(1664525, state) + 1013904223) >>> 0;
    return state / 0x100000000;
  };
}

/**
 * Generate a purely synthetic song draft (beats, boundaries, sounds) for testing and benchmarks.
 *
 * @param {{
 *   durationSec?: number,
 *   bpm?: number,
 *   jitterMs?: number,
 *   soundCount?: number,
 *   seed?: number,
 *   startSec?: number,
 * }} [options]
 */
export function generateSyntheticDraft(options = {}) {
  const durationSec = options.durationSec ?? 32;
  const bpm = options.bpm ?? 120;
  const jitterMs = options.jitterMs ?? 0;
  const startSec = options.startSec ?? 0.5;
  const rand = createPrng(options.seed ?? 1337);

  const periodSec = 60 / bpm;
  /** @type {import("../index.d.ts").Beat[]} */
  const beats = [];
  let idx = 0;
  for (let t = startSec; t < durationSec; t += periodSec) {
    const jitterSec = jitterMs > 0 ? ((rand() * 2 - 1) * jitterMs) / 1000 : 0;
    beats.push({
      id: `beat_${idx}`,
      t: roundTime(Math.max(0, t + jitterSec), 6),
      source: "model",
      version: 1,
      conf: roundTime(0.85 + rand() * 0.14, 3),
    });
    idx += 1;
  }

  /** @type {import("../index.d.ts").Boundary[]} */
  const boundaries = [
    {
      id: "bnd_0",
      t: beats.length > 0 ? beats[0].t : startSec,
      kind: "steady_start",
      source: "model",
      version: 1,
      bpm,
    },
  ];

  const targetSoundCount = options.soundCount ?? Math.min(beats.length, 64);
  /** @type {import("../index.d.ts").SoundAnchor[]} */
  const sounds = [];
  if (beats.length > 0 && targetSoundCount > 0) {
    for (let i = 0; i < targetSoundCount; i += 1) {
      const beatIdx = Math.min(
        beats.length - 1,
        Math.floor((i * beats.length) / targetSoundCount),
      );
      const baseT = beats[beatIdx].t;
      const subOffset = i % 3 === 1 ? periodSec / 2 : 0;
      const t = roundTime(Math.min(durationSec - 0.01, baseT + subOffset), 6);
      sounds.push({
        id: `snd_${i}`,
        t,
        emphasis: roundTime(0.3 + rand() * 0.7, 3),
        source: "model",
        version: 1,
        conf: roundTime(0.8 + rand() * 0.19, 3),
        experimental: true,
      });
    }
  }

  return { beats, boundaries, sounds };
}

/**
 * Helper to wait for `delayMs`, optionally rejecting early when `signal` aborts.
 * @param {number} delayMs
 * @param {AbortSignal | undefined} signal
 * @param {boolean} ignoreAbort
 */
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
   * @param {{
   *   id?: string,
   *   version?: string,
   *   draft?: { beats?: any[], boundaries?: any[], sounds?: any[], statements?: any[] },
   *   defaultScenario?: string,
   *   delayMs?: number,
   *   ignoreAbort?: boolean,
   *   script?: Array<any>,
   * }} [options]
   */
  constructor(options = {}) {
    this._id = options.id ?? "mock.scripted.v0";
    this._version = options.version ?? "0.1.0";
    this._draft = options.draft ?? null;
    this._defaultScenario = options.defaultScenario ?? "local_rewrite";
    this._delayMs = options.delayMs ?? 0;
    this._ignoreAbort = Boolean(options.ignoreAbort);
    this._script = Array.isArray(options.script) ? options.script.slice() : [];
    this._callCount = 0;
    /** @type {Array<{ generation: number, reason: string, aborted: boolean }>} */
    this.recomputeCalls = [];
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
        soundsExperimental: true,
        constrainedRecompute: true,
        honoredStatements: [...STATEMENT_KINDS],
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
    if (signal?.aborted) throw signal.reason ?? new Error("Aborted");
    return {
      audioId: audio.audioId,
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
    if (signal?.aborted) throw signal.reason ?? new Error("Aborted");
    if (this._draft) {
      return structuredClone(this._draft);
    }
    return generateSyntheticDraft({ durationSec: evidence?.durationSec ?? 16, bpm: 120 });
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
      signal.addEventListener(
        "abort",
        () => {
          callRecord.aborted = true;
        },
        { once: true },
      );
    }

    // Check custom queued script first
    if (this._script.length > 0) {
      const nextStep = this._script.shift();
      if (typeof nextStep === "function") {
        return await nextStep({ evidence, state, request, signal, callIdx });
      }
      return structuredClone(nextStep);
    }

    const scenario = request.reason || this._defaultScenario;
    const writeScope =
      request.scope === "song"
        ? { t0: 0, t1: evidence?.durationSec ?? state.audio?.durationSec ?? 16 }
        : { ...request.scope };

    if (scenario === "slow_cancellable" || this._delayMs > 0) {
      await delay(this._delayMs || 40, signal, this._ignoreAbort);
    }

    const batchId = `batch_${this._id}_g${request.generation}_${callIdx}`;

    if (scenario === "triplet_vs_reset") {
      return this._buildTripletVsReset(state, request, writeScope, batchId, callIdx);
    }
    if (scenario === "forward_continuation") {
      return this._buildForwardContinuation(state, request, writeScope, batchId, callIdx);
    }
    if (scenario === "cross_region") {
      return this._buildCrossRegion(state, request, writeScope, batchId, callIdx);
    }
    if (scenario === "hard_constraint_conflict") {
      return this._buildHardConstraintConflict(state, request, writeScope, batchId, callIdx);
    }
    return this._buildLocalRewrite(state, request, writeScope, batchId, callIdx);
  }

  /**
   * Build 2 mutually conflicting sibling offers in one batch:
   *  - Option 0: triplet subdivision interpretation (alternative)
   *  - Option 1: phase reset interpretation (quick_fix)
   */
  _buildTripletVsReset(state, request, writeScope, batchId, callIdx) {
    const expectedRangeEntityIds = getRangeEntityIds(state, writeScope);
    const existingInScope = Array.from(state.beats.values())
      .filter((b) => b.t >= writeScope.t0 && b.t < writeScope.t1)
      .sort((a, b) => a.t - b.t);
    /** @type {Record<string, number>} */
    const expectedVersions = {};
    for (const b of existingInScope) expectedVersions[b.id] = b.version;

    const spanLen = Math.max(0.5, writeScope.t1 - writeScope.t0);
    const tripletStep = spanLen / 3;
    const opsA = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats: [0, 1, 2].map((k) => ({
        id: `mock_trip_${callIdx}_${k}`,
        t: roundTime(writeScope.t0 + k * tripletStep, 6),
        source: /** @type {const} */ ("model"),
        conf: 0.88,
      })),
      expectedVersions,
      expectedRangeEntityIds,
    };

    const opsB = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats: [
        {
          id: `mock_reset_${callIdx}_0`,
          t: roundTime(writeScope.t0 + 0.1, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.91,
        },
        {
          id: `mock_reset_${callIdx}_1`,
          t: roundTime(writeScope.t0 + 0.6, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.91,
        },
      ],
      upsertBoundaries: [
        {
          id: `mock_reset_bnd_${callIdx}`,
          t: roundTime(writeScope.t0 + 0.1, 6),
          kind: /** @type {const} */ ("reset"),
          source: /** @type {const} */ ("model"),
          bpm: 120,
        },
      ],
      expectedVersions,
      expectedRangeEntityIds,
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_opt0`,
          batchId,
          optionIndex: 0,
          role: "alternative",
          scopeKind: "local",
          readScope: writeScope,
          writeScope,
          ops: opsA,
          summary: "Interpret span as triplet pulse",
        }),
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_opt1`,
          batchId,
          optionIndex: 1,
          role: "quick_fix",
          scopeKind: "local",
          readScope: writeScope,
          writeScope,
          ops: opsB,
          summary: "Insert phase reset and realign downbeat",
        }),
      ],
    };
  }

  _buildLocalRewrite(state, request, writeScope, batchId, callIdx) {
    const expectedRangeEntityIds = getRangeEntityIds(state, writeScope);
    const existingInScope = Array.from(state.beats.values())
      .filter((b) => b.t >= writeScope.t0 && b.t < writeScope.t1)
      .sort((a, b) => a.t - b.t);
    /** @type {Record<string, number>} */
    const expectedVersions = {};
    for (const b of existingInScope) expectedVersions[b.id] = b.version;

    const ops = {
      removeBeatIds: existingInScope.map((b) => b.id),
      upsertBeats: [
        {
          id: `mock_local_${callIdx}_0`,
          t: roundTime(writeScope.t0 + 0.25, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.94,
        },
        {
          id: `mock_local_${callIdx}_1`,
          t: roundTime(writeScope.t0 + 0.75, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.94,
        },
      ],
      expectedVersions,
      expectedRangeEntityIds,
    };

    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_local`,
          batchId,
          optionIndex: 0,
          role: "rewrite",
          scopeKind: "local",
          readScope: writeScope,
          writeScope,
          ops,
          summary: `Local rewrite over [${writeScope.t0}, ${writeScope.t1})`,
        }),
      ],
    };
  }

  _buildForwardContinuation(state, request, readScope, batchId, callIdx) {
    const writeScope = {
      t0: roundTime(readScope.t1, 6),
      t1: roundTime(readScope.t1 + 2.0, 6),
    };
    const expectedRangeEntityIds = getRangeEntityIds(state, writeScope);
    const ops = {
      upsertBeats: [0, 1, 2, 3].map((k) => ({
        id: `mock_fwd_${callIdx}_${k}`,
        t: roundTime(writeScope.t0 + k * 0.5, 6),
        source: /** @type {const} */ ("model"),
        conf: 0.9,
      })),
      expectedRangeEntityIds,
    };
    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_fwd`,
          batchId,
          role: "extend",
          scopeKind: "forward_continuation",
          readScope,
          writeScope,
          ops,
          summary: `Continue steady grid into [${writeScope.t0}, ${writeScope.t1})`,
        }),
      ],
    };
  }

  _buildCrossRegion(state, request, readScope, batchId, callIdx) {
    const offset = 8.0;
    const writeScope = {
      t0: roundTime(readScope.t0 + offset, 6),
      t1: roundTime(readScope.t1 + offset, 6),
    };
    const expectedRangeEntityIds = getRangeEntityIds(state, writeScope);
    const ops = {
      upsertBeats: [
        {
          id: `mock_prop_${callIdx}_0`,
          t: roundTime(writeScope.t0 + 0.25, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.87,
        },
        {
          id: `mock_prop_${callIdx}_1`,
          t: roundTime(writeScope.t0 + 0.75, 6),
          source: /** @type {const} */ ("model"),
          conf: 0.87,
        },
      ],
      expectedRangeEntityIds,
    };
    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_prop`,
          batchId,
          role: "propagate",
          scopeKind: "cross_region",
          readScope,
          writeScope,
          ops,
          summary: `Propagate timing pattern to repeated region [${writeScope.t0}, ${writeScope.t1})`,
        }),
      ],
    };
  }

  _buildHardConstraintConflict(state, request, writeScope, batchId, callIdx) {
    // Deliberately propose a beat inside the first freeze/forbid constraint (or writeScope midpoint)
    const hardConstraint = request.constraints.find(
      (c) => c.form === "freeze" || c.form === "forbid",
    );
    const targetT =
      hardConstraint?.t !== undefined
        ? hardConstraint.t
        : hardConstraint?.span
          ? roundTime((hardConstraint.span.t0 + hardConstraint.span.t1) / 2, 6)
          : roundTime((writeScope.t0 + writeScope.t1) / 2, 6);
    const targetScope = hardConstraint?.span ?? writeScope;

    const ops = {
      upsertBeats: [
        {
          id: `mock_conflict_${callIdx}_0`,
          t: targetT,
          source: /** @type {const} */ ("model"),
          conf: 0.65,
        },
      ],
    };
    return {
      batchId,
      offers: [
        this._makeOffer(state, request, {
          id: `offer_${callIdx}_conflict`,
          batchId,
          role: "rewrite",
          scopeKind: "local",
          readScope: targetScope,
          writeScope: targetScope,
          ops,
          summary: "Conflicting candidate violating active freeze/forbid constraint",
        }),
      ],
    };
  }

  _makeOffer(state, request, spec) {
    return {
      id: spec.id,
      batchId: spec.batchId,
      ...(spec.optionIndex !== undefined ? { optionIndex: spec.optionIndex } : {}),
      providerId: this._id,
      providerKind: /** @type {const} */ ("mock"),
      generation: request.generation,
      basisSeq: request.basisSeq,
      role: spec.role,
      scopeKind: spec.scopeKind,
      readScope: spec.readScope,
      writeScope: spec.writeScope,
      ops: spec.ops,
      summary: spec.summary,
      consequences: computeConsequences(state, spec.ops),
      conflicts: [],
      semanticKey: computeSemanticKey(spec.writeScope, spec.ops),
      status: /** @type {const} */ ("pending"),
    };
  }
}
