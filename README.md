---
commit: 5c11794f08016478c1edbe2df27464f649b639c8
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
macOS 14 and iOS 17. Generate the project from `project.yml`:

```sh
xcodegen generate
open Ensomi.xcodeproj
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -destination 'platform=macOS' test
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiIOS -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Debug app builds disable code signing. For microphone or system-audio testing,
follow the [canonical macOS installation guidance](AGENTS.md#macos-installation).

SwiftPM resolves [EnsomiProtocol](https://github.com/ensomi-labs/protocol) and
[Sonalign](https://github.com/ensomi-labs/sonalign), both pinned to `0.1.0` in
`project.yml`. App builds need neither sibling package checkouts nor Rust.
The optional synthetic alignment comparison uses the released Rust CLI:

```sh
cargo install sonalign --version 0.1.0 --locked --root .build/sonalign
python3 Tools/check_sonalign_parity.py --suites synthetic
```

## Live recognition and generation

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

Generation requires the companion [ensomi-model](https://github.com/ensomi-labs/ensomi-model)
service, listening at `ws://localhost:8765` by default. It must be able to read the
selected audio path; the client sends a path, not audio bytes. The websocket uses
binary protobuf envelopes from EnsomiProtocol.

Audio, credentials, recordings, and generated reports are local inputs. Keep them
in ignored `.env`, `LocalFixtures/`, or `artifacts/`; a fresh clone includes no
private audio corpus.
