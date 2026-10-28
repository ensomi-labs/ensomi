---
commit: eecff2b
---

# The Android probe: how the client was started

This document records the first Android vertical slice: what was verified on the target device, what
the `clients/android` module does today, and which of the report's assumptions are still open. It
exists because the research report's first phase is an evidence inventory, not an implementation, and
the difference between "a public repository does this" and "this device does this" decides the
architecture.

The proposal this implements is [Windows and Android clients](platform-clients.md); the research it
follows is `deep-research-report.md`.

## The frozen target

Every result below belongs to these versions and to no others. Anything measured later must be tied to
the same three values.

| What | Value |
| --- | --- |
| Device | Redmi K80, model `24117RK2CC`, `ro.product.device=zorn` |
| Android | 16, sdk 36 |
| Build | `OS3.0.307.0.WOKCNXM` (fingerprint `Redmi/zorn/zorn:16/BP2A.250605.031.A3/OS3.0.307.0.WOKCNXM:user/release-keys`) |
| NetEase | 9.2.45, versionCode `9002045`, targetSdk 33, installed 2025-01-01, last updated 2025-02-09 |

The APK is 189.5 MB in 20 dex files with 939 activities, 113 of them exported.

## What the device confirmed

**A third-party app can open a song.** `orpheus://song/31861269` resolves to
`com.netease.cloudmusic/.activity.RedirectActivity` and `am start -W` reports `Status: ok` with the song
page open. This is the report's Architecture A shortcut, and it holds on the target.

**A third-party app can open the recognition page.** `orpheus://identify` also resolves to
`RedirectActivity`, and it lands on 听歌识曲 with its microphone permission prompt. The report lists this
as `Unsupported` ("本次未找到具体、可复现证据"); on 9.2.45 it works, and the probe app reproduced it as a
third-party caller, not through adb: its trace records `recognition.launch` with `"started": true`, and
the device showed `IdentifyActivityV2` on screen afterwards. The ladder's UI path therefore does not need
to find its way to the recognition screen through tabs.

**The recognition activity itself is not reachable.** `IdentifyActivityV2` has no intent filter and no
`android:exported`, so it is private by default at targetSdk 33. `adb shell am start -n` fails with
`SecurityException: … not exported from uid 10317` — the shell cannot start it either. This is stronger
than the report expected: there is no privileged shortcut to borrow, and any recognition that is not the
direct protocol has to arrive through the exported `RedirectActivity`.

**`orpheus://recognize` is not the same thing.** It resolves, because `RedirectActivity` accepts the whole
`orpheus` scheme, but the screen it produced was an update dialog. Resolution is not behaviour; the probe
records both.

**The protocol family is present in 9.2.45.** The dex string tables contain `shazam_v2` (classes4),
`algorithmCode` (classes7, 14, 19) and `rawdatas` (classes19), next to `music/audio/match/similar` and
`music/file/match`. The exact literal `/api/music/audio/match` was not found as one string, so the
endpoint spelling still needs a source-level confirmation before a client posts to it.

**The download directory is under `Download/`.** The client keeps its library at
`/storage/emulated/0/Download/netease/cloudmusic/` (`Music`, `Dj`, `MV`, `Video`), not at the top level.
With all-files access the probe's `FileObserver` starts on `…/cloudmusic/Music` and reports `watching`;
without it the probe refuses the session instead of failing silently.

**The package manager shows less than `dumpsys` does.** `dumpsys package` lists 939 activities and 113
exported; the probe's own `getPackageInfo(GET_ACTIVITIES)` sees 843 and 26. Component visibility is
filtered per caller, so a count is only meaningful with the caller that produced it.

## What the probe harness does

`clients/android` opens on the client's workbench; the probe is the developer screen behind
Settings. The probe's job is to run the report's high-value experiments on a device and leave a trace
that can be compared later.

