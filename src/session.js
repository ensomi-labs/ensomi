/**
 * Headless `SessionController` with three independent `(songId, layer, generation)`
 * trigger policy lifecycles (`song_open`, `micro_assist`, `heavy_recompute`):
 *
 *  - `suggestSnaps(...)` (`micro_assist`) NEVER aborts an in-flight `recompute(...)`
 *    (`heavy_recompute`) or `analyze(...)` (`song_open`), and writes ZERO events to `.jsonl`.
 *  - A newer call on the SAME layer preempts (`abort()`) the prior in-flight call on that
 *    layer and drops stale responses silently.
 *  - Switching song (`songId` change) or calling `abortAll()` / `close()` aborts all three
 *    layers cleanly.
 *  - `setHoldOffers(boolean)` buffers completed `heavy_recompute` offers while `Alt`-auditioning,
 *    validating each offer independently against current `state` on flush/arrival so
 *    non-overlapping `cross_region` offers stay `"valid"` while overlapping local edits mark
 *    only overlapping offers `"stale"`.
 */

import { compileConstraints } from "./constraints.js";
import { canonicalJson } from "./hash.js";
import {
  computeConsequences,
  computeSemanticKey,
  formatConsequenceSummary,
  getScopeBeatIds,
  isOfferSuppressed,
  validatePatchOffer,
} from "./offers.js";
import {
  applyEvent,
  computeUndoRedoTargets,
  createInitialState,
  foldTrace,
  serializeTraceNdjson,
} from "./trace.js";
import {
  assertRange,
  DEFAULT_TRIGGER_POLICY,
  decomposeRangeToSpans,
  EnsomiError,
  normalizeAudioDescriptor,
  normalizeSnapCandidate,
  TRACE_SCHEMA_VERSION,
  TRIGGER_LAYERS,
} from "./types.js";

const TRIGGER_LAYER_SET = new Set(TRIGGER_LAYERS);
const DRAFT_INVALIDATING_EVENTS = new Set([
  "draft_loaded",
  "edit",
  "offer_accept",
  "retract",
]);

/**
 * Fast defensive clone of `SessionState` so caller/provider mutations cannot corrupt
 * internal controller state while keeping 10-minute song edits well under 1 ms.
 * @param {import("../index.d.ts").SessionState} state
 * @returns {import("../index.d.ts").SessionState}
 */
function cloneSessionStateFast(state) {
  const beats = new Map();
  for (const [k, v] of state.beats) beats.set(k, { ...v });

  const sounds = new Map();
  for (const [k, v] of state.sounds) sounds.set(k, { ...v });

  const offers = new Map();
  for (const [k, v] of state.offers) offers.set(k, structuredClone(v));

  return {
    audio: state.audio ? { ...state.audio } : null,
    initialDurationSec: state.initialDurationSec ?? 0,
    durationSec: state.durationSec,
    segments: state.segments.map((s) => ({ ...s })),
    spans: state.spans.map((s) => ({ ...s })),
    beats,
    sounds,
    unbeats: state.unbeats.map((u) => ({ ...u })),
    offers,
    entityRevisions: new Map(state.entityRevisions),
    segmentMutations: state.segmentMutations.map((m) => ({
      ...m,
      segmentIds: m.segmentIds.slice(),
    })),
    suppressedOffers: structuredClone(state.suppressedOffers),
    suppressions: state.suppressions.map((s) => ({
      ...s,
      writeScopes: s.writeScopes.map((ws) => ({ ...ws })),
    })),
    lastSignal: state.lastSignal ? structuredClone(state.lastSignal) : null,
    generation: state.generation,
    effort: { ...state.effort },
    visitedRanges: state.visitedRanges.map((r) => ({ ...r })),
    ready: state.ready,
    readyAtSeq: state.readyAtSeq,
    events: state.events.slice(),
    eventIds: new Set(state.eventIds),
    retractedEventIds: new Set(state.retractedEventIds),
    lastSeq: state.lastSeq,
    foldWarnings: state.foldWarnings.map((w) => ({ ...w })),
  };
}

/**
 * Link an optional caller `AbortSignal` into an internal `AbortController`.
 * @param {AbortSignal | undefined} callerSignal
 * @param {AbortController} internalController
 */
function linkAbortSignal(callerSignal, internalController) {
  if (!callerSignal) return () => {};
  if (callerSignal.aborted) {
    internalController.abort(callerSignal.reason ?? new EnsomiError("ABORTED", "Operation aborted"));
    return () => {};
  }
  const onAbort = () => {
    internalController.abort(callerSignal.reason ?? new EnsomiError("ABORTED", "Operation aborted"));
  };
  callerSignal.addEventListener("abort", onAbort, { once: true });
  return () => callerSignal.removeEventListener("abort", onAbort);
}

/**
 * Wait `ms` unless `signal` aborts first. Resolves `false` if aborted, `true` if elapsed.
 * @param {number} ms
 * @param {AbortSignal} signal
 * @returns {Promise<boolean>}
 */
