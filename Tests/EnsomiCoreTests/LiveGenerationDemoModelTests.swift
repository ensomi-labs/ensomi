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

/// Stands in for the generator: records each chart asked for, with a scheduler that makes it when the test says.
@MainActor
private final class SchedulerMaker {
    private(set) var made: [(asset: LocalAudioAsset, lead: LeadPolicy, scheduler: FakeChartScheduler)] = []

    func make(_ asset: LocalAudioAsset, _ request: GenerateChartRequest, _ lead: LeadPolicy) -> ChartScheduler {
        let scheduler = FakeChartScheduler(request: request)
        made.append((asset, lead, scheduler))
        return scheduler
    }

    func finish(_ chart: Chart) {
        made.last?.scheduler.finish(chart)
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
            makeScheduler: { _, _, _ in throw CancellationError() }
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
            stars: { capabilities.selectableStars }, makeScheduler: { _, _, _ in throw CancellationError() }
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
        model.stop()
    }

    @MainActor
    func testAModelThatDoesNotOpenNeverStartsCapture() async throws {
        let permission = DemoPermissionProbe()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission),
            gameplay: Mania4KPlaySessionModel(), models: try installedLibrary(),
            stars: { throw ModelFailure() }, makeScheduler: { _, _, _ in throw CancellationError() }
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
            makeScheduler: { _, _, _ in throw CancellationError() }
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
        let maker = SchedulerMaker()
        let model = try await readyModel(maker)
        model.seedText = "42"
        let reference = makeLocalAudioAsset(displayPath: song.path)

        model.referenceResolved(reference)
        try await eventually { maker.made.count == 1 }
        maker.finish(listenAndPlayChart)
        try await eventually { model.chartNoteCount != nil }
        model.locked(lock(at: 10_000))
        try await eventually { model.gameplay.phase == .playing }

