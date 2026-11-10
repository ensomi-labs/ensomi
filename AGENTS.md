# Agent guide

`ensomi` is the developer kit for Ensomi models: an npm package of plain ECMAScript modules. Start with
`README.md`, which says what the current release does.

## Checks

- `node --test` runs every file in `test/` and must pass before a commit. `npm test` runs the same command.
- `npm pack --dry-run` lists what would be published: `package.json`, `README.md`, `LICENSE`, `index.d.ts` and
  `src/`, nothing else.
- CI (`.github/workflows/check.yml`) runs both on Node 22 and 24.

## Rules

- **Zero dependencies, no build step.** The source is `.js` ECMAScript modules with JSDoc comments, and the
  types are the hand-written `index.d.ts`. No `dependencies` or `devDependencies`, no lockfile, no compiler, no
  bundler. Tests use only `node:test` and `node:assert/strict`.
- **Synthetic fixtures only.** Write every test input by hand. Nothing from a real beatmap, a real song or
  private data goes into this repository: no copied lines, titles or names.
- **No tokens or secrets** in the repository or its workflows. Publishing uses npm trusted publishing (OIDC),
  which needs none.
- **Releases follow [`RELEASING.md`](RELEASING.md).** Do not publish, tag or change the version outside it.
- **Public text states only what a release does.** `README.md` and the package description describe the
  current release; they do not announce later ones or claim accuracy, speed or compatibility that has not
  been tested. A known loss or limit is stated plainly.

## When changing behaviour

- A change of behaviour comes with a test that fails without it.
- Keep `index.d.ts`, the JSDoc in `src/` and `README.md` in step with the code; the three functions are
  exported from `src/index.js`.
- The API is unstable while the version is `0.0.x`, but say in the commit message when a change breaks it.
