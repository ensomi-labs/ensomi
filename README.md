# Ensomi for Android

Ensomi is a rhythm game whose charts are generated from music. This branch holds its native Android app,
written in Kotlin with Jetpack Compose. Chart generation and gameplay come from the shared Rust core,
[ensomi-runtime](https://github.com/ensomi-labs/runtime), through its Kotlin bindings. The app shares no
code or history with the Swift app, which is the reference for what it does.

The app is an early play demo. It does drop-in play on the app's own stores: the settings, kept
with Jetpack DataStore (latency slots for the sound and for each input method); the song library, from the system's document picker; decoding
songs to PCM; the chart model, downloaded from Hugging Face at a pinned tag or installed from the package's files in a
folder the player picks, either way only the pinned package and checked file by file; charts made on
the device by the runtime from the player's songs, the last one per song kept; play: the chart on the song's own sound,
timed by the `AudioTrack`'s timestamps, on a `SurfaceView` render thread with the runtime judging, in landscape with
the lanes leaning back by default (the orientation and the lean are settings with a live preview); and the timing
calibration, on a click track through the same output and clock. Diagnostics, under Settings or the launcher icon’s long-press shortcut,
shares device facts, recovered process exits and a bounded event journal. Loading the runtime is an optional self-test. The design is in [docs/design.md](docs/design.md) and
[docs/gameplay.md](docs/gameplay.md), the order of work in [docs/plan.md](docs/plan.md).

## Get the demo

Download the signed APK from the [Android 0.1.2 prerelease](https://github.com/ensomi-labs/ensomi/releases/tag/android-v0.1.2), open it on your device, and allow installation from the app you opened it with. It needs Android 13 or
later, an arm64-v8a device, an internet connection for the first-run model download from Hugging Face (about 120 MB),
and a song you select through Android's system file picker. Chart generation runs on your device and needs additional
storage and memory. This is a prerelease; performance and audio timing vary by device and output.

## Build requirements

- macOS. Builds run only there for now, and there is no CI.
- Android Studio, whose bundled JDK runs Gradle (any JDK 17 or later works).
- Android SDK platform 37, build-tools 37.0.0, NDK 30.0.16248370, platform-tools and the emulator.
- A device or emulator with an arm64-v8a CPU and Android 13 (API 33) or later. The app is built for
  arm64-v8a only.
- The runtime AAR, built from ensomi-runtime commit `d090374` (ORT without KleidiAI) with
  `scripts/build-android.sh` (see its `android/README.md`). The required AAR SHA-256 is
  `468a11aa3f53db041b44478fbf8d6e49d57411b72c5d01594f1c6b05a3ec0738`.

The 0.1.3 candidate (code 4) removes KleidiAI kernels from ONNX Runtime to avoid the suspected
SME-without-SME2 failure. It has not run on a CPU with SME; the affected phone still needs a tester rerun.

Every version the build uses is pinned in `gradle/libs.versions.toml`.

## local.properties

Create `local.properties` at the repository root. Git ignores it.

```properties
sdk.dir=/absolute/path/to/Android/sdk
ensomi.runtime.aar=/absolute/path/to/ensomi-runtime/build/android/ensomi-runtime.aar
```

`ensomi.runtime.aar` selects the runtime AAR the app links. Gradle stops with a message when it is missing
or does not name a file. Gradle hashes that exact AAR into `BuildConfig.RUNTIME_AAR_SHA256`;
Diagnostics exports it as `runtime.identity` without loading native code. The AAR carries no dependency metadata, so the app declares JNA and
kotlinx-coroutines itself. Once a runtime release carries the AAR, the app will pin that release instead.

## Build, test and install

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.ensomi.android/.MainActivity
```

`./gradlew assembleDebugAndroidTest assembleChecks` builds the phone checks (`app/src/androidTest`) and the checks
build (`app-checks.apk`: the same app, not debuggable, signed with the debug key, with the scenarios and frame logs),
which [docs/phone-checks.md](docs/phone-checks.md) and `scripts/phone-checks.sh` use. Do not run the checks with
`connectedDebugAndroidTest` on HyperOS: its install dialog makes the install fail while Gradle reports success.

Without a phone, use an arm64 emulator. On Apple silicon it runs natively, for example:

```sh
"$ANDROID_HOME/emulator/emulator" -avd <avd-name> -no-window &
adb wait-for-device
```

## Prototype screens

A normal launch uses the app's own stores. For review, a debuggable build opens instead in any state of every screen
from a launch extra, on fakes (`prototype/Fakes.kt`: sample songs, a model state, a chart state, settings in memory, a
click track without sound); the states are listed in `app/src/main/kotlin/io/ensomi/android/prototype/Prototype.kt`:

```sh
adb shell am force-stop io.ensomi.android
adb shell am start -n io.ensomi.android/.MainActivity --es prototype make-chart-made
```

MainActivity is `singleTask`: debug launch extras are read only when a new instance is created. Force-stop the app before launching with those extras.

A few scenarios use the app's own stores instead of fakes: `results-stored` (the sample results, whose Use writes the
active audio latency slot), `play-stored` (the sample game, no sound, played with the stored input latency slots),
`settings-stored`, `calibration-stored` and `calibration-picture-stored` (the stored latency slots on those screens).

`scripts/capture-prototype.sh OUT_DIR` captures all of them in light and dark mode, the game at each lean in both
orientations, some screens at 200 % font size and with the phone in landscape, the UI hierarchy of every kind of
screen, the frame pacing at each lean (below), records the playfield preview and, with `REAL_SONG="<title>"` set,
plays a real game of that song (it needs a stored chart) by the autoplay to its results, on the one booted emulator or
device.

`scripts/capture-frame-timeline.sh OUT_DIR NAME [SECONDS]` records a Perfetto trace of whatever plays: SurfaceFlinger's
FrameTimeline (each frame's expected and actual presentation and its jank, per layer), the graphics atrace categories
and the render thread's own sections (`EnsomiFrame`, with `EnsomiRuntime` and `EnsomiDraw`). With Perfetto's
`trace_processor` or the `perfetto` Python package it also summarises the app's frames by jank type.

`scripts/make-click-tracks.sh OUT_DIR` writes click tracks (a click every 500 ms from 1 s) in each container and MP3
header class, from one PCM, with ffmpeg. With songs in the library, a debuggable build decodes each of them and logs one
JSON line per song (length, the MP3 lead, the first click's time) under the tag `EnsomiDecodeCheck`:

```sh
adb shell am start -n io.ensomi.android/.MainActivity --ez decode-check true
adb logcat -d -s EnsomiDecodeCheck:I -v raw
```

The model download logs each request and its answer under `EnsomiModel`, and each install, the download or Install from
files, logs its start and its outcome as one JSON line under `EnsomiModelInstall`
(`{"source":"huggingface"|"files","result":"started"|"installed"|"stopped"|"cancelled","seconds":…}`, with the revision,
manifest SHA-256, files and bytes once installed, or the reason it stopped), which `scripts/phone-checks.sh` waits on. A
debuggable build starts the download from a launch extra, as the Download button does:

```sh
adb shell am force-stop io.ensomi.android
adb shell am start -n io.ensomi.android/.MainActivity --ez download-model true
adb logcat -d -s EnsomiModelInstall:I -v raw
```

Installation separates transfer, file verification, commit and the first model open. A fully staged
pinned package resumes without network access. Verification never opens the model. After commit,
a durable marker protects the first open and first preparation or chart; a crash leaves a blocked card with **Try again**,
**Remove model** and **Share diagnostics**. Capabilities are saved for the installed revision and app
update, so subsequent starts do not open ORT even before inference. Each marker exists only during its
native call and is removed on return, cancellation or refusal. Only the first successful inference completes
that guard; cancelled or refused calls leave the next inference guarded. An Android user-requested stop retries automatically. Stage boundaries are appended to the journal; the OS process
summary names the active guarded stage. Cold shares and chart requests wait up to 60 seconds for readiness.

Install 0.1.3 over 0.1.2 without uninstalling to preserve Android's earlier crash record. To share it,
long-press the launcher icon → **Diagnostics** → **Share diagnostics**. The ZIP has schema-2 device/CPU
facts, the exact runtime identity, recovered exits and redacted traces (when Android provides them).

The model's files are in the app's
`no_backup/models/ensomi-r2` (`adb shell run-as io.ensomi.android ls no_backup/models/ensomi-r2`), with
`ensomi-install.json` recording the tag, the manifest's SHA-256 and the source; an install from files copies into
`no_backup/models/.files-ensomi-r2` first and keeps the staging when it stops. The settings are in
`files/settings.json`, the library in `files/library.json`, and each song's last chart in `files/charts/<song id>/`
(`chart.osu`, the runtime's `receipt.json`, and the app's `chart.json`). The settings keep the latency slots
(`audioLatency`, `inputLatency`, docs/latency.md); the first start of this build moves a 0.1.x file's `audioOffsets` and
`visualOffsetMs` into them and logs the move under `EnsomiSettings` by slot number, and later writes leave the old
fields out.

Making charts logs under `EnsomiCharts` (each song's audio stages and each chart, with their times and the peak
resident memory), games under `EnsomiPlay` (the latency for the game, "Latency for this game: audio slot 1 +142 ms; touch slot 0 +8 ms;
keyboard slot 1 +30 ms", with "(no slot)" for a value no slot gave; each input method's first press and how far the
input clock held for it; why each pause happened; at the first frame of each pause and after it the inputs delivered
then or the lanes released at the pause's time, the lanes the session holds and the holds held in them, numbered 1 to
4, and the late inputs so far; at game over the score, the presses by source, the methods that pressed, the hits timed
by source with their average error, and the late inputs by source), their sound under
`EnsomiAudio` (the track's buffer, its route and its underruns), and the playfield under `EnsomiPlayfield`: at each
resume the lanes it released at the pause's time and those still held, and from its render thread the frame rate it
asked for and got, then every two seconds the frames drawn, the vsyncs missed, the frames finished after their
deadline, and the time in the runtime and in drawing (average, most, and the whole frame's work at the median and the
99th percentile). The calibration logs under `EnsomiCalibration`: the output the clicks play on, every five seconds
when click 0 was heard on the `System.nanoTime()` clock, and each tap's event time, song time, error and the suggestion.
A debuggable build takes these extras on a normal launch, for checks on an emulator:

```sh
adb shell am start -n io.ensomi.android/.MainActivity --ef stars 7         # stars outside the slider: the runtime refuses
adb shell am start -n io.ensomi.android/.MainActivity --ez autoplay true   # games played by the autoplay
adb shell am start -n io.ensomi.android/.MainActivity --ez low-memory true # the generator's lowMemory option
```

and, during a game, broadcasts that do what an emulator cannot (another output, another app taking audio focus):

```sh
adb shell am broadcast -a io.ensomi.android.debug.PLAY --es do change-output   # or take-focus, give-focus
```

A debuggable build (and the checks build) also writes each game's frame times to `files/clock-log.csv`
(`nanos,songMs,renderMs`) when the game closes, for checking that song time never goes back, and each frame's pacing to
`files/frame-log.csv` (`vsyncNanos,presentNanos,runtimeNanos,drawNanos,deadlineNanos,doneNanos,startNanos,recordNanos,postNanos,renderUs,notes,syncResult,drained`:
the frame's deadline from `FrameData`, when its drawing was posted, when its work began, the time recording the canvas
and handing it to HWUI, the song time drawn in microseconds, the objects in the frame, HWUI's answer for it, and 1 when
the vsync before it drew nothing to drain the buffer queue), each touch but moves to `files/touch-log.csv`
(`action,eventNanos,handledNanos,pointers`: the `MotionEvent` action, its own time, when the playfield handled it), and
every input the game's session was given to `files/input-log.csv`
(`sequence,source,lane,press,atPause,eventNanos,songMs,chartMs,takenNanos,deliveredNanos,inputClockMs,late`: its
event's song time and its chart time, whose difference is minus the audio latency and its source's input latency, the
frames that took it and handed it on, the input clock then, and 1 when it was late), summarised by source under
`EnsomiPlay` as the game closes ("Inputs: touch 113, chart less song time −30.0 to −30.0 ms, waited 0 (most 0.0 ms),
late 0; …"). The same builds take the launch extras above; the `pacing-flat`,
`pacing`, `pacing-lean-max` and `pacing-portrait` scenarios play the sample game without end at each lean, for the
frame pacing check.

## Phone checks

[docs/phone-checks.md](docs/phone-checks.md) is the runbook for testers. `scripts/phone-checks.sh` (POSIX `sh` and `adb`
only) installs the APKs, makes sure the model is installed (the app's download first, Install from files when Hugging
Face cannot be reached), pushes click tracks and songs to `/data/local/tmp/ensomi-checks`, runs each group of the
instrumented checks (`app/src/androidTest/kotlin/io/ensomi/android/checks/`) with `am instrument`, and packs the results,
logcat and the device's facts into one `.tar.gz`. `scripts/make-phone-kit.sh` assembles the kit testers get (APKs, the
model checked against the pin, click tracks, songs).

Each check logs one JSON object per result under the tag `EnsomiPhoneCheck` (split into `part i/n` lines like the
Diagnostics JSON when longer than a logcat line) and appends it to `results.jsonl` in
`/sdcard/Android/media/io.ensomi.android/phone-checks/`, beside CSV files of frames and timestamps:

```sh
adb shell am instrument -w -r -e class io.ensomi.android.checks.RuntimeCheck \
  io.ensomi.android.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s EnsomiPhoneCheck:I -v raw
```

## Diagnostics output

The independent Diagnostics activity reads device facts and previous process exits without creating app stores
or loading the runtime. **Share diagnostics** opens the Android chooser with a redacted ZIP; **Copy JSON**
copies the schema-2 summary. The optional runtime self-test expects the empty package to be refused.
See [the schema and privacy contract](docs/diagnostics.md). The summary also goes to logcat under `EnsomiDiagnostics`:

```sh
adb logcat -d -s EnsomiDiagnostics:I -v raw
```

JSON that fits in one logcat line is printed as is. Longer JSON is split into lines that start with
`part i/n `; remove that prefix and join the pieces in order.

## The recognition session

Listen & Play ships in the public APK. Once the model is ready, open it beside Add songs, allow microphone and
music access, and follow the guide in English or Simplified Chinese. Download the song as MP3, let it finish, then
share it to Ensomi. The shared-song session finds the file, makes its chart and lines up locally with the room’s music.
Every build needs a runtime AAR with the `SyncSession` binding. See [docs/recognition.md](docs/recognition.md).

Ensomi never operates another app. It uses no Accessibility service, reads no other app's screen, and makes no taps for the person. The person recognises the song, downloads it and shares it to Ensomi themselves; Ensomi explains each step, then waits for the share.

## Project layout

- `app/src/main/kotlin/io/ensomi/android/`: the app. `Destinations.kt` and `EnsomiApp.kt` hold the
  navigation, `AppStores.kt` the app's stores; each feature has its own package: `library/`, `model/`, `chart/`,
  `play/`, `settings/`, `audio/`, `diagnostics/`.
- `docs/`: the design, the gameplay design, the build plan, the phone checks' runbook and the recognition session.
- `scripts/`: the screenshot capture script, the Perfetto frame-timeline script, the click-track generator, the phone
  checks' collection script and the kit script.
- `app/src/test/kotlin/`: unit tests that run on the JVM.
- `app/src/androidTest/kotlin/`: the phone checks, instrumented tests that measure on a device and log their results.
- `app/src/checks/`: the checks build's resources (the debug tools on in a build that is not debuggable).
- `app/src/recognition/`: the public guide and recognition session, compiled into every build.
  `app/src/release/keepRules` holds release keep rules, and `app/src/testDebug/` the recognition JVM tests.
- `gradle/libs.versions.toml`: every pinned version.

## License

Apache License 2.0. See [LICENSE](LICENSE).
