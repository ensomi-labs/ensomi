# Ensomi for Android: build plan for drop-in play

Small increments from the prototype to a playable app, each reviewable on its own. Each says what is built, how it is
checked on the emulator now (builds and captures run on the owner's Mac through Astra) and on a phone later, and what
it needs first. The design is in [design.md](design.md) and [gameplay.md](gameplay.md).

Each step replaces one sample of the prototype with the real thing behind the same interface; the step numbers are the
ones the prototype's "not built yet" messages name. A step is done when `assembleDebug`, `testDebugUnitTest` and
`lint` pass, its emulator check passes, and the screenshots of the screens it touches were looked at.

| Step | Builds | Emulator check | Phone check | Needs |
| --- | --- | --- | --- | --- |
| 0 | Design, prototype screens on sample data, playfield on a fake clock (this round), including landscape play, the lane lean and its live preview | Every screen and state captured light and dark, 200 % font, landscape; the game at no lean, 40° and 55° in landscape and in portrait; playfield recording | — | — |
| 1 | Settings stored with Jetpack DataStore, the play orientation and each orientation's lean among them; the audio offset kept per output (routed `AudioDeviceInfo` type and name); Results' and calibration's Use buttons write it | Change every setting, force-stop, relaunch: values kept; JVM tests for the output key | — | — |
| 2 | Song library: `OpenMultipleDocuments` for `audio/*`, persistable read permission, name, length and tags (`MediaMetadataRetriever`), stored as JSON in app storage; File not found, Find again, Remove | Push audio files to the AVD, add them through the picker with UI Automator, relaunch, delete one file | Files from Downloads and a cloud provider | — |
| 3 | Decoding with `MediaExtractor` and `MediaCodec` to float PCM at the file's rate: mono for generation, stereo for play; the MP3 lead rule the Swift app documents, met with Android's decoders | JVM tests of the MP3 header classes on frames the test builds; decode MP3, AAC, FLAC, Ogg and WAV on the AVD and compare lengths | The six click-track classes against the release engine's times | 2 |
| 4 | Model install: download from Hugging Face at the pinned tag over `HttpURLConnection`, `checkPackage`, per-file size and SHA-256, Range resume, install record, update on start, free-space check and reservation, licence and notices in Settings | JVM tests against a local HTTP server (the JDK's `com.sun.net.httpserver`): drop mid-file, resume, changed file, refused manifest; a real download on the AVD, killed and resumed | Download on mobile data and Wi-Fi | 1 |
| 5 | Making charts: `RuntimeWorker.withGenerator`, `prepare` when a song opens (the four latest `Prepared` kept in memory by the file's SHA-256), a whole-chart scheduler, phases, Stop through `Cancellation` from another thread, the runtime's messages; the last chart per song kept on disk | Generate a real chart on the AVD with the real model; Stop mid-way; 7 stars gives the runtime's refusal | Time and peak memory per song (the stack report's T10); whether `lowMemory` is needed | 3, 4 |
| 6 | Audio output and clock: the `AudioTrack` writer with the lead-in, the timestamp clock (32-bit extension, hold rules, no backward step), pause and resume, music volume, audio focus, becoming noisy, route changes, underrun count | JVM tests of the clock against a scripted timestamp source; the AVD plays a song and the clock log is monotonic | Clock smoothness and the output latency per route (gameplay.md) | 3 |
| 7 | Play: the controller on the real chart and clock with touch; pause on lifecycle, focus, audio focus, noisy and route change; gesture exclusion for the outer lanes; Results from the score; Play again | A full game on the AVD with taps from `adb shell input` and the autoplay as a debug option; pause and resume at each trigger | Touch stamps, touch to light, multi-touch, a full game by hand | 5, 6 |
| 8 | Calibration: the click track through the play output, the tap pad as a `View` with event times, median of 16, apply per output; the picture step on the real clock | Taps from `adb shell input` at known times give the expected suggestion within a frame | Three runs per output agree within 5 ms | 1, 6 |
| 9 | Frame pacing: per-frame logs on the render thread, a Perfetto FrameTimeline capture script, the refresh-rate request checked; GLES only if the canvas misses frames | The logs and capture run on the AVD at 60 Hz, at each lean | 120 Hz pacing on the Xiaomi, 60 s autoplay of the densest chart at no lean, 40° and 55°; if drawing under the perspective matrix costs too much, notes drawn as quads projected on the CPU (`drawVertices`) before GLES | 7 |
| 9a | The lean in players' hands: landscape and portrait, the default lean and the range tried by people who play 4K on phones; the HUD corners, the outer lanes at the screen's edges, the back-gesture exclusion in landscape | — | Whether 40° and the eye distance read well at speed; whether thumbs land in the right lanes; whether the far notes are big enough | 7 |
| 10 | Accessibility and finish: a TalkBack pass on every screen, the TalkBack notice before play, the short-window state, haptics wired | UI Automator dumps show labels; TalkBack walked on the AVD | Haptic delay by video; decide whether to offer Vibrate on lane touch | 7, 8 |
| 11 | Remove the prototype's samples once every store is real; keep the screenshot scenarios backed by fakes for review | Capture script still runs | — | 1–10 |

## Pins the steps add

All in `gradle/libs.versions.toml`, each chosen when its step starts: Jetpack DataStore (step 1),
kotlinx-serialization-json (step 2, for the library and settings files), and WorkManager only if the owner wants the
model download to continue in the background.

## Runtime changes

None is needed for this design; every call it makes exists in the runtime's Kotlin bindings today. Two may become
useful and are listed, not made:

- **One call per frame.** A frame makes about ten runtime calls plus one `noteGeometry` per visible note, each through
  JNA. On the emulator with the host GPU they took 1.0 to 1.5 ms per frame on average (debug build); the phone measures the release
  build in step 9. If they cost more than about 1 ms there, one call that advances the session and returns the
  visible notes with their positions, lane brightness, recent batches, presentation and score would replace them.
- **Phases from the scheduler.** A whole-chart scheduler reports no phases, so "Placing notes" and "Arranging lanes"
  cannot be told apart, as in the Swift app. A listener on the scheduler would let the bar show both.

A released runtime AAR, pinned in the version catalog instead of the local file, is already planned in the catalog's
comment and is not part of this design.
