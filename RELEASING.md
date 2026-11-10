# Releasing

A release `X.Y.Z` is the tag `vX.Y.Z` on `main`. It ships the npm package `ensomi`, published with provenance
through npm trusted publishing, and a GitHub release for the tag.

On the tag push, [`publish.yml`](.github/workflows/publish.yml):

1. runs the tests (`npm test`);
2. checks that the tag is `v` followed by the `version` in `package.json`;
3. lists what the tarball holds (`npm pack --dry-run`) and publishes it (`npm publish`);
4. creates the GitHub release for the tag, with generated notes.

Credentials stay on the owner's machine. The workflow proves to npm which repository, workflow and environment
it runs in (OIDC), and npm accepts that for the trusted publisher configured below, so no token is stored in
GitHub. The only token a job uses is the one GitHub creates for that run, to create the release.

## One-time setup

Do steps 1 to 3 just before the first release, so that the stub of step 1 is not the only version for long.

### npm

npm takes a trusted publisher only for a package that exists. So version `0.0.0` is published once by hand,
from the owner's Mac, as a deprecated stub; `0.0.1` is then already the package built by GitHub Actions, with
provenance.

1. With npm 11.5.1 or later, logged in to npm as the owner (`npm login`; `npm whoami` prints `s3d-i`):

   ```sh
   cd "$(mktemp -d)"
   cat > package.json <<'EOF'
   {
     "name": "ensomi",
     "version": "0.0.0",
     "description": "Published by hand to enable trusted publishing; use 0.0.1 or later.",
     "license": "MIT",
     "repository": { "type": "git", "url": "git+https://github.com/ensomi-labs/ensomi.git" }
   }
   EOF
   npm publish --access public
   npm deprecate ensomi@0.0.0 "Published by hand to enable trusted publishing; use 0.0.1 or later."
   ```

2. Open <https://www.npmjs.com/package/ensomi/access> (the package's Settings). Under Trusted Publisher choose
   GitHub Actions, enter the values below and save:

   | Field | Value |
   | --- | --- |
   | Organization or user | `ensomi-labs` |
   | Repository | `ensomi` |
   | Workflow filename | `publish.yml` |
   | Environment name | `npm` |

   Then, under Publishing access on the same page, choose "Require two-factor authentication and disallow
   tokens": the workflow needs no token.

### GitHub

3. Create the `npm` environment, limited to tags that match `v*` (without this the first release creates it
   with no rule):

   ```sh
   gh api -X PUT repos/ensomi-labs/ensomi/environments/npm \
     -F 'deployment_branch_policy[protected_branches]=false' -F 'deployment_branch_policy[custom_branch_policies]=true'
   gh api -X POST repos/ensomi-labs/ensomi/environments/npm/deployment-branch-policies -f name='v*' -f type=tag
   ```

## Cutting a release

4. For a version other than the one in `package.json`: set `"version"` there (`npm version X.Y.Z
   --no-git-tag-version` does it; there is no lockfile to update), bring `README.md` in step with what the
   release does, commit and push `main`. Wait for the `Check` workflow to pass on that commit.

5. Tag `main`'s head and push the tag:

   ```sh
   git switch main && git pull
   git tag -a vX.Y.Z -m vX.Y.Z && git push origin vX.Y.Z
   gh run list --workflow publish.yml --limit 1    # then gh run watch <id>
   ```

6. Afterwards `npm view ensomi version` prints `X.Y.Z`, <https://www.npmjs.com/package/ensomi> shows the
   provenance of that version, and the release is at <https://github.com/ensomi-labs/ensomi/releases>.

## If a step fails

- **The tests or the version check fail.** Nothing was published. Remove the tag
  (`git push origin :refs/tags/vX.Y.Z && git tag -d vX.Y.Z`), fix `main`, and start again from step 5.
- **`npm publish` fails** (for instance before the trusted publisher or the `npm` environment exists). Nothing
  was published, and the `github-release` job did not run. If the cause is outside the repository, fix it and
  re-run the failed job from the run page: it builds the same tag. If the cause is in the repository, remove
  the tag as above, fix `main`, and tag again.
- **`npm publish` succeeded and `github-release` failed.** Re-run that job from the run page; do not move the
  tag.

npm never accepts the same version twice. Once `X.Y.Z` is on npm its tag stays where it is, and a fix is the
next version.

The workflow publishes to the `latest` dist-tag only: it passes no `--tag`, which a prerelease version such as
`X.Y.Z-rc.1` would need.
