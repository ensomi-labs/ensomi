# Ensomi for Android: agent notes

This branch is Ensomi's native Android app, built from Android's own APIs and conventions and from the
runtime's Kotlin API. The Swift app on `dev/swift` is the reference for what to build: features, flows, look
and the timing contract. Design how the Android way; do not port, copy or translate its code. It shares no code
or history with this branch.

UI and UX decisions live in `docs/design.md`, gameplay and timing in `docs/gameplay.md`, the order of work in
`docs/plan.md`. Keep them current when a decision changes.

## Layout

- One Gradle module, `:app`, with the Gradle project at the repository root.
- `app/src/main/kotlin/io/ensomi/android/`:
  - `MainActivity.kt`: the only activity.
  - `EnsomiApp.kt`: navigation with Navigation 3. A screen is a `NavKey` plus an `entry`. Screens
    navigate through callbacks that push keys, never by referring to each other.
  - `Destinations.kt`: the navigation keys.
  - One package per feature (`library/`, `model/`, `chart/`, `play/`, `settings/`, `diagnostics/`), holding
    its screens, ViewModels and data sources.
  - `play/`: the playfield, a `SurfaceView` drawn by its own render thread; every runtime call of a game
    happens on that thread. What lies on the lanes is drawn in lane space and tilted by the lean's perspective
    matrix (`PlayfieldGeometry`); touch zones come from the lanes at the line and never lean.
  - `prototype/`: sample states for screenshots, opened by a launch extra in debuggable builds. Sample
    implementations say in their comments that they are not wired, and unbuilt actions say so on screen.
  - `ui/`: shared components and the Material 3 theme. `json/`: a small JSON encoder.
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
3. On the arm64 AVD `ensomi-36` (and `ensomi-36-16k` for 16 KB pages): boot it headless, install, run
   `scripts/capture-prototype.sh` for the screens, or launch and read the Diagnostics JSON with
   `adb logcat -d -s EnsomiDiagnostics:I -v raw`, then shut the emulator down.
4. Screenshots, recordings and reports saved under `artifacts/` in its worktree, which git ignores.

The emulator shows layout, flows and rendering, not timing: refresh above 60 Hz, audio latency and touch
latency need a phone (`docs/gameplay.md`).

Astra builds and reports. It does not change the app's source or UI; UI work belongs to Claude.
