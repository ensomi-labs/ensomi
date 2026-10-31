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
| 1 (done) | Settings stored with Jetpack DataStore, the play orientation and each orientation's lean among them; the audio offset kept per output (routed `AudioDeviceInfo` type and name); Results' and calibration's Use buttons write it | Change every setting, force-stop, relaunch: values kept; JVM tests for the output key | — | — |
| 2 (done) | Song library: `OpenMultipleDocuments` for `audio/*`, persistable read permission, name, length and tags (`MediaMetadataRetriever`), stored as JSON in app storage; File not found, Find again, Remove | Push audio files to the AVD, add them through the picker with UI Automator, relaunch, delete one file | Files from Downloads and a cloud provider (checklist 7) | — |
| 3 (done) | Decoding with `MediaExtractor` and `MediaCodec` to float PCM at the file's rate: mono for generation, stereo for play; the MP3 lead rule the Swift app documents, met with Android's decoders | JVM tests of the MP3 header classes on frames the test builds; decode MP3, AAC, FLAC, Ogg and WAV on the AVD and compare lengths | The six click-track classes against the release engine's times (`ClickTrackDecodeCheck`) | 2 |
| 4 (done) | Model install: download from Hugging Face at the pinned tag over `HttpURLConnection`, `checkPackage`, per-file size and SHA-256, Range resume, install record, update on start, free-space check and reservation, licence and notices in Settings | JVM tests against a local HTTP server (the JDK's `com.sun.net.httpserver`): drop mid-file, resume, changed file, refused manifest; a real download on the AVD, killed and resumed | The installed package checked again (`ModelCheck`); download on mobile data and Wi-Fi (checklist 8) | 1 |
| 5 (done) | Making charts: `RuntimeWorker.withGenerator`, `prepare` when a song opens (the four latest `Prepared` kept in memory by the file's SHA-256), a whole-chart scheduler, phases, Stop through `Cancellation` from another thread, the runtime's messages; the last chart per song kept on disk | Generate a real chart on the AVD with the real model; Stop mid-way; 7 stars gives the runtime's refusal | Time and peak memory per song (the stack report's T10); whether `lowMemory` is needed (`GenerationCheck`, off and on; `DeterminismCheck`) | 3, 4 |
| 6 (done) | Audio output and clock: the `AudioTrack` writer with the lead-in, the timestamp clock (32-bit extension, hold rules, no backward step), pause and resume, music volume, audio focus, becoming noisy, route changes, underrun count | JVM tests of the clock against a scripted timestamp source; the AVD plays a song and the clock log is monotonic | Clock smoothness per route (`AudioClockCheck`, once per output) and the output latency per route (checklist 4) (gameplay.md) | 3 |
| 7 (done) | Play: the controller on the real chart and clock with touch; pause on lifecycle, focus, audio focus, noisy and route change; gesture exclusion for the outer lanes; Results from the score; Play again | A full game on the AVD with taps from `adb shell input` and the autoplay as a debug option; pause and resume at each trigger | Touch stamps, touch to light, multi-touch, a full game by hand; the pause when the output changes (the emulator has one output) (checklist 1 to 3, 5, 6); the pauses a test can make (`LifecycleCheck`) | 5, 6 |
| 8 (done) | Calibration: the click track through the play output, the tap pad as a `View` with event times, median of 16, apply per output; the picture step on the real clock | Taps from `adb shell input` at known times give the expected suggestion within a frame | Three runs per output agree within 5 ms (checklist 4) | 1, 6 |
| 9 (done) | Frame pacing: per-frame logs on the render thread, a Perfetto FrameTimeline capture script, the refresh-rate request checked; GLES only if the canvas misses frames | The logs and capture run on the AVD at 60 Hz, at each lean | 120 Hz pacing on the Redmi K80 and K Pad, 60 s autoplay of the densest chart at no lean, 40° and 55° (`FramePacingCheck`, in the debug build and the checks build; the runtime's cost per call in `RuntimeCheck`) and the frame log of a game played by hand: under 0.5 % of vsyncs missed; if drawing under the perspective matrix costs too much, notes drawn as quads projected on the CPU (`drawVertices`) before GLES | 7 |
| 9a | The lean in players' hands: landscape and portrait, the default lean and the range tried by people who play 4K on phones; the HUD corners, the outer lanes at the screen's edges, the back-gesture exclusion in landscape | — | Whether 40° and the eye distance read well at speed; whether thumbs land in the right lanes; whether the far notes are big enough (checklist 9, 10) | 7 |
| 10 (done) | Accessibility and finish: a TalkBack pass on every screen, the TalkBack notice before play, the short-window state, haptics wired | UI Automator dumps show labels; TalkBack walked on the AVD | — | 7, 8 |
| 11 (done) | Remove the prototype's samples once every store is real; keep the screenshot scenarios backed by fakes for review | Capture script still runs | — | 1–10 |
| 12 | Install from files (the owner's decision of 2026-10-29): the package's folder picked with `ACTION_OPEN_DOCUMENT_TREE` and copied into app storage with the download's checks; both ways in accept only the pinned manifest's SHA-256 | JVM tests of the copy, checks, pin and refusals (a missing or changed file, a copy cut short, cancel, no space); on the AVD, the real picker from `Download/ensomi-r2` and a refused folder; the script's fallback with the network off | The Redmi K80, which cannot reach huggingface.co, installs from files (`ModelCheck`) | 4 |
| 13 | Phone checks: the instrumented suite (`app/src/androidTest`), the checks build, `scripts/phone-checks.sh`, `scripts/make-phone-kit.sh`, the runbook (docs/phone-checks.md) | The suite and the script end to end on both AVDs, the Hugging Face path on one and the file-pick path on the other | The runbook on the Redmi K80, then the Redmi K Pad | 1–12 |
| 14 | The recognition session (docs/recognition.md), debug and checks builds only: a share to Ensomi, the song's file found, the chart, Sync on the runtime's aligner, a game joined at the room's position | JVM tests of share parsing, the file rules, the session in and out of order, time limits, `.ncm` refusal, the clock and retry; a whole session on an AVD with a WAV replay in place of the microphone; a release build's merged manifest unchanged | A session on the K Pad with the host, by hand: the share sheet, which file source fires, the room through the microphone, how the join feels | 1–13 and the runtime's `SyncSession` |

## Steps done

What steps 1 to 4 showed (`ensomi-36`, builds b1 to b3 of `claude/android-impl-1`):

- **1.** Every setting changed in Settings, the app force-stopped and launched: all kept in `settings.json`; the
  calibration's Use and Results' Use (the `results-stored` scenario) wrote the speaker's offset, which a restart kept;
  JVM tests cover the output key, the route order and per-output offsets.
- **2.** Nine click tracks pushed to Downloads and added at once through the picker, kept across a restart; a deleted
  file showed File not found, Find again brought it back at once under its new name, and Remove forgot it.
- **3.** WAV (44.1 and 48 kHz), FLAC, Ogg Vorbis and Opus decoded to ffmpeg's frame counts, the first click at 1000.1 ms;
  MP3 with a LAME delay (CBR and VBR) at 1000.1 ms and without a Xing header at 1025.1 ms (the 529 frames
  `c2.android.mp3.decoder` trims put back, as BASS keeps them), AAC at 1000.1 ms with its 1024 frames of priming off;
  compressed files keep their end padding (up to 936 frames). Stereo mixed down equals mono; a deleted file reports
  itself missing.
- **4.** The 120.4 MB package downloaded from Hugging Face; force-stopped at 42 %, the app showed the stopped download,
  and Resume asked for `beatthis.onnx` from byte 51817683 (HTTP 206), checked every file, had the runtime open the
  model and wrote `ensomi-install.json` (`r2-1.0.1` and the manifest's SHA-256); the licence, model card link and the
  three notices show; the install survived an emulator cold boot. JVM tests against a local server cover a drop
  mid-file, resume, a server ignoring Range, a changed file, a refused manifest, a file not matching, no space, and
  Download again copying from the install.

What steps 5 to 7 showed (`ensomi-36`, builds b1 and b2 of `claude/android-impl-2`, with a 105-second MP3):

- **5.** The audio stages ran as the song opened (decoded in 2.2 s, prepared in 4.5 s), so Make chart took 1.2 s to a
  953-note chart, kept on disk: after a force-stop the library said "1 chart" and the song opened on Chart ready. Stop
  ended each stage: decoding, Finding the beat (Stopped within about 2 s, the adb and UI dumps included) and the chart
  stage; 7 stars gave the runtime's refusal `star_out_of_range: star must be in [2, 6)`. **`lowMemory`**: by default the
  audio stages peaked at 1.8 GB resident and left 0.7 GB available, and the low-memory killer ended seven background
  apps; with `lowMemory` they peaked at 1.1 GB (1.7 GB left, no kill) in 6.4 s instead of 4.5 s, the chart stage alike
  (1.1 s). So the app turns it on below 7 GiB of memory (`GeneratorChoices`), which covers the emulator and 6 GB phones;
  phones sold with 8 GB or more keep the faster default until their own measurement (the phone check) says otherwise.
  With it chosen so (b2), the emulator logged `lowMemory true: 3.8 GiB of memory`, peaked at 1.07 GB and kept 1.8 GB
  available.
  The same seed makes the same chart only with the same option, which a device keeps.
- **6.** A whole autoplay game on the `AudioTrack` clock: 6 622 frames from −1 961 to 104 807 ms, song time and render
  time never went back; 181 timestamps read, 29 steps back held (all under 2 ms, from the emulator's computed
  position), 0 underruns; the track at 44.1 kHz float stereo with a 400 ms buffer, its gain the music volume. JVM tests
  cover the clock against a scripted output (lead-in, 32-bit wrap, steps back and forward, pause and resume, polling,
  a whole song with jitter, an underrun and a pause).
- **7.** The autoplay played the song to Results (953 Perfect, 3 Good, 34 Miss: it leaves every 29th note alone) and
  Play again started a new game; taps from `adb shell input` were judged (1 Perfect, 117 Good, 872 Miss, 2 late
  inputs). The game paused and resumed where it stopped, sound included, on the button, Back, leaving the app, the
  notification shade, an emulated phone call (audio focus) and `ACTION_AUDIO_BECOMING_NOISY`, and the launcher icon and
  Recents both brought back the same paused game (one activity, b2); a change of output could
  not be made, since the emulator has one output (unverified, a phone check). Both back-gesture exclusion rects were
  set. The emulator's own recorder kept the sound of a whole game. The judgement word and combo are drawn under the
  notes, so a note crossing them in the middle lanes stays whole.

What steps 8 to 11 showed (`ensomi-36`, builds b1 to b3 of `claude/android-impl-3`):

- **8.** The click track through the play output, recorded by the emulator's own recorder: clicks 500.04 ms apart on
  average (500.0 to 500.6). 24 taps from `adb shell input tap` were all taken; recomputed from their logged event
  times and the logged time of click 0 alone, their song times and errors matched the app's within 0.012 ms and the
  suggestion was the app's (+8 ms, against 16.7 ms allowed); Use wrote it for the speaker and Start over cleared the
  taps. The picture step drew the clicks' notes with the player's lean on the clicks' clock at 60 fps; the clicks
  paused on Home and went on from where they stopped (252 timestamps read, 19 steps back held, 0 underruns). The
  phone check (three runs per output within 5 ms) is not done.
- **9.** At no lean, 40° and 55° in landscape and flat in portrait the sample game held 60 fps with no missed vsync and
  no frame past its deadline; the whole frame's work was 2.4 to 2.9 ms at the median and 4.0 to 5.2 ms at the 99th
  percentile (a debug build on the Mac). A real game of a 990-note chart: 6 456 frames, 0.31 % of vsync gaps over
  1.5 intervals, work 2.1 ms median, 9.0 ms p99. The request asked for 60 Hz, the AVD's one mode, and SurfaceFlinger
  shows the SurfaceView's vote (60 Hz, default compatibility, only if seamless). FrameTimeline lists the app window's
  frames but not the SurfaceView's (gameplay.md). The emulator cannot say whether the canvas is fast enough on a phone,
  so GLES is not started; the 120 Hz phone check decides.
- **10.** UI Automator dumps of every kind of screen: every control has a label, on itself or on its text or icon
  (Compose's buttons keep it on the child). TalkBack 16 walked the Library, a song, Settings and Calibration in order
  without an unnamed stop (Settings' scroll-speed slider took two stops in the same place, not yet explained); the notice before play, a dialog since b3, took TalkBack's first focus and held the game
  until Start anyway was double tapped (Back returned to the song); with a window 600 px wide (228 dp tall in
  landscape) the game paused and said the window was too small, and the pause panel came back with the size.
- **11.** The scenarios' fakes moved to `prototype/Fakes.kt`; the feature packages hold no samples and no screen says
  "not built yet". The capture script ran every group (135 files), now with UI dumps of every screen, Perfetto at each
  lean and a real game to its results, and holds the rotation itself (`wm user-rotation`).

What steps 12 and 13 showed on the emulators (builds b2 and b3 of `claude/android-phone-checks`; the emulator's
timing, not a phone's):

- **12.** `ensomi-36-16k` downloaded the model from Hugging Face through the app (290 s; record `source:
  huggingface`). `ensomi-36`, cut off the network, failed the download at once ("The connection was lost."), the
  script copied the kit's files to `Download/ensomi-r2`, and the real system picker installed them (record `source:
  files`). A copy of that folder with one byte of `runtime.json` changed was refused with "runtime.json did not match
  the package's manifest." and left nothing installed. The manifest at `r2-1.0.1` hashed to the pinned SHA-256 on the
  Mac, and `ensomi-36`'s earlier download had recorded the same. After the offline failure the card said nothing,
  since a download that keeps nothing fell back to "not installed" without its reason; it now says why.
- **13.** On both AVDs the script ran every group to its archive. The runtime loaded and its 52 UniFFI checksums read
  back stable (26 of them at 0x8000 or above, none with high bits through JNA); a frame's runtime calls took 0.58 ms
  at the median and 2.0 ms at the 99th percentile in the debug build. All nine click tracks landed within 0.03 ms of
  the release engine's times. The synthetic four-minute song made a 1 600-note chart in 8.7 s with `lowMemory` off
  (prepare 6.5 s, peak 1 930 MB resident) and 9.6 s with it on (prepare 7.6 s, peak 872 MB), the same `.osu` both
  times and in the determinism check's two fresh runs. The audio clock never went back over three minutes (timestamp
  residual sd 0.09 ms, 0 underruns); once, the vsync thread's preferred presentation time went back by a frame,
  which the check now counts apart (the playfield's own frame logs never went back, but repeated a presentation time
  one to three times a minute). The checks build, not debuggable, took the runtime part of a frame from 1.2 ms to
  0.5 ms at the median against the debug build on the same AVD, so frame pacing is judged on it. Every lifecycle
  interruption paused the game on `ensomi-36-16k`; on `ensomi-36` the pause panel after Back was not found in time,
  which then left Home without a new pause, so each interruption now starts from a game that plays.
- **b3**, with those fixes: all 20 check runs on both AVDs passed (68 results, none not ok), the five interruptions
  included. A real 105-second song made the same chart (one `.osu` SHA-256) on both AVDs, with `lowMemory` off and on,
  and in the determinism runs. The Mac's connection dropped `ensomi-36`'s download at 75 of 120 MB; the script fell
  back to Install from files through the real picker, as on a phone without Hugging Face. At 60 Hz the checks build met
  the frame targets (under 0.5 % late, work p99 under 3 ms) at every lean on `ensomi-36-16k`, and the debug build did
  not; the emulator's numbers decide nothing about the Redmi K80's 120 Hz.

What step 14 showed on the emulators (builds b2 to b4 of `claude/android-recognition`; the emulator's timing, no host app
and no driver: a share from adb, files from a shell, a WAV of the song in place of the microphone):

- **14.** A share alone started a session on a 105-second song already on the device and went through to the game: decoded,
  a chart made in 6.2 to 7.6 s, a final lock from the runtime's aligner after 6.6 s of audio, 0.84 ms from where the replay
  really was (1.43 ms on a start at 97 s). The game joined 4 s past the heard position, keeping 512 of 856 notes, and the autoplay
  played it to the end with no `AudioTrack` of the app's in `dumpsys`. Its 4 071 frames fit the anchor to 2e-11 ms with no step
  back, through a pause too, which jumped forward by the time paused. A wrong song gave no lock in 60 s and no game; a start too
  near the end locked, and the join was refused. A file arriving as `.tmp` was ready 2 s after its rename; a 1 MB fragment under
  the final name was refused as `TRUNCATED` (43.5 s decoded of 104.8 s declared) until the rest came; a `.ncm` was refused with
  no `decode` line, and an MP3 after it went on. A session the process died with picked up from its snapshot; one that died in
  a game said it was lost. `FileObserver` fired through FUSE with all-files access on and off. A file outside the watched folder
  was found by MediaStore alone, 2.5 s after its row stopped being pending, and not before: a file a shell writes stays pending
  with no date until a scan, which is why the query looks for such rows and only traces them. The AVD's microphone gave a
  timestamp for all 1 200 blocks, 0.8 to 14 ms before the read returned. A release build's merged manifest, permissions and
  badging are the same as without this step. `AudioClockCheck`, `LifecycleCheck` and `FramePacingCheck` passed on the same build.
  Nothing here says how a room sounds, or how the host behaves (recognition.md, What is not known yet).

## Pins the steps add

All in `gradle/libs.versions.toml`, each chosen when its step starts: Jetpack DataStore 1.3.0 and
kotlinx-serialization-json 1.12.0 (step 1, for the settings and library files; the same version as the serialization
core), AndroidX Test runner 1.7.0, core 1.7.0, JUnit extensions 1.3.0 and UI Automator 2.4.0 (step 13, the phone
checks only), and WorkManager only if the owner wants the model download to continue in the background.

## Runtime changes

None is needed for this design; every call it makes exists in the runtime's Kotlin bindings today. Two may become
useful and are listed, not made:

- **One call per frame.** A frame calls the runtime through JNA only for what can have changed (gameplay.md, Frame
  pacing): `advance` every frame, `noteGeometry` per visible hold, and the rest around inputs and events. If a release
  build's calls still cost more than about 1 ms per frame, one call that advances the session and returns the visible
  notes with their positions, lane brightness, recent batches, presentation and score would replace them.
- **Phases from the scheduler.** A whole-chart scheduler reports no phases, so "Placing notes" and "Arranging lanes"
  cannot be told apart, as in the Swift app. A listener on the scheduler would let the bar show both.

A released runtime AAR, pinned in the version catalog instead of the local file, is already planned in the catalog's
comment and is not part of this design.
