# Phone-check kit at a7fd06a

Prepared on 2026-10-30 on the owner's Mac in `/Users/l/ensomi/ensomi--kpad-kit`, branch `codex/kpad-kit`.

## Build input

- Built commit: `a7fd06a8ad28ecc829afa9e6f028e08566637745`. Initial HEAD and `origin/dev/android` both matched this commit.
- Runtime AAR: `/Users/l/ensomi/ensomi-runtime--android-aar/build/android/ensomi-runtime.aar`.
- Runtime SHA-256: `4ba0aff26a36de38cee602c34d0288d606bfc2ea77348c2d57b91599a82ca2bf` — exact match to the brief and build b3.
- Song input: `/Users/l/ensomi/ensomi/artifacts/renders/android-impl/b1/mosaic.mp3`.
- Song SHA-256: `e8400c1ec54dc31d39e41d7d9a4b7d608809287f89e8ac2cf77284b1e95f81d4` — exact match, both at the source and in the kit.

Created the uncommitted `local.properties` exactly as requested:

```properties
sdk.dir=/Users/l/Library/Android/sdk
ensomi.runtime.aar=/Users/l/ensomi/ensomi-runtime--android-aar/build/android/ensomi-runtime.aar
```

## Gradle result

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug assembleDebugAndroidTest assembleChecks testDebugUnitTest lint
```

Exit code 0:

```text
BUILD SUCCESSFUL in 47s
125 actionable tasks: 125 executed
```

- All three requested APKs built successfully.
- JVM tests: **121 passed**, 0 failures, 0 errors, 0 skipped, across **21 suites**, counted from `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
- Lint: **0 errors, 1 warning**, from `app/build/reports/lint-results-debug.sarif`. The `lint` task ran `lintDebug`.
- Instrumentation APK built; instrumentation and phone checks were not run, as requested.

Warnings and notices:

1. Lint `AndroidGradlePluginVersion`, `gradle/libs.versions.toml:4`: “A newer version of com.android.application than 9.4.1 is available: 9.5.0”. Informational upgrade notice; the requested commit and its pins were preserved.
2. `stripDebugDebugSymbols` and `stripChecksDebugSymbols` each printed:

   ```text
   Unable to strip the following libraries, packaging them as they are: libjnidispatch.so. Run with --info option to learn more.
   ```

   Both APK builds succeeded with that library packaged as-is. This does not establish its behavior on a physical device; that awaits the later checks.
3. Gradle printed:

   ```text
   Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
   ```

   The generated Problems report records `The Configuration.setVisible(boolean) method has been deprecated.`, attributed to `com.android.internal.application`, with details `This is scheduled to be removed in Gradle 11.` It contains 15 diagnostic entries for that same warning and a summary count of 43. These are the report's literal counts; no Gradle or plugin pin was changed.
4. Gradle also announced its incubating Problems report and suggested enabling configuration cache. Neither prevented the build.

Full build log: `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/gradle.log`. Lint HTML: `/Users/l/ensomi/ensomi--kpad-kit/app/build/reports/lint-results-debug.html`. Gradle Problems report: `/Users/l/ensomi/ensomi--kpad-kit/build/reports/problems/problems-report.html`.

## Kit and archive

- Kit directory: `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit`.
- ZIP: `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/ensomi-phone-kit-a7fd06a.zip`.
- ZIP SHA-256: `7439688c9e628988279958ae73fd19f8ba633f5be21d30f7e5f499ca2f657d59`.
- ZIP size: **149,599,045 bytes** (142.67 MiB).

Only the cached model directory was copied from `/Users/l/ensomi/ensomi--android-phone-checks-b3/artifacts/b3/kit/model/ensomi-r2`. The kit's APKs were built here; its script and runbook came from this checkout. The old kit itself was not reused.

Ran exactly:

```sh
HTTPS_PROXY=http://127.0.0.1:6152 HTTP_PROXY=http://127.0.0.1:6152 \
  scripts/make-phone-kit.sh artifacts/kit/ensomi-phone-kit \
  /Users/l/ensomi/ensomi/artifacts/renders/android-impl/b1/mosaic.mp3
```

Exit code 0; output:

```text
model: 31 files, manifest 6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb
kit ready in artifacts/kit/ensomi-phone-kit: zip it and hand it over
```

Created the archive from `artifacts/kit` with `zip -qr ensomi-phone-kit-a7fd06a.zip ensomi-phone-kit` (exit 0).

All requested contents checks passed:

