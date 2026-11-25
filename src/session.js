/**
 * Headless `SessionController` with three independent `(audioId, layer, generation)` lifecycles:
 *  1. `song_open`       : `bindAudio`, `openSong`, `analyze`, `loadDraft`, `resetDocument`
 *  2. `micro_assist`    : `suggestSnaps`, `suggestSoundOptions` (100% trace-free!)
 *  3. `heavy_recompute` : `recompute`, `matchSounds`, `suggestSegmentation`
 *
 * Host-bound safety invariants:
 *  - Every provider call receives a plain JSON-safe `ProviderView` (`{ snapshot, constraints }`),
 *    `requestId`, `generation`, `audioId`, and opaque `EvidenceRef` — NEVER live `SessionState`.
 *  - `dependencies` & `basisDigest` on returned `Offer`s are bound on the host from `capturedBasis`.
 *  - Preemption / cancellation triggers both optional JS `AbortSignal` and JSON-safe `provider.cancel?.(requestId)`.
 *  - `analyze()` NEVER overwrites active human `verdict`s or `approval`s (`keep`).
 */

import { assertPlainObject, canonicalJson, compareCodeUnits, EnsomiError } from "./jcs.js";
import {
  bindOfferToBasis,
  isOfferSuppressed,
  previewOffer as previewOfferPure,
  validateOffer,
} from "./offers.js";
import {
  cloneSessionStateShallow,
  createInitialState,
  createProviderView,
  hasActiveHumanTruth,
  toSnapshot,
} from "./state.js";
import { assertNonNegSafeIntUs, assertPointUs, assertRangeUs } from "./time.js";
import {
  applyEvent,
  computeUndoRedoTargets,
  evaluateOfferAcceptBatch,
  foldTrace,
  serializeTraceNdjson,
} from "./trace.js";
import {
  assertNonEmptyId,
  DEFAULT_TRIGGER_POLICY,
  deepFreeze,
  normalizeAudioBinding,
  normalizeSnapCandidate,
  normalizeSoundOptionCandidate,
  SOUND_MATCH_MODES,
  TRACE_SCHEMA_VERSION,
  TRIGGER_LAYERS,
  validateAuthorizedChallenges,
} from "./types.js";

const TRIGGER_LAYER_SET = new Set(TRIGGER_LAYERS);
const SOUND_MATCH_MODE_SET = new Set(SOUND_MATCH_MODES);
const DRAFT_INVALIDATING_EVENTS = new Set([
  "reset_document",
  "draft_loaded",
  "edit",
  "offer_accept",
  "retract",
]);

function linkAbortSignal(callerSignal, internalController, onCancel) {
  if (!callerSignal) return () => {};
  if (callerSignal.aborted) {
    internalController.abort(
      callerSignal.reason ?? new EnsomiError("ABORTED", "Operation aborted"),
    );
    onCancel?.();
    return () => {};
  }
  const onAbort = () => {
    internalController.abort(
      callerSignal.reason ?? new EnsomiError("ABORTED", "Operation aborted"),
    );
    onCancel?.();
  };
  callerSignal.addEventListener("abort", onAbort, { once: true });
  return () => callerSignal.removeEventListener("abort", onAbort);
}

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

function resolveTriggerPolicy(custom) {
  /** @type {any} */
  const resolved = {};
  for (const layer of TRIGGER_LAYERS) {
    const base = DEFAULT_TRIGGER_POLICY[layer];
    const over = custom?.[layer];
    const mode = over?.mode ?? base.mode;
    if (mode !== "auto" && mode !== "manual") {
      throw new EnsomiError("INVALID_POLICY", `Invalid mode "${String(mode)}" for layer "${layer}"`);
    }
    const debounceMs = over?.debounceMs ?? base.debounceMs;
    if (typeof debounceMs !== "number" || !Number.isFinite(debounceMs) || debounceMs < 0) {
      throw new EnsomiError(
        "INVALID_POLICY",
        `Invalid debounceMs "${String(debounceMs)}" for layer "${layer}"`,
      );
    }
    const cancellation = over?.cancellation ?? base.cancellation;
    if (cancellation !== "latest_wins") {
      throw new EnsomiError(
        "INVALID_POLICY",
        `Invalid cancellation "${String(cancellation)}" for layer "${layer}"`,
      );
    }
    resolved[layer] = Object.freeze({ mode, debounceMs, cancellation });
  }
  return Object.freeze(resolved);
}

function normalizeEvidenceRef(raw, audioId) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new EnsomiError("INVALID_EVIDENCE_REF", "Provider.prepare() must return a plain JSON EvidenceRef object");
  }
  const r = /** @type {Record<string, unknown>} */ (raw);
  const evidenceId =
    typeof r.evidenceId === "string" && r.evidenceId.length > 0
      ? r.evidenceId
      : `ev_${audioId}`;
  // Verify strict RFC 8785 JSON-safety of EvidenceRef!
  const candidate = {
    ...r,
    evidenceId,
    audioId: typeof r.audioId === "string" && r.audioId.length > 0 ? r.audioId : audioId,
  };
  canonicalJson(candidate);
  return deepFreeze(structuredClone(candidate));
}

/**
 * Validate a `Provider` implements required contract methods (`manifest`, `prepare`,
 * `analyze`, `suggestSnaps`, `recompute`) and any optional methods are functions.
 *
 * @param {import("../index.d.ts").Provider} provider
 */
