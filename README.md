# Ensomi for Android

Ensomi is a rhythm game whose charts are generated from music. This branch holds its native Android app,
written in Kotlin with Jetpack Compose. Chart generation and gameplay come from the shared Rust core,
[ensomi-runtime](https://github.com/ensomi-labs/runtime), through its Kotlin bindings. The app shares no
code or history with the Swift app, which is the reference for what it does.

The app is in development and is not distributed. It holds a prototype of drop-in play: the library, making a
chart, play, results, settings and timing calibration, on sample data. The playfield is real (a `SurfaceView`
render thread with the runtime judging) and plays a sample chart without sound. Diagnostics, under Settings,
shows what the device is (SoC, CPU, memory page size, display modes, audio, thermal state) and checks that the
runtime's native library loads. The design is in [docs/design.md](docs/design.md) and
[docs/gameplay.md](docs/gameplay.md), the order of work in [docs/plan.md](docs/plan.md).

## Requirements

- macOS. Builds run only there for now, and there is no CI.
- Android Studio, whose bundled JDK runs Gradle (any JDK 17 or later works).
- Android SDK platform 37, build-tools 37.0.0, NDK 30.0.16248370, platform-tools and the emulator.
- A device or emulator with an arm64-v8a CPU and Android 13 (API 33) or later. The app is built for
  arm64-v8a only.
- The runtime AAR, built in an ensomi-runtime checkout with `scripts/build-android.sh`
  (see its `android/README.md`).

Every version the build uses is pinned in `gradle/libs.versions.toml`.

## local.properties

Create `local.properties` at the repository root. Git ignores it.

```properties
sdk.dir=/Users/you/Library/Android/sdk
ensomi.runtime.aar=/absolute/path/to/ensomi-runtime/build/android/ensomi-runtime.aar
```

`ensomi.runtime.aar` selects the runtime AAR the app links. Gradle stops with a message when it is missing
or does not name a file. The AAR carries no dependency metadata, so the app declares JNA and
kotlinx-coroutines itself. Once a runtime release carries the AAR, the app will pin that release instead.

## Build, test and install

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.ensomi.android/.MainActivity
```

Without a phone, use an arm64 emulator. On Apple silicon it runs natively, for example:

```sh
"$ANDROID_HOME/emulator/emulator" -avd <avd-name> -no-window &
adb wait-for-device
```

## Prototype screens

A debuggable build opens in any sample state of the prototype from a launch extra, for screenshots. The states are
listed in `app/src/main/kotlin/io/ensomi/android/prototype/Prototype.kt`:

```sh
adb shell am start -n io.ensomi.android/.MainActivity --es prototype make-chart-made
```

`scripts/capture-prototype.sh OUT_DIR` captures all of them in light and dark mode, some at 200 % font size and in
landscape, and records the playfield preview, on the one booted emulator or device.

## Diagnostics output

The Diagnostics screen reads the device at launch. **Copy JSON** copies everything as one JSON object.
The same JSON goes to logcat once per launch, under the tag `EnsomiDiagnostics`:

```sh
adb logcat -d -s EnsomiDiagnostics:I -v raw
```

JSON that fits in one logcat line is printed as is. Longer JSON is split into lines that start with
`part i/n `; remove that prefix and join the pieces in order.

## Project layout

- `app/src/main/kotlin/io/ensomi/android/`: the app. `Destinations.kt` and `EnsomiApp.kt` hold the
  navigation; each feature has its own package: `library/`, `model/`, `chart/`, `play/`, `settings/`,
  `diagnostics/`.
- `docs/`: the design, the gameplay design and the build plan.
- `scripts/`: the screenshot capture script.
- `app/src/test/kotlin/`: unit tests that run on the JVM.
- `gradle/libs.versions.toml`: every pinned version.

## License

Apache License 2.0. See [LICENSE](LICENSE).
