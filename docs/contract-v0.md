# Ensomi v0 Contract Specification

`ensomi` v0 is an **LLM-free, MIT, clock-agnostic, zero-runtime-dependency** ESM JavaScript package defining the shared timing contract, sans-IO trace reducer, conflict-first patch offer semantics, pure derived views, headless `SessionController`, and deterministic `ScriptedMockProvider`.

All timestamps are expressed in **source-audio seconds**. No audio decoding, playback clock, or UI framework is assumed.

---

## 1. Core Entities

Every stored entity carries a stable string `id`, a finite source-audio time `t` (seconds), provenance `source`, and a positive integer `version` that increments monotonically on every mutation.

- **`EntitySource`**: `"model" | "user" | "import" | "rule"`
- **`BoundaryKind`**: `"steady_start" | "free_start" | "reset" | "gap_start" | "gap_end"`
- **`Span`**: `{ t0: number, t1: number }` — half-open interval `[t0, t1)` in source-audio seconds (`t1 >= t0`). Point events/constraints use `t0 === t1`.
- **`Beat`**: `{ id, t, source, version, conf?, meta? }`
- **`Boundary`**: `{ id, t, kind, source, version, bpm?, meta? }`
- **`SoundAnchor`**: `{ id, t, emphasis, source, version, conf?, experimental?, meta? }`
  - `emphasis` is validated as finite and clamped to `[0, 1]`.
  - Stored completely independently of `Beat` entities. Moving or removing a beat never mutates a `SoundAnchor`; grid alignment is purely derived via `computeGridFit()`.
- **`AudioDescriptor`**: `{ audioId, durationSec, sampleRate?, clock, decoder?, leadOffsetSec? }`

Schemas: [`schemas/entities.schema.json`](../schemas/entities.schema.json).

---

## 2. Two Decoupled Action Systems + Explicit Recompute Signal

1. **Direct Edits (`EditOp`)** mutate entity state immediately and **never** trigger provider recompute automatically:
   - `upsert_beat`, `move_beat`, `remove_beat`, `shift_run_offset`
   - `upsert_boundary`, `remove_boundary`
   - `upsert_sound`, `remove_sound`
2. **Semantic Constraints (`Statement`)** stand immediately as active constraints without mutating beats directly:
   - `StatementKind`: `"steady" | "free" | "no_pulse" | "keep" | "beat_run" | "sound_at" | "beat_at" | "forbid_beat"`
   - `Statement`: `{ id, kind, span?, t?, value?, active, version, createdAtSeq }`
   - `compileConstraints(statements)` compiles active statements (`active !== false`) into the deterministic 4-form **Constraint IR**:
     - `observe`: `sound_at`
     - `clamp`: `steady`, `free`, `beat_at`, `beat_run`
     - `forbid`: `forbid_beat`, `no_pulse`
     - `freeze`: `keep`
3. **Explicit Recompute Signal (`SignalPayload`)**:
   - `{ scope: Span | "song", reason: string, generation: number, statementIds?: string[] }`
   - Recompute runs only when explicitly signaled (e.g., `controller.triggerRecompute(...)`).

---

## 3. First-Class Conflicts & Orthogonal `PatchOffer`

A provider proposes changes as sparse versioned `PatchOffer` objects rather than mutating state directly:

- **`OfferScopeKind`**: `"local" | "forward_continuation" | "cross_region"`
- **`OfferRole`**: `"extend" | "rewrite" | "quick_fix" | "alternative" | "propagate"`
- **`OfferStatus`**: `"pending" | "accepted" | "rejected" | "stale" | "superseded"`
- **`PatchOp`**: `{ upsertBeats?, removeBeatIds?, upsertBoundaries?, removeBoundaryIds?, upsertSounds?, removeSoundIds?, expectedVersions?, expectedRevisions?, expectedRangeEntityIds?, expectedRangeKinds?, includeSounds? }`
- **`Conflict`**: `{ id, kind, message, entityIds?, statementIds?, span? }`
  - `version_mismatch`: an entity listed in `expectedVersions` or `expectedRevisions` is missing, has a different `version` or immutable originating revision (`seq:eventId`), was modified after `offer.basisSeq`, lacks an explicit update/remove precondition for an existing entity, or the offer is no longer `"pending"`.
  - `range_changed`: the set of entity IDs currently inside `writeScope` (filtered by `expectedRangeKinds` / `includeSounds` when provided) differs from `expectedRangeEntityIds`.
  - `constraint_violation`: the patch touches any entity position outside `writeScope`, contains duplicate IDs in an upsert array, mutates a frozen (`keep`) span/entity (respecting entity-kind scope and full entity content), places a beat in a forbidden (`forbid_beat` / `no_pulse`) location, or leaves a clamped `beat_at` position unoccupied in the resulting state.
  - `sibling_conflict`: mutually conflicting options sharing a `batchId`. Accepting one sibling immediately transitions all other pending siblings in the same `batchId` to `"superseded"`, and attempting to accept a superseded sibling returns `{ ok: false, conflicts: [{ kind: "sibling_conflict", ... }] }`.

### Semantic Keys & Rejection Suppression

- `computeSemanticKey(writeScope, ops)` computes a deterministic SHA-256-derived key over canonicalized `(writeScope, ops)` preserving stable IDs for existing/updated entities while stripping freshly generated ephemeral entity IDs and versions from newly inserted entities.
- Rejecting a `"pending"` offer records `{ semanticKey, writeScope }` in `state.suppressions`. Subsequent offers with the same `semanticKey` overlapping `writeScope` are suppressed on arrival.
- Any new `edit` or `statement` overlapping a suppression's `writeScope` automatically clears that suppression so fresh offers can resurface after user changes.
- Duplicate offer IDs within or across `offer_batch` events are rejected (`EnsomiError("DUPLICATE_OFFER_ID")`).