- **Three APKs:** `app-debug.apk`, `app-debug-androidTest.apk`, `app-checks.apk`, each byte-identical to its fresh `app/build/outputs/apk/` output.
- **Runner:** `phone-checks.sh` is byte-identical to both the worktree's script and `git show a7fd06a8ad28ecc829afa9e6f028e08566637745:scripts/phone-checks.sh`.
- **Runbook:** `phone-checks.md` is byte-identical to `docs/phone-checks.md` at the same commit.
- **Nine nonempty click tracks:** `click-48k.wav`, `click-lame.mp3`, `click-noxing.mp3`, `click-vbr.mp3`, `click.flac`, `click.m4a`, `click.ogg`, `click.opus`, `click.wav`.
- **One song:** `songs/mosaic.mp3`, with the expected SHA-256 above.
- **Model:** exactly 30 payload files plus `ensomi-package.json`; all payload SHA-256 hashes and byte sizes match the manifest. Manifest SHA-256 `6e05317427cc24f3d46c0e8dcdd7a44078954437129c60f8797acb80bea6a1fb` matches the app's pin at `r2-1.0.1`.
- **Archive integrity:** exactly 46 regular files; every file read back from the ZIP matches the verified kit's SHA-256 and size. The archive contains only `ensomi-phone-kit/` and its contents.

| Kit file | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 63,482,501 | `fd496fe5158788ce888d95e9c84dd95a485712fabf9437e8ba60a203cb6b5e45` |
| `app-debug-androidTest.apk` | 1,338,370 | `e796c3104f9f38e642f5b0de412a57abc9ab4e1821be2d131772d18fc14a0e70` |
| `app-checks.apk` | 55,283,000 | `a04b34b878c243ce2bc38fb16c57134d27c096dc553297b3ec6090946e84362b` |
| `phone-checks.sh` | 15,836 | `b0b76fb0050bad6899b976dc6f22d882731080b80e4f00aa3d0ff4ff1bd908f7` |
| `phone-checks.md` | 14,541 | `b77abb34f76447e8ef63b2dc28f49e3fa38baec776a142c4ee3d4ebf5770bc30` |
| `songs/mosaic.mp3` | 2,518,746 | `e8400c1ec54dc31d39e41d7d9a4b7d608809287f89e8ac2cf77284b1e95f81d4` |

Detailed local verification records: `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/verification.json` and `/Users/l/ensomi/ensomi--kpad-kit/artifacts/kit/zip-verification.json`. These and the binaries remain uncommitted.

## Device snapshot (verbatim)

At `2026-10-30T21:47:25.805252+09:00`, ran `/Users/l/Library/Android/sdk/platform-tools/adb devices -l` **once** (exit 0). Standard output, including its blank final line:

```text
List of devices attached

```

Standard error was empty. No device was listed. Step 6 was therefore not applicable: no properties or shell values were read. No emulator was started, and no further adb commands were issued.

## Deviations and remaining work

- Environment adjustment: this worktree initially had `artifacts -> /Users/l/ensomi/ensomi/artifacts`, an untracked symlink. Replaced that link with a real worktree-local `artifacts/` directory so the requested kit path is local and `artifacts/kit/report.md` can be committed. The shared target and its contents were left untouched. The pre-existing `LocalFixtures` symlink was also left untouched.
- A local report-generation helper initially raised `KeyError: 'level'` because the lint SARIF result omitted its default warning level. Corrected the helper to accept that default and regenerated this report; no build rerun or project changes were needed.
- The initial HTTPS push and a first URL-override retry failed (exit 128): `fatal: could not read Username for 'https://github.com': Device not configured`. GitHub CLI reported invalid saved tokens. Existing SSH authentication succeeded. The configured origin is `https://github.com/Pulsefield/Pulsefield-app`; its SSH equivalent returned `a7fd06a8ad28ecc829afa9e6f028e08566637745` for `refs/heads/dev/android`. Delivery uses that same origin repository over SSH via a command-local push URL override (`git -c remote.origin.pushurl=git@github.com:Pulsefield/Pulsefield-app.git push origin HEAD:refs/heads/codex/kpad-kit`); no persisted remote or credential configuration was changed.
- No other deviations. Steps 1–5 completed successfully. Step 6 was skipped only because the single device snapshot was empty. No unresolved failures.
- No source, scripts, docs, version pins or device settings were changed. No installs, device file transfers, injected input, `phone-checks.sh` executions or instrumentation runs occurred.
- Commit scope is only `artifacts/kit/report.md`; APKs, model, song, ZIP, logs and `local.properties` are excluded.
- Remaining: a later job must obtain the requested read-only values from the connected device and run the phone checks with the owner available to tap HyperOS install prompts. This kit has not been exercised on a physical device.
