# Android CI and release: proposed design

Proposed on 2026-10-31. Nothing here is enabled, and until the owner takes this design AGENTS.md stands: builds run on the
owner's Mac. Each point is decided so that [the steps](#9-order-of-work) can be carried out in order; that section marks what
only the owner can do.

| Topic | Decision |
| --- | --- |
| CI | `android.yml` on `dev/android` only, on `ubuntu-24.04`: every build type, the `androidTest` APK, JVM tests, lint, a 16 KB check. No secrets, no APK artifacts. |
| Runtime AAR | A runtime release asset, fetched by Gradle and pinned by SHA-256. `ensomi.runtime.aar` stays as the local override. |
| Emulators | No hosted runner can run an arm64 image, so arm64 AVDs stay on the Mac. One hosted experiment with x86_64 translation. No self-hosted runner, no device farm. |
| Phones | The human-run kit, built on the Mac from a commit whose CI passed. |
| Release | A signed arm64-v8a APK built by CI from a tag. Draft: internal. Prerelease: public, on the owner's word. No AAB yet. |
| Versions | Tags `android-vX.Y.Z[-rc.N]`; `versionCode` a plain counter in the catalog. |
| Signing | One key for all channels, used only by `apksigner` in a job that runs no repository code. |
| Testers | The draft release, or the owner forwards the zip; the SHA-256 is checked. |
| Coordinated release | Runtime and model first; one commit moves the app's pins; the app goes last. |

## 1. CI on `dev/android`

**Where the file lives.** `.github/workflows/android.yml` on `dev/android`. "Each workflow run will use the version of the
workflow that is present in the associated commit SHA or Git ref of the event", so pushes, pull requests and tags need nothing
on `dev/swift`. Only `schedule` and `workflow_dispatch` need the file on the default branch, and nothing here uses them.

**Triggers.** Pushes to `dev/android`, `claude/**` and `codex/**`, and pull requests into `dev/android`, unless they touch only
docs. Agent branches are in so that an agent without the toolchain gets a build from a push. Swift branches have no
`android.yml`, so they start nothing. There is no required check: `dev/android` keeps taking direct pushes and its ruleset stays.

