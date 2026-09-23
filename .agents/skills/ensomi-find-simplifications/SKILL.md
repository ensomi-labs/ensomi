---
name: ensomi-find-simplifications
description: Audit or implement requested Ensomi app cleanup, including dead code, redundant state, unnecessary observation, duplicate ownership, and stale documentation. Require caller evidence and preserve live app behavior.
metadata:
  commit: 5c11794f08016478c1edbe2df27464f649b639c8
---

# Find Ensomi app simplifications

Follow the request's authority: audits identify candidates; implementation and
cleanup requests authorize the scoped changes. Read the relevant source and
tests before selecting a deletion. For a broad cleanup, divide independent areas
among agents when useful and keep edits in separate files.

## Establish actual use

Trace from `Apps/` through `Sources/EnsomiUI` into `Sources/EnsomiCore`; inspect
debug windows, platform conditionals, tools, and tests as separate consumers.
Use `rg` to find symbols, persisted keys, wire fields, and file paths. A missing
direct call does not prove a dynamically selected or serialized contract is dead.

Treat previews and tests as evidence of purpose, not automatic reasons to retain
an unreachable demo. A closed obsolete feature can be removed with its dedicated
fixtures and mirror tests; retain tests protecting behavior still owned by the app.
Check public framework APIs for external use before claiming exhaustive removal.

## Prefer changes with a concrete benefit

- Remove unused feature clusters and duplicate sources of truth. `project.yml`
  owns target configuration; regenerate the project instead of editing both.
- Keep `@Observable` for view-consumed state. Read SwiftUI and computed-property
  dependencies before adding `@ObservationIgnored`: a private field can still
  drive a public computed property. Processing buffers, tasks, and engine state
  should publish deliberate UI snapshots rather than every internal mutation.
- Inspect repeated frame work, sorting, copying, and feedback publication. Avoid
  no-op updates while preserving input feedback, clock progression, and expiry.
  State what work was removed; claim speedups only with measurements.
- Map each task, actor, worker, queue, and generation check to its purpose before
  merging them. Preserve capture teardown, stale callback rejection, input order,
  stream completeness, and native-session serialization.
- Distinguish same-process typed handoffs from external data. Protobuf frames,
  `.osu` files, database records, audio metadata, and cached indexes still require
  validation even if a local happy-path test never exercises failure.
- Prefer existing APIs when they remove more code than their adapters add. Verify
  macOS/iOS availability, package pins, and binary-framework support. Do not
  replace intentional Swift/native fallback behavior by resemblance alone.

Keep private audio, `.build/`, and generated reports outside broad discovery.
Do not remove a library folder, reset permissions, or change app identity merely
because source cleanup is authorized. An explicitly requested breaking migration
does not need unrequested compatibility aliases or historical prose.

For each significant change, identify the removed cost, surviving callers,
behavior affected, strongest reason to keep it, and smallest useful check. Reject
candidates that only move complexity or break a still-owned contract. Apply
[focused verification](../ensomi-pre-push-checks/SKILL.md) and use the
[prose standard](../ensomi-prose-standard/SKILL.md) for documentation changes.
