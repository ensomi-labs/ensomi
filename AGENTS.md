---
commit: 5c11794f08016478c1edbe2df27464f649b639c8
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

## macOS installation

Keep bundle ID `io.ensomi.mac`, signing identity, and launch path stable within a
channel. Permission-sensitive runs use `/Applications/EnsomiMac.app`; do not
launch from build output or stale copies. Quit the app before replacing that
bundle, then launch the installed path. Debug disables signing; use Release only
after configuring a stable signing identity. Coexisting channels need distinct
names and bundle IDs.

```sh
set -e
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -configuration Debug -destination 'platform=macOS' -derivedDataPath .build/EnsomiMacInstallDerivedData build
test -d .build/EnsomiMacInstallDerivedData/Build/Products/Debug/EnsomiMac.app
pkill -x EnsomiMac || true
rm -rf /Applications/EnsomiMac.app
ditto .build/EnsomiMacInstallDerivedData/Build/Products/Debug/EnsomiMac.app /Applications/EnsomiMac.app
open /Applications/EnsomiMac.app
```

Remove stale same-named app bundles before requesting capture permissions. To
reset capture access, run `tccutil reset ScreenCapture io.ensomi.mac`, launch the
canonical app, and grant permission again.

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
| Final checks and authorized publication | [Pre-push checks](.agents/skills/ensomi-pre-push-checks/SKILL.md) |
| Persistent working notes on `agent-notes` | [Agent Notes](.agents/skills/ensomi-archive-agent-notes/SKILL.md) |

Skill baseline pins use `metadata.commit` in YAML frontmatter. Product docs must
remain self-contained without the notes branch.
