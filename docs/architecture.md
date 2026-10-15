---
commit: 353ad2d
---

# Ensomi architecture

This document describes the implemented application contracts. `EnsomiMac` and
`EnsomiIOS` compose the shared `EnsomiUI` workbench; `EnsomiCore` owns domain
models and services. Feature models coordinate asynchronous work and publish UI
state. Audio processing and alignment run on workers; judgement logic is
synchronous and independent of SwiftUI.

## Recognition and local audio

The macOS debug flow captures microphone or system audio, calls ACRCloud, maps
the result to `RecognitionSnapshot` and `CanonicalTrack`, and resolves it against
the SQLite local library. Recognition identifies a track; synchronization finds
the position inside a selected local recording. A provider's reported offset is
not a substitute for aligning that recording.

[`LocalTrackResolver`](../Sources/EnsomiCore/Services/LocalLibrary/LocalTrackResolver.swift)
ranks ready or partially indexed assets using ISRC, title, artists, album,
duration, and filename evidence. Close competing automatic matches require user
confirmation. File access and reference preparation must succeed before capture
starts for alignment. Recognition capture and alignment capture have separate
lifecycles; stop the previous capture before starting the next. Session identity
checks prevent stopped or replaced work from publishing into a new run.

The local audio file is the reference used for alignment and generation. Directory
bookmarks preserve user-granted access; file access must remain valid through the
operation that consumes it. Missing files, failed decoding, ambiguous local
matches, and weak alignment evidence remain distinct failure conditions.

## Alignment and time

[`AmbientSyncReferenceIndexBuilder`](../Sources/EnsomiCore/Services/AmbientSync/AmbientSyncReferenceIndexBuilder.swift)
decodes and resamples reference audio with the same feature configuration as live
capture. Cached indexes are accepted only when their schema, feature configuration,
and source identity match. Identity validation checks source path, size,
modification time, and SHA-256. Invalid or stale cache data must not become a
reference for matching.

[`AmbientSyncSessionEngine`](../Sources/EnsomiCore/Services/AmbientSync/AmbientSyncSessionEngine.swift)
uses Sonalign for the exact `.v2` configuration. Legacy and custom configurations
use the Swift engine so unsupported native settings are not silently discarded.
One serial worker owns native session construction, processing, and destruction.
A native processing error invalidates that session; subsequent calls rethrow the
error until a new session is created.

Alignment uses recorded audio timestamps, with `referenceTimeMS =
recordedTimeMS + offsetMS`. The estimate refers to the query's audio endpoint,
not processing completion. Host-time projection compensates for elapsed time
between that endpoint and receipt. Capture must preserve sample and host timing;
replacing it with callback delivery time changes synchronization.

Provisional, confirmed, and final locks are distinct. Only a final lock triggers
the play handoff, which Listen & Play takes; the debug recognition window stops at
the lock. The handoff creates a host-time-anchored clock from the lock's anchor,
with no correction afterwards, and does not replay the local reference audio. The
clock has no clamp: the chart's duration, measured from the decoded audio, ends
play, so a rounded library duration cannot stop the clock early. The shared play
model drives both the workbench Play tab and the macOS play window.
See [`LiveRecognitionSyncModel`](../Sources/EnsomiUI/Features/Recognition/LiveRecognitionSyncWindow.swift)
and [`Mania4KPlaySessionModel`](../Sources/EnsomiCore/Features/Mania4K/Mania4KPlaySessionModel.swift).

## Listen & Play

The macOS Live Demo entry opens Listen & Play in Debug and Release builds. It
generates with R2 on the device, through the same `ChartModelLibrary` and
`OnDeviceChartGenerator` as Generate a Chart's on-device engine; no generation
service runs. It is offered when that model is installed: without one, the
window offers the download or a folder and capture never starts, and a model
that does not open is reported without starting capture. The star control takes
the model's range `[min_star, max_star_exclusive)` in 0.1 steps and is brought
into it when the model opens; the seed field follows Generate a Chart, and a
blank one draws a new seed.

