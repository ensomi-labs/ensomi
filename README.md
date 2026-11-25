# ensomi

Zero-dependency, DOM-free ESM contract, pure transaction reducer, epistemic truth model, RFC 8785 (JCS) canonical hashing, format-neutral tempo/sound views, and three-layer `SessionController` for Ensomi timing and sound intelligence.

## Package Subpaths

- `ensomi` — Production contract constants, microsecond timebase helpers, RFC 8785 (JCS) + SHA-256 utilities, pure transaction interpreter, offer validation/preview, NDJSON trace reducer, derived views (`computeGridFit`, `projectTempoMap`, `rankInboxItems`), and `SessionController`.
- `ensomi/testing` — Deterministic `ScriptedMockProvider`, `generateSyntheticDraft`, and `createDeterministicPrng` for tests and offline UI integration.
- `ensomi/schemas/*` — JSON Schema Draft 2020-12 definitions (`entities.schema.json`, `trace-event.schema.json`, `offer.schema.json`, `provider-manifest.schema.json`).
- `conformance/v0.1/*` — Executable cross-language RFC 8785 (JCS) + SHA-256 conformance fixtures targeting JS, Python daemon, and Rust/Wasm runtimes.

## Core Invariants (`0.1.0` / Trace Schema `0.3`)

1. **Integer Microsecond Wire Clock (`atUs` / `[startUs, endUs)`):** Every committed coordinate, interval, duration, and tolerance on the wire is a non-negative safe integer in microseconds, paired with `secToUs`, `usToSec`, `sampleToUs`, and `usToNearestSample` boundary helpers.
2. **Strict RFC 8785 (JCS) Canonicalization:** UTF-16 code-unit key sorting, standard ECMAScript number formatting (`-0` -> `"0"`), `null` (never `Infinity`) in derived views, and explicit `INVALID_CANONICAL_VALUE` rejection on non-finite values, lone surrogates, or non-plain objects.
3. **Global Entity ID Namespace & Orthogonal Epistemic Model:**
   - Single document-wide ID registry across `beat`, `unbeat`, `sound`, `sound_class`, `boundary`, `segment`, `span`, and `approval` (both active and retired IDs forbid reuse).
   - `Origin` (`"import" | "model" | "rule" | "user"`) records immutable creation lineage.
   - `Verdict` (`"placed" | "adjusted" | "confirmed"`) marks human truth (including facet-scoped `Sound` verdicts). Bulk `shift_beats` shifts unverified marks without fabricating human verdicts.
   - `Approval` (`keep`) locks a section's full musical projection (`computeIntervalMusicalDigest` + frozen `keep` boundary conditions in `projectTempoMap`) against automated offers while permitting direct user edits (`modifiedSinceApproval: true`).
4. **Unified Pure Transaction Interpreter & Event-Atomic `retract`:** One ordered `TransactionOp` interpreter powers direct user edits, `validateOffer`, `previewOffer`, `accept_offer` (with explicit `OfferChallenge` authorization), and `foldTrace` (atomically skipping whole dependent transactions into `orphanedTransactions` on selective `retract`).

## License

MIT