| Screen section | Experiment it serves |
| --- | --- |
| Device | The report's phase one: freeze build, HyperOS incremental, NetEase versionCode, and the recognition components with their exported flag |
| Deep links | Resolve every URI a client might use and start the two that matter, recording the component, the caller and the outcome |
| Download watcher | `FileObserver` on the NetEase download directory plus the correlation rules below |
| Capture | 5 s at 44.1 kHz mono, kept as a WAV with its monotonic bounds and its `AudioTimestamp` |
| Trace | One JSONL file, in the report's field names, with its path shown for `adb pull` |

The trace is written under the app's external files directory, so it can be pulled without root:

```
adb pull /sdcard/Android/data/io.ensomi.android/files/traces/android-<stamp>.jsonl
```

Every event carries `cid`, `seq`, `event`, `t_mono_ns` (`SystemClock.elapsedRealtimeNanos()`),
`wall_ms`, `process`, `netease_version_code` and `hyperos_build`. The wall clock is there for a human
reading the file afterwards; every latency is computed from the monotonic field.

The names a run can produce are: `session.start`, `device.frozen`, `link.resolve`, `link.launch`,
`recognition.launch`, `watch.start`, `watch.stop`, `watch.error`, `file.create` / `file.modify` /
`file.close_write` / `file.moved_to` / `file.delete`, `audio.candidate`, `audio.ready`, `capture.start`,
`capture.end`, `capture.failure`, `session.end`. A `capture.end` carries the WAV path, its RMS and peak,
the first and last `elapsedRealtimeNanos` readings of the block reads, the platform's
`AudioTimestamp` and the file's SHA-256.

### The correlation rules

A file the watcher saw is only the audio this session asked for when all of these hold, in this order,
which is the report's acceptance condition:

| Step | Rule | Verdict when it fails |
| --- | --- | --- |
| 1 | The event is `CLOSE_WRITE` or `MOVED_TO` | `IGNORE_UNFINISHED` |
| 2 | The name is not a downloader's temporary name | `IGNORE_TEMP` |
| 3 | The modification time is not older than the session | `IGNORE_BEFORE_SESSION` |
| 4 | The file has been quiet for at least one second | `WAIT_SIZE_STABLE` |
| 5 | A decoder opens it | `IGNORE_UNDECODABLE` |
| 6 | Its duration matches the recognized recording within 2 s | `IGNORE_DURATION` |
| — | Otherwise | `READY` |

The probes and the rule set are unit-tested on the JVM (`:android:test`), because the rules are plain
Kotlin and the value of a rule is that it says the same thing off the device.

### Direct recognition, confirmed on the device

A window captured by this phone answers the `audio/match` endpoint. With the source playing at 50,
100 and 180 s and a 5 s capture starting three seconds later, the endpoint returned song id `22765943`
with `startTime` of `52180`, `103040` and `183160` ms.

- **`startTime` is the capture window's start, not its end.** Each value sits where the capture began,
  not five seconds later; the residual is under 0.2 s. That moves `PositionSemantics` off `UNKNOWN`
  for this recording, but one song is not a calibration.
- **The window length is part of the request.** A 5.0 s capture answers when asked for 6 s and returns
  nothing for 5 s or 10 s. Whether the server requires at least 6 s, or the encoder needs samples past
  the end, is not separated yet.
- **The acoustic path needs level.** The same capture measured −2.9 dB peak; at −20 dB the app's own
  recognizer failed on the same music. A capture probe that reports RMS is measuring the right thing.

### What the client may not do yet

- **No fingerprinter in the app.** The fingerprint comes from the reference `afp.wasm` on a build
  machine; `clients/android` still reaches recognition through `orpheus://identify` (ladder level 3).
- **No download trigger.** `downLoadBtn` on the song detail page opens a playlist sheet in 9.2.45, and
  the full-screen player exposes no download control. Reaching the real enqueue needs the detail-page
  action row; enqueueing one from inside the NetEase process is still the report's open question.