Recognition, local resolution and Sonalign locking are those of the debug flow.
When sync starts on the resolved recording, [`LiveGenerationDemoModel`](../Sources/EnsomiUI/Features/Recognition/LiveGenerationDemo.swift)
makes a [`ScheduledChart`](../Sources/EnsomiCore/Services/Beatmap/ScheduledChart.swift)
for that file with the star and seed set then, which stay fixed for that chart:
a runtime `ChartScheduler` built from the recording's kept audio stages when the
generator holds them for its SHA-256 (the library's), otherwise from its decoded
PCM, and its first `work()` starts at once, alongside alignment. With no play
started, that `work()` generates the whole chart and returns `.done`; the
recording's `Prepared` is then kept for its next chart. Final lock calls
`startAmbientChartPlay` with that chart, never a new one. Play shows loading
until the chart is whole, then calls `start(atMs:session:)` with the chart time
heard at that moment and a new `GameplaySession.streaming`, and the next `work()`
appends the chart from the join point to that session and returns
`.ready(joinMs:)`. The join and lead are the runtime's: the lead is
`LeadPolicy(ruleset:, renderLeadMs: <the playfield's scroll time>, marginMs: 0)`,
and play joins at the first time at least that lead past the heard position with
no hold open. Objects before it are not in the session. `start` joins from the
time it is given while the music runs on, so it is called only once the chart is
whole, not at final lock. Each frame passes the heard chart time to the
scheduler's `heard`. Alignment sets one anchor at final lock with no tracking
afterwards. Stop, Recognize again and a newly resolved recording stop the
scheduler. Operator steps are in the [manual](live_generation_demo.md).

## Chart generation

Every chart the app makes on the device comes from the runtime's `ChartScheduler`
in `ScheduleMode.wholeChart`, which commits the chart in one page at the end:
`OnDeviceChartGenerator.scheduleMode` names the mode for every scheduler the app
makes. Play while generating (`.aheadOfPlay`) waits for a streaming R2. A chart
that is already complete, Generate a Chart's or one being inspected, needs no
scheduler: play judges it with a plain `GameplaySession(chart:ruleset:)`.

Local-audio generation uses a complete-chart exchange, made by
one of two engines that answer the same questions: capabilities, a complete chart,
progress by phase and a stop. `Mania4KChartGenerationModel` picks one: the on-device
engine when a model is installed and the person has not chosen otherwise, else the
chart server. The app's controls, its own request check, the play path and
`Mania4KGeneratedChart` are the same for both; what each engine accepts is its own. The
runtime is the production engine for released models. The model service is a development
server for whatever ensomi-model's Python system currently does, and it need not refuse what
the runtime refuses. The on-device engine is described after the server contract.

[`ChartGenerationClient`](../Sources/EnsomiCore/Services/Beatmap/ChartGenerationClient.swift)
opens a binary websocket (`ENSOMI_SERVICE_URL`, default `ws://localhost:8765`), sends
`capabilities_request` with an empty session ID (reply: model name and version, star
range `[min_star, max_star_exclusive)`) and `generate_chart` with a session ID, audio path,
star, seed and `ChartControls.sections`. Section targets map to either a star or
a pattern kind and strength. The app reads the local audio duration and requires
ordered endpoints inside the song, no overlapping sections (adjacency is allowed),
spans of 8–32 seconds whatever the target (the runtime's rule), strength in `[0, 1]`, and
section stars in the song's selectable range. Capability statuses determine which section
kinds are offered; long-note controls remain unspecified. The service replies with `generation_progress`
phases, then one `chart_result`, or an `ErrorEvent` whose string `code` names the
failure; `stop_session` is answered by a stopped status. Generation state owns the
setup panels' phase, readiness and error message. There is no `ready` or node hello. Chart times are whole-millisecond
audio time, unshifted; the app's offsets apply at play time. `Mania4KGeneratedChart` has the
gameplay core validate the chart (it rejects out-of-range lanes or times, unsorted objects and
same-lane overlaps), then plays it with its audio from time 0, with every note.

The on-device engine ([`OnDeviceChartGenerator`](../Sources/EnsomiCore/Services/Beatmap/OnDeviceChartGenerator.swift),
details in [solo device mode](solo-device.md)) decodes the chosen file with `AVAudioFile` to
mono Float PCM at the file's own rate (channels averaged in Double) and gives it to the runtime's
`ChartGenerator`, which opens once on a model directory and keeps ONNX Runtime's CPU sessions.
When a song is chosen, the generation model starts `prepare`: the stages that read only the audio
(PCM preparation, the spectrogram, BeatThis, the features and the beat grid), most of a
generation's time. Choosing another song cancels it, and a cancelled preparation keeps nothing.
Generate waits for the preparation of the song it generates, or starts one, then runs the chart
stages from the `Prepared` through a whole-chart scheduler. The generator keeps each song's
`Prepared` in memory for its model, keyed by the audio file's SHA-256, the four most recent songs;
nothing is stored on disk. Progress shows loading while the file decodes, timing while the audio
stages run and rows for the chart stages, which report no phases of their own. The generation
model maps what it already has to the runtime's types and back: `ModelCapabilities` to the
protocol `ModelCapabilities` (so the same statuses decide the section kinds), the sections to
`ChartControls` (long notes stay unspecified), and the runtime's `Chart` to
`Mania4KGeneratedChart`. A refusal arrives as `GeneratorError` and is shown as the core's
message. The seed comes from the seed field, or is a new random one when the field is blank. Stop
cancels the generation task and a preparation it waits for, which throw `GeneratorError.Cancelled`
at the core's next graph run, and the state becomes stopped. Either engine's chart ends as a
`Mania4KChartGenerationResult`: the chart, the seed and star the engine reports in its receipt,
the receipt JSON and, on this device, the `.osu`. Saving the `.osz` passes that `.osu`, the song
file's bytes and the receipt to the runtime's `exportOsz`, so every client writes the same archive.
The model is the published Ensomi R2 download in Application Support or a folder the person chose;
`ChartModelLibrary` owns that choice, opens the generator and keeps folder access across launches
with a bookmark. The download reads the package manifest (`ensomi-package.json`), checks it with the
runtime's `checkPackage` and fetches exactly the files it lists, each checked against the size and
SHA-256 the manifest gives. A download that stops keeps its files and resumes with HTTP Range requests.
The install records the tag it came from and its manifest's SHA-256 in `ensomi-install.json`
(`ChartModelInstallRecord`). When the library starts with an install whose record names another
tag than `ChartModelSource.pinnedRevision`, or that has none, it downloads the pinned tag over it,
copying the files that are unchanged, and the installed model stays in use until that succeeds.
The manifest's `model.version` stays the model identity the runtime checks; a model on this device
from the download is named with the tag after it, "Ensomi R2 r2-1.0 · package r2-1.0.1".

## Gameplay and scoring

The session applies offsets exactly once:

```text
gameplayChartTimeMs = audioTimeMs + audioOffsetMilliseconds
renderChartTimeMs   = gameplayChartTimeMs + visualOffsetMilliseconds
scrollTimeMs        = 11485 / scrollSpeed
```

The audio time is the audio heard at a host time, not how far the decoder has
got. The local-file clock plays through `AVAudioEngine`: it takes the player
node's sample time at its last render, carries it to the asked host time at rate
1, and subtracts the output's presentation latency, so it is smooth between
render callbacks and monotonic while the output stays the same. Audio offset
therefore corrects only what the device does not report. When the output device
changes, the clock rebuilds the engine for the new device and resumes from the
position being heard; until the resumed audio is heard, it reads earlier by the
new device's latency plus the render lead (13 to 22 ms on the built-in speakers,
about 45 ms on Bluetooth headphones), and the gameplay
core, which never moves its clock back, waits for it to catch up. Ambient play
projects from a host-time anchor instead. [Gameplay timing](gameplay-timing.md)
records the engine behavior behind this and what the audio offset still corrects.

The play model owns the music volume and the offsets and stores them in the app's
defaults (`mania4k.musicVolume`, `mania4k.offsetCalibrationState`). The music
volume is a fraction from 0 to 1 of the song's own level, so it only scales down;
the local-file clock applies it at the main mixer, as it applies silence, because
a lowered player volume makes the player's time drift against the device clock.
The audio offset belongs to the output device: macOS's default output device or
iOS's current route output, identified by its UID. A device's offset is a preset
with that UID, named for the device and without a visual offset, made the first
time the offset is set on it by Settings or calibration; a device without one,
including every device in state stored before device presets, uses
`Mania4KDefaultPlaySettings.audioOffsetMilliseconds`. Presets saved by name keep
both offsets and load into the calibration as before. The visual offset belongs
to the display and is one value for every device. While a game is in progress
(loading, playing or paused) the volume and offsets do not change, and a change of
output device applies its offset when the game ends; the clock still re-reads the
new device's latency at once.

Audio offset affects judgement and hit errors. Visual offset and scroll speed
affect rendering and lookahead only. A key event is stamped with its own
timestamp (`NSEvent.timestamp`, system uptime), converted to gameplay time
through the audio clock's host-time mapping, so judgement uses when the key was
pressed rather than when it was handled. Repeated presses are filtered. Each
frame delivers the inputs stamped up to its chart time in timestamp order, then
advances the session to that time; an input stamped later waits for the next
frame. The play view's display link makes the frames, one per display refresh,
and nothing else ticks the session: each frame judges at the current chart time
and is drawn for the time the display shows it (`targetTimestamp`). A frame the
session's chart has not covered fails the play; a whole chart covers every frame.
Paused sessions do not judge input or advance misses. The renderer
consumes snapshots and never infers judgements.

The playfield (lanes, notes, hold bodies, the key tray, the judgement line, the
lane-press lights, the hit feedback at the line and the song progress) is drawn
with Metal by the view that owns the display link. A pure function,
`Mania4KPlayfieldLayout`, turns the published play frame, the lane brightness and
the feedback's recent judgement event batches into a flat list of
rounded-rectangle primitives (`Mania4KQuad`: five float vectors, 80 bytes), which
the renderer draws in order as one instanced call. Hit feedback is drawn only for
the events the core reported, aged from the chart time each batch was recorded at;
note states (hit, missed, held) come from the core's visible objects. The shader is
compiled from source at run time, so building needs no Metal toolchain. The HUD
(accuracy), the judgement text and combo, and the key labels stay in SwiftUI over
the Metal view and observe `hud`, `judgementPresentation` and the live lane states,
which change only when their value does, not the per-frame `playFrame`. The look
is described in [gameplay look](gameplay-look.md).

Every play mode judges with the Rust gameplay core (`EnsomiRuntime`, rules in the
runtime repository's `crates/gameplay/README.md`), driven through
[`Mania4KGameplay`](../Sources/EnsomiCore/Features/Mania4K/Mania4KGameplay.swift): inputs go
in with their chart time and each frame advances the session. A scheduler appends its pages
to the session inside the runtime, and the events a page decides come out of the next input or
advance. A frame whose session has not decided through the gameplay time fails the play. The
core applies
per-lane note lock: earlier unresolved objects cannot remain hittable past the
next object's start. A tap or hold head pressed too early is ignored; an unresolved
head misses after its successful window. A hold resolves once, using the worse
head and tail tier. Breaking a hold or missing either endpoint misses the whole
object, without recovery. Tail windows have a `1.5` multiplier.

Judge difficulties A–E use these inclusive symmetric half-windows in milliseconds:

| Judge | bigP | p1 | p2 | p3 | g |
| --- | ---: | ---: | ---: | ---: | ---: |
| A | 60 | 105 | 120 | 135 | 200 |
| B | 47 | 63 | 88 | 118 | 200 |
| C | 40 | 56 | 81 | 111 | 200 |
| D | 34 | 50 | 75 | 105 | 200 |
| E | 25 | 50 | 75 | 105 | 200 |

`bigP` displays Perfect; `p1`, `p2`, `p3`, and `g` display Good; `m` displays Miss.
Accuracy averages tier weights `1`, `0.9`, `0.85`, `0.8`, `0.4`, and `0` across
resolved objects, defaulting to `1` before any judgement. Success increments combo;
a miss resets it. Successful tap and hold-head errors contribute to the suggested
audio-offset adjustment, which is the negative mean error; misses and release
errors do not. A session finishes only after the chart is complete, all objects resolve,
and the audio clock reaches the end.

## Chart inspection

The app does not play existing charts. The macOS Chart Inspector draws one against its
song without judging it.
[`Mania4KChartInspectionModel`](../Sources/EnsomiCore/Features/Mania4K/Mania4KChartInspectionModel.swift)
validates what it opens, and nothing after it does: one `.osz`, or one `.osu` with one
other file taken as its audio. An `.osz` is read through its zip central directory
([`OszArchive`](../Sources/EnsomiCore/Services/Beatmap/OszArchive.swift)). Stored and
deflated entries are supported, every offset and stored size is checked against the
file's length, and anything else makes the archive unreadable. The gameplay core's
loader (`parseOsu`) reads each `.osu`: it accepts only four-key mania and rejects
unsupported objects, invalid times and same-lane overlaps, and a hold whose end is
missing or earlier than its start becomes a zero-length hold. The loader does not
report the difficulty name or the audio file, so they come from the `.osu`'s `Version`
and `AudioFilename` lines; the audio is found in the archive without regard to case
and written to a temporary file for the audio engine. An `.osz` with one `.osu` opens
it; with several, they are listed, each refused one with the core's reason. The title
falls back to the file name.

The chart goes into a core session that is never advanced and gets no input, so every
object stays waiting, `visibleObjects` answers for any chart time, earlier or later,
and there is no score. The playfield's display link makes the frames as in play, and
`Mania4KFrameTiming` times them for both: each frame reads the play model's audio
offset for the current output device, its visual offset and its scroll speed, so the
drawn chart time is the heard audio time plus the offsets exactly as in play. The song
plays at the play model's music volume on an `AVFoundationMania4KAudioClock` of its own.

A seek builds that clock's engine again, paused at the new position with the file
scheduled from there, so reads return the position until playback resumes from it.
Pause holds the player at the position being heard, so playback resumes from what is
drawn. A rate other than 1 puts an `AVAudioUnitVarispeed` between the player and its
mixer. The player's time then counts source time, the clock carries it to the asked
host time at that rate, and the unit's reported latency is added to the output's:

```text
audioTimeMs = renderedAudioTimeMs
            + rate × (hostTimeMs − renderHostTimeMs − presentationLatencyMs − varispeedLatencyMs)
```

The varispeed resamples, so the pitch follows the rate; the delay it adds to a sound is
the latency it reports. The time-pitch unit, which keeps the pitch, reports a latency
that does not match its output and is not used. Play runs at rate 1, without the unit.
The inspected song lasts until the audio or the chart's last note ends, whichever is
later, and playback pauses there.

Focused regression coverage lives in
[`Tests/EnsomiCoreTests`](../Tests/EnsomiCoreTests), including the ambient join,
input ordering, stale callback, cache validation, and ambient clock tests. Private
recordings and historical measurements are not requirements for these contracts.
