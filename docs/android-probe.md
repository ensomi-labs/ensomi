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
| S3 Play setup | Up to the audio choice | A library song can be chosen and its length read; the star control, sections and Generate report that the device engine is not linked instead of pretending |
| S4 Play scene | Not started | Needs the runtime binding and the audio clock |

Two storage decisions are temporary and should not spread. The library document is `filesDir/library.json`
rather than the app core's SQLite, because the app core does not exist on Android yet; the settings share
the Apple keys so a value still means the same thing across clients. The library and settings rules are
unit-tested on the JVM, as the probe's rules are.

The bottom bar is lifted above the gesture strip: on this ROM the last few dp of the window belong to the
system home gesture, and a tab row flush with the bottom edge is untappable rather than merely low. The
first version of S3 also crashed the app by nesting a `LazyColumn` in a vertically scrolling column; the
list is a plain column now, which is what a page this size wants anyway.

## Building and installing

```
cd clients
./gradlew :android:assembleDebug
adb install -r android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:test
```

`clients/local.properties` holds the SDK path and is ignored; the JDK is the same 22 the JVM modules use,
and the Android module compiles to Java 17 bytecode.
