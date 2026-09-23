---
commit: 5c11794f08016478c1edbe2df27464f649b639c8
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

The `.osu` adapter accepts only four-key mania. It rejects unsupported objects,
invalid times, and same-lane overlaps before playback. Finite hold ends earlier
than their starts are clamped to the start; malformed hold ends are rejected.

## Gameplay and scoring

The session applies offsets exactly once:

```text
gameplayChartTimeMs = audioTimeMs + audioOffsetMilliseconds
renderChartTimeMs   = gameplayChartTimeMs + visualOffsetMilliseconds
scrollTimeMs        = 11485 / scrollSpeed
```

Audio offset affects judgement and hit errors. Visual offset and scroll speed
affect rendering and lookahead only. Input uses gameplay time, filters repeated
presses, and preserves press/release order across asynchronous clock reads.
Paused sessions do not judge input or advance misses. The renderer consumes
snapshots and never infers judgements.

[`Mania4KJudgementEngine`](../Sources/EnsomiCore/Domain/Mania4KDomain.swift) owns
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