- **MediaSession is live while NetEase plays.** `dumpsys media_session` reports
  `package=com.netease.cloudmusic`, `PLAYING`, `position=42268`, `speed=1.0` and an `updated` value on
  the monotonic clock, with the track's title and artist in its metadata. That is a cross-check for a
  song NetEase is playing, not a position for the ambient recording it recognized.
- **One recording measured.** Three offsets of one song; other songs, live versions and the start of
  a track are not measured.
- **All-files access.** Watching a directory by path needs `MANAGE_EXTERNAL_STORAGE` on the demo device.
  It is a probe affordance; a product client would take a persisted SAF grant instead.

### Where the next slice starts

The request side is settled and reproducible: a form post with `algorithmCode=shazam_v2`, `sessionId`,
`duration`, `rawdata`, `times=2`, `decrypt=1`. What is missing is computing `rawdata` on the device.
The reference implementation ships the same 231 KB `afp.wasm` and calls it from JavaScript, so the next
step is either running that module on Android or writing the spectral-peak fingerprint from Kotlin, and
then checking it against the three captures already on disk.

## The client shell

The app opens on the workbench the screen specification calls S1: a bottom bar over Library and Play,
and a Settings action in the top bar. The probe is no longer the entry point; it is a private activity
reached from Settings, because it is a developer surface and S5 gives Android no Developer section.

| Screen | State | What is real today |
| --- | --- | --- |
| S2 Library | Implemented | The system folder picker, a persisted grant per folder, an automatic scan on add, the four counts, last scan, rescan, and removal that leaves files on disk |
| S5 Settings | Implemented in part | Scroll speed (1–40), judge difficulty (A–E), music volume, visual offset, all stored under the `mania4k.*` keys; the audio offset needs the calibration sheet, which needs the engine |
| S3 Play setup | Complete for the device engine | A library song is decoded to mono PCM, the model generates a chart on the phone, and the setup reports the note counts, seed and star. Star and seed are inputs: the slider's range is the one the model reports, and the receipt records the star and seed that were asked for |
| S4 Play scene | Renderer and session | `PlayScene` draws the four lanes from the core's `visibleObjects` and `noteGeometry`, advances a `GameplaySession` on the audio clock, feeds presses through `InputTimeline`, and shows the core's own score when the run resolves. It plays the generated chart when the setup made one and a labelled demo pattern otherwise. A pause holds the player's output, so the clock, the advance and the input path all stop; a device test checks that the heard position freezes and continues from there |

Two storage decisions are temporary and should not spread. The library document is `filesDir/library.json`
rather than the app core's SQLite, because the app core does not exist on Android yet; the settings share
the Apple keys so a value still means the same thing across clients. The library and settings rules are
unit-tested on the JVM, as the probe's rules are.

The two platform-shell contracts that do not need the engine are implemented as well, so the session has
somewhere to plug in. `AnchorAudioClock` takes the output device's readings and answers both directions —
audio time at a host time, host time at an audio time — measures the drift from two readings instead of
assuming 1:1, and ignores a reading that arrives late or moves backwards. `InputTimeline` converts a
pointer event's `uptimeMillis` to `elapsedRealtimeNanos` by the measured offset between the two clocks,
then asks the audio clock for the song time: a press is placed where it happened, not where the frame
that handled it landed.

`LibraryPlayer` completes the audible half: `MediaExtractor` → `MediaCodec` → `AudioTrack`, with the
position taken from `AudioTrack.getTimestamp` and never from the decoder's read-ahead. On this API the
timestamp call returns a boolean rather than the status code the older documentation describes, and the
extractor's file descriptor has to outlive `setDataSource` or the first read fails; both cost a build
and a run to discover.

