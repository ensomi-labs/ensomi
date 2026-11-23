/**
 * Headless SessionController:
 *  - Owns append-only trace + materialized SessionState + TimingProvider
 *  - Enforces latest-wins preemptive cancellation (AbortController + generation guard)
 *  - Supports audition lock (defers offer swap while user is auditioning)
 *  - Validates acceptOffer conflict-first (never silently overwrites)
 *  - Suppresses rejected semanticKeys and resurfaces them after overlapping edits/statements
 *  - Tracks effort ledger (listenSec, actionCount, waitSec)
 */

import { compileConstraints } from "./constraints.js";
import { computeConsequences, computeSemanticKey, validatePatchOffer } from "./offers.js";
import {
  applyEvent,
  createInitialState,
  foldTrace,
  serializeTraceNdjson,
} from "./trace.js";
import { assertSpan, EnsomiError, TRACE_SCHEMA_VERSION } from "./types.js";

/**
 * Validate a TimingProvider object has the required contract methods.
 * @param {import("../index.d.ts").TimingProvider} provider
 */
export function assertTimingProvider(provider) {
  if (!provider || typeof provider !== "object") {
    throw new EnsomiError("INVALID_PROVIDER", "TimingProvider must be an object");
  }
  for (const method of ["manifest", "prepare", "analyze", "recompute"]) {
    if (typeof /** @type {any} */ (provider)[method] !== "function") {
      throw new EnsomiError("INVALID_PROVIDER", `TimingProvider is missing required method "${method}()"`);
    }
  }
}

export class SessionController {
  /**
   * @param {{
   *   provider: import("../index.d.ts").TimingProvider,
   *   clock?: () => number,
   *   idGenerator?: (prefix: string, seq: number) => string,
   *   initialEvents?: import("../index.d.ts").TraceEvent[],
   * }} options
   */
  constructor(options) {
    if (!options || typeof options !== "object") {
      throw new EnsomiError("INVALID_OPTIONS", "SessionController requires an options object");
    }
    assertTimingProvider(options.provider);

    this._provider = options.provider;
    this._clock = options.clock ?? (() => Date.now());
    this._idGenerator = options.idGenerator ?? ((prefix, seq) => `${prefix}_${seq}`);

    this._state =
      Array.isArray(options.initialEvents) && options.initialEvents.length > 0
        ? foldTrace(options.initialEvents)
        : createInitialState();

    this._nextSeq = this._state.lastSeq + 1;
    this._generation = this._state.generation;
    this._evidence = null;

    /** @type {AbortController | null} */
    this._inFlightAbort = null;
    this._inFlightGeneration = 0;

    this._auditionLocked = false;
    /** @type {{ generation: number, batchId: string, offers: import("../index.d.ts").PatchOffer[], waitSec: number } | null} */
    this._auditionBuffer = null;
  }

  /** @returns {import("../index.d.ts").ProviderManifest} */
  getManifest() {
    return this._provider.manifest();
  }

  /** @returns {import("../index.d.ts").SessionState} */
  getState() {
    return this._state;
  }

  /** @returns {readonly import("../index.d.ts").TraceEvent[]} */
  getTrace() {
    return this._state.events;
  }

  /** @returns {number} */
  getGeneration() {
    return this._generation;
  }

  /** @returns {import("../index.d.ts").EffortLedger} */
  getEffortLedger() {
    return { ...this._state.effort };
  }

  /** @returns {import("../index.d.ts").PatchOffer[]} */
  getPendingOffers() {
    return Array.from(this._state.offers.values()).filter((o) => o.status === "pending");
  }

  /** @returns {import("../index.d.ts").PatchOffer[]} */
  getBufferedOffers() {
    return this._auditionBuffer ? this._auditionBuffer.offers.slice() : [];
  }

  /** @returns {boolean} */
  isAuditionLocked() {
    return this._auditionLocked;
  }