Schemas: [`schemas/patch-offer.schema.json`](../schemas/patch-offer.schema.json).

---

## 4. Append-Only NDJSON Trace (`.jsonl`) & Retraction Semantics

Every state change is represented by an immutable event envelope (`schemaVersion: "0.1"`):

```json
{
  "schemaVersion": "0.1",
  "seq": 0,
  "id": "audio_bound_0",
  "type": "audio_bound",
  "tsMs": 1700000000000,
  "author": { "kind": "host" },
  "payload": { "audioId": "synth-1", "durationSec": 60, "clock": "source-audio-sec" }
}
```

Event types: `"audio_bound" | "draft_loaded" | "edit" | "statement" | "signal" | "offer_batch" | "offer_accept" | "offer_reject" | "retract" | "listen_effort" | "ready"`.

### Incremental vs. Full Re-fold Invariant

- Normal events apply incrementally via `applyEvent(state, event)` without replaying history.
- `"retract"` events are **strictly backward-referencing** (`target.seq < event.seq`).
- **Redo = retracting a retraction.** `resolveActiveRetractions(events)` resolves retractions newest-first, so retracting a retraction cancels it cleanly across arbitrary undo/redo chains.
- On `"retract"`, `applyEvent` performs a clean full re-fold from event `0` via `foldTrace(events)`. This eliminates inverse-delta clobbering: if edit `A` moves `b1` from `1 -> 2` and edit `B` moves `b1` from `2 -> 3`, retracting `A` replays `B` cleanly so `b1` remains at `3` rather than reverting to `1`.
- For all traces `events`, `events.reduce(applyEvent, createInitialState())` is deeply equivalent to `foldTrace(events)`.

### Crash-Safe NDJSON & Checkpoints

- `serializeTraceNdjson(events)` writes one JSON object per line with a trailing `\n`.
- `parseTraceNdjson(text, { discardTruncatedTail?: boolean })`:
  - When `discardTruncatedTail: true`, discards **only** an incomplete/corrupt final line (e.g., partial crash write).
  - Interior line corruption **always throws** `EnsomiError("CORRUPT_NDJSON")`.
- `createCheckpoint(state)` and `verifyCheckpoint(checkpoint, prefixEvents)` produce deeply cloned snapshots and verify `schemaVersion`, structure, `hash(checkpoint.snapshot) === checkpoint.stateHash`, and deterministic SHA-256 equivalence against prefix replay.

Schemas: [`schemas/trace-event.schema.json`](../schemas/trace-event.schema.json).

---

## 5. Pure Derived Views

Derived views are never stored in `SessionState`:

1. **`computeGridFit(sounds, beats, boundaries, { divisors = [1, 2, 3, 4, 6, 8], tolMs = 20 })`**
   - Classifies every `SoundAnchor` as `"on_grid"` (`nearestDivisor === 1`), `"subdivision"` (`nearestDivisor > 1`), or `"unexplained"` (`nearestDivisor === null`), reporting unsigned `nearestBeatDistMs` and **signed** `residualMs = (sound.t - nearestSlotTime) * 1000` (negative = early relative to slot, positive = late relative to slot; `Infinity` when 0 beats exist).
   - Gap spans are swept independently from `gap_start` (inclusive start `[t, ...)`) to `gap_end` (exclusive end), so subdivisions do not interpolate across any active gap interval (including `gap_start` at a beat timestamp).
   - Moving a beat automatically reclassifies every affected sound.
2. **`projectRedLines(beats, boundaries, { defaultMeter = 4, jitterTolMs = 15 })`**
   - Segments inter-beat intervals via ordinary least-squares linear fit within `jitterTolMs`, sharing the pivot beat between adjoining runs so tempo transitions take effect at the pivot beat with zero phantom beats.
   - Honors `steady_start`, `reset`, `free_start`, `gap_start`, and `gap_end` boundaries.
   - Explicitly returns `barPolicy: "barlines_not_inferred"` and a `losses: string[]` report.
3. **`rankTroubleSpans(state, { windowSec = 4 })`**
   - Validates `windowSec` is finite and `> 0`, and ranks song windows descending by a transparent weighted sum of low beat confidence, unexplained inter-beat interval jumps, emphasis-weighted unexplained sound density, active offer conflicts, and unvisited status.

---

## 6. Headless `SessionController` & `TimingProvider`

`TimingProvider` implements:
- `manifest(): ProviderManifest`
- `prepare(audio, signal): Promise<PreparedEvidence>`
- `analyze(evidence, signal): Promise<AnalysisDraft>`
- `recompute(evidence, state, request, signal): Promise<RecomputeResult>`

`SessionController` wraps a `TimingProvider` with:
- **Latest-wins preemptive cancellation**: triggering a newer recompute immediately aborts any in-flight `AbortController` and increments a monotonic `generation`. Late promises from superseded generations are discarded even if a provider ignores `AbortSignal`.
- **Audition lock** (`setAuditionLock(true/false)`): holds incoming offer batches in a buffer while the user is auditioning and flushes the latest generation on unlock.
- **Conflict-first acceptance**: `acceptOffer(offerId)` validates versions, range membership, active constraints, and sibling status, returning `{ ok: false, conflicts }` instead of silently overwriting.
- **Effort ledger**: tracks `{ listenSec, actionCount, waitSec }` from trace events.

Schemas: [`schemas/provider-manifest.schema.json`](../schemas/provider-manifest.schema.json).