**The scene.** `PlayScene` is S4 built on that clock: it asks the session which objects are visible in the
render window, asks `noteGeometry` where each one sits, and draws the four lanes from those answers rather
than from its own layout arithmetic. A run on the K80 finished the five-second clip with the core's own
result — `Accuracy 0%`, `Max combo 0`, `Misses 8` — which is the session resolving eight notes that nobody
pressed, not the page counting anything. Presses enter through `InputTimeline`, so the timestamp is the
pointer event's, mapped through the audio clock.

The chart is the one thing the scene invents: a tap every half second, rotating through the lanes, because
the generator needs ONNX Runtime and a model. The subtitle says so on screen.

The bottom bar is lifted above the gesture strip: on this ROM the last few dp of the window belong to the
system home gesture, and a tab row flush with the bottom edge is untappable rather than merely low. The
first version of S3 also crashed the app by nesting a `LazyColumn` in a vertically scrolling column; the
list is a plain column now, which is what a page this size wants anyway.

## The runtime binding

The Android client links the same Rust core as the Apple and Windows clients. The library is built in the
runtime checkout beside this repository and is not committed, as the Windows DLL is not:

```
runtime/                       the runtime checkout beside this repository
$NDK=/path/to/android-ndk-r29
$NDK_TC=$NDK/toolchains/llvm/prebuilt/windows-x86_64

CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK_TC/bin/clang.exe
CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=--target=aarch64-linux-android26 \
  -C link-arg=--sysroot=$NDK_TC/sysroot"
cargo build --release -p ensomi-runtime-ffi --target aarch64-linux-android

cargo run --release -p ensomi-runtime-ffi --features bindgen --bin uniffi-bindgen -- \
  generate --library target/aarch64-linux-android/release/libensomi_runtime_ffi.so \
  --language kotlin --out-dir ../ensomi/clients/android/src/main/kotlin --no-format

cp target/aarch64-linux-android/release/libensomi_runtime_ffi.so \
   ../ensomi/clients/android/src/main/jniLibs/arm64-v8a/
```

Two details decide whether that works. The NDK's linker wrappers are `.cmd` files, which cargo on Windows
cannot execute, so the linker is `clang.exe` with `--target` and `--sysroot` passed as link arguments. And
the generated exceptions carry a field named `message`, which clashes with `Throwable.message`; rename the
two constructor properties and the two interpolations to `messageText`, as the Windows binding needs.

`jniLibs` is ignored: the shared library is a build product. The generated Kotlin binding is committed, so
the module compiles without a Rust toolchain and only a runtime check fails without the library.

**What the device proved.** The packaged library loads through JNA, and `parse_osu` on a one-note mania
beatmap returns `1 objects, 1000 ms` — the binding, the native core and the transport all work on the
phone. `ChartGenerator.open` then reaches ONNX Runtime: with the model package installed it answers
`generator opened`, and without it the error names the first missing file (`runtime.json`), which is how
the two halves were told apart. Generation itself is the next step: the package is in place, but no chord
has been generated on the phone yet.

**ONNX Runtime.** `ensomi-generate-ort` loads ONNX Runtime dynamically, so the client ships
`libonnxruntime.so` from `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` (18.5 MB, matching the
API version `ort 2.0.0-rc.11` expects) beside the runtime library. Both are build products in `jniLibs`
and neither links `libc++_shared`, so the APK carries no extra C++ runtime.

**The model package.** The published R2 package is the `onnx/` directory of `sed-i/ensomi-r2` at
`r2-1.0.1`. `huggingface.co` is unreachable from this machine and `hf-mirror.com` serves the same
revision, so the files were fetched from the mirror; nothing about the app depends on that.

The client installs a package by itself: Settings → Chart model → **Choose Model Folder...** takes the
folder that holds `ensomi-package.json`, and `ModelInstaller` copies what the manifest lists. The manifest
check is the runtime's `checkPackage`, not a second implementation, and every file's SHA-256 is verified
while it is copied, so an edited or truncated package is refused instead of half-used. What it wrote on the
K80:

```json
{"modelId":"ensomi-r2","modelName":"Ensomi R2","version":"r2-1.0","license":"MIT",
 "fileCount":30,"totalBytes":120377244}
```

That matches the package format's own count — 30 files and 120 377 244 bytes — and Settings reads the
record back as `Ensomi R2 r2-1.0 - 30 files, 114 MB, MIT`. **Remove Download** deletes only the model
directory. A generation run against this install still produces the same chart, so the installer and the
generator agree on what a package is.

**Downloading it in the app.** **Download Model** fetches the package itself: the manifest first, judged by
the same `checkPackage`, then every file it lists from
`hf-mirror.com/sed-i/ensomi-r2/resolve/r2-1.0.1/onnx/`, each verified against the manifest's SHA-256 as it
lands. A file already present at the right size is skipped, so a dropped connection costs only what it
dropped; a file that times out is retried up to three times before the install is called failed.

The first attempt reached 21 of 30 files and 102 MB before a socket read timeout stopped it, which the app
reported as `timeout`. After the retry was added, the next run skipped those files and finished the rest:
the record names the mirror as its source, and the determinism check passes against the downloaded
package — so the download, the installer and the generator agree on one package.

The runtime's own conformance case runs on the device too. `:android:connectedDebugAndroidTest` installs
both APKs and runs `GameplayConformanceTest` — the fixture `early_press_ignored.json`, written out in
Kotlin as on Windows — and it passes with the same judgement, tier, time, score and accuracy the fixture
expects:

```
io.ensomi.android.GameplayConformanceTest:.
OK (1 test)
```

That ROM intercepts an APK install with its own confirmation dialog, which the connected-test runner does
not answer, so it reports `INSTALL_FAILED_USER_RESTRICTED` and then a misleading `BUILD SUCCESSFUL`. The
same test runs from the shell when the dialog is accepted:

```
adb install -r android/build/outputs/apk/debug/android-debug.apk
adb install -r android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
adb shell am instrument -w io.ensomi.android.test/androidx.test.runner.AndroidJUnitRunner
```

**Generation on the phone.** `ChartGenerationService` decodes the chosen file to mono PCM, clamps the star
into the range `capabilities()` reports, and asks the generator for a chart. A sixty-second test track
produced `373 notes (303 taps, 70 long)` at star 2.0 in about ten seconds, and the scene then drew that
chart rather than the demo pattern. Decoding resamples to 22 050 Hz in Kotlin on purpose: the core's own
resampler needs the model's `resample-*` tables, and the client does not ship them.

**Saving the chart.** The setup writes the generated beatmap through the platform's create-document
picker. The picker enforces the extension its MIME type implies, so `text/plain` produced
`… (Ensomi).osu.txt`; `application/octet-stream` writes the name as typed, and the saved file starts with
`osu file format v14`, `Mode:3`, `Creator:Ensomi`, `Version:Ensomi r2 s0` — 390 lines, 10 780 bytes for the
test track, and it parses back through `parse_osu`.

**The same input gives the same chart.** `ChartDeterminismTest` runs on the device with a synthesized
signal and the installed model: two generations with the same star and seed produce the same `.osu` bytes,
the same object count and the same seed, and the generated beatmap survives `parse_osu` — the parser the
other clients read it with. One nuance came out of it: the parsed duration comes back 19 ms shorter than
the in-memory chart's, because the parser derives it from the beatmap's timed content, so the test compares
it with a tolerance instead of byte for byte.

The PCM crosses the binding as a `List<Float>`, which boxes one float per sample — about 1.3 million
objects for a minute of audio. It works, but a buffer-shaped parameter is the right fix if generation ever
runs on full-length songs.

## Building and installing

```
cd clients
./gradlew :android:assembleDebug
adb install -r android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:test
```

`clients/local.properties` holds the SDK path and is ignored; the JDK is the same 22 the JVM modules use,
and the Android module compiles to Java 17 bytecode.