  /**
   * Toggle hold-while-auditioning lock.
   * While locked, incoming recompute batches are held in a buffer instead of swapping live offers.
   * Unlocking flushes the latest buffered batch (if still matching the current generation).
   *
   * @param {boolean} locked
   * @returns {{ flushed: boolean, offers: import("../index.d.ts").PatchOffer[] }}
   */
  setAuditionLock(locked) {
    const next = Boolean(locked);
    this._auditionLocked = next;
    if (!next && this._auditionBuffer) {
      const buffered = this._auditionBuffer;
      this._auditionBuffer = null;
      if (buffered.generation === this._generation) {
        const beforeSuppressedCount = this._state.suppressedOffers.length;
        this._commitEvent(
          "offer_batch",
          {
            batchId: buffered.batchId,
            offers: buffered.offers,
            waitSec: buffered.waitSec,
          },
          { kind: this._provider.manifest().kind === "rule" ? "rule" : "model", id: this._provider.manifest().id },
        );
        const newlySuppressed = new Set(
          this._state.suppressedOffers.slice(beforeSuppressedCount).map((o) => o.id),
        );
        const surfaced = buffered.offers
          .filter((o) => !newlySuppressed.has(o.id))
          .map((o) => this._state.offers.get(o.id))
          .filter(Boolean);
        return { flushed: true, offers: /** @type {import("../index.d.ts").PatchOffer[]} */ (surfaced) };
      }
    }
    return { flushed: false, offers: [] };
  }

  /**
   * Export the full session trace as NDJSON (.jsonl).
   * @returns {string}
   */
  exportNdjson() {
    return serializeTraceNdjson(this._state.events);
  }

