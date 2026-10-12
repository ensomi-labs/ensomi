---
commit: 8e3de3b
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
the ambient generated-play handoff. That handoff creates a host-time-anchored
clock, clamped to track duration, and does not replay the local reference audio.
The shared play model drives both the workbench Play tab and the macOS play window.
See [`LiveRecognitionSyncModel`](../Sources/EnsomiUI/Features/Recognition/LiveRecognitionSyncWindow.swift)
and [`Mania4KPlaySessionModel`](../Sources/EnsomiCore/Features/Mania4K/Mania4KPlaySessionModel.swift).

## Listen & Play demo

The macOS Live Demo entry is available in Debug and Release. It checks the local
HTTP service before starting capture, then reuses ACRCloud identification, local
resolution and Sonalign final-lock handling. This path disables the prototype
inference connection during recognition; final lock starts one controlled
H/R/R1 generation session and switches the demo window to the shared gameplay
view. The host-time clock continues external playback without replaying the
reference file. Difficulty/style selections are fixed when the session starts.

`ControlledGenerationClient` uses `ensomi-demo/v1` at `http://127.0.0.1:8766`.
A health response requires a loaded compatible model. Session creation sends the
local audio path, optional controls and a client-owned identity. Sequenced window
requests receive complete four-lane rows, confirmed `coverage_ms` and EOS.
The first request may catch up from BOS for a late join; later responses advance
by at most one audio second. Refill runs independently of frame rendering,
starting near six seconds ahead and targeting eight seconds. Read requests do
not themselves establish completeness. Limited pages cannot settle timestamps
with undelivered events. Settled silence can satisfy initial coverage, but entry
must not begin inside a hold. Stop, replacement, failure and completion release
the backend session. The producer's decoded duration owns termination.

The model service caches immutable full-audio features; physical state, history
caches and RNGs remain private to each generation session. This local protocol
is independent of the prototype protobuf transport described below. Operator
setup and limits are in the [demo manual](live_generation_demo.md).

## Inference transport and chart streams

[`InferenceEndpointWebSocketClient`](../Sources/EnsomiCore/Services/Beatmap/InferenceEndpointWebSocketClient.swift)
sends binary EnsomiProtocol `Envelope` frames: `ready`, then session `audio` with
the local path, sync source, difficulty and route, then `reference_time` with the
reference position and monotonic host send time. `stop_session` ends a session.
The server must have access to that path. Text websocket frames are rejected.
Incoming `hit_object_token` and `end_of_stream` events require a session ID;
events from obsolete sessions cannot update the active game.

Tokens become four-lane `tap`, `holdStart`, and `holdEnd` events. Event times must
be finite and nonnegative, ordered by time then lane, preserving source order for
ties. Each lane permits one open hold; its next hold end closes it. Taps or new
holds inside an open hold, unmatched ends, overlapping objects, and holds left
open at end of stream are errors. A hold's two endpoints create one scoring object.

Streams are append-only. `completeThroughChartTimeMs` promises that no future
batch contains an event at or before that watermark. Empty batches can mean that
generation is pending; only `isEndOfStream` declares completion. The session
serializes reads, rejects watermark violations, and fails if the judgement clock
outpaces complete data. Initial inference readiness requires a clean hold boundary
and sufficient future coverage; it cannot begin halfway through an existing hold.

Local-audio generation uses a complete-chart exchange instead of a stream, made by
one of two engines that answer the same questions: capabilities, a complete chart,
progress by phase and a stop. `Mania4KChartGenerationModel` picks one: the on-device
engine when a model is installed and the person has not chosen otherwise, else the
chart server. Controls, validation, the play path and `Mania4KGeneratedChart` are the
same for both. The on-device engine is described after the server contract.

