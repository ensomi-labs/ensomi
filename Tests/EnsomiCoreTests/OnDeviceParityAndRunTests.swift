import AVFoundation
import Darwin
import EnsomiRuntime
import XCTest
@testable import EnsomiCore

/// Runs the real model on saigetsu's audio; each test skips when the model, the resampler tables, the song or the
/// command line generator is absent. `ENSOMI_PARITY_OUT=<dir>` keeps the PCM dump and both charts there.
@MainActor
final class OnDeviceParityAndRunTests: XCTestCase {
    private static let title = "Saigetsu"
    private static let artist = "Parity"

    /// The app's decoder and generator give the chart the native command line generator gives for the same PCM,
    /// request and seed, byte for byte.
    func testChartMatchesTheNativeGeneratorOnTheSamePCM() async throws {
        let model = try OnDeviceFixtures.model()
        let resampler = try OnDeviceFixtures.resampler()
        let song = try OnDeviceFixtures.song()
        let cli = try OnDeviceFixtures.cli()
        let output = try ProcessInfo.processInfo.environment["ENSOMI_PARITY_OUT"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? temporaryDirectory()
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        if ProcessInfo.processInfo.environment["ENSOMI_PARITY_OUT"] == nil { addTeardownBlock { try? FileManager.default.removeItem(at: output) } }

        let audio = try OnDeviceAudioDecoder.decode(song)
        let pcm = output.appendingPathComponent("saigetsu.f32")
        try audio.samples.withUnsafeBufferPointer { Data(buffer: $0) }.write(to: pcm)

        let generator = try OnDeviceChartGenerator(modelDirectory: model, resamplerDirectory: resampler)
        let result = try await generator.generate(
            audioURL: song, star: 3.5, seed: 0, title: Self.title, artist: Self.artist)
        let app = output.appendingPathComponent("app.osu")
        try Data(result.osu.utf8).write(to: app)

        let native = output.appendingPathComponent("native.osu")
        let process = Process()
        process.executableURL = cli
        process.arguments = [
            "--model-dir", model.path, "--assets", resampler.path, "--pcm", pcm.path,
            "--sample-rate", String(Int(audio.sampleRate)), "--star", "3.5", "--seed", "0",
            "--title", Self.title, "--artist", Self.artist, "--audio-name", song.lastPathComponent, "--out", native.path
        ]
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)

        print("PARITY \(audio.samples.count) samples at \(audio.sampleRate) Hz, \(result.chart.objects.count) objects, app.osu \(try Data(contentsOf: app).count) bytes, native.osu \(try Data(contentsOf: native).count) bytes")
        XCTAssertEqual(try Data(contentsOf: app), try Data(contentsOf: native))
        XCTAssertEqual(result.receipt.seed, 0)
        XCTAssertEqual(result.receipt.star, 3.5)
    }