**What runs.** One job: `assembleDebug assembleDebugAndroidTest assembleChecks assembleRelease testDebugUnitTest lint`, the
16 KB check, and a check that `scripts/phone-checks.sh` and `ModelSource.kt` pin the same model. The
[appendix](#appendix-the-first-workflow) is the complete file.

**Runner.** `ubuntu-24.04`: 4 CPUs, 16 GB, free on a public repository. Its image already holds the pinned SDK packages; the
workflow still installs the catalog's pins, so they stay written in one place.

**Gradle cache.** `setup-gradle` with `cache-provider: basic` (MIT), not its proprietary default. By default it writes only on
the default branch, `dev/swift`, so the workflow sets `cache-read-only` itself. A new agent branch builds cold once: a run
restores caches only from its own branch, the default branch and a pull request's base.

**Forks and secrets.** The workflow has no secrets and runs with `contents: read`. A fork's pull request gets a read-only token
and no secrets, and first-time contributors wait for approval (today's setting). `pull_request_target` and `workflow_run` are
never used. No APK goes into artifacts: anyone signed in to GitHub can read a public repository's artifacts, which would
distribute `checks`.

**Beside `dev/swift`'s CI.** `ci.yml` exists only on `dev/swift` and `android.yml` only on `dev/android`, so neither sees the
other's pushes. They share only the repository's cache allowance.

## 2. The runtime AAR

This blocks all CI: the app does not compile without the runtime's classes.

| Option | Verdict |
| --- | --- |
| Runtime release asset, fetched by Gradle, checked by SHA-256 | **Chosen.** No token for public reads, no runtime toolchain in the app's CI, and how Swift already consumes the runtime. |
| GitHub Packages (Maven) | No: "You need an access token to publish, install, and delete private, internal, and public packages", forks' builds included. |
| Maven Central | Not now: namespace verification and signing for one consumer. |
| Build in the app's CI from a pinned commit | No: Rust, cargo-ndk and an ONNX Runtime source build in every cold run. |

**The app's side.** Gradle resolves the asset as an Ivy module, so caching and checking are Gradle's own. In
`settings.gradle.kts`, under `dependencyResolutionManagement.repositories`:

```kotlin
// The runtime's AAR, an asset of a release of ensomi-labs/runtime. Nothing else comes from here.
exclusiveContent {
    forRepository {
        ivy("https://github.com/ensomi-labs/runtime/releases/download") {
            name = "ensomi-runtime-releases"
            patternLayout { artifact("[revision]/[artifact].[ext]") }
            metadataSources { artifact() }
        }
    }
    filter { includeModule("io.ensomi", "ensomi-runtime") }
}
```

In `gradle/libs.versions.toml`, replacing the comment there:

```toml
[versions]
ensomiRuntime = "android-aar-affb60a" # a tag of ensomi-labs/runtime; its AAR's SHA-256 is in verification-metadata.xml
[libraries]
ensomi-runtime = { module = "io.ensomi:ensomi-runtime", version.ref = "ensomiRuntime" }
```

A new `gradle/verification-metadata.xml` pins that one file and leaves other modules as they are today:

```xml
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <trust group="^(?!io\.ensomi$).*$" regex="true"/>
      </trusted-artifacts>
   </configuration>
   <components>
      <component group="io.ensomi" name="ensomi-runtime" version="android-aar-affb60a">
         <artifact name="ensomi-runtime-android-aar-affb60a.aar">
            <sha256 value="THE-AAR-SHA-256" origin="the release's digest"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
```

In `app/build.gradle.kts`, `runtimeAar` becomes null when `ensomi.runtime.aar` is unset, which keeps the local override:

```kotlin
if (localRuntimeAar != null) implementation(files(localRuntimeAar))
else implementation(libs.ensomi.runtime) { artifact { type = "aar"; extension = "aar" } }
```

Tested with Gradle 9.8.2 on the `v0.3.2` xcframework asset: 46.8 MB through GitHub's redirect with no token; the right SHA-256
builds, a wrong or missing one stops the build. Not yet through AGP; JNA's line uses the same `artifact` form.

**The runtime's side, first (step 1).** A prerelease `android-aar-affb60a` with the AAR the Mac builds from `codex/android-aar`.
No runtime workflow fires (`release.yml` takes `v*`, `ci.yml` branches), and `main`, npm and `Package.swift` stay untouched. It
has no CI build or attestation, and claims 0.3.2 with API that 0.3.2 lacks, so it serves CI and internal builds only. The plan
was a runtime CI job with the first release carrying the AAR, so this needs the owner's yes; without it, steps 2 and 3 wait for
step 8.

**Then a release (step 8).** Before any public APK, a `vX.Y.Z` release carries the AAR. That asks of the runtime:

- `codex/android-aar` merged, and version 0.4.0. `r2-1.0.1` declares `min_runtime` 0.3.0 and no upper bound, so it still opens.
- An `android` job in `ci.yml`, with `scripts/build-android.sh` made portable to `ubuntu-24.04` (its `darwin-x86_64` path,
  `shasum`, default paths) and `target/onnxruntime-android` cached.
- `release.yml` attaching and attesting the AAR, and `RELEASING.md` saying so.
- JNA's rules in `android/consumer-rules.pro`, and notices for the Rust crates in the AAR.

## 3. Emulators and devices

| Option | Verdict | Why |
| --- | --- | --- |
| arm64 image on `ubuntu-24.04` | No | The emulator refuses an arm64 AVD of API 28 or later on an x86_64 host: "System image must match the host architecture" (`main-emulator.cpp`). |
| arm64 image on `ubuntu-24.04-arm` | No | The SDK repository has no Linux arm64 emulator: its hosts are Linux x64, macOS x64 and aarch64, Windows x64. |
| arm64 image on hosted Apple silicon | No | GitHub: "Nested-virtualization is not supported due to the limitation of Apple's Virtualization Framework." Google: "You can't run a VM-accelerated emulator inside another VM." |
| x86_64 image on `ubuntu-24.04` with KVM | **Try once** | "GitHub-hosted Linux runners support hardware acceleration for Android SDK tools." The API 34 to 37.0 x86_64 images declare `arm64-v8a` through `libndk_translation.so` in their `build.prop`, so this arm64-only APK should install. API 33's does not. |
| Self-hosted Mac runner | No | GitHub: they "should almost never be used for public repositories". The Mac holds private data; a fork's pull request must not reach it. |
| Firebase Test Lab | No | Deprecated, shut down on 2027-09-30; its replacement requires billing. |

**The experiment (step 5).** On a branch, with the API 36 x86_64 image: install the debug APK, launch it, read
`EnsomiDiagnostics`, run `RuntimeCheck`; if that holds, launch the release APK, which catches what R8 broke. Translated code says
nothing about timing, and whether JNA and ONNX Runtime run under `libndk_translation` is unknown. Keep it if a week of runs is
stable; otherwise drop it and say why here.

| Where | What runs | When |
| --- | --- | --- |
| `ubuntu-24.04` | Builds, JVM tests, lint, 16 KB check | Every push |
| `ubuntu-24.04`, x86_64 with KVM | The experiment, if it holds | `dev/android` and tags |
| Mac, arm64 AVDs `ensomi-36` and `ensomi-36-16k` | Screens, flows, real 16 KB loading, the phone checks on an emulator, the release APK's smoke test | A build brief through Astra |
| Phones | Timing, audio, HyperOS, the checklist | A person, before a release |

**The kit.** Built on the Mac from a commit whose CI passed, without the override, by `scripts/make-phone-kit.sh`, which should
refuse a dirty tree and record the commit and pins in a `KIT.txt`. Not from CI: an artifact would be public, CI's debug key is
new on each run so kits would not install over each other, and the model, click tracks and songs need the Mac anyway.

## 4. What a release is

One signed arm64-v8a APK built by CI from a tag, on a GitHub release with its SHA-256 and `mapping.txt`. The model is never in it.
No AAB until a store is planned.

| Channel | What | Who | When |
| --- | --- | --- | --- |
| Internal | A draft release | Write access, so both testers ("Only users with push access will receive listings for draft releases") | Step 7 |
| Public | That draft, published as a prerelease, not rebuilt | Anyone | Owner's decision, step 9 |
| Stores | An AAB and a listing | — | Postponed |

**Tags.** `android-vX.Y.Z` and `android-vX.Y.Z-rc.N`; bare `v*` stays Swift's. The version numbers are independent, and releases
use `--latest=false`, so Swift's `v0.1.0` keeps Latest.

**Versions.** `appVersionName` and `appVersionCode` move into the catalog. `versionCode` goes up by one for every tag and is never
reused. The release job fails unless the tag is `android-v<appVersionName>` and the code is above the last tag's.

**Changelog.** The annotated tag's message. The workflow appends the commit, the SHA-256 of the APK and the certificate, the runtime
and model tags, and links to the model card and the site's data provenance page. `--generate-notes` would compare histories that
share nothing.

**Notices.** The Notices screen has the model's and ONNX Runtime's. A public APK also needs AndroidX, Compose, Kotlin, kotlinx, JNA
(under Apache-2.0), libc++ and the Rust crates: a generated list for the Kotlin side, the AAR for the native side.

**R8.** `release` today is unsigned and without R8. Google: "You should always enable optimization for your app's release build".
On AGP 9.3 and later that is `optimization { enable = true }`. Add JNA's rules, widened from its FAQ: `-dontwarn java.awt.**`,
`-keep class com.sun.jna.** { *; }`, `-keep class * extends com.sun.jna.** { *; }`. JNA's AAR ships none, and the runtime's consumer
rules keep only `io.ensomi.runtime.**`. `checks` has no R8, so phone-check numbers do not cover this: the release APK gets its own
smoke test on the Mac AVD and by hand on both devices.

**Mapping, symbols, 16 KB.** `mapping.txt` stays on the release. There is no crash service, so nothing is uploaded; native symbols,
if ever needed, come from rebuilding the runtime at its tag. CI checks zip alignment and every library's `LOAD` segments, and `sign`
checks the zip again.

**The workflow.** `android-release.yml` on `dev/android`, on a push of `android-v*`. Job `build`, no secrets: check the tag and
versions, `assembleRelease`, upload the unsigned APK, mapping and notes for a day. Job `sign`, in environment `android-release`, no
checkout: `apksigner sign`, verify the certificate's SHA-256, attest, `gh release create --draft --latest=false`.

## 5. Signing

**One key** for internal, public and later store builds, so a tester's rc upgrades to the final build. RSA 4096, valid 30 years
(Google: "at least 25 years"; Play needs past 2033-10-22). `checks` stays on the debug key and is never distributed.

**Where it lives.** Secrets of the environment `android-release`, and two copies the owner keeps away from GitHub (a password
manager, an encrypted drive), since GitHub secrets cannot be read back. Never in the repository, in `gradle.properties`, or on a
disk an agent can read, which includes the Mac.

**Protection.** The environment allows only `android-v*` tags and has the owner as required reviewer: "a job cannot access
environment secrets until one of the required reviewers approves it". Prevent self-review stays off, since the owner both tags and
approves. The key stays out of Gradle: `sign` checks out nothing and runs only `apksigner`, so no third-party plugin runs beside it.
Approving is a person's act: an agent never approves, even with a token that could.

**By hand, once (step 6),** with no agent running on that machine, after creating the environment in Settings. Then store the
keystore and password in the two copies and delete the working file.

```sh
keytool -genkeypair -keystore ensomi-release.p12 -storetype PKCS12 -alias ensomi-release \
  -keyalg RSA -keysize 4096 -validity 11000 -dname "CN=Ensomi, O=ensomi-labs"
keytool -list -v -keystore ensomi-release.p12 | grep SHA256:   # goes in this document, below
base64 < ensomi-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release -R ensomi-labs/ensomi
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release -R ensomi-labs/ensomi
```

Release certificate SHA-256: none yet.

**Fixed by the first public APK:** the certificate, since another one means uninstalling and losing the library and the model ("You
cannot regenerate a previously generated key"), and `io.ensomi.android`, which matches Swift's `io.ensomi.ios` and should stay.

**Loss and rotation.** A lost key ends upgrades. A held key can be retired with `apksigner rotate`; rotated keys are supported from
Android 13, this app's minSdk. Rotation does not undo a leak: a device that has not updated still trusts the old key.

**Play later.** Give Google a copy of this key at enrolment ("Generate the app signing key you want to use for all app stores"), with
a new upload key. A Google-generated key would stop Play and GitHub builds upgrading each other.

**Developer verification.** Google's rule covers four countries' participating stores since 2026-09-30, and "all apps on certified
Android devices" in 2027. Direct sideloads are not covered yet and ADB is exempt. A public APK will need the package and this key
registered.

## 6. Getting a build to the testers

Both testers have write access and see the draft. Public GreatFire probes, none from the teammate's network: `github.com` is
intermittent (42 of 62 tests disturbed), `release-assets.githubusercontent.com`, where downloads redirect, connected in 5 of 5 (last
on 2026-10-10), `huggingface.co` is blocked (15 of 15). At step 7 the teammate tries one download. If it fails, the owner sends the APK
zipped over whatever the two already use, and the teammate checks the SHA-256. Behind the block the model comes from the kit's folder
by Install from files; the app takes only the pinned package, so the channel does not matter.

**On HyperOS.** `adb install -r`, tapping `继续安装` within 10 s ([the runbook](phone-checks.md)), or open the APK in the file manager and
allow that app to install unknown apps. The second way has not been done with a signed build; step 7 records the dialogs in the
runbook. From a debug build, `adb uninstall io.ensomi.android` first, which also removes the model, settings and library; the phone
checks need the debug build back the same way.

## 7. The model pin and coordinated releases

**Pins.** The runtime: the catalog and `verification-metadata.xml`. The model: `ModelSource.kt`; `scripts/phone-checks.sh` restates its
hash (CI checks it) and the runbook its tag.

**What an update does.** On the first start after an upgrade the app downloads the pinned tag over the old install, which stays in use
until the new one is in place. Where Hugging Face is blocked the download stops and waits, and Install from files takes the new
package. Stored charts stay. The same seed makes the same chart only on the same model and runtime, so a release that moves either says
so.

**Order,** as ensomi-model's `docs/ecosystem.md` says: a release or upload comes before those who take it.

1. The runtime and the model, in that page's order for the change. A package's `min_runtime` must not exceed the runtime the app pins.
   The runtime's release carries the AAR.
2. The app, last: one commit moves every pin; CI; a kit with the new package; the phone checks; a tag.

The Swift app pins independently. That page still calls this repository "the macOS and iOS app"; step 9 adds Android. A mirror of the
model for China is a new host for the weights: it goes through the project's review of weights releases, not this document.

## 8. Provenance and supply chain

Worth it for a small team, at a few lines each: actions pinned by SHA; wrapper validation, which `setup-gradle` does; the AAR's SHA-256;
`actions/attest` on the APK and, in the runtime's release, on the AAR (public Sigstore, no key to hold, like the runtime's npm
provenance; check with `gh attestation verify`); immutable releases from the first public release, which lock tag and assets and add a
release attestation (repository-wide, so Swift's too); and signing apart from building, so a compromised plugin could alter an APK but
not take the key.

## 9. Order of work

S is under half a day of an agent's work, M about a day.

| # | Step | Size | Needs | Gives |
| --- | --- | --- | --- | --- |
| 0 | **Owner:** take this design | S | | The no-CI rule lifted |
| 1 | The snapshot: Astra builds `affb60a`, then `gh release create android-aar-affb60a ensomi-runtime.aar -R ensomi-labs/runtime --prerelease --latest=false --target affb60ade90af2fb6646e8e40d6e39cc17f298de` | S | **Owner's yes** | A public AAR |
| 2 | Section 2's app changes; check on the Mac with the override unset | S | 1 | Builds without a local AAR |
| 3 | `android.yml`; AGENTS.md and README updated | S | 2 | **First value:** every push built, tested and linted |
| 4 | Optimization and JNA's rules; the smoke test on the Mac AVD, signed with the debug key | S | 3 | A release build that loads |
| 5 | The hosted experiment | M | 3 | A device-level check in CI, or a closed question |
| 6 | **Owner:** environment, key, copies, secrets | S | | Signing possible |
| 7 | `android-release.yml`, `.gitignore` for `*.p12`, `android-v0.1.0-rc.1`; **the owner approves**; both testers install | M | 4, 6 | First internal build |
| 8 | The runtime's `v0.4.0` with the AAR; the app pins it | M | Parity on the Mac | A released, attested runtime |
| 9 | Notices complete; phone checks and smoke test pass on both devices; ecosystem page updated; **the owner decides** on a public prerelease and turns on immutable releases | M | 7, 8 | First public build |

**Stays on the Mac:** arm64 AVD runs, screenshots, the kit, the runtime's parity checks, builds of a runtime branch.

**Before anything is published,** the catalog's `ensomiRuntime` is a `vX.Y.Z` tag, not a snapshot, and the APK is built from the public
tree alone.

## Considered and rejected

- A stub workflow on `dev/swift`: nothing here needs `schedule` or `workflow_dispatch`.
- A Gradle download task: custom code for what verification does.
- The AAR in git: 15.7 MB in public history at each bump.
- `v0.4.0-rc.1` as the first asset: it needs `main`, and drags npm and `Package.swift` along.
- Whole-graph dependency verification: churn at every bump, little gain over exact pins.
- SBOMs, Dependabot on the pins, `sha_pinning_required` (it would fail `dev/swift`'s `@v7` tags).
- A second key for internal builds: testers would uninstall at the first public build.
- `signingConfigs` fed from secrets: the key would sit in a Gradle process with third-party plugins.
- `applicationIdSuffix` on debug: scripts name the package, and each install would hold its own model.
- `versionCode` from a semver formula: a counter cannot run out of room.
- Firebase App Distribution, Play internal testing, Pgyer, fir.im, object storage in China: too much for two testers.
- R8 off to avoid JNA's rules: they are three lines.

## Not verified

- **Nothing has run.** The workflow passes `actionlint` with `shellcheck`, but it has never run on GitHub; its duration is unknown.
- **`compileSdk = 37` resolving to `android-37.0`** is inferred: the SDK repository has no `platforms;android-37`. The first run shows it.
- **AGP taking the AAR from the Ivy repository:** tested with Gradle alone, on another asset.
- **`scripts/build-android.sh` and ONNX Runtime's build on Linux.**
- **This app under `libndk_translation`.** Whether `ubuntu-24.04-arm` exposes `/dev/kvm` is unstated by GitHub, and moot. A software-only
  arm64 emulator on hosted Apple silicon was not tried; Google calls that mode "quite slow".
- **Reach from the teammate's network:** GreatFire's public probes only, and few for the assets host.
- **HyperOS's dialogs** for a signed APK installed by hand.
- **Developer verification:** when sideloads are enforced, and whether HyperOS China builds are "certified".

## Sources

All read on 2026-10-31.

- GitHub: [runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
  [Ubuntu 24.04 image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md),
  [how workflows are found](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows),
  [events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
  [environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments),
  [caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching),
  [secure use](https://docs.github.com/en/actions/reference/security/secure-use),
  [artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts),
  [Packages and Maven](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry),
  [releases API](https://docs.github.com/en/rest/releases/releases),
  [immutable releases](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/immutable-releases),
  [attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).
- Gradle: [setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
  [its distribution terms](https://github.com/gradle/actions/blob/main/DISTRIBUTION.md).
- Android SDK repository: [packages](https://dl.google.com/android/repository/repository2-3.xml),
  [Google APIs system images](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml). `build.prop` read from
  `x86_64-33_r17`, `34_r14`, `35_r09`, `36_r07`, `37.0_r08`.
- Emulator: [`main-emulator.cpp`](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-main-dev/android/emulator/main-emulator.cpp)
  on `emu-main-dev` (`cf0f40a`, 2026-10-27), [acceleration](https://developer.android.com/studio/run/emulator-acceleration),
  [ARM translation (2020)](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html).
- Android: [16 KB pages](https://developer.android.com/guide/practices/page-sizes),
  [app optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
  [app signing](https://developer.android.com/studio/publish/app-signing),
  [apksigner](https://developer.android.com/tools/apksigner),
  [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756),
  [developer verification](https://developer.android.com/developer-verification) and its
  [FAQ](https://developer.android.com/developer-verification/guides/faq).
- Firebase Test Lab: [quotas and deprecation](https://firebase.google.com/docs/test-lab/usage-quotas-pricing),
  [migration FAQ](https://firebase.google.com/docs/test-lab/migrate-faq).
- JNA: [FAQ](https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md); the 5.19.1 AAR from Maven Central.
- GreatFire: [github.com](https://en.greatfire.org/https/github.com),
  [release-assets](https://en.greatfire.org/https/release-assets.githubusercontent.com),
  [huggingface.co](https://en.greatfire.org/https/huggingface.co).

## Appendix: the first workflow

For `.github/workflows/android.yml` at step 3; it stays here until then, since the file on this branch would start running. The actions
are pinned to the commits their release tags pointed at on 2026-10-31; check them again on adoption. The one value still to fill is
outside this file: the AAR's SHA-256 in section 2.

```yaml
# Builds and checks the Android app on every push. docs/release.md is the design.
# A release is another workflow; this one holds no secrets and uploads no APK.
name: Android

on:
  push:
    branches: [dev/android, "claude/**", "codex/**"]
    paths-ignore: ["docs/**", "**.md"]
  pull_request:
    branches: [dev/android]
    paths-ignore: ["docs/**", "**.md"]

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build, JVM tests and lint
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0
        with:
          distribution: temurin
          java-version: "25"
      # Every pin stays in the catalog; a package the runner image already has is not fetched again.
      - name: Install the catalog's SDK packages
        run: |
          pin() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" gradle/libs.versions.toml; }
          sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
          yes | "$sdkmanager" --licenses > /dev/null
          "$sdkmanager" --install "platforms;android-$(pin compileSdk).0" "build-tools;$(pin buildTools)" "ndk;$(pin ndk)"
      # Also validates the Gradle wrapper. Without cache-read-only it would write the cache only on the default
      # branch, which is dev/swift.
      - uses: gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c # v6.4.1
        with:
          cache-provider: basic
          cache-read-only: ${{ github.event_name == 'pull_request' }}
      - name: The phone checks' script and the app pin the same model
        run: |
          app=$(sed -n 's/.*PINNED_MANIFEST_SHA256 = "\(.*\)".*/\1/p' app/src/main/kotlin/io/ensomi/android/model/ModelSource.kt)
          script=$(sed -n 's/^PINNED_MANIFEST=//p' scripts/phone-checks.sh)
          if [ -z "$app" ] || [ "$app" != "$script" ]; then
            echo "::error::scripts/phone-checks.sh and ModelSource.kt pin different manifests"
            exit 1
          fi
      - name: Build every type, the checks APK, JVM tests, lint
        run: >
          ./gradlew --no-daemon
          assembleDebug assembleDebugAndroidTest assembleChecks assembleRelease
          testDebugUnitTest lint
      - name: 16 KB alignment of the release APK
        run: |
          pin() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" gradle/libs.versions.toml; }
          apk=app/build/outputs/apk/release/app-release-unsigned.apk
          "$ANDROID_HOME/build-tools/$(pin buildTools)/zipalign" -c -P 16 4 "$apk"
          readelf="$ANDROID_HOME/ndk/$(pin ndk)/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
          unzip -q "$apk" 'lib/arm64-v8a/*' -d "$RUNNER_TEMP/apk"
          for so in "$RUNNER_TEMP"/apk/lib/arm64-v8a/*.so; do
            "$readelf" -lW "$so" | awk '
              $1 == "LOAD" { count++; if ($NF != "0x4000") bad = 1 }
              END { exit !count || bad }
            ' || { echo "::error::$so is not aligned to 16 KB"; exit 1; }
          done
      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        if: ${{ !cancelled() }}
        with:
          name: reports
          path: |
            app/build/reports/
            app/build/test-results/
          retention-days: 7
          if-no-files-found: ignore
```