[`ChartGenerationClient`](../Sources/EnsomiCore/Services/Beatmap/ChartGenerationClient.swift)
opens a binary websocket (`ENSOMI_SERVICE_URL`, default `ws://localhost:8765`), sends
`capabilities_request` with an empty session ID (reply: model name and version, star
range `[min_star, max_star_exclusive)`) and `generate_chart` with a session ID, audio path,
star, seed and `ChartControls.sections`. Section targets map to either a star or
a pattern kind and strength. The app reads the local audio duration and requires
ordered endpoints inside the song, no overlapping sections (adjacency is allowed),
8–32-second pattern spans, strength in `[0, 1]`, and section stars in the song's
selectable range. Capability statuses determine which section kinds are offered;
long-note controls remain unspecified. The service replies with `generation_progress`
phases, then one `chart_result`, or an `ErrorEvent` whose string `code` names the
failure; `stop_session` is answered by a stopped status. Generation state owns the
setup panels' phase, readiness and error message independently of live inference
session state. There is no `ready` or node hello. Chart times are whole-millisecond
audio time, unshifted; the app's offsets apply at play time. `Mania4KGeneratedChart` has the
gameplay core validate the chart (it rejects out-of-range lanes or times, unsorted objects and
same-lane overlaps), then plays it with its audio from time 0 as an in-memory chart, with every
note.

The on-device engine ([`OnDeviceChartGenerator`](../Sources/EnsomiCore/Services/Beatmap/OnDeviceChartGenerator.swift),
details in [solo device mode](solo-device.md)) decodes the chosen file with `AVAudioFile` to
mono Float PCM at the file's own rate (channels averaged in Double) and gives it to the runtime's
`ChartGenerator`, which opens once on a model directory and keeps ONNX Runtime's CPU sessions.
The generation model maps what it already has to the runtime's types and back: `ModelCapabilities`
to the protocol `ModelCapabilities` (so the same statuses decide the section kinds), the sections to
`ChartControls` (long notes stay unspecified), the runtime's phases to `GenerationPhase`, and
the runtime's `Chart` to `Mania4KGeneratedChart`. A refusal arrives as `GeneratorError` and
is shown as the core's message. The seed comes from the seed field, or is a new random one when
the field is blank. Stop cancels the generation task, which throws `GeneratorError.Cancelled` at
the core's next graph run or phase, and the state becomes stopped. Either engine's chart ends as a
`Mania4KChartGenerationResult`: the chart, the seed and star the engine reports in its receipt,
the receipt JSON and, on this device, the `.osu`. Saving the `.osz` passes that `.osu`, the song
file's bytes and the receipt to the runtime's `exportOsz`, so every client writes the same archive.
The model is the published Ensomi R2 download in Application Support or a folder the person chose;
`ChartModelLibrary` owns that choice, opens the generator and keeps folder access across launches
with a bookmark. A download that stops keeps its files and resumes with HTTP Range requests.

The `.osu` stream reads the file with the gameplay core's loader, which accepts only
four-key mania. It rejects unsupported objects, invalid times, and same-lane overlaps
before playback. A hold whose end is missing or earlier than its start becomes a
zero-length hold. The title falls back to the file name.

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

Audio offset affects judgement and hit errors. Visual offset and scroll speed
affect rendering and lookahead only. A key event is stamped with its own
timestamp (`NSEvent.timestamp`, system uptime), converted to gameplay time
through the audio clock's host-time mapping, so judgement uses when the key was
pressed rather than when it was handled. Repeated presses are filtered. Each
frame delivers the inputs stamped up to its chart time in timestamp order, then
advances the session to that time; an input stamped later waits for the next
frame. The play view's display link makes the frames, one per display refresh,
and nothing else ticks the session: each frame judges at the current chart time
and is drawn for the time the display shows it (`targetTimestamp`). The stream
is read ahead in the background, and a frame the stream has not covered fails the
session. Paused sessions do not judge input or advance misses. The renderer
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
[`Mania4KGameplay`](../Sources/EnsomiCore/Features/Mania4K/Mania4KGameplay.swift): stream
pages go in as tokens with their coverage, inputs with their chart time, and each frame
advances the session. A frame whose session has not decided through the gameplay time
because the stream lags fails the play. The core applies
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
errors do not. A session finishes only after the stream ends, all objects resolve,
and the audio clock reaches the end.

Focused regression coverage lives in
[`Tests/EnsomiCoreTests`](../Tests/EnsomiCoreTests), including stream watermark,
input ordering, stale callback, cache validation, and ambient clock tests. Private
recordings and historical measurements are not requirements for these contracts.
