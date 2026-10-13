import AVFoundation
import EnsomiProtocol
import EnsomiRuntime
import XCTest
@testable import EnsomiCore

final class OnDeviceChartGenerationTests: XCTestCase {
    func testDecoderKeepsTheFilesRateAndAveragesChannels() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: url) }
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2))
        let frames: AVAudioFrameCount = 100_000
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        let channels = try XCTUnwrap(buffer.floatChannelData)
        for frame in 0..<Int(frames) {
            channels[0][frame] = 0.5
            channels[1][frame] = frame % 2 == 0 ? -0.25 : 0.75
        }
        try AVAudioFile(forWriting: url, settings: format.settings).write(from: buffer)

        let audio = try OnDeviceAudioDecoder.decode(url)

        XCTAssertEqual(audio.sampleRate, 44_100)
        XCTAssertEqual(audio.samples.count, Int(frames))
        XCTAssertEqual(audio.samples[0], 0.125)
        XCTAssertEqual(audio.samples[1], 0.625)
        XCTAssertEqual(audio.samples[Int(frames) - 1], 0.625)
    }

    func testCapabilitiesTakeTheProtocolShape() {
        let source = ModelCapabilities(
            modelName: "Ensomi R2", modelVersion: "r2-1.0",
            files: [ModelFile(path: "runtime.json", sha256Hex: "ab", sizeBytes: 12)],
            minStar: 2, maxStarExclusive: 6,
            controls: [
                ControlCapability(name: "jumpstream", status: .works, note: ""),
                ControlCapability(name: "stream", status: .experimental, note: "Unchecked"),
                ControlCapability(name: "long_notes", status: .notWorking, note: "No effect")
            ]
        )

        let capabilities = ChartGenerationCapabilities(source)

        XCTAssertEqual(capabilities.modelName, "Ensomi R2")
        XCTAssertEqual(capabilities.modelVersion, "r2-1.0")
        XCTAssertEqual(capabilities.minStar, 2)
        XCTAssertEqual(capabilities.maxStarExclusive, 6)
        XCTAssertEqual(capabilities.files.map(\.path), ["runtime.json"])
        XCTAssertEqual(capabilities.files.map(\.sha256Hex), ["ab"])
        XCTAssertEqual(capabilities.controls.map(\.name), ["jumpstream", "stream", "long_notes"])
        XCTAssertEqual(capabilities.controls.map(\.status), [.works, .experimental, .notWorking])
        XCTAssertEqual(capabilities.controls[1].note, "Unchecked")
    }

    func testSectionsReachTheCoreAsTheRequestTheyWere() throws {
        var sections: [ChartGenerationSection] = []
        let kinds: [ChartGenerationSection.Kind] = [.stream, .jumpstream, .chordstream, .jack, .chordjack, .star]
        for (index, kind) in kinds.enumerated() {
            var section = ChartGenerationSection(kind: kind, star: 4.2)
            section.start = ChartGenerationSection.timeLabel(milliseconds: Double(index * 8_000))
            section.end = ChartGenerationSection.timeLabel(milliseconds: Double((index + 1) * 8_000))
            section.strength = 0.25
            sections.append(section)
        }
        let controls = try ChartGenerationSection.controls(for: sections, durationMS: 60_000, starRange: 2...5.9)

        let request = ChartControls(controls)

        XCTAssertNil(request.longNotes)
        XCTAssertEqual(request.sections.map(\.startMs), [0, 8_000, 16_000, 24_000, 32_000, 40_000])
        XCTAssertEqual(request.sections.map(\.endMs), [8_000, 16_000, 24_000, 32_000, 40_000, 48_000])
        XCTAssertEqual(request.sections.map(\.target), [
            .pattern(kind: .stream, strength: 0.25), .pattern(kind: .jumpstream, strength: 0.25),
            .pattern(kind: .chordstream, strength: 0.25), .pattern(kind: .jack, strength: 0.25),
            .pattern(kind: .chordjack, strength: 0.25), .star(star: 4.2)
        ])
    }

    func testPhasesKeepTheirNames() {
        let phases: [GenerationPhase] = [.loading, .timing, .rows, .arrangement, .export]
        XCTAssertEqual(phases.map(ChartGenerationPhase.init), [.loading, .timing, .rows, .arrangement, .export])
    }

    @MainActor
    func testTheDeviceIsTheDefaultOnlyWithAModelAndTheChoiceIsKept() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let models = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: models) }

        let without = Mania4KChartGenerationModel(models: isolatedLibrary(defaults: defaults, modelsDirectory: models), defaults: defaults)
        XCTAssertEqual(without.engine, .server)

        try PackageFixture.small(in: models.appendingPathComponent("ensomi-r2", isDirectory: true)).writeManifest()
        let with = Mania4KChartGenerationModel(models: isolatedLibrary(defaults: defaults, modelsDirectory: models), defaults: defaults)
        XCTAssertEqual(with.engine, .device)

        with.choose(engine: .server)
        let later = Mania4KChartGenerationModel(models: isolatedLibrary(defaults: defaults, modelsDirectory: models), defaults: defaults)
        XCTAssertEqual(later.engine, .server)
    }

    @MainActor
    func testAFolderIsKeptAcrossLaunchesOnlyIfTheGeneratorOpensIt() async throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let empty = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: empty) }
        let library = isolatedLibrary(defaults: defaults)

        await library.choose(folder: empty)

        XCTAssertNil(library.chosenFolder)
        XCTAssertNotNil(library.folderError)
        XCTAssertFalse(isolatedLibrary(defaults: defaults).hasModel)

        let model = try OnDeviceFixtures.model()
        await library.choose(folder: model)

        XCTAssertEqual(library.chosenFolder, model)
        XCTAssertNil(library.folderError)
        XCTAssertEqual(isolatedLibrary(defaults: defaults).activeModel?.standardizedFileURL.path, model.standardizedFileURL.path)

        library.forgetChosenFolder()
        XCTAssertFalse(library.hasModel)
        XCTAssertFalse(isolatedLibrary(defaults: defaults).hasModel)
    }

    /// A folder the generator opens is still refused when it lacks a licence notice of the model, so the app never
    /// runs a model whose notices it cannot show.
    @MainActor
    func testAFolderWithoutTheModelsNoticesIsRefused() async throws {
        let model = try OnDeviceFixtures.model()
        let folder = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let config = try String(contentsOf: model.appendingPathComponent("runtime.json"), encoding: .utf8)
        let notices = try noticeFiles(runtimeConfig: config)
        for name in ["runtime.json"] + (try modelFiles(runtimeConfig: config)) where !notices.contains(name) {
            try FileManager.default.createSymbolicLink(at: folder.appendingPathComponent(name), withDestinationURL: model.appendingPathComponent(name))
        }
        let library = isolatedLibrary()

        await library.choose(folder: folder)

        XCTAssertNil(library.chosenFolder)
        XCTAssertNil(library.license)
        XCTAssertTrue(try XCTUnwrap(library.folderError).contains(try XCTUnwrap(notices.first)), "\(library.folderError ?? "")")

        await library.choose(folder: model)

        XCTAssertEqual(library.chosenFolder, model)
        XCTAssertNil(library.license?.expression, "a folder without a manifest names no licence expression")
        XCTAssertEqual(library.license?.notices.map(\.name), notices)
    }
}