    /// Generates saigetsu on this device through the generation model with a random seed, then makes both exports:
    /// the `.osu` is the chart that was generated, and the `.osz` opens with the system's unzip and holds it, the song
    /// and the receipt with the seed. Nothing is played.
    func testGenerateOnThisDeviceThenExport() async throws {
        let song = try OnDeviceFixtures.song()
        let library = isolatedLibrary(
            defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)), resamplerDirectory: try OnDeviceFixtures.resampler())
        await library.choose(folder: try OnDeviceFixtures.model())
        let generation = Mania4KChartGenerationModel(models: library, defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)))
        await generation.loadCapabilities()
        generation.loadAudioDuration(for: song)

        let started = Date()
        let generated = await generation.generate(audioURL: song, star: 3.5)
        let result = try XCTUnwrap(generated, "\(generation.state)")
        let generationSeconds = Date().timeIntervalSince(started)

        let osu = try XCTUnwrap(result.osu)
        XCTAssertEqual(try parseOsu(text: osu).chart.objects.count, result.chart.objectCount)
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let archive = directory.appendingPathComponent("\(result.exportName).osz")
        try XCTUnwrap(try result.osz()).write(to: archive)
        let unpacked = directory.appendingPathComponent("unpacked")
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        unzip.arguments = ["-q", archive.path, "-d", unpacked.path]
        try unzip.run()
        unzip.waitUntilExit()

        XCTAssertEqual(unzip.terminationStatus, 0)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: unpacked.path).sorted(), ["audio.mp3", "generated.osu", "receipt.json"])
        XCTAssertEqual(try String(contentsOf: unpacked.appendingPathComponent("generated.osu"), encoding: .utf8), osu)
        XCTAssertEqual(try Data(contentsOf: unpacked.appendingPathComponent("audio.mp3")), try Data(contentsOf: song))
        let receipt = try JSONSerialization.jsonObject(with: Data(contentsOf: unpacked.appendingPathComponent("receipt.json"))) as? [String: Any]
        XCTAssertEqual((receipt?["seed"] as? NSNumber)?.uint64Value, result.seed)
        print("EXPORT generated \(result.chart.objectCount) objects with seed \(result.seed) in \(String(format: "%.2f", generationSeconds)) s; \(archive.lastPathComponent), \(try Data(contentsOf: archive).count) bytes")
    }

    /// Generates saigetsu through the generation model, then autoplays the chart through the play session with the
    /// engine's output volume at 0. It takes the song's length, so it needs `TEST_RUNNER_ENSOMI_E2E_RUN=1`.
    func testGenerateOnThisDeviceThenAutoplaySilently() async throws {
        guard ProcessInfo.processInfo.environment["ENSOMI_E2E_RUN"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_E2E_RUN=1 to generate on this device and autoplay the song's length.")
        }
        let song = try OnDeviceFixtures.song()
        let library = isolatedLibrary(
            defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)), resamplerDirectory: try OnDeviceFixtures.resampler())
        let started = Date()
        await library.choose(folder: try OnDeviceFixtures.model())
        let openSeconds = Date().timeIntervalSince(started)
        XCTAssertNotNil(library.chosenFolder)
        let generation = Mania4KChartGenerationModel(models: library, defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)))
        XCTAssertEqual(generation.engine, .device)

        let decodeStart = Date()
        let decoded = try OnDeviceAudioDecoder.decode(song)
        let decodeSeconds = Date().timeIntervalSince(decodeStart)
        await generation.loadCapabilities()
        generation.loadAudioDuration(for: song)
        let generationStart = Date()
        let generated = await generation.generate(audioURL: song, star: 3.5, seed: 0, title: Self.title, artist: Self.artist)
        let generationSeconds = Date().timeIntervalSince(generationStart)
        let chart = try XCTUnwrap(generated, "\(generation.state)").chart
        XCTAssertEqual(generation.state, generated.map { .finished($0) })

        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        let play = Mania4KPlaySessionModel(audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0, audioClock: clock)
        let playStart = Date()
        guard await play.startGeneratedChartPlay(audioFileURL: song, chart: chart, title: Self.title) else {
            throw XCTSkip("The audio engine did not start: \(play.phase)")
        }
        let prepareSeconds = Date().timeIntervalSince(playStart)

        // Each object's key goes down at its time and up at the next release: taps after 20 ms, holds at their end.
        let planHostMs = EnsomiHostClock.currentTimeMS()
        let planAudioMs = clock.audioTimeMs(atHostTimeMs: planHostMs)
        struct KeyEvent { let hostMs: Double; let key: String; let isPressed: Bool }
        let events = chart.objects.map { object -> KeyEvent in
            let hostMs = planHostMs + object.timeMs - planAudioMs
            let key = play.keyBindings.key(for: object.lane)
            switch object.kind {
            case .tap: return KeyEvent(hostMs: hostMs, key: key, isPressed: true)
            case .holdStart: return KeyEvent(hostMs: hostMs, key: key, isPressed: true)
            case .holdEnd: return KeyEvent(hostMs: hostMs, key: key, isPressed: false)
            }
        } + chart.objects.filter { $0.kind == .tap }.map {
            KeyEvent(hostMs: planHostMs + $0.timeMs - planAudioMs + 20, key: play.keyBindings.key(for: $0.lane), isPressed: false)
        }
        let queue = events.sorted { $0.hostMs < $1.hostMs }
        var next = 0
        let deadlineMs = planHostMs + chart.durationMs + 15_000
        while case .playing = play.phase, EnsomiHostClock.currentTimeMS() < deadlineMs {
            let nowMs = EnsomiHostClock.currentTimeMS()
            while next < queue.count, queue[next].hostMs <= nowMs {
                play.handleKeyboardInput(key: queue[next].key, isPressed: queue[next].isPressed, isRepeat: false, hostTimeMs: queue[next].hostMs)
                next += 1
            }
            await play.tick()
            try await Task.sleep(nanoseconds: 4_000_000)
        }
        let totalSeconds = Date().timeIntervalSince(started)

        var usage = rusage_info_v4()
        let status = withUnsafeMutablePointer(to: &usage) {
            $0.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) { proc_pid_rusage(getpid(), RUSAGE_INFO_V4, $0) }
        }
        XCTAssertEqual(status, 0)
        guard case .finished(let result) = play.phase else { return XCTFail("The play ended as \(play.phase).") }
        let counts = result.score.tierCounts
        print(String(
            format: "E2E opened the model in %.2f s; decoding %.0f s of audio alone %.2f s; decode and generation %.2f s (%d objects, %.1f s of chart); play start %.2f s; total %.1f s",
            openSeconds, Double(decoded.samples.count) / decoded.sampleRate, decodeSeconds, generationSeconds, chart.objectCount,
            chart.durationMs / 1_000, prepareSeconds, totalSeconds))
        print(String(
            format: "E2E peak physical footprint of the test process (ri_lifetime_max_phys_footprint) %.2f GiB; autoplay perfect %d, good %d, miss %d",
            Double(usage.ri_lifetime_max_phys_footprint) / Double(1 << 30), counts.perfectCount, counts.goodCount, counts.missCount))
        XCTAssertEqual(counts.missCount, 0)
    }
}
