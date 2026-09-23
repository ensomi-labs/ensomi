---
name: ensomi-prose-standard
description: Write, review, or trim Ensomi app docs, skills, Swift comments, and diagnostics. Preserve technical contracts and remove repetition, stale plans, and authoring-session references.
metadata:
  commit: 5c11794f08016478c1edbe2df27464f649b639c8
---

# Ensomi prose standard

Use the scope and edit authority in the request. A review reports findings; a
cleanup or rewrite applies them. Do not require a separate scope declaration
when the request already identifies the work.

## Ground claims in their owner

Read [AGENTS.md](../../../AGENTS.md), the target passage, and the source or test
that establishes its behavior. `project.yml` owns build settings and dependency
versions; Swift code and its tests establish implemented behavior. Edit an owner
before regenerating the Xcode project. Do not copy generated API documentation.

Keep README focused on purpose, setup, configuration, and runnable commands.
Put current cross-module contracts in [architecture](../../../docs/architecture.md),
and non-obvious ownership or failure constraints beside the relevant Swift code.
Keep proposals and execution plans separate from implemented behavior. Use the
[notes skill](../ensomi-archive-agent-notes/SKILL.md) for requested working records.

## Preserve facts, remove narration

- Preserve conditions, ordering, units, ownership, failures, and exceptions. Audio
  time, host time, render time, and judgement time are distinct; do not collapse
  them into an unspecified timestamp when explaining a contract.
- Explain actor isolation, cancellation, buffer lifetime, stream watermarks, or
  permission constraints when their consequence is not apparent from the code.
  Remove comments that merely restate adjacent declarations or control flow.
- A reader must understand product prose without the originating chat, PR review,
  terminal output, or another branch. Replace those references with the actual
  mechanism and its rationale. Do not turn cleanup history into product docs.
- Keep measured findings scoped to the device, build, workload, and measurement
  method. Fewer observable fields alone do not establish a frame-rate improvement.
- Exclude personal paths, recording inventories, credentials, and raw run output.
  A necessary conclusion must remain understandable without private fixtures.

Keep authored Markdown pinned to its repository baseline in frontmatter; use
`metadata.commit` in skills. Prefer one concise document for one purpose. Do not
add tutorials, inventories, or duplicate instruction files merely to explain code.

Check local links, source-backed claims, and `git diff --check`. Changing a CLI
option, protocol field, or tested diagnostic also needs its behavior checked;
ordinary prose edits do not require an app rebuild.
