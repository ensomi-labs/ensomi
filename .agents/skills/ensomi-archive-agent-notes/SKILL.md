---
name: ensomi-archive-agent-notes
description: Keep requested persistent Ensomi working notes on a separate agent-notes branch. Use for recording, reviewing, revising, archiving, or consolidating app investigation and decision history; keep shipped product contracts in code, tests, and docs.
metadata:
  commit: 5c11794f08016478c1edbe2df27464f649b639c8
---

# Ensomi Agent Notes

Use this workflow when persistent working notes are requested. Ordinary app work
does not require a note. Capture decisions, investigation evidence, and unfinished
work that will help a later session; promote durable product contracts to their
owning code, tests, or `docs/architecture.md`.

## Separate storage

Notes live on the orphan `agent-notes` branch, with no merge base with `main`.
Its tree contains only `.gitignore` and Markdown under `artifacts/agent-notes/`.
Locate its dedicated linked worktree through `git worktree list --porcelain`.
Never write notes in a product worktree, merge or cherry-pick note commits into a
product branch, or open a notes PR against `main`. Product docs must remain
understandable without the notes branch.

Read committed notes by default; identify worktree drafts as uncommitted when
the request concerns them. Mutations use the dedicated notes worktree. If storage
is missing, report that fact; initialize it only when setup is within the user's
request, following [the bootstrap procedure](references/bootstrap-agent-notes.md).
Existing authorization counts; do not ask again for an already requested action.

## Write a useful note

Search related notes before creating one. Use one Markdown file at
`artifacts/agent-notes/<status>/YYYY-MM-DD-topic.md`, with a stable filename and ID
across moves. Start with YAML frontmatter:

```yaml
---
commit: <full product baseline commit>
id: YYYY-MM-DD-topic
status: proposed
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

State the bounded question or decision, evidence, alternatives that matter,
rationale, unresolved risks, and next verification. For app investigations,
record the affected platform, reproducible behavior, relevant source or tests,
and verified outcome. Distinguish observations from inference. Identify any
uncommitted product changes; completed claims need a recoverable product commit.

Use repository-relative paths and immutable commit IDs. Exclude private fixture
inventories, user paths, credentials, recordings, raw payloads, and generated
reports. Summarize the relevant observation so a missing local artifact does not
make the note unintelligible.

## Preserve decision history

- `proposed`: an open direction or investigation.
- `accepted`: a human-approved direction. Record `accepted_revision` as the full
  note commit whose content was approved, plus a self-contained
  `acceptance_reference`. Move the approved note and update metadata without
  changing its decision. A material revision returns it to `proposed` and removes
  current acceptance metadata; Git retains the earlier approved version.
- `implemented`: an accepted direction whose completion and verification are
  supported by a cited product commit. Implementation does not erase the original
  rationale or remaining limitations.
- `rejected`: a declined direction; retain the reason and reconsideration
  condition. Evidence alone is not human acceptance or rejection unless the
  request authorizes applying an already recorded objective criterion.
- `archived`: an implemented or rejected note with no active follow-up and useful
  historical value. Record `archived_from`, `archived`, and `archive_reason`.
  Preserve its body and links as a historical snapshot. An authorized restoration
  returns it to `archived_from`; a newly reconsidered decision gets a new proposed
  note linked to the old ID.

Apply only lifecycle changes covered by the request; a review remains read-only.
Move the file and update status together. Do not archive by age or size. During
consolidation, preserve unique constraints, rationale, failed alternatives,
verification facts, and reconsideration conditions in the successor or product
owner before declaring full supersession. Link notes by ID, keep partially
superseded decisions distinct, and preserve archived historical content.

Delete only within the requested scope after checking that no unique decision
value or unresolved inbound reference remains. Repair active references with the
deletion. An archived reference must retain an immutable commit locator; otherwise
include its restoration and repair in the authorized scope before deleting its
target. Never reuse a deleted ID or rewrite history during ordinary cleanup.

## Verify and finish

Before editing, verify the orphan branch and tree allowlist, and check for
overlapping uncommitted changes in its worktree. Before committing, check that
the note branch has not advanced unexpectedly, status matches the directory,
IDs are unique, references resolve, prohibited content is absent, and only the
requested note changes are staged. Run `git diff --check` in the notes worktree.

A request to persist or update tracked notes includes the scoped local note
commit. Respect any instruction to leave changes uncommitted. Push only when the
user requests publication. Report changed notes, lifecycle changes, commit or
publication state, and any unresolved evidence gaps.