function waitDebounce(ms, signal) {
  if (ms <= 0) return Promise.resolve(!signal.aborted);
  if (signal.aborted) return Promise.resolve(false);
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve(true);
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      resolve(false);
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Normalize trigger policy overrides onto `DEFAULT_TRIGGER_POLICY`.
 * @param {Partial<import("../index.d.ts").TriggerPolicy> | undefined} custom
 * @returns {import("../index.d.ts").TriggerPolicy}
 */
function resolveTriggerPolicy(custom) {
  /** @type {any} */
  const resolved = {};
  for (const layer of TRIGGER_LAYERS) {
    const base = DEFAULT_TRIGGER_POLICY[layer];
    const over = custom?.[layer];
    const mode = over?.mode ?? base.mode;
    if (mode !== "auto" && mode !== "manual") {
      throw new EnsomiError("INVALID_POLICY", `Invalid mode "${mode}" for layer "${layer}"`);
    }
    const debounceMs = over?.debounceMs ?? base.debounceMs;
    if (typeof debounceMs !== "number" || !Number.isFinite(debounceMs) || debounceMs < 0) {
      throw new EnsomiError("INVALID_POLICY", `Invalid debounceMs "${debounceMs}" for layer "${layer}"`);
    }
    const cancellation = over?.cancellation ?? base.cancellation;
    if (cancellation !== "latest_wins") {
      throw new EnsomiError(
        "INVALID_POLICY",
        `Invalid cancellation "${cancellation}" for layer "${layer}"`,
      );
    }
    resolved[layer] = Object.freeze({ mode, debounceMs, cancellation });
  }
  return Object.freeze(resolved);
}

/**
 * Validate a `TimingProvider` implements `manifest`, `prepare`, `analyze`, `recompute`, and `suggestSnaps`.
 * @param {import("../index.d.ts").TimingProvider} provider
 */
export function assertTimingProvider(provider) {
  if (!provider || typeof provider !== "object") {
    throw new EnsomiError("INVALID_PROVIDER", "TimingProvider must be an object");
  }
  for (const method of ["manifest", "prepare", "analyze", "recompute", "suggestSnaps"]) {
    if (typeof /** @type {any} */ (provider)[method] !== "function") {
      throw new EnsomiError(
        "INVALID_PROVIDER",
        `TimingProvider is missing required method "${method}()"`,
      );
    }
  }
}

export class SessionController {
  /**
   * @param {import("../index.d.ts").SessionControllerOptions} options
   */
  constructor(options) {
    if (!options || typeof options !== "object") {
      throw new EnsomiError("INVALID_OPTIONS", "SessionController requires an options object");
    }
    assertTimingProvider(options.provider);

    this._provider = options.provider;
    this._clock = options.clock ?? (() => Date.now());
    this._idGenerator = options.idGenerator ?? ((prefix, seq) => `${prefix}_${seq}`);
    this._triggerPolicy = resolveTriggerPolicy(options.triggerPolicy);
    this._closed = false;

    this._state =
      Array.isArray(options.initialEvents) && options.initialEvents.length > 0
        ? foldTrace(options.initialEvents, { durationSec: options.durationSec })
        : createInitialState({ durationSec: options.durationSec });

    this._nextSeq = this._state.lastSeq + 1;
    this._audioEpoch = 0;
    this._evidence = null;
    this._evidenceEpoch = -1;
    /** @type {{ epoch: number, promise: Promise<import("../index.d.ts").PreparedEvidence>, abort: AbortController } | null} */
    this._inFlightPrepare = null;

    const initialSongId = this._state.audio?.songId ?? null;
    /**
     * Three completely independent `(songId, layer, generation, AbortController)` slots!
     * @type {Record<import("../index.d.ts").TriggerLayer, { songId: string | null, layer: import("../index.d.ts").TriggerLayer, generation: number, abort: AbortController | null }>}
     */
    this._slots = {
      song_open: {
        songId: initialSongId,
        layer: "song_open",
        generation: 0,
        abort: null,
      },
      micro_assist: {
        songId: initialSongId,
        layer: "micro_assist",
        generation: 0,
        abort: null,
      },
      heavy_recompute: {
        songId: initialSongId,
        layer: "heavy_recompute",
        generation: this._state.generation,
        abort: null,
      },
    };

    this._holdOffers = false;
    /** @type {{ songId: string | null, generation: number, batchId: string, offers: import("../index.d.ts").PatchOffer[] } | null} */
    this._heldOfferBatch = null;
  }

  _assertOpen() {
    if (this._closed) {
      throw new EnsomiError("SESSION_CLOSED", "SessionController is closed");
    }
  }

  _currentSongId() {
    return this._state.audio?.songId ?? null;
  }

  /** @returns {import("../index.d.ts").ProviderManifest} */
  getManifest() {
    return structuredClone(this._provider.manifest());
  }

  /** @returns {import("../index.d.ts").TriggerPolicy} */
  getTriggerPolicy() {
    return this._triggerPolicy;
  }

  /** @returns {boolean} */
  isClosed() {
    return this._closed;
  }

  /** @returns {import("../index.d.ts").SessionState} */
  getState() {
    return cloneSessionStateFast(this._state);
  }

  /** @returns {readonly import("../index.d.ts").TraceEvent[]} */
  getTrace() {
    return structuredClone(this._state.events);
  }

  /**
   * Read the lifecycle state of one trigger layer (`"song_open"`, `"micro_assist"`, `"heavy_recompute"`).
   * @param {import("../index.d.ts").TriggerLayer} [layer="heavy_recompute"]
   */
  getLayerState(layer = "heavy_recompute") {
    if (!TRIGGER_LAYER_SET.has(layer)) {
      throw new EnsomiError("INVALID_LAYER", `Unknown trigger layer: ${layer}`);
    }
    const slot = this._slots[layer];
    return {
      songId: slot.songId,
      layer: slot.layer,
      generation: slot.generation,
      inFlight: slot.abort !== null,
    };
  }

  /** @returns {number} */
  getGeneration() {
    return this._slots.heavy_recompute.generation;
  }

  /** @returns {import("../index.d.ts").EffortLedger} */
  getEffortLedger() {
    return { ...this._state.effort };
  }

  /** @returns {import("../index.d.ts").PatchOffer[]} */
  getValidOffers() {
    return structuredClone(
      Array.from(this._state.offers.values()).filter((o) => o.status === "valid"),
    );
  }

  /** @returns {import("../index.d.ts").PatchOffer[]} */
  getHeldOffers() {
    if (!this._heldOfferBatch) return [];
    const slot = this._slots.heavy_recompute;
    if (
      this._heldOfferBatch.songId !== this._currentSongId() ||
      this._heldOfferBatch.generation !== slot.generation
    ) {
      return [];
    }
    const liveConstraints = compileConstraints(this._state);
    const revalidated = this._heldOfferBatch.offers
      .filter((offer) => !isOfferSuppressed(this._state, offer))
      .map((offer) => {
        const check = validatePatchOffer(this._state, offer, liveConstraints);
        return {
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          advisoryNotes: check.advisoryNotes,
        };
      });
    return structuredClone(revalidated);
  }

  /** @returns {boolean} */
  isHoldingOffers() {
    return this._holdOffers;
  }

  /**
   * Hold completed `heavy_recompute` offer batches in memory while `Alt`-auditioning.
   * Releasing the hold (`setHoldOffers(false)`) commits the latest held batch against
   * current `state`, validating each offer independently.
   *
   * @param {boolean} hold
   * @returns {{ flushed: boolean, offers: import("../index.d.ts").PatchOffer[] }}
   */
  setHoldOffers(hold) {
    const next = Boolean(hold);
    this._holdOffers = next;
    if (!next && this._heldOfferBatch) {
      const held = this._heldOfferBatch;
      this._heldOfferBatch = null;
      const slot = this._slots.heavy_recompute;
      if (held.generation === slot.generation && held.songId === this._currentSongId()) {
        const beforeSuppressedCount = this._state.suppressedOffers.length;
        const manifest = this._provider.manifest();
        const authorKind = manifest.kind === "rule" ? "rule" : "model";
        this._commitEvent(
          "offer_batch",
          {
            batchId: held.batchId,
            offers: held.offers,
            waitSec: 0,
          },
          { kind: authorKind, id: manifest.id },
        );
        const newlySuppressedIds = new Set(
          this._state.suppressedOffers.slice(beforeSuppressedCount).map((o) => o.id),
        );
        const surfaced = held.offers
          .filter((o) => !newlySuppressedIds.has(o.id))
          .map((o) => this._state.offers.get(o.id))
          .filter(Boolean);
        return {
          flushed: true,
          offers: /** @type {import("../index.d.ts").PatchOffer[]} */ (structuredClone(surfaced)),
        };
      }
    }
    return { flushed: false, offers: [] };
  }

  /** @returns {string} */
  exportNdjson() {
    return serializeTraceNdjson(this._state.events);
  }

  /**
   * Commit a TraceEvent to the append-only trace and advance materialized `SessionState`.
   * @param {import("../index.d.ts").TraceEventType} type
   * @param {unknown} payload
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  _commitEvent(type, payload, author = { kind: "user" }) {
    const seq = this._nextSeq;
    const event = {
      schemaVersion: TRACE_SCHEMA_VERSION,
      seq,
      id: this._idGenerator(type, seq),
      type,
      tsMs: this._clock(),
      author: structuredClone(author),
      payload: structuredClone(payload),
    };
    this._state = applyEvent(this._state, /** @type {import("../index.d.ts").TraceEvent} */ (event));
    this._nextSeq = this._state.lastSeq + 1;
    if (this._state.generation > this._slots.heavy_recompute.generation) {
      this._slots.heavy_recompute.generation = this._state.generation;
    }
    return event;
  }

  /**
   * Abort a single trigger layer slot without disturbing the other two layers.
   * @param {import("../index.d.ts").TriggerLayer} layer
   * @param {string} [reason]
   */
  abortLayer(layer, reason = `Layer "${layer}" aborted`) {
    if (!TRIGGER_LAYER_SET.has(layer)) {
      throw new EnsomiError("INVALID_LAYER", `Unknown trigger layer: ${layer}`);
    }
    const slot = this._slots[layer];
    slot.generation += 1;
    if (slot.abort) {
      slot.abort.abort(new EnsomiError("ABORTED", reason));
      slot.abort = null;
    }
    if (layer === "heavy_recompute") {
      this._heldOfferBatch = null;
    }
  }

  /**
   * Abort all three trigger layers and any in-flight evidence preparation cleanly.
   * @param {string} [reason]
   * @param {"ABORTED" | "SUPERSEDED"} [errorCode="ABORTED"]
   */
  abortAll(reason = "All in-flight provider operations aborted", errorCode = "ABORTED") {
    if (this._inFlightPrepare) {
      this._inFlightPrepare.abort.abort(new EnsomiError(errorCode, reason));
      this._inFlightPrepare = null;
    }
    for (const layer of TRIGGER_LAYERS) {
      const slot = this._slots[layer];
      slot.generation += 1;
      if (slot.abort) {
        slot.abort.abort(new EnsomiError(errorCode, reason));
        slot.abort = null;
      }
    }
    this._heldOfferBatch = null;
  }

  _abortedStatus(abortController) {
    if (this._closed || abortController.signal.reason?.code === "ABORTED") {
      return /** @type {const} */ ("cancelled");
    }
    return /** @type {const} */ ("superseded");
  }

  /**
   * Permanently close the session controller, aborting all three trigger layers cleanly.
   */
  close() {
    this.abortAll("SessionController closed");
    this._closed = true;
  }

  /**
   * Ensure `PreparedEvidence` is cached for `epoch`, sharing one in-flight `prepare()` call
   * across concurrent layers on the same song without cross-layer interference.
   *
   * @param {number} epoch
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").PreparedEvidence>}
   */
  async _ensurePreparedEvidence(epoch, signal) {
    if (this._evidence !== null && this._evidenceEpoch === epoch) {
      return this._evidence;
    }
    if (!this._state.audio) {
      return {};
    }
    if (this._inFlightPrepare && this._inFlightPrepare.epoch === epoch) {
      const pending = this._inFlightPrepare.promise;
      if (!signal) return await pending;
      if (signal.aborted) {
        throw signal.reason ?? new EnsomiError("ABORTED", "Operation aborted");
      }
      return await new Promise((resolve, reject) => {
        const onAbort = () =>
          reject(signal.reason ?? new EnsomiError("ABORTED", "Operation aborted"));
        signal.addEventListener("abort", onAbort, { once: true });
        pending.then(
          (val) => {
            signal.removeEventListener("abort", onAbort);
            resolve(val);
          },
          (err) => {
            signal.removeEventListener("abort", onAbort);
            reject(err);
          },
        );
      });
    }

    const prepareAbort = new AbortController();
    const audioCopy = structuredClone(this._state.audio);

    const preparePromise = (async () => {
      try {
        const evidence = await this._provider.prepare(audioCopy, prepareAbort.signal);
        if (epoch !== this._audioEpoch || prepareAbort.signal.aborted) {
          throw new EnsomiError("SUPERSEDED", "Audio preparation superseded");
        }
        this._evidence = evidence;
        this._evidenceEpoch = epoch;
        return evidence;
      } finally {
        if (this._inFlightPrepare && this._inFlightPrepare.promise === preparePromise) {
          this._inFlightPrepare = null;
        }
      }
    })();

    this._inFlightPrepare = { epoch, promise: preparePromise, abort: prepareAbort };

    if (!signal) return await preparePromise;
    if (signal.aborted) {
      throw signal.reason ?? new EnsomiError("ABORTED", "Operation aborted");
    }
    return await new Promise((resolve, reject) => {
      const onAbort = () =>
        reject(signal.reason ?? new EnsomiError("ABORTED", "Operation aborted"));
      signal.addEventListener("abort", onAbort, { once: true });
      preparePromise.then(
        (val) => {
          signal.removeEventListener("abort", onAbort);
          resolve(val);
        },
        (err) => {
          signal.removeEventListener("abort", onAbort);
          reject(err);
        },
      );
    });
  }

  /**
   * Synchronously commit `audio_bound` and update `_audioEpoch` whenever ANY `AudioDescriptor`
   * property changes (`songId`, `durationSec`, `sampleRate`, `clock`, `decoder`, `leadOffsetSec`).
   *
   * @param {import("../index.d.ts").AudioDescriptorInput} rawAudio
   */
  _bindAudioSync(rawAudio) {
    this._assertOpen();
    const normalized = normalizeAudioDescriptor(rawAudio);
    const prevAudio = this._state.audio;
    const prevSongId = prevAudio?.songId ?? null;
    const songChanged = prevSongId !== normalized.songId;
    const audioChanged =
      !prevAudio || canonicalJson(prevAudio) !== canonicalJson(normalized);

    if (audioChanged) {
      this._audioEpoch += 1;
      if (songChanged && prevSongId !== null) {
        this.abortAll(
          `Switched song from "${prevSongId}" to "${normalized.songId}"`,
          "SUPERSEDED",
        );
      } else if (this._inFlightPrepare) {
        this._inFlightPrepare.abort.abort(
          new EnsomiError("SUPERSEDED", "Superseded bindAudio"),
        );
        this._inFlightPrepare = null;
      }
      this._evidence = null;
      this._evidenceEpoch = -1;
      for (const layer of TRIGGER_LAYERS) {
        this._slots[layer].songId = normalized.songId;
      }
    }

    const epoch = this._audioEpoch;
    this._commitEvent("audio_bound", normalized, { kind: "host" });
    const basisSeq = this._state.lastSeq;
    return { epoch, basisSeq, songId: normalized.songId };
  }

  /**
   * Bind an `AudioDescriptor` (initializing or updating track duration / song identity).
   * Switching `songId` aborts all three trigger layers cleanly; changing any descriptor field
   * invalidates cached `PreparedEvidence` before awaiting `_ensurePreparedEvidence`.
   *
   * @param {import("../index.d.ts").AudioDescriptorInput} rawAudio
   * @param {AbortSignal} [signal]
   */
  async bindAudio(rawAudio, signal) {
    const { epoch } = this._bindAudioSync(rawAudio);
    return await this._ensurePreparedEvidence(epoch, signal);
  }

  /**
   * Open a song and, when `triggerPolicy.song_open.mode === "auto"`, run `analyze()` automatically.
   * Captures `basisSeq` immediately after committing `audio_bound` (before awaiting `prepare()`)
   * so user edits made during `prepare()` are NEVER overwritten by `draft_loaded(replace: true)`.
   *
   * @param {import("../index.d.ts").AudioDescriptorInput} audio
   * @param {AbortSignal} [signal]
   */
  async openSong(audio, signal) {
    const { epoch, basisSeq } = this._bindAudioSync(audio);
    if (this._triggerPolicy.song_open.mode === "auto") {
      return await this.analyze(signal, { basisSeq });
    }
    await this._ensurePreparedEvidence(epoch, signal);
    return {
      status: /** @type {const} */ ("manual"),
      songId: this._currentSongId(),
      generation: this._slots.song_open.generation,
      event: null,
      state: this.getState(),
    };
  }

  /**
   * Layer 1 (`song_open`): Run `provider.analyze()` on the `"song_open"` slot.
   * Preempts any prior `"song_open"` request without touching `"micro_assist"` or `"heavy_recompute"`.
   *
   * @param {AbortSignal} [signal]
   * @param {{ basisSeq?: number }} [options]
   * @returns {Promise<import("../index.d.ts").AnalyzeOutcome>}
   */
  async analyze(signal, options) {
    this._assertOpen();
    if (!this._state.audio) {
      throw new EnsomiError("AUDIO_NOT_BOUND", "bindAudio() must be called before analyze()");
    }

    const slot = this._slots.song_open;
    if (slot.abort) {
      slot.abort.abort(new EnsomiError("SUPERSEDED", "Superseded by newer analyze()"));
      slot.abort = null;
    }

    const songId = this._state.audio.songId;
    slot.songId = songId;
    slot.generation += 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;
    const startSeq =
      typeof options?.basisSeq === "number" && Number.isInteger(options.basisSeq)
        ? options.basisSeq
        : this._state.lastSeq;

    const abortController = new AbortController();
    slot.abort = abortController;
    const unlink = linkAbortSignal(signal, abortController);
    const startMs = this._clock();

    const hasPostBasisInvalidation = () =>
      this._state.events.some(
        (ev) => ev.seq > startSeq && DRAFT_INVALIDATING_EVENTS.has(ev.type),
      );

    let draft;
    try {
      const openDebounceMs = this._triggerPolicy.song_open.debounceMs;
      if (openDebounceMs > 0) {
        const debounceOk = await waitDebounce(openDebounceMs, abortController.signal);
        if (
          !debounceOk ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentSongId() !== songId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            songId,
            generation: gen,
            event: null,
            state: this.getState(),
          };
        }
        if (hasPostBasisInvalidation()) {
          return {
            status: "superseded",
            songId,
            generation: gen,
            event: null,
            state: this.getState(),
          };
        }
      }

      const evidence =
        this._evidence !== null && this._evidenceEpoch === epoch
          ? this._evidence
          : await this._ensurePreparedEvidence(epoch, abortController.signal);

      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          event: null,
          state: this.getState(),
        };
      }

      if (hasPostBasisInvalidation()) {
        return {
          status: "superseded",
          songId,
          generation: gen,
          event: null,
          state: this.getState(),
        };
      }

      draft = await this._provider.analyze(evidence, abortController.signal);
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          event: null,
          state: this.getState(),
        };
      }
      throw err;
    } finally {
      unlink();
      if (slot.abort === abortController) {
        slot.abort = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentSongId() !== songId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        songId,
        generation: gen,
        event: null,
        state: this.getState(),
      };
    }

    if (hasPostBasisInvalidation()) {
      return {
        status: "superseded",
        songId,
        generation: gen,
        event: null,
        state: this.getState(),
      };
    }

    const waitSec = Math.max(0, (this._clock() - startMs) / 1000);
    const manifest = this._provider.manifest();
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    const ev = this._commitEvent(
      "draft_loaded",
      {
        segments: draft?.segments ?? [],
        spans: draft?.spans ?? [],
        beats: draft?.beats ?? [],
        sounds: draft?.sounds ?? [],
        unbeats: draft?.unbeats ?? [],
        replace: true,
      },
      { kind: authorKind, id: manifest.id },
    );
    if (waitSec > 0) {
      this._commitEvent("listen_effort", { waitSec }, { kind: "host" });
    }
    return {
      status: "applied",
      songId,
      generation: gen,
      event: structuredClone(ev),
      state: this.getState(),
    };
  }

  /**
   * Layer 2 (`micro_assist`): Ephemeral in-memory snap candidate query.
   * - Runs on the independent `"micro_assist"` slot (NEVER aborts `analyze` or `recompute`).
   * - Writes ZERO events to the `.jsonl` trace!
   *
   * @param {{ timeSec: number, windowSec?: number, immediate?: boolean }} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SuggestSnapsOutcome>}
   */
  async suggestSnaps(request, signal) {
    this._assertOpen();
    if (!request || typeof request !== "object") {
      throw new EnsomiError("INVALID_REQUEST", "suggestSnaps requires { timeSec, windowSec? }");
    }
    if (typeof request.timeSec !== "number" || !Number.isFinite(request.timeSec) || request.timeSec < 0) {
      throw new EnsomiError(
        "INVALID_REQUEST",
        `suggestSnaps timeSec must be a non-negative finite number, got ${request.timeSec}`,
      );
    }
    const windowSec = request.windowSec ?? 0.08;
    if (typeof windowSec !== "number" || !Number.isFinite(windowSec) || windowSec <= 0) {
      throw new EnsomiError(
        "INVALID_REQUEST",
        `suggestSnaps windowSec must be a positive finite number, got ${windowSec}`,
      );
    }

    const slot = this._slots.micro_assist;
    if (slot.abort) {
      slot.abort.abort(new EnsomiError("SUPERSEDED", "Superseded by newer suggestSnaps()"));
      slot.abort = null;
    }

    const songId = this._currentSongId();
    slot.songId = songId;
    slot.generation += 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;

    const abortController = new AbortController();
    slot.abort = abortController;
    const unlink = linkAbortSignal(signal, abortController);

    const debounceMs = request.immediate ? 0 : this._triggerPolicy.micro_assist.debounceMs;

    let rawCandidates;
    try {
      if (debounceMs > 0) {
        const debounceOk = await waitDebounce(debounceMs, abortController.signal);
        if (
          !debounceOk ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentSongId() !== songId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            songId,
            generation: gen,
            candidates: [],
          };
        }
      }

      const evidence =
        this._evidence !== null && this._evidenceEpoch === epoch
          ? this._evidence
          : this._state.audio
            ? await this._ensurePreparedEvidence(epoch, abortController.signal)
            : {};

      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          candidates: [],
        };
      }

      const snapshotState = cloneSessionStateFast(this._state);
      rawCandidates = await this._provider.suggestSnaps(
        evidence,
        snapshotState,
        {
          timeSec: request.timeSec,
          windowSec,
          generation: gen,
        },
        abortController.signal,
      );
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          candidates: [],
        };
      }
      throw err;
    } finally {
      unlink();
      if (slot.abort === abortController) {
        slot.abort = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentSongId() !== songId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        songId,
        generation: gen,
        candidates: [],
      };
    }

    const candidates = (Array.isArray(rawCandidates) ? rawCandidates : [])
      .map((c) => normalizeSnapCandidate(c, request.timeSec))
      .sort(
        (a, b) =>
          b.salience - a.salience ||
          Math.abs(a.deltaMs) - Math.abs(b.deltaMs) ||
          a.eventId.localeCompare(b.eventId),
      );

    return {
      status: "ok",
      songId,
      generation: gen,
      candidates,
    };
  }

  /**
   * Directly commit an imported/synthetic draft without invoking `provider.analyze()`.
   * Preempts any in-flight `"song_open"` (`analyze`) without disturbing other layers.
   *
   * @param {import("../index.d.ts").AnalysisDraft & { replace?: boolean, durationSec?: number }} draft
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  loadDraft(draft, author = { kind: "import" }) {
    this._assertOpen();
    this.abortLayer("song_open", "Superseded by loadDraft()");
    const event = this._commitEvent("draft_loaded", draft, author);
    return { event: structuredClone(event), state: this.getState() };
  }

  /**
   * Apply one or more direct `EditOp`s immediately. Never auto-triggers `heavy_recompute`.
   * @param {import("../index.d.ts").EditOp | import("../index.d.ts").EditOp[]} opOrOps
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  applyEdit(opOrOps, author = { kind: "user" }) {
    this._assertOpen();
    const payload = Array.isArray(opOrOps) ? { ops: opOrOps } : opOrOps;
    const event = this._commitEvent("edit", payload, author);
    return { event: structuredClone(event), state: this.getState() };
  }

  /**
   * Retract an earlier trace event by event ID or seq (backward-referencing only).
   * Retracting a prior `"retract"` event performs a clean redo.
   *
   * @param {string | number} targetEventIdOrSeq
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  retract(targetEventIdOrSeq, reason, author = { kind: "user" }) {
    this._assertOpen();
    const prevAudioJson = canonicalJson(this._state.audio);
    const prevSongId = this._currentSongId();
    const payload =
      typeof targetEventIdOrSeq === "number"
        ? { targetSeq: targetEventIdOrSeq, ...(reason ? { reason } : {}) }
        : { targetEventId: targetEventIdOrSeq, ...(reason ? { reason } : {}) };
    const event = this._commitEvent("retract", payload, author);
    if (canonicalJson(this._state.audio) !== prevAudioJson) {
      this._audioEpoch += 1;
      if (this._currentSongId() !== prevSongId) {
        this.abortAll("Audio songId changed due to retract()");
        for (const layer of TRIGGER_LAYERS) {
          this._slots[layer].songId = this._currentSongId();
        }
      } else if (this._inFlightPrepare) {
        this._inFlightPrepare.abort.abort(
          new EnsomiError("SUPERSEDED", "Audio descriptor changed due to retract()"),
        );
        this._inFlightPrepare = null;
      }
      this._evidence = null;
      this._evidenceEpoch = -1;
    }
    return { event: structuredClone(event), state: this.getState() };
  }

  /** @returns {boolean} */
  canUndo() {
    return computeUndoRedoTargets(this._state).canUndo;
  }

  /** @returns {boolean} */
  canRedo() {
    return computeUndoRedoTargets(this._state).canRedo;
  }

  /**
   * Undo the latest active user action reconstructed deterministically from `state.events`.
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").UndoRedoOutcome}
   */
  undo(reason = "undo", author = { kind: "user" }) {
    this._assertOpen();
    const { undoTargetEventId } = computeUndoRedoTargets(this._state);
    if (!undoTargetEventId) {
      return {
        ok: false,
        targetEventId: null,
        event: null,
        state: this.getState(),
      };
    }
    const res = this.retract(undoTargetEventId, reason, author);
    return {
      ok: true,
      targetEventId: undoTargetEventId,
      event: res.event,
      state: res.state,
    };
  }

  /**
   * Redo the latest undone user action reconstructed deterministically from `state.events`.
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").UndoRedoOutcome}
   */
  redo(reason = "redo", author = { kind: "user" }) {
    this._assertOpen();
    const { redoTargetEventId } = computeUndoRedoTargets(this._state);
    if (!redoTargetEventId) {
      return {
        ok: false,
        targetEventId: null,
        event: null,
        state: this.getState(),
      };
    }
    const res = this.retract(redoTargetEventId, reason, author);
    return {
      ok: true,
      targetEventId: redoTargetEventId,
      event: res.event,
      state: res.state,
    };
  }

  /**
   * Record user listening time / visited range / wait time in the effort ledger.
   * @param {{ listenSec?: number, waitSec?: number, range?: import("../index.d.ts").Range }} effort
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  recordListenEffort(effort, author = { kind: "host" }) {
    this._assertOpen();
    const event = this._commitEvent("listen_effort", effort, author);
    return { event: structuredClone(event), state: this.getState() };
  }

  /**
   * Mark the session ready.
   * @param {string} [notes]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  markReady(notes, author = { kind: "user" }) {
    this._assertOpen();
    const event = this._commitEvent("ready", { ready: true, ...(notes ? { notes } : {}) }, author);
    return { event: structuredClone(event), state: this.getState() };
  }

  /**
   * Layer 3 (`heavy_recompute`): Explicitly trigger constrained provider recompute on the
   * `"heavy_recompute"` slot. Preempts any prior `"heavy_recompute"` without touching
   * `"song_open"` or `"micro_assist"`.
   *
   * @param {{ scope: import("../index.d.ts").Range | "song", reason: string }} request
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {Promise<import("../index.d.ts").RecomputeOutcome>}
   */
  async recompute(request, author = { kind: "user" }) {
    this._assertOpen();
    if (!request || typeof request !== "object") {
      throw new EnsomiError("INVALID_SIGNAL", "recompute requires a request object");
    }
    const scope = request.scope === "song" ? "song" : assertRange(request.scope);

    const slot = this._slots.heavy_recompute;
    if (slot.abort) {
      slot.abort.abort(new EnsomiError("SUPERSEDED", "Superseded by newer recompute()"));
      slot.abort = null;
    }

    const songId = this._currentSongId();
    slot.songId = songId;
    slot.generation = Math.max(slot.generation, this._state.generation) + 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;

    const abortController = new AbortController();
    slot.abort = abortController;

    this._commitEvent(
      "signal",
      {
        layer: "heavy_recompute",
        scope,
        reason: request.reason,
        generation: gen,
      },
      author,
    );

    const basisSeq = this._state.lastSeq;
    const constraints = compileConstraints(this._state);
    const snapshotState = cloneSessionStateFast(this._state);
    const startMs = this._clock();

    let result;
    try {
      const heavyDebounceMs = this._triggerPolicy.heavy_recompute.debounceMs;
      if (heavyDebounceMs > 0) {
        const debounceOk = await waitDebounce(heavyDebounceMs, abortController.signal);
        if (
          !debounceOk ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentSongId() !== songId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            songId,
            generation: gen,
            offers: [],
            suppressedOffers: [],
          };
        }
      }

      const evidence =
        this._evidence !== null && this._evidenceEpoch === epoch
          ? this._evidence
          : this._state.audio
            ? await this._ensurePreparedEvidence(epoch, abortController.signal)
            : {};

      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          offers: [],
          suppressedOffers: [],
        };
      }

      result = await this._provider.recompute(
        evidence,
        snapshotState,
        {
          scope,
          reason: request.reason,
          generation: gen,
          basisSeq,
          constraints,
        },
        abortController.signal,
      );
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentSongId() !== songId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          songId,
          generation: gen,
          offers: [],
          suppressedOffers: [],
        };
      }
      throw err;
    } finally {
      if (slot.abort === abortController) {
        slot.abort = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentSongId() !== songId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        songId,
        generation: gen,
        offers: [],
        suppressedOffers: [],
      };
    }

    const waitSec = Math.max(0, (this._clock() - startMs) / 1000);
    const manifest = this._provider.manifest();
    const defaultBatchId = result?.batchId ?? `batch_g${gen}_s${basisSeq}_${this._nextSeq}`;
    const rawOffers = Array.isArray(result?.offers) ? result.offers : [];

    /** @type {Map<string, number>} */
    const batchCounts = new Map();
    for (const raw of rawOffers) {
      const bId = raw.batchId ?? defaultBatchId;
      batchCounts.set(bId, (batchCounts.get(bId) ?? 0) + 1);
    }

    const normalizedOffers = rawOffers.map((raw, idx) => {
      const bId = raw.batchId ?? defaultBatchId;
      const siblingCount = batchCounts.get(bId) ?? 1;
      const ops = raw.ops ? structuredClone(raw.ops) : {};

      // Normalize writeScopes against snapshotState segments if needed
      let writeScopes = Array.isArray(raw.writeScopes) ? raw.writeScopes : [];
      if (writeScopes.length === 0 && raw.writeRange) {
        writeScopes = decomposeRangeToSpans(snapshotState.segments, raw.writeRange);
      }
      writeScopes = writeScopes.map((ws) => ({
        segmentId: ws.segmentId,
        ...assertRange(ws),
      }));

      // Bind immutable expectedRevisions from snapshotState for modified/removed entities
      const expectedRevisions =
        raw.expectedRevisions && typeof raw.expectedRevisions === "object"
          ? { ...raw.expectedRevisions }
          : {};
      const bindSnapshotRev = (entityId) => {
        if (Object.prototype.hasOwnProperty.call(expectedRevisions, entityId)) return;
        const rev = snapshotState.entityRevisions.get(entityId);
        if (rev) expectedRevisions[entityId] = rev;
      };
      for (const b of ops.upsertBeats ?? []) {
        if (snapshotState.beats.has(b.id)) bindSnapshotRev(b.id);
      }
      for (const id of ops.removeBeatIds ?? []) bindSnapshotRev(id);
      for (const s of ops.upsertSounds ?? []) {
        if (snapshotState.sounds.has(s.id)) bindSnapshotRev(s.id);
      }
      for (const id of ops.removeSoundIds ?? []) bindSnapshotRev(id);
      for (const sp of ops.splitSegments ?? []) bindSnapshotRev(sp.segmentId);
      for (const mv of ops.moveSegmentBoundaries ?? []) {
        bindSnapshotRev(mv.leftSegmentId);
        bindSnapshotRev(mv.rightSegmentId);
      }
      for (const rg of ops.setSegmentRegimes ?? []) bindSnapshotRev(rg.segmentId);

      const expectedScopeBeatIds =
        raw.expectedScopeBeatIds ?? getScopeBeatIds(snapshotState, writeScopes);

      return {
        ...raw,
        id: raw.id ?? `offer_g${gen}_s${basisSeq}_${this._nextSeq}_${idx}`,
        batchId: bId,
        ...(siblingCount > 1 && raw.optionCount === undefined
          ? { optionCount: siblingCount }
          : {}),
        ...(siblingCount > 1 && raw.optionIndex === undefined ? { optionIndex: idx } : {}),
        providerId: raw.providerId ?? manifest.id,
        providerKind: raw.providerKind ?? manifest.kind,
        generation: gen,
        basisSeq: raw.basisSeq ?? basisSeq,
        expectedRevisions,
        expectedScopeBeatIds,
        writeScopes,
        ...(raw.readScope !== undefined
          ? { readScope: raw.readScope === "song" ? "song" : assertRange(raw.readScope) }
          : {}),
        ops,
        title: raw.title ?? "Refine timing grid",
        consequenceSummary:
          typeof raw.consequenceSummary === "string" && raw.consequenceSummary.trim().length > 0
            ? raw.consequenceSummary
            : formatConsequenceSummary(snapshotState, ops),
        advisoryNotes: Array.isArray(raw.advisoryNotes) ? raw.advisoryNotes.slice() : [],
        providerAdvisories: Array.isArray(raw.advisoryNotes) ? raw.advisoryNotes.slice() : [],
        consequences: raw.consequences ?? computeConsequences(snapshotState, ops),
        conflicts: [],
        semanticKey:
          raw.semanticKey || computeSemanticKey(writeScopes, ops, expectedRevisions),
        status: /** @type {const} */ ("valid"),
      };
    });

    if (this._holdOffers) {
      if (waitSec > 0) {
        this._commitEvent("listen_effort", { waitSec }, { kind: "host" });
      }
      const liveConstraints = compileConstraints(this._state);
      /** @type {import("../index.d.ts").PatchOffer[]} */
      const suppressedWhileHeld = [];
      /** @type {import("../index.d.ts").PatchOffer[]} */
      const previewOffers = [];
      for (const offer of normalizedOffers) {
        if (isOfferSuppressed(this._state, offer)) {
          suppressedWhileHeld.push(offer);
          continue;
        }
        const check = validatePatchOffer(this._state, offer, liveConstraints);
        previewOffers.push({
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          advisoryNotes: check.advisoryNotes,
        });
      }
      this._heldOfferBatch = {
        songId,
        generation: gen,
        batchId: defaultBatchId,
        offers: previewOffers,
      };
      return {
        status: "held",
        songId,
        generation: gen,
        offers: structuredClone(previewOffers),
        suppressedOffers: structuredClone(suppressedWhileHeld),
      };
    }

    const beforeSuppressedCount = this._state.suppressedOffers.length;
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    this._commitEvent(
      "offer_batch",
      {
        batchId: defaultBatchId,
        offers: normalizedOffers,
        waitSec,
      },
      { kind: authorKind, id: manifest.id },
    );

    const newlySuppressed = this._state.suppressedOffers.slice(beforeSuppressedCount);
    const newlySuppressedIds = new Set(newlySuppressed.map((o) => o.id));
    const surfacedOffers = normalizedOffers
      .filter((o) => !newlySuppressedIds.has(o.id))
      .map((o) => this._state.offers.get(o.id))
      .filter(Boolean);

    return {
      status: "applied",
      songId,
      generation: gen,
      offers: /** @type {import("../index.d.ts").PatchOffer[]} */ (structuredClone(surfacedOffers)),
      suppressedOffers: structuredClone(newlySuppressed),
    };
  }

  /**
   * Conflict-first offer acceptance.
   * Requires `offer.status === "valid"` and passes `validatePatchOffer(this._state, offer)`.
   *
   * @param {string} offerId
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").AcceptOfferOutcome}
   */
  acceptOffer(offerId, author = { kind: "user" }) {
    this._assertOpen();
    const offer = this._state.offers.get(offerId);
    if (!offer) {
      return {
        ok: false,
        conflicts: [
          {
            kind: "range_changed",
            message: "This suggestion is no longer available.",
          },
        ],
      };
    }

    const check = validatePatchOffer(this._state, offer);
    if (offer.status !== "valid" || !check.valid) {
      return {
        ok: false,
        conflicts:
          check.conflicts.length > 0
            ? check.conflicts
            : [
                {
                  kind: "version_mismatch",
                  message: "This suggestion is no longer active.",
                },
              ],
      };
    }

    const siblingsBefore = Array.from(this._state.offers.values())
      .filter(
        (o) =>
          o.id !== offerId &&
          o.batchId === offer.batchId &&
          o.status !== "accepted" &&
          o.status !== "rejected" &&
          o.status !== "superseded",
      )
      .map((o) => o.id);

    this._commitEvent("offer_accept", { offerId }, author);

    const acceptedOffer = this._state.offers.get(offerId);
    return {
      ok: true,
      conflicts: [],
      offer: acceptedOffer ? structuredClone(acceptedOffer) : undefined,
      supersededOfferIds: siblingsBefore,
    };
  }

  /**
   * Dismiss/reject an active offer, recording semantic suppression over its `writeScopes`.
   * @param {string} offerId
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").RejectOfferOutcome}
   */
  rejectOffer(offerId, reason, author = { kind: "user" }) {
    this._assertOpen();
    const offer = this._state.offers.get(offerId);
    if (!offer) {
      throw new EnsomiError("MISSING_OFFER", "Suggestion does not exist in session state");
    }
    if (offer.status === "accepted" || offer.status === "rejected" || offer.status === "superseded") {
      throw new EnsomiError(
        "OFFER_NOT_REJECTABLE",
        `Suggestion with status "${offer.status}" cannot be dismissed`,
        { status: offer.status },
      );
    }
    this._commitEvent("offer_reject", { offerId, ...(reason ? { reason } : {}) }, author);
    const rejectedOffer = this._state.offers.get(offerId);
    return {
      ok: true,
      offer: rejectedOffer ? structuredClone(rejectedOffer) : undefined,
    };
  }
}
