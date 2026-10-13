#if os(macOS) && DEBUG
import EnsomiProtocol
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

private actor DemoPermissionProbe: MicrophonePermissionProviding {
    var calls = 0
    func currentStatus() -> MicrophonePermissionStatus { calls += 1; return .denied }
    func requestAccess() -> MicrophonePermissionStatus { calls += 1; return .denied }
}

/// Stands in for the generator: records each chart asked for and finishes it when the test says.
@MainActor
private final class ChartMaker {
    struct Request: Equatable {
        let audioURL: URL
        let star: Double
        let seed: UInt64
    }

    private(set) var requests: [Request] = []
    private var waiting: CheckedContinuation<Mania4KGeneratedChart, Error>?

    func make(_ audioURL: URL, _ star: Double, _ seed: UInt64) async throws -> Mania4KGeneratedChart {
        requests.append(Request(audioURL: audioURL, star: star, seed: seed))
        return try await withCheckedThrowingContinuation { waiting = $0 }
    }

    func finish(_ chart: Mania4KGeneratedChart) {
        waiting?.resume(returning: chart)
        waiting = nil
    }
}

final class LiveGenerationDemoModelTests: XCTestCase {
    private let song = URL(fileURLWithPath: "/tmp/listen-and-play.mp3")

    @MainActor
    func testWithoutAModelNothingStarts() async throws {
        let permission = DemoPermissionProbe()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission),
            gameplay: Mania4KPlaySessionModel(), models: isolatedLibrary(),
            stars: {
                XCTFail("Without a model nothing is opened.")
                return offered().selectableStars
            },
            makeChart: { _, _, _ in throw CancellationError() }
        )

        model.enter()
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertFalse(model.modelReady)
        XCTAssertFalse(model.checkingModel)
        XCTAssertNil(model.starRange)
        let calls = await permission.calls
        XCTAssertEqual(calls, 0)
        model.stop()
    }

    @MainActor
    func testAnInstalledModelGivesItsStarRangeAndStartsRecognition() async throws {
        let permission = DemoPermissionProbe()
        let capabilities = offered()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission),
            gameplay: Mania4KPlaySessionModel(), models: try installedLibrary(),
            stars: { capabilities.selectableStars }, makeChart: { _, _, _ in throw CancellationError() }
        )
        model.gameplay.starDifficulty = capabilities.maxStarExclusive + 1

        model.enter()
        try await eventually { await permission.calls > 0 }

        let range = try XCTUnwrap(model.starRange)
        XCTAssertTrue(model.modelReady)
        XCTAssertEqual(range, capabilities.selectableStars)
        XCTAssertTrue(range.contains(capabilities.minStar))
        XCTAssertFalse(range.contains(capabilities.maxStarExclusive))
        XCTAssertEqual(model.gameplay.starDifficulty, range.upperBound, "the star is brought into the range")
        XCTAssertFalse(model.recognition.startsPrototypeInference)
        model.stop()
    }

    @MainActor
    func testAModelThatDoesNotOpenNeverStartsCapture() async throws {
        let permission = DemoPermissionProbe()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission),
            gameplay: Mania4KPlaySessionModel(), models: try installedLibrary(),
            stars: { throw ModelFailure() }, makeChart: { _, _, _ in throw CancellationError() }
        )

        model.enter()
        try await eventually { !model.checkingModel }

        XCTAssertFalse(model.modelReady)
        XCTAssertNotNil(model.errorMessage)
        let calls = await permission.calls
        XCTAssertEqual(calls, 0)
        model.stop()
    }

    @MainActor
    func testStopDiscardsALateModelOpen() async throws {
        let permission = DemoPermissionProbe()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission),
            gameplay: Mania4KPlaySessionModel(), models: try installedLibrary(),
            stars: {
                try await Task.sleep(for: .milliseconds(100))
                throw ModelFailure()
            },
            makeChart: { _, _, _ in throw CancellationError() }
        )

        model.enter()
        model.stop()
        try await Task.sleep(for: .milliseconds(150))

        XCTAssertFalse(model.modelReady)
        XCTAssertFalse(model.checkingModel)
        XCTAssertNil(model.errorMessage)
        let calls = await permission.calls
        XCTAssertEqual(calls, 0)
    }

    @MainActor
    func testAChartMadeBeforeFinalLockIsTheOnePlayed() async throws {
        let maker = ChartMaker()
        let model = try await readyModel(maker)
        model.seedText = "42"

        model.referenceResolved(song)
        try await eventually { maker.requests.count == 1 }
        let chart = try listenAndPlayChart()
        maker.finish(chart)
        try await eventually { model.chartNoteCount != nil }
        model.locked(lock(at: 10_000))
        try await eventually { model.gameplay.phase == .playing }

        XCTAssertEqual(maker.requests, [.init(audioURL: song, star: model.gameplay.starDifficulty, seed: 42)])
        XCTAssertEqual(model.chartSeed, 42)
        XCTAssertEqual(model.chartNoteCount, chart.objectCount)
        XCTAssertEqual(model.gameplay.playFrame?.metadata.durationMs, chart.durationMs)
        XCTAssertGreaterThanOrEqual(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, 10_000)
        model.stop()
    }

    @MainActor
    func testFinalLockBeforeTheChartIsMadeWaitsForIt() async throws {
        let maker = ChartMaker()
        let model = try await readyModel(maker)

        model.referenceResolved(song)
        try await eventually { maker.requests.count == 1 }
        model.locked(lock(at: 10_000))
        try await eventually { model.gameplay.phase == .loading }
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(model.gameplay.phase, .loading, "nothing plays before the chart is made")
        XCTAssertTrue(model.showingGameplay)

        maker.finish(try listenAndPlayChart())
        try await eventually { model.gameplay.phase == .playing }

        XCTAssertEqual(maker.requests.count, 1, "final lock takes the chart started at resolution")
        XCTAssertGreaterThanOrEqual(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, 10_000)
        model.stop()
    }

    @MainActor
    func testAStarOutsideTheRangeMakesNoChartAndFinalLockPlaysNothing() async throws {
        let maker = ChartMaker()
        let model = try await readyModel(maker)
        let range = try XCTUnwrap(model.starRange)
        model.gameplay.starDifficulty = range.upperBound + 1

        model.referenceResolved(song)
        model.locked(lock(at: 10_000))
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertTrue(maker.requests.isEmpty)
        XCTAssertNil(model.chart)
        XCTAssertEqual(model.errorMessage, model.starValidationMessage)
        XCTAssertFalse(model.showingGameplay)
        model.stop()
    }

    /// The released model on a real song: the time from the recording's resolution to the finished chart, with final
    /// lock coming first and waiting for it. Nothing else competes for the CPU: no capture, reference index or alignment.
    @MainActor
    func testTheReleasedModelMakesTheSongsChartAndPlaysItFromTheHeardPosition() async throws {
        let modelDirectory = try OnDeviceFixtures.model()
        let song = try OnDeviceFixtures.song()
        let library = isolatedLibrary()
        await library.choose(folder: modelDirectory)
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: DemoPermissionProbe()),
            gameplay: Mania4KPlaySessionModel(audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0), models: library
        )
        model.enter()
        try await eventually(within: .seconds(10)) { model.modelReady }
        let offeredStars = try await library.generator().selectableStars
        XCTAssertEqual(model.starRange, offeredStars)

        let resolved = ContinuousClock.now
        model.referenceResolved(song)
        let making = try XCTUnwrap(model.chart)
        model.locked(lock(at: 30_000, of: song))
        let chart = try await making.value
        let ready = resolved.duration(to: .now)
        try await eventually(within: .seconds(10)) { model.gameplay.phase == .playing }
        let playing = resolved.duration(to: .now)

        XCTAssertGreaterThan(chart.objectCount, 0)
        XCTAssertEqual(model.gameplay.playFrame?.metadata.durationMs, chart.durationMs)
        XCTAssertGreaterThan(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, 30_000)
        let stars = model.gameplay.starDifficulty.formatted(.number.precision(.fractionLength(1)))
        print("""
            Listen & Play R2: \(song.lastPathComponent), \(Int(chart.durationMs)) ms, \(chart.objectCount) objects at \(stars) \
            stars, seed \(model.chartSeed ?? 0): reference resolved to chart ready \(ready), to play \(playing)
            """)
        model.stop()
    }

    /// A model ready to play, with `maker` making its charts.
    @MainActor
    private func readyModel(_ maker: ChartMaker) async throws -> LiveGenerationDemoModel {
        let capabilities = offered()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: DemoPermissionProbe()),
            gameplay: Mania4KPlaySessionModel(audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0),
            models: try installedLibrary(),
            stars: { capabilities.selectableStars }, makeChart: { try await maker.make($0, $1, $2) }
        )
        model.enter()
        try await eventually { model.modelReady }
        return model
    }

    /// Final lock now, at `referenceTimeMS` of the recording.
    private func lock(at referenceTimeMS: Double, of audioFileURL: URL? = nil) -> LiveRecognitionPlaySessionRequest {
        LiveRecognitionPlaySessionRequest(
            audioFileURL: audioFileURL ?? song, referenceTimeMS: referenceTimeMS,
            anchorHostTimeMS: EnsomiHostClock.currentTimeMS(), durationMS: nil, title: "Song", isMock: false,
            musicSource: .background
        )
    }
}