export function assertProvider(provider) {
  if (!provider || typeof provider !== "object") {
    throw new EnsomiError("INVALID_PROVIDER", "Provider must be an object");
  }
  for (const method of ["manifest", "prepare", "analyze", "suggestSnaps", "recompute"]) {
    if (typeof /** @type {any} */ (provider)[method] !== "function") {
      throw new EnsomiError(
        "INVALID_PROVIDER",
        `Provider is missing required method "${method}()"`,
      );
    }
  }
  for (const opt of ["suggestSoundOptions", "matchSounds", "suggestSegmentation", "cancel"]) {
    const fn = /** @type {any} */ (provider)[opt];
    if (fn !== undefined && typeof fn !== "function") {
      throw new EnsomiError(
        "INVALID_PROVIDER",
        `Provider optional property "${opt}" must be a function when provided`,
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
    assertProvider(options.provider);

    this._provider = options.provider;
    this._clock = options.clock ?? (() => Date.now());
    this._idGenerator = options.idGenerator ?? ((prefix, seq) => `${prefix}_${seq}`);
    this._triggerPolicy = resolveTriggerPolicy(options.triggerPolicy);
    this._closed = false;

    this._state =
      Array.isArray(options.initialEvents) && options.initialEvents.length > 0
        ? foldTrace(options.initialEvents, { durationUs: options.durationUs })
        : createInitialState({ durationUs: options.durationUs });

    this._nextSeq = this._state.lastSeq + 1;
    this._audioEpoch = 0;
    /** @type {import("../index.d.ts").EvidenceRef | null} */
    this._evidenceRef = null;
    this._evidenceEpoch = -1;
    /** @type {{ epoch: number, requestId: string, promise: Promise<import("../index.d.ts").EvidenceRef>, abort: AbortController } | null} */
    this._inFlightPrepare = null;

    const initialAudioId = this._state.audio?.audioId ?? null;
    /**
     * Three independent `(audioId, layer, generation, requestId, AbortController)` slots.
     * @type {Record<import("../index.d.ts").TriggerLayer, { audioId: string | null, layer: import("../index.d.ts").TriggerLayer, generation: number, requestId: string | null, abort: AbortController | null }>}
     */
    this._slots = {
      song_open: {
        audioId: initialAudioId,
        layer: "song_open",
        generation: 0,
        requestId: null,
        abort: null,
      },
      micro_assist: {
        audioId: initialAudioId,
        layer: "micro_assist",
        generation: 0,
        requestId: null,
        abort: null,
      },
      heavy_recompute: {
        audioId: initialAudioId,
        layer: "heavy_recompute",
        generation: this._state.generation,
        requestId: null,
        abort: null,
      },
    };

    this._holdOffers = false;
    /** @type {{ audioId: string | null, audioEpoch: number, generation: number, batchId: string, offers: import("../index.d.ts").Offer[] } | null} */
    this._heldOfferBatch = null;
  }

  _assertOpen() {
    if (this._closed) {
      throw new EnsomiError("SESSION_CLOSED", "SessionController is closed");
    }
  }

  _currentAudioId() {
    return this._state.audio?.audioId ?? null;
  }

  _notifyProviderCancel(requestId) {
    if (!requestId || typeof this._provider.cancel !== "function") return;
    try {
      const res = this._provider.cancel(requestId);
      if (res && typeof /** @type {any} */ (res).catch === "function") {
        /** @type {Promise<void>} */ (res).catch(() => {});
      }
    } catch {
      // Ignore provider cancel cleanup errors
    }
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
    return cloneSessionStateShallow(this._state);
  }

  /** @returns {import("../index.d.ts").Snapshot} */
  getSnapshot() {
    return toSnapshot(this._state);
  }

  /** @returns {readonly import("../index.d.ts").TraceEvent[]} */
  getTrace() {
    return this._state.events.slice();
  }

  /**
   * Read the lifecycle state of one trigger layer (`"song_open" | "micro_assist" | "heavy_recompute"`).
   * @param {import("../index.d.ts").TriggerLayer} [layer="heavy_recompute"]
   * @returns {import("../index.d.ts").LayerLifecycleState}
   */
  getLayerState(layer = "heavy_recompute") {
    if (!TRIGGER_LAYER_SET.has(layer)) {
      throw new EnsomiError("INVALID_LAYER", `Unknown trigger layer: ${String(layer)}`);
    }
    const slot = this._slots[layer];
    return {
      audioId: slot.audioId,
      layer: slot.layer,
      generation: slot.generation,
      requestId: slot.requestId,
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

  /** @returns {import("../index.d.ts").Offer[]} */
  getValidOffers() {
    return Array.from(this._state.offers.values()).filter(
      (o) => o.status === "valid" || o.status === "requires_challenge_confirm",
    );
  }

  /** @returns {import("../index.d.ts").Offer[]} */
  getHeldOffers() {
    if (!this._heldOfferBatch) return [];
    const slot = this._slots.heavy_recompute;
    if (
      this._heldOfferBatch.audioId !== this._currentAudioId() ||
      this._heldOfferBatch.audioEpoch !== this._audioEpoch ||
      this._heldOfferBatch.generation !== slot.generation
    ) {
      return [];
    }
    return this._heldOfferBatch.offers
      .filter((offer) => !isOfferSuppressed(this._state, offer))
      .map((offer) => {
        const check = validateOffer(this._state, offer, { ignoreTerminalStatus: true });
        return deepFreeze({
          ...offer,
          status: check.status,
          conflicts: check.conflicts,
          notes: check.notes,
        });
      });
  }

  /** @returns {boolean} */
  isHoldingOffers() {
    return this._holdOffers;
  }

  /**
   * Hold completed `heavy_recompute` offer batches in memory while `Alt`-auditioning.
   * Releasing the hold (`setHoldOffers(false)`) commits the held batch against current state.
   *
   * @param {boolean} hold
   * @returns {{ flushed: boolean, offers: import("../index.d.ts").Offer[] }}
   */
  setHoldOffers(hold) {
    const next = Boolean(hold);
    this._holdOffers = next;
    if (!next && this._heldOfferBatch) {
      const held = this._heldOfferBatch;
      this._heldOfferBatch = null;
      const slot = this._slots.heavy_recompute;
      if (
        held.generation === slot.generation &&
        held.audioId === this._currentAudioId() &&
        held.audioEpoch === this._audioEpoch
      ) {
        const beforeSuppressedCount = this._state.suppressedOffers.length;
        const manifest = this._provider.manifest();
        const authorKind = manifest.kind === "rule" ? "rule" : "model";
        this._commitEvent(
          "offer_batch",
          {
            batchId: held.batchId,
            offers: held.offers,
            waitUs: 0,
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
          offers: /** @type {import("../index.d.ts").Offer[]} */ (surfaced),
        };
      }
    }
    return { flushed: false, offers: [] };
  }

  /** @returns {string} */
  exportNdjson() {
    return serializeTraceNdjson(this._state.events);
  }

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
    return this._state.events[this._state.events.length - 1];
  }

  _preemptSlot(layer, errorCode, reason) {
    const slot = this._slots[layer];
    if (slot.abort) {
      const prevReqId = slot.requestId;
      slot.abort.abort(new EnsomiError(errorCode, reason));
      slot.abort = null;
      slot.requestId = null;
      this._notifyProviderCancel(prevReqId);
    }
  }

  /**
   * Abort a single trigger layer slot without disturbing the other two layers.
   *
   * @param {import("../index.d.ts").TriggerLayer} layer
   * @param {string} [reason]
   */
  abortLayer(layer, reason = `Layer "${layer}" aborted`) {
    if (!TRIGGER_LAYER_SET.has(layer)) {
      throw new EnsomiError("INVALID_LAYER", `Unknown trigger layer: ${String(layer)}`);
    }
    const slot = this._slots[layer];
    slot.generation += 1;
    this._preemptSlot(layer, "ABORTED", reason);
    if (layer === "heavy_recompute") {
      this._heldOfferBatch = null;
    }
  }

  /**
   * Abort all three trigger layers and any in-flight `prepare()` cleanly.
   *
   * @param {string} [reason]
   * @param {"ABORTED" | "SUPERSEDED"} [errorCode="ABORTED"]
   */
  abortAll(reason = "All in-flight provider operations aborted", errorCode = "ABORTED") {
    if (this._inFlightPrepare) {
      const reqId = this._inFlightPrepare.requestId;
      this._inFlightPrepare.abort.abort(new EnsomiError(errorCode, reason));
      this._inFlightPrepare = null;
      this._notifyProviderCancel(reqId);
    }
    for (const layer of TRIGGER_LAYERS) {
      this._slots[layer].generation += 1;
      this._preemptSlot(layer, errorCode, reason);
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
   * Permanently close the session controller, aborting all layers cleanly.
   */
  close() {
    this.abortAll("SessionController closed", "ABORTED");
    this._closed = true;
  }

  /**
   * Ensure JSON-safe `EvidenceRef` is prepared for `epoch`.
   *
   * @param {number} epoch
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").EvidenceRef>}
   */
  async _ensureEvidenceRef(epoch, signal) {
    if (this._evidenceRef !== null && this._evidenceEpoch === epoch) {
      return this._evidenceRef;
    }
    if (!this._state.audio) {
      throw new EnsomiError("AUDIO_NOT_BOUND", "Audio is not bound");
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
    const requestId = `req_prepare_e${epoch}_${audioCopy.audioId}`;

    const preparePromise = (async () => {
      try {
        const rawRef = await this._provider.prepare(audioCopy, prepareAbort.signal);
        if (epoch !== this._audioEpoch || prepareAbort.signal.aborted) {
          throw new EnsomiError("SUPERSEDED", "Audio preparation superseded");
        }
        const evidenceRef = normalizeEvidenceRef(rawRef, audioCopy.audioId);
        this._evidenceRef = evidenceRef;
        this._evidenceEpoch = epoch;
        return evidenceRef;
      } finally {
        if (this._inFlightPrepare && this._inFlightPrepare.promise === preparePromise) {
          this._inFlightPrepare = null;
        }
      }
    })();

    this._inFlightPrepare = { epoch, requestId, promise: preparePromise, abort: prepareAbort };

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

  _advanceAudioEpoch(reason, audioChanged) {
    this._audioEpoch += 1;
    this.abortAll(reason, "SUPERSEDED");
    const nextAudioId = this._currentAudioId();
    for (const layer of TRIGGER_LAYERS) {
      this._slots[layer].audioId = nextAudioId;
    }
    if (audioChanged || !this._state.audio) {
      this._evidenceRef = null;
      this._evidenceEpoch = -1;
    } else if (this._evidenceRef !== null) {
      this._evidenceEpoch = this._audioEpoch;
    }
  }

  _bindAudioSync(rawAudio) {
    this._assertOpen();
    const normalized = normalizeAudioBinding(rawAudio);
    const prevAudio = this._state.audio;
    const prevAudioId = prevAudio?.audioId ?? null;
    const audioIdChanged = prevAudioId !== normalized.audioId;
    const audioChanged = !prevAudio || canonicalJson(prevAudio) !== canonicalJson(normalized);

    if (
      prevAudio !== null &&
      (audioIdChanged || normalized.durationUs < prevAudio.durationUs) &&
      hasActiveHumanTruth(this._state)
    ) {
      throw new EnsomiError(
        "PROTECTED_HUMAN_TRUTH",
        "Cannot rebind different audio over active human verdicts or approvals; call resetDocument() first",
      );
    }

    // Commit and validate `audio_bound` BEFORE mutating `_audioEpoch`, `_evidenceRef`, or `_slots`!
    this._commitEvent("audio_bound", normalized, { kind: "host" });

    if (audioChanged) {
      this._advanceAudioEpoch(
        audioIdChanged && prevAudioId !== null
          ? `Switched audio from "${prevAudioId}" to "${normalized.audioId}"`
          : `Updated audio binding for "${normalized.audioId}"`,
        true,
      );
    }

    const epoch = this._audioEpoch;
    const basisSeq = this._state.lastSeq;
    return { epoch, basisSeq, audioId: normalized.audioId };
  }

  /**
   * Bind an `AudioBinding` (`{ audioId, durationUs, sampleRateHz?, sampleCount?, audioSha256?, decodedPcmSha256? }`)
   * and prepare provider `EvidenceRef`.
   *
   * @param {import("../index.d.ts").AudioBinding} audio
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").EvidenceRef>}
   */
  async bindAudio(audio, signal) {
    const { epoch } = this._bindAudioSync(audio);
    return await this._ensureEvidenceRef(epoch, signal);
  }

  /**
   * Open a song and, when `triggerPolicy.song_open.mode === "auto"`, run `analyze()`.
   * Captures `basisSeq` immediately after `audio_bound` (before `prepare()`) so user edits
   * made while `prepare()` runs are NEVER overwritten!
   *
   * @param {import("../index.d.ts").AudioBinding} audio
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").AnalyzeOutcome>}
   */
  async openSong(audio, signal) {
    const { epoch, basisSeq } = this._bindAudioSync(audio);
    if (this._triggerPolicy.song_open.mode === "auto") {
      return await this.analyze(signal, { basisSeq });
    }
    await this._ensureEvidenceRef(epoch, signal);
    return {
      status: "manual",
      audioId: this._currentAudioId(),
      generation: this._slots.song_open.generation,
      event: null,
      state: this.getState(),
    };
  }

  /**
   * Explicitly reset the document (`reset_document`), retiring active marks/approvals cleanly.
   * Undoable via `undo()` / `retract()`!
   *
   * @param {{ reason?: string, audio?: import("../index.d.ts").AudioBinding }} [options]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  resetDocument(options = {}, author = { kind: "user" }) {
    this._assertOpen();
    assertPlainObject(options, "resetDocument.options", "INVALID_EVENT");
    const prevAudioJson = canonicalJson(this._state.audio);
    // Validate & commit `reset_document` BEFORE mutating `_slots`, `_audioEpoch`, or `_evidenceRef`!
    const event = this._commitEvent("reset_document", options, author);
    const audioChanged = canonicalJson(this._state.audio) !== prevAudioJson;
    this._advanceAudioEpoch("Reset document", audioChanged);
    return { event, state: this.getState() };
  }

  /**
   * Layer 1 (`song_open`): Run `provider.analyze(...)` on the `"song_open"` slot.
   * Refuses to overwrite active human `verdict`s or `approval`s (`status: "superseded"`).
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
    this._preemptSlot("song_open", "SUPERSEDED", "Superseded by newer analyze()");

    const audio = this._state.audio;
    const audioId = audio.audioId;
    slot.audioId = audioId;
    slot.generation += 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;
    const startSeq =
      typeof options?.basisSeq === "number" && Number.isSafeInteger(options.basisSeq)
        ? options.basisSeq
        : this._state.lastSeq;
    const requestId = `req_song_open_g${gen}_s${startSeq}`;

    if (hasActiveHumanTruth(this._state)) {
      return {
        status: "superseded",
        audioId,
        generation: gen,
        event: null,
        state: this.getState(),
      };
    }

    const abortController = new AbortController();
    slot.abort = abortController;
    slot.requestId = requestId;
    const unlink = linkAbortSignal(signal, abortController, () =>
      this._notifyProviderCancel(requestId),
    );
    const startMs = this._clock();
    const capturedView = createProviderView(this._state);

    const hasPostBasisInvalidation = () =>
      hasActiveHumanTruth(this._state) ||
      this._state.events.some(
        (ev) => ev.seq > startSeq && DRAFT_INVALIDATING_EVENTS.has(ev.type),
      );

    let draft;
    try {
      const debounceMs = this._triggerPolicy.song_open.debounceMs;
      if (debounceMs > 0) {
        const ok = await waitDebounce(debounceMs, abortController.signal);
        if (
          !ok ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentAudioId() !== audioId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            audioId,
            generation: gen,
            event: null,
            state: this.getState(),
          };
        }
        if (hasPostBasisInvalidation()) {
          return {
            status: "superseded",
            audioId,
            generation: gen,
            event: null,
            state: this.getState(),
          };
        }
      }

      const evidenceRef = await this._ensureEvidenceRef(epoch, abortController.signal);
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          event: null,
          state: this.getState(),
        };
      }
      if (hasPostBasisInvalidation()) {
        return {
          status: "superseded",
          audioId,
          generation: gen,
          event: null,
          state: this.getState(),
        };
      }

      draft = await this._provider.analyze(
        deepFreeze({
          requestId,
          generation: gen,
          audioId,
          audio,
          evidenceRef,
          view: capturedView,
        }),
        abortController.signal,
      );
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
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
        slot.requestId = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentAudioId() !== audioId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        audioId,
        generation: gen,
        event: null,
        state: this.getState(),
      };
    }

    if (hasPostBasisInvalidation()) {
      return {
        status: "superseded",
        audioId,
        generation: gen,
        event: null,
        state: this.getState(),
      };
    }

    const waitUs = Math.max(0, Math.round((this._clock() - startMs) * 1000));
    const manifest = this._provider.manifest();
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    const ev = this._commitEvent(
      "draft_loaded",
      {
        segments: draft?.segments ?? [],
        boundaries: draft?.boundaries ?? [],
        spans: draft?.spans ?? [],
        beats: draft?.beats ?? [],
        sounds: draft?.sounds ?? [],
        soundClasses: draft?.soundClasses ?? [],
        unbeats: draft?.unbeats ?? [],
        replace: true,
      },
      { kind: authorKind, id: manifest.id },
    );
    if (waitUs > 0) {
      this._commitEvent("listen_effort", { waitUs }, { kind: "host" });
    }
    return {
      status: "applied",
      audioId,
      generation: gen,
      event: ev,
      state: this.getState(),
    };
  }

  /**
   * Layer 2 (`micro_assist`): Trace-free snap candidate query near `atUs`.
   * NEVER aborts `song_open` or `heavy_recompute` and writes ZERO `.jsonl` events.
   *
   * @param {{ atUs: number, windowUs?: number, immediate?: boolean }} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SuggestSnapsOutcome>}
   */
  async suggestSnaps(request, signal) {
    this._assertOpen();
    if (!this._state.audio) {
      throw new EnsomiError("AUDIO_NOT_BOUND", "bindAudio() must be called before suggestSnaps()");
    }
    if (!request || typeof request !== "object") {
      throw new EnsomiError("INVALID_REQUEST", "suggestSnaps requires { atUs, windowUs? }");
    }
    const atUs = assertPointUs(request.atUs, this._state.durationUs, "suggestSnaps.atUs");
    const windowUs =
      request.windowUs !== undefined
        ? assertNonNegSafeIntUs(request.windowUs, "suggestSnaps.windowUs", "INVALID_REQUEST")
        : 80_000;
    if (windowUs <= 0) {
      throw new EnsomiError("INVALID_REQUEST", "suggestSnaps.windowUs must be > 0");
    }

    const slot = this._slots.micro_assist;
    this._preemptSlot("micro_assist", "SUPERSEDED", "Superseded by newer micro_assist request");

    const audioId = this._state.audio.audioId;
    slot.audioId = audioId;
    slot.generation += 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;
    const requestId = `req_snaps_g${gen}_t${atUs}`;

    const abortController = new AbortController();
    slot.abort = abortController;
    slot.requestId = requestId;
    const unlink = linkAbortSignal(signal, abortController, () =>
      this._notifyProviderCancel(requestId),
    );

    const debounceMs = request.immediate ? 0 : this._triggerPolicy.micro_assist.debounceMs;

    let rawCandidates;
    try {
      if (debounceMs > 0) {
        const ok = await waitDebounce(debounceMs, abortController.signal);
        if (
          !ok ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentAudioId() !== audioId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            audioId,
            generation: gen,
            candidates: [],
          };
        }
      }

      const evidenceRef = await this._ensureEvidenceRef(epoch, abortController.signal);
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          candidates: [],
        };
      }

      const view = createProviderView(this._state);
      rawCandidates = await this._provider.suggestSnaps(
        deepFreeze({
          requestId,
          generation: gen,
          audioId,
          evidenceRef,
          atUs,
          windowUs,
          view,
        }),
        abortController.signal,
      );
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          candidates: [],
        };
      }
      throw err;
    } finally {
      unlink();
      if (slot.abort === abortController) {
        slot.abort = null;
        slot.requestId = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentAudioId() !== audioId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        audioId,
        generation: gen,
        candidates: [],
      };
    }

    const candidates = (Array.isArray(rawCandidates) ? rawCandidates : [])
      .map((c) => normalizeSnapCandidate(c, atUs, this._state.durationUs))
      .sort(
        (a, b) =>
          b.salience - a.salience ||
          Math.abs(a.deltaUs) - Math.abs(b.deltaUs) ||
          compareCodeUnits(a.candidateId, b.candidateId),
      );

    return {
      status: "ok",
      audioId,
      generation: gen,
      candidates,
    };
  }

  /**
   * Layer 2 (`micro_assist`): Trace-free polyphonic `[startUs, endUs)` Sound option query.
   *
   * @param {{ atUs?: number, rangeUs?: import("../index.d.ts").RangeUs, windowUs?: number, immediate?: boolean }} request
   * @param {AbortSignal} [signal]
   * @returns {Promise<import("../index.d.ts").SuggestSoundOptionsOutcome>}
   */
  async suggestSoundOptions(request, signal) {
    this._assertOpen();
    if (!this._state.audio) {
      throw new EnsomiError(
        "AUDIO_NOT_BOUND",
        "bindAudio() must be called before suggestSoundOptions()",
      );
    }
    if (typeof this._provider.suggestSoundOptions !== "function") {
      throw new EnsomiError(
        "UNSUPPORTED_CAPABILITY",
        "Provider does not implement suggestSoundOptions()",
      );
    }
    if (!request || typeof request !== "object" || (request.atUs === undefined && !request.rangeUs)) {
      throw new EnsomiError(
        "INVALID_REQUEST",
        "suggestSoundOptions requires at least one of { atUs, rangeUs }",
      );
    }
    const atUs =
      request.atUs !== undefined
        ? assertPointUs(request.atUs, this._state.durationUs, "suggestSoundOptions.atUs")
        : undefined;
    const rangeUs = request.rangeUs
      ? assertRangeUs(request.rangeUs, this._state.durationUs)
      : undefined;
    const windowUs =
      request.windowUs !== undefined
        ? assertNonNegSafeIntUs(request.windowUs, "suggestSoundOptions.windowUs", "INVALID_REQUEST")
        : 80_000;

    const slot = this._slots.micro_assist;
    this._preemptSlot("micro_assist", "SUPERSEDED", "Superseded by newer micro_assist request");

    const audioId = this._state.audio.audioId;
    slot.audioId = audioId;
    slot.generation += 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;
    const requestId = `req_sound_opts_g${gen}`;

    const abortController = new AbortController();
    slot.abort = abortController;
    slot.requestId = requestId;
    const unlink = linkAbortSignal(signal, abortController, () =>
      this._notifyProviderCancel(requestId),
    );
    const debounceMs = request.immediate ? 0 : this._triggerPolicy.micro_assist.debounceMs;

    let rawOptions;
    try {
      if (debounceMs > 0) {
        const ok = await waitDebounce(debounceMs, abortController.signal);
        if (
          !ok ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentAudioId() !== audioId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            audioId,
            generation: gen,
            options: [],
          };
        }
      }

      const evidenceRef = await this._ensureEvidenceRef(epoch, abortController.signal);
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          options: [],
        };
      }

      const view = createProviderView(this._state);
      rawOptions = await this._provider.suggestSoundOptions(
        deepFreeze({
          requestId,
          generation: gen,
          audioId,
          evidenceRef,
          ...(atUs !== undefined ? { atUs } : {}),
          ...(rangeUs ? { rangeUs } : {}),
          windowUs,
          view,
        }),
        abortController.signal,
      );
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          options: [],
        };
      }
      throw err;
    } finally {
      unlink();
      if (slot.abort === abortController) {
        slot.abort = null;
        slot.requestId = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentAudioId() !== audioId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        audioId,
        generation: gen,
        options: [],
      };
    }

    const options = (Array.isArray(rawOptions) ? rawOptions : [])
      .map((o) => normalizeSoundOptionCandidate(o, this._state.durationUs))
      .sort(
        (a, b) => b.salience - a.salience || compareCodeUnits(a.optionId, b.optionId),
      );

    return {
      status: "ok",
      audioId,
      generation: gen,
      options,
    };
  }

  /**
   * Load an imported/synthetic draft directly on the `song_open` slot.
   *
   * @param {import("../index.d.ts").AnalysisDraft & { replace?: boolean }} draft
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  loadDraft(draft, author = { kind: "import" }) {
    this._assertOpen();
    this.abortLayer("song_open", "Superseded by loadDraft()");
    const event = this._commitEvent("draft_loaded", draft, author);
    return { event, state: this.getState() };
  }

  /**
   * Execute one or more `TransactionOp`s atomically as a user `edit` event.
   *
   * @param {import("../index.d.ts").TransactionOp | readonly import("../index.d.ts").TransactionOp[]} opOrOps
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  applyEdit(opOrOps, author = { kind: "user" }) {
    this._assertOpen();
    const payload = Array.isArray(opOrOps) ? { ops: opOrOps } : opOrOps;
    const event = this._commitEvent("edit", payload, author);
    return { event, state: this.getState() };
  }

  /**
   * Retract an earlier trace event by eventId or seq (event-atomic selective undo/redo).
   *
   * @param {string | number} targetEventIdOrSeq
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  retract(targetEventIdOrSeq, reason, author = { kind: "user" }) {
    this._assertOpen();
    const prevAudioJson = canonicalJson(this._state.audio);
    const prevEvents = this._state.events;
    const prevRetracted = this._state.retractedEventIds;
    const payload =
      typeof targetEventIdOrSeq === "number"
        ? { targetSeq: targetEventIdOrSeq, ...(reason ? { reason } : {}) }
        : { targetEventId: targetEventIdOrSeq, ...(reason ? { reason } : {}) };
    const event = this._commitEvent("retract", payload, author);
    const audioChanged = canonicalJson(this._state.audio) !== prevAudioJson;
    const resetOrAudioToggled = prevEvents.some(
      (ev) =>
        (ev.type === "audio_bound" || ev.type === "reset_document") &&
        prevRetracted.has(ev.id) !== this._state.retractedEventIds.has(ev.id),
    );
    if (audioChanged || resetOrAudioToggled) {
      this._advanceAudioEpoch("Audio or reset state changed due to retract()", audioChanged);
    }
    return { event, state: this.getState() };
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
   * Undo the latest active user action.
   *
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").UndoRedoOutcome}
   */
  undo(reason = "undo", author = { kind: "user" }) {
    this._assertOpen();
    const { undoTargetEventId } = computeUndoRedoTargets(this._state);
    if (!undoTargetEventId) {
      return { ok: false, targetEventId: null, event: null, state: this.getState() };
    }
    const res = this.retract(undoTargetEventId, reason, author);
    return { ok: true, targetEventId: undoTargetEventId, event: res.event, state: res.state };
  }

  /**
   * Redo the latest undone user action.
   *
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").UndoRedoOutcome}
   */
  redo(reason = "redo", author = { kind: "user" }) {
    this._assertOpen();
    const { redoTargetEventId } = computeUndoRedoTargets(this._state);
    if (!redoTargetEventId) {
      return { ok: false, targetEventId: null, event: null, state: this.getState() };
    }
    const res = this.retract(redoTargetEventId, reason, author);
    return { ok: true, targetEventId: redoTargetEventId, event: res.event, state: res.state };
  }

  /**
   * Record user listening effort (`listenUs`, `waitUs`, `range`) in the effort ledger.
   *
   * @param {{ listenUs?: number, waitUs?: number, range?: import("../index.d.ts").RangeUs }} effort
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  recordListenEffort(effort, author = { kind: "host" }) {
    this._assertOpen();
    const event = this._commitEvent("listen_effort", effort, author);
    return { event, state: this.getState() };
  }

  /**
   * Mark the document ready.
   *
   * @param {string} [notes]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  markReady(notes, author = { kind: "user" }) {
    this._assertOpen();
    const event = this._commitEvent(
      "ready",
      { ready: true, ...(notes ? { notes } : {}) },
      author,
    );
    return { event, state: this.getState() };
  }

  /**
   * Shared `heavy_recompute` lifecycle runner for `recompute`, `matchSounds`, and `suggestSegmentation`.
   */
  async _runHeavyLayer(scope, reason, author, invokeProvider) {
    this._assertOpen();
    if (!this._state.audio) {
      throw new EnsomiError("AUDIO_NOT_BOUND", "bindAudio() must be called before heavy_recompute");
    }
    const normalizedScope =
      scope === "song" ? "song" : assertRangeUs(scope, this._state.durationUs);

    const slot = this._slots.heavy_recompute;
    this._preemptSlot("heavy_recompute", "SUPERSEDED", "Superseded by newer heavy_recompute call");

    const audioId = this._state.audio.audioId;
    slot.audioId = audioId;
    slot.generation = Math.max(slot.generation, this._state.generation) + 1;
    const gen = slot.generation;
    const epoch = this._audioEpoch;

    this._commitEvent(
      "signal",
      {
        layer: "heavy_recompute",
        scope: normalizedScope,
        reason,
        generation: gen,
      },
      author,
    );

    // Capture immutable basis BEFORE awaiting debounce / prepare / provider!
    const basisSeq = this._state.lastSeq;
    const capturedView = createProviderView(this._state);
    const requestId = `req_heavy_g${gen}_s${basisSeq}`;

    const abortController = new AbortController();
    slot.abort = abortController;
    slot.requestId = requestId;
    const startMs = this._clock();

    let rawResult;
    try {
      const debounceMs = this._triggerPolicy.heavy_recompute.debounceMs;
      if (debounceMs > 0) {
        const ok = await waitDebounce(debounceMs, abortController.signal);
        if (
          !ok ||
          abortController.signal.aborted ||
          slot.generation !== gen ||
          this._currentAudioId() !== audioId ||
          epoch !== this._audioEpoch
        ) {
          return {
            status: this._abortedStatus(abortController),
            audioId,
            generation: gen,
            offers: [],
            suppressedOffers: [],
          };
        }
      }

      const evidenceRef = await this._ensureEvidenceRef(epoch, abortController.signal);
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          offers: [],
          suppressedOffers: [],
        };
      }

      rawResult = await invokeProvider({
        requestId,
        generation: gen,
        basisSeq,
        audioId,
        evidenceRef,
        scope: normalizedScope,
        reason,
        view: capturedView,
        signal: abortController.signal,
      });
    } catch (err) {
      if (
        abortController.signal.aborted ||
        slot.generation !== gen ||
        this._currentAudioId() !== audioId ||
        epoch !== this._audioEpoch ||
        this._closed
      ) {
        return {
          status: this._abortedStatus(abortController),
          audioId,
          generation: gen,
          offers: [],
          suppressedOffers: [],
        };
      }
      throw err;
    } finally {
      if (slot.abort === abortController) {
        slot.abort = null;
        slot.requestId = null;
      }
    }

    if (
      abortController.signal.aborted ||
      slot.generation !== gen ||
      this._currentAudioId() !== audioId ||
      epoch !== this._audioEpoch ||
      this._closed
    ) {
      return {
        status: this._abortedStatus(abortController),
        audioId,
        generation: gen,
        offers: [],
        suppressedOffers: [],
      };
    }

    const waitUs = Math.max(0, Math.round((this._clock() - startMs) * 1000));
    const manifest = this._provider.manifest();
    const rawOffers = Array.isArray(rawResult)
      ? rawResult
      : Array.isArray(rawResult?.offers)
        ? rawResult.offers
        : [];
    const defaultBatchId =
      (!Array.isArray(rawResult) && rawResult?.batchId) ||
      `batch_g${gen}_s${basisSeq}_${this._nextSeq}`;

    // Host-bind every returned Offer strictly to `capturedView.snapshot`!
    const boundOffers = rawOffers.map((raw, idx) =>
      bindOfferToBasis(
        capturedView.snapshot,
        {
          ...raw,
          id: raw.id ?? `offer_g${gen}_s${basisSeq}_${this._nextSeq}_${idx}`,
          batchId: raw.batchId ?? defaultBatchId,
        },
        {
          defaultBatchId,
          defaultProviderId: manifest.id,
          defaultModelRevision: manifest.modelRevision ?? manifest.version,
          defaultAudioId: audioId,
          defaultBasisSeq: basisSeq,
        },
      ),
    );

    if (this._holdOffers) {
      if (waitUs > 0) {
        this._commitEvent("listen_effort", { waitUs }, { kind: "host" });
      }
      /** @type {import("../index.d.ts").Offer[]} */
      const suppressedWhileHeld = [];
      /** @type {import("../index.d.ts").Offer[]} */
      const previewOffers = [];
      /** @type {import("../index.d.ts").Offer[]} */
      const retainableOffers = [];
      for (const offer of boundOffers) {
        if (isOfferSuppressed(this._state, offer)) {
          suppressedWhileHeld.push(offer);
          continue;
        }
        retainableOffers.push(offer);
        const check = validateOffer(this._state, offer, { ignoreTerminalStatus: true });
        previewOffers.push(
          deepFreeze({
            ...offer,
            status: check.status,
            conflicts: check.conflicts,
            notes: check.notes,
          }),
        );
      }
      this._heldOfferBatch = {
        audioId,
        audioEpoch: epoch,
        generation: gen,
        batchId: defaultBatchId,
        offers: retainableOffers,
      };
      return {
        status: "held",
        audioId,
        generation: gen,
        offers: previewOffers,
        suppressedOffers: suppressedWhileHeld,
      };
    }

    const beforeSuppressedCount = this._state.suppressedOffers.length;
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    this._commitEvent(
      "offer_batch",
      {
        batchId: defaultBatchId,
        offers: boundOffers,
        waitUs,
      },
      { kind: authorKind, id: manifest.id },
    );

    const newlySuppressed = this._state.suppressedOffers.slice(beforeSuppressedCount);
    const newlySuppressedIds = new Set(newlySuppressed.map((o) => o.id));
    const surfacedOffers = boundOffers
      .filter((o) => !newlySuppressedIds.has(o.id))
      .map((o) => this._state.offers.get(o.id))
      .filter(Boolean);

    return {
      status: "applied",
      audioId,
      generation: gen,
      offers: /** @type {import("../index.d.ts").Offer[]} */ (surfacedOffers),
      suppressedOffers: newlySuppressed,
    };
  }

  /**
   * Layer 3 (`heavy_recompute`): Constrained timing/propagation recompute.
   *
   * @param {{ scope: import("../index.d.ts").RangeUs | "song", reason: string }} request
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {Promise<import("../index.d.ts").RecomputeOutcome>}
   */
  async recompute(request, author = { kind: "user" }) {
    if (!request || typeof request !== "object") {
      throw new EnsomiError("INVALID_SIGNAL", "recompute requires { scope, reason }");
    }
    return await this._runHeavyLayer(
      request.scope,
      request.reason ?? "recompute",
      author,
      async (ctx) =>
        await this._provider.recompute(
          deepFreeze({
            requestId: ctx.requestId,
            generation: ctx.generation,
            basisSeq: ctx.basisSeq,
            audioId: ctx.audioId,
            evidenceRef: ctx.evidenceRef,
            scope: ctx.scope,
            reason: ctx.reason,
            view: ctx.view,
          }),
          ctx.signal,
        ),
    );
  }

  /**
   * Layer 3 (`heavy_recompute`): Whole-song or scoped Sound matching (`hit | voice | figure`).
   *
   * @param {{
   *   exemplarSoundIds: string[],
   *   matchMode: import("../index.d.ts").SoundMatchMode,
   *   classId?: string,
   *   scope?: import("../index.d.ts").RangeUs | "song",
   *   reason?: string
   * }} request
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {Promise<import("../index.d.ts").RecomputeOutcome>}
   */
  async matchSounds(request, author = { kind: "user" }) {
    if (typeof this._provider.matchSounds !== "function") {
      throw new EnsomiError("UNSUPPORTED_CAPABILITY", "Provider does not implement matchSounds()");
    }
    if (
      !request ||
      typeof request !== "object" ||
      !Array.isArray(request.exemplarSoundIds) ||
      request.exemplarSoundIds.length === 0
    ) {
      throw new EnsomiError("INVALID_REQUEST", "matchSounds requires non-empty exemplarSoundIds[]");
    }
    if (!SOUND_MATCH_MODE_SET.has(request.matchMode)) {
      throw new EnsomiError("INVALID_REQUEST", `Invalid matchMode: ${String(request.matchMode)}`);
    }
    for (const id of request.exemplarSoundIds) {
      assertNonEmptyId(id, "exemplarSoundId", "INVALID_REQUEST");
    }
    return await this._runHeavyLayer(
      request.scope ?? "song",
      request.reason ?? `match_sounds:${request.matchMode}`,
      author,
      async (ctx) =>
        await this._provider.matchSounds(
          deepFreeze({
            requestId: ctx.requestId,
            generation: ctx.generation,
            basisSeq: ctx.basisSeq,
            audioId: ctx.audioId,
            evidenceRef: ctx.evidenceRef,
            exemplarSoundIds: request.exemplarSoundIds.slice(),
            matchMode: request.matchMode,
            ...(request.classId ? { classId: request.classId } : {}),
            scope: ctx.scope,
            view: ctx.view,
          }),
          ctx.signal,
        ),
    );
  }

  /**
   * Layer 3 (`heavy_recompute`): Request structural segmentation offers.
   *
   * @param {{ scope?: import("../index.d.ts").RangeUs | "song", reason?: string }} [request]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {Promise<import("../index.d.ts").RecomputeOutcome>}
   */
  async suggestSegmentation(request = {}, author = { kind: "user" }) {
    if (typeof this._provider.suggestSegmentation !== "function") {
      throw new EnsomiError(
        "UNSUPPORTED_CAPABILITY",
        "Provider does not implement suggestSegmentation()",
      );
    }
    return await this._runHeavyLayer(
      request.scope ?? "song",
      request.reason ?? "suggest_segmentation",
      author,
      async (ctx) =>
        await this._provider.suggestSegmentation(
          deepFreeze({
            requestId: ctx.requestId,
            generation: ctx.generation,
            basisSeq: ctx.basisSeq,
            audioId: ctx.audioId,
            evidenceRef: ctx.evidenceRef,
            scope: ctx.scope,
            reason: ctx.reason,
            view: ctx.view,
          }),
          ctx.signal,
        ),
    );
  }

  /**
   * Preview an `Offer` (by ID or object) against current state using the pure transaction interpreter.
   *
   * @param {string | import("../index.d.ts").Offer} offerIdOrOffer
   * @param {import("../index.d.ts").TempoMapOptions} [tempoOptions]
   * @returns {import("../index.d.ts").OfferPreviewResult}
   */
  previewOffer(offerIdOrOffer, tempoOptions) {
    this._assertOpen();
    const offer =
      typeof offerIdOrOffer === "string"
        ? this._state.offers.get(offerIdOrOffer)
        : offerIdOrOffer;
    if (!offer) {
      throw new EnsomiError("MISSING_OFFER", `Offer "${String(offerIdOrOffer)}" does not exist`);
    }
    return previewOfferPure(this._state, offer, tempoOptions);
  }

  /**
   * Accept one or more `Offer`s atomically, enforcing `protected_keep`, `protected_verified`,
   * `authorizedChallenges`, and `alternativeGroup`-scoped sibling supersession.
   *
   * @param {string | { offerIds: string[], authorizedChallenges?: import("../index.d.ts").ChallengeAuthorization[] }} offerIdOrInput
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").AcceptOfferOutcome}
   */
  acceptOffer(offerIdOrInput, author = { kind: "user" }) {
    this._assertOpen();
    if (typeof offerIdOrInput !== "string" && (!offerIdOrInput || typeof offerIdOrInput !== "object")) {
      return {
        ok: false,
        status: "invalid",
        conflicts: [{ kind: "invalid_op", message: "No suggestion IDs specified." }],
        requiredChallenges: [],
      };
    }
    const rawOfferIds =
      typeof offerIdOrInput === "string"
        ? [offerIdOrInput]
        : Array.isArray(offerIdOrInput.offerIds)
          ? offerIdOrInput.offerIds
          : [];
    const rawAuthorized =
      typeof offerIdOrInput === "object" ? offerIdOrInput.authorizedChallenges : undefined;
    const authMap = validateAuthorizedChallenges(rawAuthorized);

    if (
      rawOfferIds.length === 0 ||
      rawOfferIds.some((id) => typeof id !== "string" || id.length === 0)
    ) {
      return {
        ok: false,
        status: "invalid",
        conflicts: [{ kind: "invalid_op", message: "No suggestion IDs specified." }],
        requiredChallenges: [],
      };
    }
    const offerIds = /** @type {string[]} */ (rawOfferIds);

    // Run sequential scratch pre-check across all `offerIds` so duplicate IDs, shared
    // `alternativeGroup`, overlapping `writeRanges`, and missing challenge confirmations
    // return structured `{ ok: false, ... }` instead of throwing mid-commit!
    const sim = evaluateOfferAcceptBatch(this._state, offerIds, authMap, "__preview__");
    if (!sim.ok) {
      return {
        ok: false,
        status: sim.status,
        conflicts: sim.conflicts,
        requiredChallenges: sim.requiredChallenges,
      };
    }

    const prePendingIds = new Set(
      Array.from(this._state.offers.values())
        .filter((o) => o.status !== "superseded")
        .map((o) => o.id),
    );

    const authorizedChallenges = Array.from(authMap.values());
    this._commitEvent(
      "offer_accept",
      {
        offerIds,
        ...(authorizedChallenges.length > 0 ? { authorizedChallenges } : {}),
      },
      author,
    );

    const acceptedOffers = offerIds
      .map((id) => this._state.offers.get(id))
      .filter(Boolean);
    const supersededOfferIds = Array.from(this._state.offers.values())
      .filter((o) => prePendingIds.has(o.id) && o.status === "superseded")
      .map((o) => o.id);

    return {
      ok: true,
      status: "accepted",
      conflicts: [],
      requiredChallenges: [],
      acceptedOffers: /** @type {import("../index.d.ts").Offer[]} */ (acceptedOffers),
      supersededOfferIds,
    };
  }

  /**
   * Dismiss/reject an active offer, recording semantic suppression over its `writeRanges`.
   *
   * @param {string} offerId
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   * @returns {import("../index.d.ts").RejectOfferOutcome}
   */
  rejectOffer(offerId, reason, author = { kind: "user" }) {
    this._assertOpen();
    const offer = this._state.offers.get(offerId);
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
        { status: offer.status },
      );
    }
    this._commitEvent("offer_reject", { offerId, ...(reason ? { reason } : {}) }, author);
    return {
      ok: true,
      offer: this._state.offers.get(offerId),
    };
  }
}