        let made = try XCTUnwrap(maker.made.first)
        XCTAssertEqual(made.asset, reference)
        XCTAssertEqual(made.scheduler.request.star, model.gameplay.starDifficulty)
        XCTAssertEqual(made.scheduler.request.seed, 42)
        XCTAssertEqual(made.lead.renderLeadMs, model.gameplay.scrollTimeMs, "play joins a scroll time ahead")
        XCTAssertEqual(made.lead.marginMs, 0)
        XCTAssertEqual(model.chartSeed, 42)
        XCTAssertEqual(model.chartNoteCount, listenAndPlayChart.objects.count)
        XCTAssertEqual(model.gameplay.playFrame?.metadata.durationMs, Double(listenAndPlayChart.durationMs))
        XCTAssertGreaterThanOrEqual(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, 10_000)
        model.stop()
    }

    @MainActor
    func testFinalLockBeforeTheChartIsMadeWaitsForIt() async throws {
        let maker = SchedulerMaker()
        let model = try await readyModel(maker)

        model.referenceResolved(makeLocalAudioAsset(displayPath: song.path))
        try await eventually { maker.made.count == 1 }
        model.locked(lock(at: 10_000))
        try await eventually { model.gameplay.phase == .loading }
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(model.gameplay.phase, .loading, "nothing plays before the chart is made")
        XCTAssertTrue(model.showingGameplay)

        maker.finish(listenAndPlayChart)
        try await eventually { model.gameplay.phase == .playing }

        XCTAssertEqual(maker.made.count, 1, "final lock takes the chart started at resolution")
        XCTAssertGreaterThanOrEqual(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, 10_000)
        model.stop()
    }

    @MainActor
    func testStopEndsTheChartBeingMade() async throws {
        let maker = SchedulerMaker()
        let model = try await readyModel(maker)

        model.referenceResolved(makeLocalAudioAsset(displayPath: song.path))
        try await eventually { maker.made.count == 1 }
        let chart = try XCTUnwrap(model.chart)
        model.stop()

        do {
            _ = try await chart.result()
            XCTFail("A stopped chart is never made.")
        } catch {}
        XCTAssertNil(model.chart)
        XCTAssertNil(model.chartNoteCount)
    }

    @MainActor
    func testAStarOutsideTheRangeMakesNoChartAndFinalLockPlaysNothing() async throws {
        let maker = SchedulerMaker()
        let model = try await readyModel(maker)
        let range = try XCTUnwrap(model.starRange)
        model.gameplay.starDifficulty = range.upperBound + 1

        model.referenceResolved(makeLocalAudioAsset(displayPath: song.path))
        model.locked(lock(at: 10_000))
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertTrue(maker.made.isEmpty)
        XCTAssertNil(model.chart)
        XCTAssertEqual(model.errorMessage, model.starValidationMessage)
        XCTAssertFalse(model.showingGameplay)
        model.stop()
    }

    /// The released model on a real song, with final lock coming at once: first from the song's PCM, while the chart
    /// is still being made, then from the audio stages the first chart kept. Logs the time from resolution to the
    /// finished chart and from final lock to the first note. Nothing else competes for the CPU: no capture, reference
    /// index or alignment.
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
        let reference = makeLocalAudioAsset(displayPath: song.path, sha256: try sha256Hex(of: song))
        let generator = try await library.generator()
        model.seedText = "7"
        var charts: [ChartResult] = []

        for source in ["PCM", "kept audio stages"] {
            model.enter()
            try await eventually(within: .seconds(10)) { model.modelReady }
            XCTAssertEqual(model.starRange, generator.selectableStars)

            let resolved = ContinuousClock.now
            model.referenceResolved(reference)
            let chart = try XCTUnwrap(model.chart)
            let lockedAtMs = 30_000.0
            model.locked(lock(at: lockedAtMs, of: song))
            let made = try await chart.result()
            let ready = resolved.duration(to: .now)
            try await eventually(within: .seconds(10)) { model.gameplay.phase == .playing }
            charts.append(made)

            let joinMs = try XCTUnwrap(chart.joinMs)
            let firstNoteMs = try XCTUnwrap(made.chart.objects.map { Double($0.startMs) }.filter { $0 >= joinMs }.min())
            XCTAssertGreaterThan(made.chart.objects.count, 0)
            XCTAssertEqual(model.gameplay.playFrame?.metadata.durationMs, Double(made.chart.durationMs))
            XCTAssertGreaterThan(model.gameplay.playFrame?.gameplayChartTimeMs ?? 0, lockedAtMs)
            XCTAssertGreaterThanOrEqual(joinMs, lockedAtMs + model.gameplay.scrollTimeMs, "the join is at least a scroll time ahead")
            // The recording's audio stages are kept for its next chart.
            try await eventually { await generator.preparedSong(sha256: reference.sha256) != nil }
            let stars = model.gameplay.starDifficulty.formatted(.number.precision(.fractionLength(1)))
            print("""
                Listen & Play R2 from \(source): \(song.lastPathComponent), \(made.chart.durationMs) ms, \
                \(made.chart.objects.count) objects at \(stars) stars, seed \(model.chartSeed ?? 0): reference resolved to \
                chart ready \(ready); final lock to the first note \(Int(firstNoteMs - lockedAtMs)) ms (join at \
                \(Int(joinMs)) ms, first note at \(Int(firstNoteMs)) ms)
                """)
        }
        XCTAssertEqual(charts.map(\.osu).last, charts.first?.osu, "the kept audio stages make the chart the PCM made")
        model.stop()
    }

    /// A model ready to play, with `maker` making its charts.
    @MainActor
    private func readyModel(_ maker: SchedulerMaker) async throws -> LiveGenerationDemoModel {
        let capabilities = offered()
        let model = LiveGenerationDemoModel(
            recognition: LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: DemoPermissionProbe()),
            gameplay: Mania4KPlaySessionModel(audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0),
            models: try installedLibrary(),
            stars: { capabilities.selectableStars }, makeScheduler: { maker.make($0, $1, $2) }
        )
        model.enter()
        try await eventually { model.modelReady }
        return model
    }

    /// Final lock now, at `referenceTimeMS` of the recording.
    private func lock(at referenceTimeMS: Double, of audioFileURL: URL? = nil) -> LiveRecognitionPlaySessionRequest {
        LiveRecognitionPlaySessionRequest(
            audioFileURL: audioFileURL ?? song, referenceTimeMS: referenceTimeMS,
            anchorHostTimeMS: EnsomiHostClock.currentTimeMS(), title: "Song"
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
private let listenAndPlayChart = Chart(durationMs: 30_000, objects: [
    ChartObject(lane: 0, startMs: 2_000, endMs: nil), ChartObject(lane: 2, startMs: 9_000, endMs: nil),
    ChartObject(lane: 1, startMs: 10_500, endMs: 11_500), ChartObject(lane: 3, startMs: 12_000, endMs: nil),
    ChartObject(lane: 0, startMs: 20_000, endMs: nil)
])

@MainActor
private func eventually(within limit: Duration = .seconds(2), _ condition: @MainActor () async -> Bool) async throws {
    let start = ContinuousClock.now
    while !(await condition()) {
        guard start.duration(to: .now) < limit else { throw TimedOut() }
        try await Task.sleep(for: .milliseconds(5))
    }
}
#endif
