---
commit: c90a66a
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
[`sed-i/ensomi-r2`](https://huggingface.co/sed-i/ensomi-r2) on Hugging Face
(`runtime.json`, six graphs, state and array files: 29 files, 120 MB). Two ways to have it,
in Settings under Chart model, or on Generate a Chart when "On this device" is chosen
and no model is installed:

- **Download Model** fetches it into `Application Support/Ensomi/Models/ensomi-r2`
  with a progress bar and **Cancel Download**. It lists the directory
  (`/api/models/sed-i/ensomi-r2/tree/main/onnx`), downloads `runtime.json`, asks the
  runtime's `modelFiles(runtimeConfig:)` for the rest, adds `ensomi-model.json` if the
  listing has one (the runtime then checks the package against it on open), and checks
  every file's size and, where the listing gives an LFS object id, its SHA-256 (CryptoKit,
  in 1 MiB chunks). Files go to a `.download-*` directory beside the install directory.
  Only when every file is right and `ChartGenerator.open` accepts the directory is it moved
  into place, replacing an older install. A cancelled or failed download deletes
  the temporary directory and leaves the installed model, if any, untouched. A stale
  temporary directory from a crash is removed at the next download.
- **Choose Folder…** uses any directory that holds the files, such as a local copy of
  `onnx/`. The folder is refused unless `ChartGenerator` opens it. It is kept across
  launches as a bookmark (security-scoped on macOS, which also makes it work in a
  sandboxed build) and takes precedence over the download; **Use Downloaded Model**
  goes back. **Remove Download** deletes the downloaded copy.

`EnsomiMac` has no entitlements file, so it is not sandboxed: it reaches Hugging Face and
any folder without a grant. A sandboxed channel would need the network client and
user-selected read entitlements. iOS is always sandboxed; the bookmark covers the
chosen folder there.

When a model is installed, "On this device" is the default engine; "Chart server" stays
selectable and the choice is remembered. The engine picker is disabled during a generation.

## What runs where

| Step | Where |
| --- | --- |
| Pick the song, section validation, star range | App (`Mania4KChartGenerationModel`); the range and section kinds come from the model's capabilities (`ChartGenerator.capabilities()`, mapped to the protocol `ModelCapabilities`) |
| Decode | App: `AVAudioFile` at the file's own sample rate, channels averaged in Double, then Float (`OnDeviceAudioDecoder`) |
| Resample to 22050 Hz, beat tracking (BeatThis), rows, arrangement, `.osu` | The runtime's Rust core in the app process; ONNX Runtime CPU runs the graphs on a thread of its own |
| Validate and play | App: the gameplay core checks the chart, `Mania4KPlaySessionModel` plays it from the song's time 0 |
| Server | Not involved |

The core resamples any PCM not at 22050 Hz with the runtime's soxr tables. Those 27 files
(`resample-<rate>.json`, `.bin` and `-boundary.bin` for 11025, 16000, 24000, 32000, 44100, 48000,
88200, 96000 and 192000 Hz, 14 MB) are not in the runtime repository. The runtime's
`scripts/build-apple.sh` copies them from the TypeScript runtime's
`ensomi-web/packages/runtime/assets` into `../ensomi-runtime/build/apple/resampler`, and
`project.yml` bundles that directory into `EnsomiMac` and `EnsomiIOS` as a folder resource
(`resampler` in the bundle's resources). The app passes the bundled directory as
`resamplerDirectory`. A song at 22050 Hz needs none of them.

Progress follows the core's phases (loading, timing, rows, arrangement, export). Stop
cancels the task; the core stops at its next graph run or phase and the screen returns to
idle. A refusal (`star_out_of_range`, `unsupported_control`, `generation_failed`) or an
unreadable model shows the core's message. The generator is opened once and kept; the
seed is a new random one unless the caller gives it.

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
working memory is most. The decoded song as mono Float is 23.5 MB. Core ML is not offered:
it opens in 10 s, runs BeatThis two to three times slower and needs 11.5 GiB (see the
runtime's Swift README). On iOS the core's `lowMemory` default is on (1.3 to 1.6 GiB, a
little slower); it has been built for the simulator only.

Downloading the 29 files from Hugging Face to the Mac took 36 s and 64 s in two runs
(the link, not the app, sets that).

## Checks

All of these run on the Mac with `xcodebuild … test -only-testing:EnsomiCoreTests/<class>`;
each skips when its files are absent (`ENSOMI_R2_ONNX`, `ENSOMI_RESAMPLER`, `ENSOMI_SONG`
and `ENSOMI_GENERATE_CLI` override the sibling-checkout defaults).

- `OnDeviceChartGenerationTests`: decoder rate and channel averaging, the capability,
  phase and control mappings, the engine default and its persistence, a chosen folder
  kept only if the generator opens it.
- `ChartModelDownloaderTests`: install from a local copy of the listing and files
  (only the named files, opens, no leftovers), a wrong checksum, a file the listing lacks,
  cancelling mid-file, each leaving no model or temporary directory.
  `TEST_RUNNER_ENSOMI_MODEL_DOWNLOAD_LIVE=1` also downloads from Hugging Face.
- `OnDeviceParityAndRunTests.testChartMatchesTheNativeGeneratorOnTheSamePCM`: the
  app's decoder and generator against `ensomi-runtime/target/apple/release/generate` on
  the same PCM dump, byte for byte. To keep the dump and both charts and compare by hand,
  from the app root on the Mac:

  ```sh
  TEST_RUNNER_ENSOMI_PARITY_OUT="$PWD/.build/parity" xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac \
    -destination 'platform=macOS' -derivedDataPath .build/solo test \
    -only-testing:EnsomiCoreTests/OnDeviceParityAndRunTests/testChartMatchesTheNativeGeneratorOnTheSamePCM
  ../ensomi-runtime/target/apple/release/generate --model-dir ../ensomi-model/artifacts/release-r2/bulk/hf/ensomi-r2/onnx \
    --assets ../ensomi-runtime/build/apple/resampler --pcm .build/parity/saigetsu.f32 --sample-rate 44100 \
    --star 3.5 --seed 0 --title Saigetsu --artist Parity --audio-name audio.mp3 --out .build/parity/by-hand.osu
  cmp .build/parity/app.osu .build/parity/by-hand.osu
  ```

  The PCM is 5,875,200 samples at 44,100 Hz; both charts are 34,867 bytes with the same
  SHA-256. Other decoders give other PCM and other charts, and were not compared.
- `OnDeviceParityAndRunTests.testGenerateOnThisDeviceThenAutoplaySilently`
  (`TEST_RUNNER_ENSOMI_E2E_RUN=1`): the numbers above. It drives the generation model and
  the play session in process with the engine's output volume at 0, so nothing is heard
  and no window opens.

## Limits

- CPU only, on macOS and iOS. The footprint is about 2 GiB on the Mac.
- One generation at a time. Stop takes effect at the core's next graph run or phase.
- The whole song is decoded into memory before generation.
- The seed is not shown or settable in the screen, and the `.osu` the core returns is not
  offered for saving; only the chart tokens play.
- Long-note amount stays unspecified, as with the server.
- A download is not resumed and has no retry. A file without an LFS object id (the JSON
  files) is checked by size only.
- ONNX Runtime's MIT licence and notices (`ensomi-runtime/licenses/onnxruntime`) are not
  yet shown in the app.
- On iOS the build is verified for the simulator; no run on a device.