  /**
   * Append a TraceEvent internally and advance SessionState.
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
      author,
      payload,
    };
    this._state = applyEvent(this._state, /** @type {import("../index.d.ts").TraceEvent} */ (event));
    this._nextSeq = this._state.lastSeq + 1;
    return event;
  }

  /**
   * Bind an AudioDescriptor and prepare provider evidence.
   * @param {import("../index.d.ts").AudioDescriptor} audio
   * @param {AbortSignal} [signal]
   */
  async bindAudio(audio, signal) {
    this._commitEvent("audio_bound", audio, { kind: "host" });
    this._evidence = await this._provider.prepare(this._state.audio, signal);
    return this._evidence;
  }

  /**
   * Run provider.analyze() on prepared evidence and commit "draft_loaded".
   * @param {AbortSignal} [signal]
   */
  async loadInitialDraft(signal) {
    if (!this._state.audio) {
      throw new EnsomiError("AUDIO_NOT_BOUND", "bindAudio() must be called before loadInitialDraft()");
    }
    const startMs = this._clock();
    const draft = await this._provider.analyze(this._evidence, signal);
    const waitSec = Math.max(0, (this._clock() - startMs) / 1000);
    const manifest = this._provider.manifest();
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    const ev = this._commitEvent(
      "draft_loaded",
      {
        beats: draft.beats ?? [],
        boundaries: draft.boundaries ?? [],
        sounds: draft.sounds ?? [],
        statements: draft.statements ?? [],
        replace: true,
      },
      { kind: authorKind, id: manifest.id },
    );
    if (waitSec > 0) {
      this._commitEvent("listen_effort", { waitSec }, { kind: "host" });
    }
    return { event: ev, state: this._state };
  }

  /**
   * Directly commit a draft without invoking provider.analyze().
   * @param {{ beats?: any[], boundaries?: any[], sounds?: any[], statements?: any[], replace?: boolean }} draft
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  loadDraft(draft, author = { kind: "import" }) {
    const event = this._commitEvent("draft_loaded", draft, author);
    return { event, state: this._state };
  }

  /**
   * Apply a direct edit immediately. Never automatically triggers recompute.
   * @param {import("../index.d.ts").EditOp | import("../index.d.ts").EditOp[]} opOrOps
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  applyEdit(opOrOps, author = { kind: "user" }) {
    const payload = Array.isArray(opOrOps) ? { ops: opOrOps } : opOrOps;
    const event = this._commitEvent("edit", payload, author);
    return { event, state: this._state };
  }

  /**
   * Add or update a semantic Statement immediately without mutating beats directly
   * and without automatically triggering recompute.
   * @param {Omit<import("../index.d.ts").Statement, "version" | "createdAtSeq" | "active"> & { active?: boolean, version?: number, createdAtSeq?: number }} statement
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  upsertStatement(statement, author = { kind: "user" }) {
    const event = this._commitEvent("statement", { action: "upsert", statement }, author);
    return { event, state: this._state };
  }

  /**
   * Deactivate an existing semantic Statement by ID.
   * @param {string} id
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  deactivateStatement(id, author = { kind: "user" }) {
    const event = this._commitEvent("statement", { action: "deactivate", id }, author);
    return { event, state: this._state };
  }

  /**
   * Retract an earlier trace event by event ID or seq (backward-referencing only).
   * Retracting a prior "retract" event performs a clean redo.
   * @param {string | number} targetEventIdOrSeq
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  retract(targetEventIdOrSeq, reason, author = { kind: "user" }) {
    const payload =
      typeof targetEventIdOrSeq === "number"
        ? { targetSeq: targetEventIdOrSeq, ...(reason ? { reason } : {}) }
        : { targetEventId: targetEventIdOrSeq, ...(reason ? { reason } : {}) };
    const event = this._commitEvent("retract", payload, author);
    return { event, state: this._state };
  }

  /**
   * Record user listening time / visited span / wait time in the effort ledger.
   * @param {{ listenSec?: number, waitSec?: number, span?: import("../index.d.ts").Span }} effort
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  recordListenEffort(effort, author = { kind: "host" }) {
    const event = this._commitEvent("listen_effort", effort, author);
    return { event, state: this._state };
  }

  /**
   * Mark the session ready.
   * @param {string} [notes]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  markReady(notes, author = { kind: "user" }) {
    const event = this._commitEvent("ready", { ready: true, ...(notes ? { notes } : {}) }, author);
    return { event, state: this._state };
  }

  /**
   * Preemptively abort any in-flight recompute without triggering a new one.
   */
  cancelRecompute() {
    this._generation += 1;
    if (this._inFlightAbort) {
      this._inFlightAbort.abort(new EnsomiError("ABORTED", "Recompute cancelled"));
      this._inFlightAbort = null;
    }
    this._auditionBuffer = null;
  }

  /**
   * Explicitly trigger provider recompute with latest-wins preemptive cancellation.
   * Any newer trigger immediately aborts the prior in-flight request and discards late results.
   *
   * @param {{ scope: import("../index.d.ts").Span | "song", reason: string, statementIds?: string[] }} request
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  async triggerRecompute(request, author = { kind: "user" }) {
    if (!request || typeof request !== "object") {
      throw new EnsomiError("INVALID_SIGNAL", "triggerRecompute requires a request object");
    }
    const scope = request.scope === "song" ? "song" : assertSpan(request.scope);

    // Preempt any in-flight recompute immediately
    if (this._inFlightAbort) {
      this._inFlightAbort.abort(new EnsomiError("SUPERSEDED", "Superseded by newer recompute"));
      this._inFlightAbort = null;
    }

    this._generation += 1;
    const gen = this._generation;
    const abortController = new AbortController();
    this._inFlightAbort = abortController;
    this._inFlightGeneration = gen;

    this._commitEvent(
      "signal",
      {
        scope,
        reason: request.reason,
        generation: gen,
        ...(request.statementIds ? { statementIds: request.statementIds.slice() } : {}),
      },
      author,
    );

    const basisSeq = this._state.lastSeq;
    const constraints = compileConstraints(this._state.statements);
    const snapshotState = this._state;
    const startMs = this._clock();

    let result;
    try {
      result = await this._provider.recompute(
        this._evidence,
        snapshotState,
        {
          scope,
          reason: request.reason,
          generation: gen,
          basisSeq,
          constraints,
          ...(request.statementIds ? { statementIds: request.statementIds.slice() } : {}),
        },
        abortController.signal,
      );
    } catch (err) {
      if (abortController.signal.aborted || gen !== this._generation) {
        return {
          status: /** @type {const} */ ("cancelled"),
          generation: gen,
          offers: [],
          suppressedOffers: [],
        };
      }
      throw err;
    } finally {
      if (this._inFlightGeneration === gen) {
        this._inFlightAbort = null;
      }
    }

    // Generation guard: discard late promises from superseded generations
    if (abortController.signal.aborted || gen !== this._generation) {
      return {
        status: /** @type {const} */ ("superseded"),
        generation: gen,
        offers: [],
        suppressedOffers: [],
      };
    }

    const waitSec = Math.max(0, (this._clock() - startMs) / 1000);
    const manifest = this._provider.manifest();
    const batchId = result?.batchId ?? `batch_g${gen}_${this._nextSeq}`;
    const rawOffers = Array.isArray(result?.offers) ? result.offers : [];

    const normalizedOffers = rawOffers.map((raw, idx) => {
      const writeScope = assertSpan(raw.writeScope);
      const ops = raw.ops ? structuredClone(raw.ops) : {};
      return {
        ...raw,
        id: raw.id ?? `offer_g${gen}_${idx}`,
        batchId: raw.batchId ?? batchId,
        providerId: raw.providerId ?? manifest.id,
        providerKind: raw.providerKind ?? manifest.kind,
        generation: gen,
        basisSeq: raw.basisSeq ?? basisSeq,
        writeScope,
        readScope: raw.readScope === "song" ? "song" : assertSpan(raw.readScope ?? writeScope),
        ops,
        consequences: raw.consequences ?? computeConsequences(this._state, ops),
        conflicts: Array.isArray(raw.conflicts) ? structuredClone(raw.conflicts) : [],
        semanticKey: raw.semanticKey || computeSemanticKey(writeScope, ops),
        status: /** @type {const} */ ("pending"),
      };
    });

    if (this._auditionLocked) {
      this._auditionBuffer = {
        generation: gen,
        batchId,
        offers: normalizedOffers,
        waitSec,
      };
      return {
        status: /** @type {const} */ ("buffered"),
        generation: gen,
        offers: normalizedOffers,
        suppressedOffers: [],
      };
    }

    const beforeSuppressedCount = this._state.suppressedOffers.length;
    const authorKind = manifest.kind === "rule" ? "rule" : "model";
    this._commitEvent(
      "offer_batch",
      {
        batchId,
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
      status: /** @type {const} */ ("applied"),
      generation: gen,
      offers: /** @type {import("../index.d.ts").PatchOffer[]} */ (surfacedOffers),
      suppressedOffers: newlySuppressed,
    };
  }

  /**
   * Conflict-first offer acceptance.
   * Validates entity versions, range membership, active constraints, and sibling conflicts
   * before committing "offer_accept". Returns `{ ok: false, conflicts }` instead of overwriting.
   *
   * @param {string} offerId
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  acceptOffer(offerId, author = { kind: "user" }) {
    const offer = this._state.offers.get(offerId);
    if (!offer) {
      return {
        ok: /** @type {const} */ (false),
        conflicts: [
          {
            id: `conflict:missing:${offerId}`,
            kind: /** @type {const} */ ("range_changed"),
            message: `Offer "${offerId}" does not exist in session state`,
          },
        ],
      };
    }

    const conflicts = validatePatchOffer(this._state, offer);
    if (conflicts.length > 0) {
      return {
        ok: /** @type {const} */ (false),
        conflicts,
      };
    }

    const pendingSiblingsBefore = Array.from(this._state.offers.values())
      .filter((o) => o.id !== offerId && o.batchId === offer.batchId && o.status === "pending")
      .map((o) => o.id);

    this._commitEvent("offer_accept", { offerId }, author);

    return {
      ok: /** @type {const} */ (true),
      conflicts: [],
      offer: this._state.offers.get(offerId),
      supersededOfferIds: pendingSiblingsBefore,
    };
  }

  /**
   * Reject an offer, recording its semanticKey suppression over its writeScope.
   * @param {string} offerId
   * @param {string} [reason]
   * @param {import("../index.d.ts").TraceAuthor} [author]
   */
  rejectOffer(offerId, reason, author = { kind: "user" }) {
    const offer = this._state.offers.get(offerId);
    if (!offer) {
      throw new EnsomiError("MISSING_OFFER", `Offer "${offerId}" does not exist in session state`);
    }
    this._commitEvent("offer_reject", { offerId, ...(reason ? { reason } : {}) }, author);
    return {
      ok: /** @type {const} */ (true),
      offer: this._state.offers.get(offerId),
    };
  }
}
