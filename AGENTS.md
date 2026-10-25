---
commit: e376342f9bc8df64a30e7fbc4c77a042042fce25
---

# Ensomi repository guidance

`project.yml` owns targets, build settings, and dependency versions. Regenerate
`Ensomi.xcodeproj` with `xcodegen generate` after changing it. Keep domain and
service logic in `EnsomiCore`, shared SwiftUI in `EnsomiUI`, and platform wiring in
`Apps`. Use observation for view-consumed state; keep processing buffers and
service internals outside observation tracking.

Validate changed behavior with focused tests. Prefer concrete state transitions,
domain mapping, and regression cases over implementation-mirroring tests or new
test infrastructure. Build commands are in [README.md](README.md).

## UI and UX

For user-facing interface changes, read [Design guidelines](docs/design-guidelines.md)
and the relevant Apple Human Interface Guidelines before editing. Apply platform
conventions to navigation, settings, controls, accessibility, and layout. Keep
intentional gameplay presentation distinct from application chrome. Verify API
availability against `project.yml`, inspect affected screens, and report any UI
checks that could not be run. Keep UI/UX decisions in the design document; keep
service and domain contracts in `docs/architecture.md`.

## macOS builds and installation

Build, test and install with `Tools/mac.sh`, not `xcodebuild` directly; it keeps
every output under `.build/xcode/` and `Tools/mac.sh help` lists its commands.
`test` and `build dev|ios|cli|release` share the Debug `dev` cache, and a lock
makes a second build wait for the first. `install` builds the `play` channel, the
Debug configuration compiled with `-O` and whole-module optimisation. It refuses
while the app runs and never quits it, replaces `/Applications/EnsomiMac.app`,
keeps the previous app as one rollback zip (`Tools/mac.sh rollback`) and records
the commit in the app, marked `-modified` when the app's sources have uncommitted
changes or a local runtime is set. Recording never blocks an install.

Keep bundle ID `io.ensomi.mac` and the Debug signing settings. Permission-sensitive
runs use `/Applications/EnsomiMac.app`; launch only that path, never a build
product or an older copy. Install Release only after configuring a stable signing
identity. Channels installed side by side need distinct names and bundle IDs.

Every task that builds ends with `Tools/mac.sh clean` and reports
`Tools/mac.sh status`. After a merge or a pin change on `dev/swift`, an agent may
run `Tools/mac.sh install` once the human has quit the app; otherwise it reports
the `STALE` and `MODIFIED` lines that `status` prints: `STALE` when the app's
sources changed since the installed app's commit, `MODIFIED` when that app was
built from uncommitted changes or a local runtime. Put renders and screenshots
for review in `artifacts/renders/<topic>/`, not `.build/`.

Before requesting capture permissions, remove the stale app copies `status` lists:
`clean` removes those under `.build/xcode/`, and the human decides on the others.
To reset capture access, run `tccutil reset ScreenCapture io.ensomi.mac`, launch
the installed app, and grant permission again.

## Documentation

Keep durable product contracts in [docs/architecture.md](docs/architecture.md).
Pin the repository baseline commit in authored Markdown frontmatter. Write one
clearly named document by default; keep current behavior, proposed designs, and
execution plans separate. Exclude private fixture inventories, local paths, run
reports, and generated artifacts from committed docs.

Use repository skills when their scope matches the task:

| Task | Skill |
| --- | --- |
| Docs, comments, and prose cleanup | [Prose standard](.agents/skills/ensomi-prose-standard/SKILL.md) |
| Dead code, redundancy, and observation audits | [Find simplifications](.agents/skills/ensomi-find-simplifications/SKILL.md) |
| Final checks and publication | [Pre-push checks](.agents/skills/ensomi-pre-push-checks/SKILL.md) |

Skill baseline pins use `metadata.commit` in YAML frontmatter. Product docs must
remain self-contained.

## Facts shared with other Ensomi repositories

Some facts this repository states are defined in another Ensomi repository, and
some defined here are stated elsewhere: the released model's files, revision and
licence, versions and pins, protocol messages, generation rules. ensomi-model's
[`docs/ecosystem.md`](https://github.com/ensomi-labs/ensomi-model/blob/main/docs/ecosystem.md)
says where each kind is defined and what a change affects in the other
repositories.
Before such a change, follow ensomi-model's
[`ensomi-cross-repo-changes`](https://github.com/ensomi-labs/ensomi-model/blob/main/.agents/skills/ensomi-cross-repo-changes/SKILL.md)
skill.
