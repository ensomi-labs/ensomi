# Ensomi for Android: agent notes

This branch is Ensomi's native Android app, built from Android's own APIs and conventions and from the
runtime's Kotlin API. The Swift app on `dev/swift` is the reference for what to build: features, flows, look
and the timing contract. Design how the Android way; do not port, copy or translate its code. It shares no code
or history with this branch.

UI and UX decisions live in `docs/design.md`, gameplay and timing in `docs/gameplay.md`, the order of work in
`docs/plan.md`. Keep them current when a decision changes. `docs/release.md` is a proposed design for CI and releases; nothing in it is enabled.

## Layout

- One Gradle module, `:app`, with the Gradle project at the repository root.
- `app/src/main/kotlin/io/ensomi/android/`:
  - `MainActivity.kt`: the only activity.
  - `EnsomiApp.kt`: navigation with Navigation 3. A screen is a `NavKey` plus an `entry`. Screens
    navigate through callbacks that push keys, never by referring to each other.
  - `AppStores.kt`: the app's stores, one per process (DataStore allows one per file): settings, songs,
    the chart model, the charts made (`RuntimeChartMaker`, `ChartStore`) and the sound of the song last played.
    Screens reach them through the `AppGraph` interfaces, never directly.
  - `Destinations.kt`: the navigation keys.
  - One package per feature (`library/`, `model/`, `chart/`, `play/`, `settings/`, `diagnostics/`), holding
    its screens, ViewModels and data sources. `audio/` decodes songs (`SongDecoder`, `Mp3Lead`), plays a
    game's sound (`GameAudio`, an `AudioTrack` and its writer thread) and keeps song time from it
    (`AudioPlayClock`, JVM-tested against a scripted output); `ClickTrack` is the calibration's click track,
    played through the same `GameAudio` and clock (`settings/Calibration.kt`).
  - `play/`: the playfield, a `SurfaceView` drawn by its own render thread; every runtime call of a game
    happens on that thread. What lies on the lanes is drawn in lane space and tilted by the lean's perspective
    matrix (`PlayfieldGeometry`); touch zones come from the lanes at the line and never lean. A game
    (`PlayController`) outlives the surface, so it pauses rather than restarts when the app goes to the
    background.
  - `prototype/`: the screenshot scenarios (`Prototype.kt`), opened by a launch extra in debuggable builds, and the
    fakes behind them (`Fakes.kt`); a normal launch uses the app's stores. The feature packages hold no samples,
    apart from the sample charts the playfield preview and the calibration's picture step play (`SampleCharts`).
  - `ui/`: shared components and the Material 3 theme. `json/`: a small JSON encoder.
- `app/src/test/kotlin/`: JVM unit tests for logic that does not need a device.
- `app/src/androidTest/kotlin/io/ensomi/android/checks/`: the phone checks, instrumented tests that measure on a device
  (runtime, model, decoders, generation time and memory, determinism, the audio clock, frame pacing, pauses) and log
  their results; `scripts/phone-checks.sh` runs them with `am instrument` and `docs/phone-checks.md` is the runbook.
- `app/src/checks/`: the `checks` build type's resources. It is the app not debuggable, signed with the debug key,
  with the debug tools on (`prototype/DebugTools.kt`), so frame pacing is measured without ART's debuggable mode.
- User-visible text lives in `app/src/main/res/values/strings.xml`.

## Pins and the runtime

- Every version is pinned in `gradle/libs.versions.toml`: plugins, libraries, SDK levels, build-tools
  and NDK. Change a pin only there.
- The runtime is a local AAR named by `ensomi.runtime.aar` in `local.properties`. It has no dependency
  metadata, so the app declares JNA (as an AAR) and kotlinx-coroutines. A pinned runtime release
  replaces it once one exists.
- Run blocking runtime calls through the runtime's `RuntimeWorker`, never on the main thread.
- The logcat tag `EnsomiDiagnostics` carries only the Diagnostics JSON, `EnsomiDecodeCheck` only the decode check's
  JSON lines, `EnsomiModelInstall` only each model install's start and outcome as a JSON line, and `EnsomiPhoneCheck`
  only the phone checks' results, one JSON object each (README). Scripts read them. `EnsomiCharts`, `EnsomiPlay`, `EnsomiAudio`,
  `EnsomiPlayfield` and `EnsomiCalibration` are for people reading a check's log; the README lists the debug launch
  extras, broadcasts and files the checks use.
- The model comes only as the pinned package: `ModelSource` names the tag and its manifest's SHA-256, and both ways in,
  the download and Install from files, refuse any other manifest. Change both together, nowhere else.
- `app/lint.xml` ignores lint findings that restate a decision, each with its reason. Fix other
  findings, or list and judge them in the build report.

## Build and verify

Builds run only on the owner's Mac; there is no CI workflow. Agents without the Android toolchain push
a commit and hand the build to Astra (Codex on the Mac) with `ens astra`. A build brief asks for:

1. `local.properties` with `sdk.dir` and `ensomi.runtime.aar`.
2. `./gradlew assembleDebug assembleDebugAndroidTest assembleChecks testDebugUnitTest lint`, with Android Studio's
   bundled JDK.
3. On the arm64 AVD `ensomi-36` (and `ensomi-36-16k` for 16 KB pages): boot it headless, install, run
   `scripts/capture-prototype.sh` for the screens, or launch and read the Diagnostics JSON with
   `adb logcat -d -s EnsomiDiagnostics:I -v raw`, then shut the emulator down.
4. For the phone checks: a kit from `scripts/make-phone-kit.sh`, then `scripts/phone-checks.sh -k KIT` on the booted
   AVD, as a tester runs it (docs/phone-checks.md).
5. Screenshots, recordings and reports saved under `artifacts/` in its worktree, which git ignores.

The emulator shows layout, flows and rendering, not timing: refresh above 60 Hz, audio latency and touch
latency need a phone (`docs/gameplay.md`). An emulator run of the phone checks shows that they work, not a phone's
numbers.

Astra builds and reports. It does not change the app's source or UI; UI work belongs to Claude.
