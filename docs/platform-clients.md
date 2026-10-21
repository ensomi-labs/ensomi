---
commit: 3000fc33f58e067d52ccc1d7b55cc26a13350ad8
---

# Windows and Android clients: proposed design

This document proposes clients for Windows and Android. None of it is implemented, and the
repository still builds only `EnsomiMac` and `EnsomiIOS`; their behavior is described in
[architecture](architecture.md) and their presentation in [design guidelines](design-guidelines.md).
The Apple clients are the reference: Windows takes the macOS feature set, Android takes the iOS
one. HarmonyOS is out of scope, including its layout, its toolchain and its bindings.

## Ownership and the contract with the Apple clients

The macOS and iOS clients are maintained separately from the clients this document proposes, and
they stay the reference for behavior. What binds the two is the runtime's `conformance/` fixtures,
the protocol, the model pin, and the equality of what every client produces: the same chart for
the same song, stars, sections and seed, and the same `.osz` bytes. Presentation, navigation,
storage and platform integration belong to each client; a change to a gameplay rule is a runtime
or protocol change, not one client's decision.

## What the core already decides

The [runtime](https://github.com/ensomi-labs/runtime) already owns what every client must agree on:
gameplay rules and judgement, note geometry, chart sources, the generator and its scheduler, the
model-package format and reader, and `.osz` export. It does no IO: the caller passes time,
timestamped input, PCM and graph outputs in. That split is what makes a fourth and fifth client
affordable, and it is the boundary this design must not widen.

A platform therefore supplies exactly these, and nothing else of the engine:

| Per platform | Why it cannot be shared |
| --- | --- |
| Audio codec and lead handling | Only the codec differs; the song's time and the chart's time must not |
| Audio output and its clock | The clock is the device's latency and position report |
| Timestamped input | Only the OS knows when a key or a touch happened |
| Display link and playfield rendering | The core supplies layout primitives, not frames |
| UI, storage, file access, model download | Platform services |

## Layers

```
core            ensomi-runtime, sonalign (Rust, no IO)          already exists
app core        library index, decode, model download (Rust)    new
platform shell  audio, input, render surface, storage, capture  small, per platform
UI              one Compose Multiplatform codebase              new
```

**App core.** The Swift services that read and write the outside world — the local library
(`LocalAudioLibraryDatabase`, `LocalAudioLibraryIndexer`), decoding and `MP3Lead`,
`ChartModelDownloader` and its install record — carry behaviour every client needs identically.
They move to Rust so that two clients cannot disagree about a chart's song time, a model's
contents, or the archive they save. Their Swift implementations stay the reference, and their
tests become the app core's conformance cases. A platform that needs a native picker or a
persisted directory grant keeps that grant in its shell and hands the app core a path or a file
descriptor.

**Platform shell.** Each shell implements the four contracts below plus storage and capture, and
holds no judgement, generation or chart-serialization logic.

## Repository layout

Both clients live in this repository. `dev/swift` carries the Apple client; `dev/windows` carries
the clients this document proposes, and their trees are outside what `project.yml` builds, so
`xcodegen generate` ignores them.

```
Apps/EnsomiMac, Apps/EnsomiIOS        Apple entry points                  dev/swift
Sources/EnsomiCore, Sources/EnsomiUI  shared Swift domain and interface   dev/swift
clients/
  settings.gradle.kts                 the Gradle root of both clients
  shared/                             Compose UI and the shell contracts
  windows/                            Windows entry, jpackage, native libraries
  android/                            Android entry, manifest, JNI libraries
  app-core/                           Rust: library index, decode, model download
docs/                                 contracts, guidelines, these proposals
.github/workflows/                    ci.yml stays the Apple checks; new workflows for the
                                      clients trigger on dev/windows and on pull requests into it
```

The app core sits here rather than in the runtime repository because only these clients need it;
anything both clients must agree on belongs in the runtime. The Gradle root is a subdirectory so
the Xcode project's roots stay as they are.

## The binding

`crates/ffi` is UniFFI 0.31 and exposes `GameplaySession`, `GameplayFeedback`, `ChartGenerator`,
`ChartScheduler`, `parse_osu` and the geometry helpers. UniFFI generates Kotlin from the same
crate that generates Swift today; the generated Kotlin loads the native library through JNA on
both the Android runtime and the desktop JVM. One generated binding therefore serves both
clients, and no second, hand-written boundary is needed.

That choice constrains the Windows client to a JVM: the binding, the UI and the packaging share
one runtime, which is also why Compose Desktop is the UI choice below. A future client outside the
JVM — native C++, C#, or anything else — would need its own boundary; this design does not build
one, and the `conformance/` fixtures are what such a boundary would be checked against.

The per-frame calls (`input`, `advance`, `score`, `visible_objects`, `note_geometry`) allocate
nothing and do not block: they run inside a display frame.

## Platform shell contracts

**1. Audio clock.** The clock answers the audio time heard at a host time, and the host time at
which a given audio time was heard. The invariants in [architecture](architecture.md#alignment-and-time)
apply unchanged: the reading is the audio heard, not how far the decoder has got; it is monotonic
while the output device is unchanged; a device change rebuilds the output, resumes from the
position being heard, and reads earlier until the new audio is heard; the gameplay session never
moves its clock back. Windows uses `IAudioClient3` with the device position and QPC; Android uses
AAudio through Oboe and its timestamp.

**2. Input timestamps.** A press enters the session with the OS timestamp at which it happened,
converted to chart time through the clock's host-time mapping, not with the time the UI handled
it. A frame delivers the inputs stamped up to its chart time, in order, then advances the session;
an input stamped later waits. Windows stamps `WM_KEYDOWN` with the message time; Android uses
`MotionEvent.getEventTime()`. The UI toolkit's own key events are not a timestamp source.

**3. Display link.** One frame per display refresh, driven by the platform's display link
(the Windows composition target or `DwmFlush`, Android's `Choreographer`). The frame is drawn for
the time the display shows it. Nothing else advances the session; a paused session neither judges
input nor advances misses.

**4. Renderer.** The core's `note_geometry` and `visible_objects` answer where an object is and
what state it is in; the renderer draws them and never infers a judgement. The layout stays a
pure function of the play frame, so the four-lane look is reproduced from the same inputs on every
platform.

## UI

One Compose Multiplatform codebase, because the reference clients are SwiftUI: `@Observable`
state models, `NavigationSplitView`, `TabView` and a `Settings` scene have direct Compose
counterparts, and the macOS client needs several windows — a main window, Settings, Listen & Play,
a play session window and the Chart Inspector — which Compose Desktop hosts natively. Flutter is
the alternative; it is stronger on Windows today, but its desktop multi-window support is
third-party, and it would not share the Kotlin binding above.

The screens themselves are specified in [platform screens](platform-screens.md).

The playfield is not a toolkit canvas. It is a native surface (Direct3D or Vulkan through the
shell) drawn from the core's primitives, as the Metal view is today; the toolkit draws the shell
around it. Mixing them is a known cost of this choice.

## Parity

| Surface | macOS | iOS | Windows | Android |
| --- | --- | --- | --- | --- |
| Workbench | sidebar: Library, Play | tabs: Library, Play | as macOS | as iOS |
| Settings | `Settings` scene | sheet with Done | own window | sheet |
| Generate a Chart | in Play setup | in Play setup | as macOS | as iOS |
| Play | in workbench and a play window | in workbench | both | in workbench |
| Chart Inspector | window, Settings → Developer | — | window | — |
| Listen & Play | own window, Live Demo | — | own window | — |
| Recognition Flow | Debug only | — | Debug only | — |
| Music library | folders, scan status, Library Tools | as macOS | folders, scan status | SAF folders, scan status |
| System audio capture | screen/audio capture | — | WASAPI loopback | — |
| Microphone capture | yes | yes | yes | yes |
| Save `.osu` / `.osz` | yes | yes | yes | yes |

Android does not get Listen & Play: it recognizes audio playing nearby, which needs a capture
source Android grants per session and does not provide in the background. iOS does not have it
either, and the parity target for Android is iOS.

## Storage and identity

- **Library.** One SQLite schema, owned by the app core, so the index moves once. A platform's
  directory grant (a security-scoped bookmark, a SAF persisted URI) stays in the shell.
- **Settings.** The keys and their value encodings stay those of the Apple clients where the
  concept exists — `mania4k.keyBindings`, `mania4k.offsetCalibrationState`,
  `mania4k.musicVolume` — so the same defaults, calibration presets and key bindings apply
  everywhere. A setting the platform cannot offer is absent, not silently false.
- **Model install.** The same `ensomi-install.json` record, the same pinned revision and the same
  `checkPackage` verification as `ChartModelLibrary`; a model installed by one client is
  recognizable by another.

## Timing and generation

The session applies offsets exactly once, on the song's time, as
[architecture](architecture.md#gameplay-and-scoring) states. Charts are made in `wholeChart` for
Generate a Chart and in `aheadOfPlay` for Listen & Play; both modes already exist in the core and
the scheduler, and a client chooses one per generation. The setup shows the model's phases and
the scheduler's progress from the core; it does not estimate them.

## Build, release and pinning

- The runtime release gains the Kotlin binding and its Android and Windows library artifacts,
  alongside today's xcframework and npm package.
- Clients pin an exact runtime version, as `project.yml` does today; a protocol change follows the
  ecosystem's usual order (protocol, runtime, clients).
- CI keeps the Rust tests and the conformance fixtures, and adds workflows that build the Kotlin
  binding with its two native libraries and run the fixtures through it. They trigger on
  `dev/windows` and on pull requests into it, and do not change what `ci.yml` runs for the Apple
  client.
- A client release is tagged per client — `windows/v0.1.0`, `android/v0.1.0` — so it cannot be
  mistaken for the Apple app's tag, and its installer or package is the release's artifact.
- The chart server stays a development engine; a released model runs in the runtime on every
  client.

## Risks

1. **Clock accuracy on Windows.** WASAPI reports output latency but not every device's; the
   existing calibration flow absorbs what it does not, and the shipped default offsets are tuned
   by ear on one device, not universal.
2. **Decoder differences.** One decoder in the app core keeps a chart's song time identical
   everywhere. The Apple clients keep AVFoundation and `MP3Lead`, which are matched to the release
   engine's time; a change there is a separate, testable change.
3. **Toolkit latency.** The UI toolkit must not own the audio clock or the frame loop, or the
   timing contract becomes a toolkit behavior.
4. **Packaging.** A JVM Windows client needs a trimmed runtime and a signed installer; startup
   time is a product decision.
5. **Android capture.** A capture source granted per session bounds what Listen & Play can do
   there; the parity target for Android does not include it.

## Open decisions

1. Whether the Windows client mirrors the macOS window set or uses one window with panes.
2. Whether the first Windows release downloads the 120 MB model or offers the chart server first.
3. Which reference device the Windows audio offsets are measured on before they ship.
