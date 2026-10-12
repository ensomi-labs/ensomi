---
commit: 4f39e8f3675dbb8b722b89e3c352f9d4902cf46f
---

# Ensomi

Ensomi is a SwiftUI rhythm-game prototype for macOS and iOS. It recognizes music,
matches it to user-supplied local audio, synchronizes with the playing track, and
plays a generated four-lane mania chart. Imported `.osu` charts are also supported.

`EnsomiCore` owns recognition, local indexing, synchronization, inference transport,
and gameplay; `EnsomiUI` owns the shared interface. See [architecture](docs/architecture.md)
for the timing, streaming, and scoring contracts.

## Build and test

Requires Xcode with Swift 6 and XcodeGen 2.45.0 or newer. Deployment targets are
macOS 14 and iOS 17. The gameplay rules and the chart generator come from the Rust core in
[ensomi-runtime](https://github.com/ensomi-labs/runtime): SwiftPM resolves its `EnsomiRuntime`
package at the version pinned in `project.yml` (`0.1.0`) and downloads the xcframework from
that release. The package carries the generator's resampler tables; the apps bundle none. The
gameplay conformance test reads its fixtures from a runtime checkout beside this repository
(`../ensomi-runtime/conformance/gameplay`) and is skipped without one.

To develop against a local runtime, change `EnsomiRuntime` in `project.yml` to
`path: ../ensomi-runtime` (do not commit it), build the xcframework with
`../ensomi-runtime/scripts/build-apple.sh` (Rust with the `aarch64-apple-darwin`,
`aarch64-apple-ios` and `aarch64-apple-ios-sim` targets) and set
`ENSOMI_RUNTIME_XCFRAMEWORK=build/apple/EnsomiRuntimeFFI.xcframework`, a path relative to the
runtime package. SwiftPM reads the variable whenever it loads that package, also the released
one: `xcodebuild` takes it from the shell, Xcode from `launchctl setenv`. Unset it in both to
build with the release.

Then generate the project from `project.yml`:

```sh
xcodegen generate
open Ensomi.xcodeproj
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' test
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiIOS -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Debug app builds disable code signing. For microphone or system-audio testing,
follow the [canonical macOS installation guidance](AGENTS.md#macos-installation).

SwiftPM resolves [EnsomiProtocol](https://github.com/ensomi-labs/protocol) and
[Sonalign](https://github.com/ensomi-labs/sonalign), pinned in `project.yml`
(EnsomiProtocol `0.2.0`, Sonalign `0.2.0`).

## Live recognition and generation

The macOS **Live Demo** toolbar button opens **Listen & Play**: it checks the
local H/R/R1 service, starts recognition and synchronization automatically, and
opens gameplay after final lock. Optional difficulty/style requests are captured
at lock. See the [operation manual](docs/live_generation_demo.md) for backend
startup, checkpoint identity, local audio preparation and troubleshooting.


**Generate a Chart** builds a complete chart from a local audio file. Pick the file,
set the stars (the model's range), an optional seed and optional sections, and the chart is
made on this device by the Rust core's generator with ONNX Runtime, from the Ensomi R2 model
that Settings downloads from [Hugging Face](https://huggingface.co/sed-i/ensomi-r2) (about
120 MB, resumed after a dropped connection) or reads from a folder you choose. Nothing is sent
to a server. The finished chart shows the seed that makes it again, plays from the start, and
saves as `.osu` or as `.osz` with the song. See [solo device mode](docs/solo-device.md).

The chart server stays selectable on the same screen: it runs the local Ensomi R2 model service
(`python -m ensomi_model.service --model-dir <dir>` from
[ensomi-model](https://github.com/ensomi-labs/ensomi-model), `ws://localhost:8765` by
default; override with `ENSOMI_SERVICE_URL` or the `EnsomiServiceURL` default).

Index your audio in **Library**. On macOS Debug builds, **Debug → Open Recognition
Sync Flow** recognizes microphone or system audio, resolves a local reference,
and opens generated gameplay after a final sync lock. The Play interface also
supports local-audio generation and imported charts. Live ambient recognition is
a macOS debug workflow; ShazamKit has no implemented provider.

The recognition window reads process environment values, then the first readable
`.env` in the working directory or source project root. Values in that file
override the environment. Configure the ACRCloud Identification API with:

```sh
ACRCLOUD_IDENTIFICATION_HOST=your-project-identification-host
ACRCLOUD_ACCESS_KEY=your-access-key
ACRCLOUD_ACCESS_SECRET=your-access-secret
```

Filescan debugging additionally requires the `acrcloud` executable and
`ACRCLOUD_ACCESS_TOKEN`; `ACRCLOUD_CLI` can select its path. Build the optional helper
with `xcodebuild -project Ensomi.xcodeproj -scheme EnsomiACRCloudDebugCLI -configuration Debug -destination 'platform=macOS' build`
and use its `--help` for options.

Generating on the chart server requires the companion [ensomi-model](https://github.com/ensomi-labs/ensomi-model)
service, listening at `ws://localhost:8765` by default. It must be able to read the
selected audio path; the client sends a path, not audio bytes. The websocket uses
binary protobuf envelopes from EnsomiProtocol.

Audio, credentials, recordings, and generated reports are local inputs. Keep them
in ignored `.env`, `LocalFixtures/`, or `artifacts/`; a fresh clone includes no
private audio corpus.
