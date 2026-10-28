# Ensomi for Android: agent notes

This branch is Ensomi's native Android app. It is greenfield: it is designed from Android's own APIs and
conventions and from the runtime's Kotlin API. Do not port, copy or mirror the Swift app; it lives on
other branches and shares no code or history with this one.

## Layout

- One Gradle module, `:app`, with the Gradle project at the repository root.
- `app/src/main/kotlin/io/ensomi/android/`:
  - `MainActivity.kt`: the only activity.
  - `EnsomiApp.kt`: navigation with Navigation 3. A screen is a `NavKey` plus an `entry`. Screens
    navigate through callbacks that push keys, never by referring to each other.
  - One package per feature, such as `diagnostics/`, holding its screen, ViewModel and data sources.
  - `ui/theme/`: the Material 3 theme. `json/`: a small JSON encoder.
- `app/src/test/kotlin/`: JVM unit tests for logic that does not need a device.
- User-visible text lives in `app/src/main/res/values/strings.xml`.

## Pins and the runtime

- Every version is pinned in `gradle/libs.versions.toml`: plugins, libraries, SDK levels, build-tools
  and NDK. Change a pin only there.
- The runtime is a local AAR named by `ensomi.runtime.aar` in `local.properties`. It has no dependency
  metadata, so the app declares JNA (as an AAR) and kotlinx-coroutines. A pinned runtime release
  replaces it once one exists.
- Run blocking runtime calls through the runtime's `RuntimeWorker`, never on the main thread.
- The logcat tag `EnsomiDiagnostics` carries only the Diagnostics JSON. Scripts read it.
- `app/lint.xml` ignores lint findings that restate a decision, each with its reason. Fix other
  findings, or list and judge them in the build report.

## Build and verify

Builds run only on the owner's Mac; there is no CI workflow. Agents without the Android toolchain push
a commit and hand the build to Astra (Codex on the Mac) with `ens astra`. A build brief asks for:

1. `local.properties` with `sdk.dir` and `ensomi.runtime.aar`.
2. `./gradlew assembleDebug testDebugUnitTest lint`, with Android Studio's bundled JDK.
3. On the arm64 AVD `ensomi-36-16k` (16 KB pages), then `ensomi-36` (4 KB pages): boot it headless,
   install, launch, read the JSON with `adb logcat -d -s EnsomiDiagnostics:I -v raw`, take a
   screenshot with `adb exec-out screencap -p`, and shut the emulator down.
4. Screenshots, JSON and reports saved under `artifacts/` in its worktree, which git ignores.

Astra builds and reports. It does not change the app's source or UI; UI work belongs to Claude.
