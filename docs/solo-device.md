---
commit: 54e6356
---

# Solo device mode

Generate a Chart can make the chart on the device that plays it. The app decodes the
song, the runtime's Rust core runs the Ensomi R2 generator with ONNX Runtime's CPU
provider inside the app process, and the chart plays through the same path as one from
the chart server. Nothing is sent to a server and no service has to run. The contract
is in [architecture](architecture.md#inference-transport-and-chart-streams); this
document is how the mode is installed, what runs where, what it costs and what it
does not do.

## Installing the model

The model is the published Ensomi R2 package, the `onnx/` directory of
[`sed-i/ensomi-r2`](https://huggingface.co/sed-i/ensomi-r2) on Hugging Face at the tag
`r2-1.0`: its manifest `ensomi-package.json` and the files the manifest lists (`runtime.json`,
the graphs, state and array files and BeatThis's licence; 30 files and about 120 MB by the
manifest at the tag). The format and its checks are the runtime's
[model package](https://github.com/ensomi-labs/runtime/blob/v0.3.0/docs/model-package.md). The
tag is `ChartModelSource.revision`, the one place the app names it; the app never reads `main`,
so later commits there do not change what it installs. Two ways to have it, in Settings under
Chart model, or on Generate a Chart when "On this device" is chosen and no model is installed:

- **Download Model** fetches it into `Application Support/Ensomi/Models/ensomi-r2`
  with a progress bar and **Cancel Download**. It downloads `ensomi-package.json`, checks it
  with the runtime's `checkPackage` (which refuses, among others, a format it does not read, a
  `min_runtime` above its own version or another model), then downloads exactly the files the
  manifest lists and checks each one's size and SHA-256 against the manifest (CryptoKit, in
  1 MiB chunks). Files collect in `.download-ensomi-r2` beside the install directory; a file
  the install directory already holds with its listed size and SHA-256 is copied from there
  instead of fetched, so **Download Again** over an install fetches only the manifest and the
  files that are missing or changed. Only when every file is right and `ChartGenerator.open`
  accepts the directory with its manifest is it moved into place, replacing an older install.
- **A stopped download resumes.** A failed or cancelled download leaves the installed model,
  if any, untouched and keeps `.download-ensomi-r2`: the files finished so far and the one in
  progress as `<path>.part`. The section then says the download stopped and offers **Resume
  Download**, which fetches the manifest again, checks each finished file's size and SHA-256
  against it (fetching the file again if it no longer matches), and asks for the rest
  of the partial file with `Range: bytes=<n>-`. A 206 answer is appended; a 200 answer (a
  server that ignores the range) rewrites the file from the start. When a resumed file then
  fails its size or SHA-256, as it does when the published file changed in between, it is
  fetched again whole. **Remove Download** also deletes the partial files. There is no
  automatic retry: apart from completing an install (next item), resuming is the person's
  choice.
- **An incomplete install is completed when the app starts.** The library reads the installed
  manifest: when there is none, as in an install made before the app downloaded it, or when a
  file it lists is missing or has another size, the library starts the download by itself. It
  fetches the manifest and the files that are missing or changed, copies the rest from the
  install, and leaves out files the manifest does not list, such as the `ensomi-model.json` of
  earlier installs. The installed model stays in use meanwhile; a failed download leaves it as
  it was and offers **Resume Download**.
- **Choose Folder…** uses any directory that holds the files, such as a local copy of
  `onnx/`. The folder is refused unless `ChartGenerator` opens it and it holds the model's
  licence notices (the manifest's `notices`, or for a folder without a manifest the files
  `noticeFiles(runtimeConfig:)` names). It is kept across launches as a bookmark
  (security-scoped on macOS, which also makes it work in a sandboxed build) and takes
  precedence over the download; **Use Downloaded Model** goes back. **Remove Download**
  deletes the downloaded copy.

`EnsomiMac` has no entitlements file, so it is not sandboxed: it reaches Hugging Face and
any folder without a grant. A sandboxed channel would need the network client and
user-selected read entitlements. iOS is always sandboxed; the bookmark covers the
chosen folder there.

Below the controls, Settings shows the licence of the model in use as its manifest gives it
(an SPDX expression, `CC-BY-NC-SA-4.0 AND MIT` at `r2-1.0`) with a link to the model card,
which says what each part covers, and a button for each of the model's notices, BeatThis's
licence (`BeatThis-LICENSE.txt`). A folder without a manifest has no expression; its notices
come from `noticeFiles(runtimeConfig:)`. ONNX Runtime's licence and third-party notices, which
the runtime package carries (`ChartGenerator.onnxRuntimeLicenseFiles`), follow with a button
each. A button opens the text in a sheet. The library reads the licence off the main actor
whenever the model in use changes.

When a model is installed, "On this device" is the default engine; "Chart server" stays
selectable and the choice is remembered. The engine picker is disabled during a generation.

## What runs where

| Step | Where |
| --- | --- |
| Pick the song, section validation, star range | App (`Mania4KChartGenerationModel`); the range and section kinds come from the model's capabilities (`ChartGenerator.capabilities()`, mapped to the protocol `ModelCapabilities`) |
| Decode | App: `AVAudioFile` at the file's own sample rate, channels averaged in Double, then Float (`OnDeviceAudioDecoder`) |
| Resample to 22050 Hz, beat tracking (BeatThis), rows, arrangement, `.osu` | The runtime's Rust core in the app process; ONNX Runtime CPU runs the graphs on a thread of its own |
| `.osz` | The runtime's Rust core (`exportOsz`, `crates/osz`) from the `.osu`, the song file's bytes and the receipt; the app reads the song and writes the file the save panel names |
| Validate and play | App: the gameplay core checks the chart, `Mania4KPlaySessionModel` plays it from the song's time 0 |
| Server | Not involved |

The core resamples any PCM not at 22050 Hz with the runtime's soxr tables. Those 27 files
(`resample-<rate>.json`, `.bin` and `-boundary.bin` for 11025, 16000, 24000, 32000, 44100, 48000,
88200, 96000 and 192000 Hz, 14 MB) are in the runtime repository (`assets/resampler`), and its
Swift package carries them as a resource, which `ChartGenerator.open` reads when no
`resamplerDirectory` is given. The app bundles none of them. A song at 22050 Hz needs none of them.

Generate lists the core's five phases (loading, timing, rows, arrangement, export) as they
pass. Stop cancels the task; the core stops at its next graph run or phase and the screen says
no chart was made. A refusal (`star_out_of_range`, `unsupported_control`, `generation_failed`) or
an unreadable model shows the core's message under "The chart could not be made." So does a panic
in the core, which ends only its own generation (`generation_failed`, "internal error: ..."). The
generator is opened once and kept.

## Seed and export

The Seed field takes a whole number from 0 to 2^64 − 1; blank gives a new chart each time
(on this device a random seed below 2^32, small enough to retype). A finished chart shows its
note count and the seed the engine used, from its receipt, with **Use Seed** to put it in the
field: the same song, model, stars, sections and seed make the same chart. Then come **Play**,
**Save .osu…**, **Save .osz…** and **Generate Again**. The chart and the chosen mode stay
after play ends, so a chart can be played and then saved.

The save panel proposes the web app's name, `Artist - Title (ensomi R2 s<seed>)`, with the song
file's name as the title. The `.osu` is the core's text. The `.osz` is a zip made by the Rust
core with the web runtime's layout: `generated.osu` (deflated), the song file as
`audio.<extension>` (stored), the name the `.osu`'s `AudioFilename` gives it, and
`receipt.json` (the receipt JSON and a newline, deflated). Entries carry the zip format's earliest
date, so the same chart and song give the same bytes. The chart server returns no `.osu`, so
its charts have no save buttons; when its receipt names an `.osz` it wrote on its own machine,
the screen shows that path.

## Time and memory

On bings-mac (Apple M5, macOS 27, Debug build of the app's test bundle, CPU provider), for a
133 s stereo MP3 at 44.1 kHz that gives a chart of 1225 objects at 3.5 stars, seed 0:

| Quantity | Result |
| --- | --- |
| Open the model (`ChartGenerator.open`) | 0.24 s |
| Decode the song alone | 1.47 s |
| Decode and generate | 7.30 s |
| Native command line generator on the same PCM (generation only) | 5.35 s |
| Start the play session | 0.62 s |
| Silent autoplay of the whole chart | 1225 perfect, 0 good, 0 miss |
| Peak physical footprint of the process | 1.99 GiB |

The footprint is `ri_lifetime_max_phys_footprint` from `proc_pid_rusage` at the end of the
run, in the XCTest process that opened the model, decoded, generated and played: the
runtime's own measurement for this model is 2.0 to 2.3 GiB peak, of which BeatThis's
working memory is most. The decoded song as mono Float is 23.5 MB. Core ML is not offered; the
runtime's [execution provider](https://github.com/ensomi-labs/runtime/blob/v0.3.0/swift/README.md#execution-provider)
measurements give its cost. On iOS the core's `lowMemory` default is on (1.3 to 1.6 GiB on the Mac).

In the iOS simulator (iPhone 17, iOS 27 simulator on bings-mac, the runtime's `GeneratorTests`
through the Swift API on the recorded PCM of hesitation-snow), every generation gave the chart the
native generator made on macOS (`conformance/generate/native-charts`), byte for byte. Seconds per
generation, with the same test under `swift test` on macOS beside it; other jobs loaded the Mac
during the second and third runs, so the times are an upper bound:

| Run | Mac load average (1 min) | iOS, low memory off | iOS, low memory on | macOS, off | macOS, on |
| --- | --- | --- | --- | --- | --- |
| 1 | not recorded | 11.1 | 21.1 | 9.4 | 10.1 |
| 2 | 35 to 67 | 29.7 | 32.2 | 9.7 | 13.6 |
| 3 | 10 to 75 | 19.6 | 22.2 | 11.3 | 11.1 |

The simulator runs the arm64 simulator slice on the Mac's cores; it says nothing about a phone's
speed or memory limit.

Downloading the package from Hugging Face to the Mac took 36 s and 64 s in two runs, when it
had 29 files and no manifest (the link, not the app, sets that).

## Checks

All of these run on the Mac with `xcodebuild … test -only-testing:EnsomiCoreTests/<class>`;
each skips when its files are absent (`ENSOMI_R2_ONNX`, `ENSOMI_RESAMPLER`, `ENSOMI_SONG`
and `ENSOMI_GENERATE_CLI` override the sibling-checkout defaults).

- `OnDeviceChartGenerationTests`: decoder rate and channel averaging, the capability,
  phase and control mappings, the engine default and its persistence, a chosen folder
  kept only if the generator opens it, and refused when it lacks the model's notice.
- `ChartModelDownloaderTests`: install from a local copy of the package with a manifest the
  test writes (exactly the listed files and the manifest, opens, the licence read from the
  manifest, no leftovers), a file the manifest leaves out although `runtime.json` names it
  (the runtime's open refuses it), and, through an HTTP server inside the test process that
  serves the copy with Range support and can drop or stall a response: a connection lost 3 MB
  into `beatthis.onnx` (the next start sends `Range: bytes=3000000-` and fetches no finished
  file again; Remove Download leaves nothing), a file changed on the server while 5 MB of it
  waited (the resumed file fails its SHA-256 and is fetched again whole), cancelling mid-file
  (the finished files and the partial one stay, the next start resumes), and an install
  without a manifest that lacks a file, completed by the library when it starts with only the
  manifest and that file fetched. On a package of three small files, so without the model and
  in CI: a manifest `checkPackage` refuses (`min_runtime` 9.0.0; nothing is fetched after it), a
  file whose SHA-256 differs from the manifest's (the bad file is not kept), completing an
  install without a manifest (the matching file copied, the changed and the missing one
  fetched, `ensomi-model.json` dropped), and the licence and notice texts read from a manifest
  together with ONNX Runtime's files from the runtime package.
  `TEST_RUNNER_ENSOMI_MODEL_DOWNLOAD_LIVE=1` also downloads from Hugging Face at `r2-1.0`.
- `ChartGenerationClientTests`: the seed field (refuses what is not a seed, sends 2^64 − 1), the
  result keeping the seed the server reports though none was asked for, Stop ending in the
  stopped state, and the section check, which refuses a section outside 8–32 seconds whatever
  its target, as the runtime does.
- `OnDeviceParityAndRunTests.testChartMatchesTheNativeGeneratorOnTheSamePCM`: the
  app's decoder and generator against `ensomi-runtime/target/apple/release/generate` on
  the same PCM dump, byte for byte. To keep the dump and both charts and compare by hand,
  from the app root on the Mac:

  ```sh
  TEST_RUNNER_ENSOMI_PARITY_OUT="$PWD/.build/parity" xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac \
    -destination 'platform=macOS' -derivedDataPath .build/solo test \
    -only-testing:EnsomiCoreTests/OnDeviceParityAndRunTests/testChartMatchesTheNativeGeneratorOnTheSamePCM
  ../ensomi-runtime/target/apple/release/generate --model-dir ../ensomi-model/artifacts/release-r2/bulk/hf/ensomi-r2/onnx \
    --assets ../ensomi-runtime/assets/resampler --pcm .build/parity/saigetsu.f32 --sample-rate 44100 \
    --star 3.5 --seed 0 --title Saigetsu --artist Parity --audio-name audio.mp3 --out .build/parity/by-hand.osu
  cmp .build/parity/app.osu .build/parity/by-hand.osu
  ```

  The PCM is 5,875,200 samples at 44,100 Hz; both charts are 34,867 bytes with the same
  SHA-256. Other decoders give other PCM and other charts, and were not compared.
- `OnDeviceParityAndRunTests.testGenerateOnThisDeviceThenExport`: generates saigetsu at 3.5
  stars with a random seed through the generation model, checks the `.osu` parses to the chart,
  writes the `.osz` and opens it with `/usr/bin/unzip`: `audio.mp3` (the song's bytes),
  `generated.osu` (the `.osu`) and `receipt.json` (with the seed). Nothing is played. One run:
  1152 objects with seed 1520549647 in 8.8 s, a 2,138,366-byte `.osz`.
- The runtime's checks for the exporter: `cargo test -p ensomi-osz` reads the archive back with
  the `zip` crate (names, order, stored and deflated entries, contents, same bytes twice), and
  `swift test` at `ensomi-runtime`'s root opens `exportOsz`'s archive with `/usr/bin/unzip`.
- `OnDeviceParityAndRunTests.testAFailedGenerationLeavesTheGeneratorWorking`: a 48 kHz song with a
  resampler table cut short fails with the core's message, and the next song (22050 Hz) gets a
  chart from the same generator. That a panic in the core ends only its own generation is shown by
  the runtime's `crates/ffi/tests/generator.rs`, whose listener panics: the app has no way to make
  the core panic on purpose.
- `OnDeviceParityAndRunTests.testGenerateOnThisDeviceThenAutoplaySilently`
  (`TEST_RUNNER_ENSOMI_E2E_RUN=1`): the numbers above. It drives the generation model and
  the play session in process with the engine's output volume at 0, so nothing is heard
  and no window opens.

- `SetupScreenScreenshotTests` (`TEST_RUNNER_ENSOMI_SCREENSHOT_DIR`): renders Settings and
  Generate a Chart offscreen into PNGs, without a window that takes events. Settings: the Chart
  model section with no model, a download starting, a refused folder and a chosen folder.
  Generate a Chart (1120 by 1180 points, the play window's width, at 2x) on this device with
  saigetsu as `Saigetsu.mp3` and one jumpstream section: no model, ready, generating (set to the
  rows phase), stopped (set), finished (a real generation, seed 2914) and failed (the core's real
  refusal of 7 stars). The set in `docs/screenshots/solo-device` was rendered with:

  ```sh
  TEST_RUNNER_ENSOMI_SCREENSHOT_DIR="$PWD/docs/screenshots/solo-device" xcodebuild -project Ensomi.xcodeproj \
    -scheme EnsomiMac -destination 'platform=macOS' test -only-testing:EnsomiCoreTests/SetupScreenScreenshotTests
  ```

The review of the first renders found the setup screen in the placeholder navy, blue and
green with a grid and a lane preview in per-lane amber, teal, red and blue, against the play
screen's plum theme and notes coloured by kind; Session tiles for Mode, Beatmap ("Backend
generated"), Mock and Reference that mean nothing here; an unlabelled model line; a slider
labelled twice; and the raw core message as the only failure text. Each is changed as the
[design guidelines](design-guidelines.md) now describe. The save panel itself and the screen
at narrow widths and with large text were not rendered.

| State | Screen |
| --- | --- |
| No model | ![](screenshots/solo-device/generate-1-no-model.png) |
| Ready | ![](screenshots/solo-device/generate-2-ready.png) |
| Generating | ![](screenshots/solo-device/generate-3-generating.png) |
| Stopped | ![](screenshots/solo-device/generate-4-stopped.png) |
| Finished | ![](screenshots/solo-device/generate-5-finished.png) |
| Failed | ![](screenshots/solo-device/generate-6-failed.png) |

## Limits

- CPU only, on macOS and iOS. The footprint is about 2 GiB on the Mac.
- One generation at a time. Stop takes effect at the core's next graph run or phase.
- The whole song is decoded into memory before generation.
- Charts from the chart server cannot be saved from the app: the protocol's result carries no
  `.osu`.
- Long-note amount stays unspecified, as with the server.
- A download has no automatic retry. Resuming against Hugging Face itself (its redirect to the
  file host keeping the Range header) was not tried; the tests use a local server.
- A chosen folder's files are checked as the runtime's open checks them (with a manifest, each
  listed file at its listed size); their SHA-256s are not computed.
- On iOS the app builds for the simulator and the generator runs there through the Swift API;
  the app's screens were not run on iOS and nothing ran on a device.
