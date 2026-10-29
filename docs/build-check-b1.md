# Android phone checks: build check b1

Date: 2026-10-29 (Mac local time).
Source revision: `c91011f3261c3014c6aa5b99a39e8b4898474742`.
Build branch: `codex/android-phone-checks-b1`.

## Result

| Requested task | Result |
| --- | --- |
| `assembleDebug` | Failed: prerequisite `:app:compileDebugKotlin` failed. No APK was assembled. |
| `testDebugUnitTest` | Blocked by compilation; no tests ran. |
| `lint` | Blocked by compilation, including a separate `lint --continue` attempt. No lint report was generated. |

The requested command exited with status 1 after 11 seconds. The separate lint
attempt exited with status 1 after 1 second. App source and build configuration
were not changed. No emulator was started.

## Kotlin diagnostics under `app/src/`

One unique error was emitted, identically in both attempts:

```text
app/src/main/kotlin/io/ensomi/android/chart/MakeChartScreen.kt:161:21 No value passed for parameter 'onInstallFromFolder'.
```

No Kotlin warnings under `app/src/` were emitted. Compilation stopped at this
error, so this is not a claim that later compilation would be warning-free.

## Unit tests

No tests ran and no test result files were generated. There are no failing test
names or assertion messages to report; the suite has not passed.

## Lint

No lint findings are available: compilation prevented lint analysis and report
generation. `app/build/reports/lint-results-debug.*` does not exist, so no lint
report could be copied to the artifact directory. This is not a clean lint result.
`app/lint.xml` ignores `OldTargetApi` and `ChromeOsAbiSupport`; it was unchanged.

## Build environment and commands

Created the ignored root `local.properties` with:

```properties
sdk.dir=/Users/l/Library/Android/sdk
ensomi.runtime.aar=/Users/l/ensomi/ensomi-runtime--android-aar/build/android/ensomi-runtime.aar
```

Used Android Studio's bundled JDK, OpenJDK `25.0.3`, build
`25.0.3+-15898627-b508.16`:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest lint
./gradlew lint --continue
```

Runtime AAR SHA-256:
`4ba0aff26a36de38cee602c34d0288d606bfc2ea77348c2d57b91599a82ca2bf`.

Other Gradle output, outside the Kotlin source diagnostics:

- `Unable to strip the following libraries, packaging them as they are: libjnidispatch.so. Run with --info option to learn more.`
- `Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.`

Neither message was the build blocker.

## Saved artifacts and remaining work

Local artifacts are available through this worktree's existing `artifacts` symlink:

- `artifacts/b1/gradle.log`: complete requested-command output.
- `artifacts/b1/lint-gradle.log`: complete separate lint attempt output.
- `artifacts/b1/problems-report.html`: Gradle's problems report from the lint attempt.
- `artifacts/b1/report.md`: a copy of this report.

Claude needs to fix the missing `onInstallFromFolder` argument at the `ModelPanel`
call in `MakeChartScreen.kt`, then rerun all three requested tasks to obtain unit
test results and the lint report.