private struct ModelFailure: LocalizedError {
    var errorDescription: String? { "The model files are unreadable." }
}

private struct TimedOut: Error {}

/// R2's capabilities as far as Listen & Play reads them.
private func offered() -> ChartGenerationCapabilities {
    var capabilities = ChartGenerationCapabilities()
    capabilities.minStar = 2
    capabilities.maxStarExclusive = 6
    return capabilities
}

/// A library whose downloaded model is complete; the generator cannot open its files.
@MainActor
private func installedLibrary() throws -> ChartModelLibrary {
    let models = try temporaryDirectory()
    try PackageFixture.small(in: models.appendingPathComponent("ensomi-r2", isDirectory: true)).writeManifest()
    return isolatedLibrary(modelsDirectory: models)
}

/// A 30 s chart with notes before and after 11 s.
private func listenAndPlayChart() throws -> Mania4KGeneratedChart {
    try Mania4KGeneratedChart(makeChart(duration: 30_000, [
        (0, 2_000, nil), (2, 9_000, nil), (1, 10_500, 11_500), (3, 12_000, nil), (0, 20_000, nil)
    ]))
}

@MainActor
private func eventually(within limit: Duration = .seconds(2), _ condition: @MainActor () async -> Bool) async throws {
    let start = ContinuousClock.now
    while !(await condition()) {
        guard start.duration(to: .now) < limit else { throw TimedOut() }
        try await Task.sleep(for: .milliseconds(5))
    }
}
#endif
