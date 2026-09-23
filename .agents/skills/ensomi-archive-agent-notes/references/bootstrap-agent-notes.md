---
commit: 5c11794f08016478c1edbe2df27464f649b639c8
---

# Initialize Agent Note storage

Use only when creating or repairing note storage is within the user's request.
Run from a product worktree. Setup creates a separate Git branch and worktree;
it does not modify product files or publish a remote ref.

## Reuse existing history

Inspect the local ref and registered worktrees:

```sh
git show-ref --verify refs/heads/agent-notes
git worktree list --porcelain
git remote -v
```

If a remote is configured, check its `refs/heads/agent-notes` with
`git ls-remote --exit-code <remote> refs/heads/agent-notes`. Exit 2 with no match
means absent; authentication or network failure does not prove absence. Resolve
uncertainty before creating a new orphan history. If multiple remotes might own
notes, establish the intended source before proceeding.

- An existing valid local ref and registered worktree need no setup.
- For a local ref without a worktree, validate it and attach it with
  `git worktree add <notes-worktree> agent-notes`.
- If only a remote ref exists, fetch it and create the local branch at that exact
  commit, then validate and attach it. Do not replace its history.
- If both exist, confirm the local ref is equal to or descends from the remote
  ref. Reconcile divergence or missing remote commits before continuing; do not
  reset an existing ref or replace a dirty worktree.

Choose an unused notes-worktree directory outside all existing worktrees,
honoring any user-specified location. Do not overwrite a populated directory.

## Create an orphan branch when no notes ref exists

After confirming that neither local nor applicable remote refs exist:

```sh
git worktree add --orphan -b agent-notes <notes-worktree>
```

Create only this `.gitignore` in that new worktree:

```gitignore
/*
!/.gitignore
!/artifacts/
/artifacts/*
!/artifacts/agent-notes/
!/artifacts/agent-notes/**
```

Stage and commit that file as the initial notes commit. Do not copy product
files into the worktree. The allowlist permits the notes directory; before any
later commit, additionally verify that all tracked notes are Markdown.

## Verify isolation

Confirm the worktree reports branch `agent-notes`, there is exactly one linked
worktree for it, and `git merge-base main agent-notes` returns no commit (exit 1).
Inspect its tree: only `.gitignore` and Markdown under `artifacts/agent-notes/`
may be tracked. With `git check-ignore`, verify that an unrelated root probe is
ignored and a Markdown path under `artifacts/agent-notes/` is allowed. Product
worktrees must still ignore `artifacts/agent-notes/` and retain their prior state.
Report the created or reused store, notes commit, and isolation checks. A push
requires the user's request to publish.
