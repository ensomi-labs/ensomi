---
name: ensomi-pre-push-checks
description: Select and verify evidence for a completed Ensomi app change before handoff, an authorized push, or PR readiness. Match checks to Swift, Xcode, protocol, alignment, documentation, or skill changes.
metadata:
  commit: 5c11794f08016478c1edbe2df27464f649b639c8
---

# Verify an Ensomi app change

Inspect the actual final diff, including intended untracked files and renames.
For publication, establish the destination branch and its real base; do not assume
the default branch is the parent of a stacked change. This skill does not grant
permission to commit, push, rewrite history, or change PR state.

## Select checks from behavior

| Changed area | Relevant evidence |
| --- | --- |
| Gameplay, observation, input, offsets | Owning `Mania4K*Tests`; check immediate input, idle expiry, and UI consumers of changed state. |
| Recognition, local library, capture lifecycle | Owning model/service tests; hardware capture requires a separate actual run. |
| Inference/protobuf | Codec and live-session model tests; inspect the resolved package version. A round trip is not a live service handshake. |
| Ambient matching/features/cache | Owning `AmbientSync*`, `MicFeature*` tests; run synthetic Sonalign parity for matching/backend changes. |
| Targets, dependencies, shared platform code | Regenerate the project, resolve packages, run macOS tests, and build the iOS simulator and affected tools/configurations. |
| Docs or skills | Check claims, relative links, frontmatter, UI metadata, and whitespace; run the installed skill-creator validator for changed skills. |

Use an owning test class first, or omit `-only-testing` for a cross-cutting change:

```sh
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' -derivedDataPath .build/EnsomiValidation test -only-testing:EnsomiCoreTests/Mania4KPlaySessionModelTests
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiIOS -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/EnsomiValidation build CODE_SIGNING_ALLOWED=NO
```

Build the `EnsomiACRCloudDebugCLI` scheme when its source or dependencies change.
Check Release when conditional code changes; an unsigned validation build may use
`CODE_SIGNING_ALLOWED=NO`, but is not an installed permission-tested application.
Follow [AGENTS.md](../../../AGENTS.md#macos-installation) for capture runs.
Run builds sequentially when they share derived data.

`python3 Tools/check_sonalign_parity.py --suites synthetic` compares the Swift
engine with the released replay CLI; installation is in [README](../../../README.md).
Private suites require explicit local inputs. Report skipped fixtures and missing
services or hardware as limits of the evidence, not passes.

Do not rerun passing checks unless relevant source, generated output, dependencies,
or environment changed. Do not hide a failure by weakening assertions or silently
excluding a test. Finish with `git diff --check`; include new files in review.

## Publication, when requested

Keep `artifacts/agent-notes/` out of product commits, including add-then-delete
history. Use the [notes skill](../ensomi-archive-agent-notes/SKILL.md) for that orphan
branch; it has no product merge base or product test claim.

Before an authorized force-push, fetch and record the remote branch OID; use an
exact `--force-with-lease=<branch>:<observed-oid>`, never raw `--force`. Verify the
published head and any required CI/review state before a readiness claim. Local
tests alone establish neither remote CI success nor permission to publish.

Report what changed, checks actually run, failures/skips, and meaningful untested
paths. Keep the report proportional to the task; do not generate a check report
file unless requested.
