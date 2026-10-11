import XCTest
@testable import EnsomiCore

@MainActor
final class Mania4KPlaySessionModelTests: XCTestCase {
    func testDefaultsMatchFirstPlayableSpec() {
        let model = Mania4KPlaySessionModel()

        XCTAssertEqual(model.starDifficulty, 4.0)
        XCTAssertEqual(model.scrollSpeed, 25.0)
        XCTAssertEqual(model.audioOffsetMilliseconds, -20)
        XCTAssertEqual(model.visualOffsetMilliseconds, -15)
        XCTAssertEqual(model.judgeDifficulty, .c)
        XCTAssertEqual(model.keyBindings, .default)
        XCTAssertEqual(model.liveInputLaneStates.filter(\.isPressed), [])
        XCTAssertEqual(model.phase, .setup)
        XCTAssertFalse(model.isReadyToStart)
        XCTAssertNil(model.activeConfiguration)
        XCTAssertNil(model.playFrame)
    }

    func testHostTimeAnchoredClockProjectsFromHostTimeAndClampsAtDuration() async throws {
        let projectedClock = HostTimeAnchoredMania4KAudioClock(
            referenceTimeAtAnchorMS: 179_500,
            durationMS: 180_000,
            anchorHostTimeMS: 10_000,
            hostTimeProvider: { 10_250 }
        )

        let metadata = try await projectedClock.prepare(audioFileURL: URL(fileURLWithPath: "/tmp/audio.mp3"))
        let projectedTimeMS = await projectedClock.currentAudioTimeMs()

        XCTAssertEqual(metadata.durationMs, 180_000)
        XCTAssertEqual(projectedTimeMS, 179_750)
        XCTAssertEqual(projectedClock.audioTimeMs(atHostTimeMs: 10_100), 179_600)
        XCTAssertEqual(projectedClock.audioTimeMs(atHostTimeMs: 9_000), 178_500)
        XCTAssertEqual(projectedClock.audioTimeMs(atHostTimeMs: 10_600), 180_000)

        let clampedClock = HostTimeAnchoredMania4KAudioClock(
            referenceTimeAtAnchorMS: 179_500,
            durationMS: 180_000,
            anchorHostTimeMS: 10_000,
            hostTimeProvider: { 12_000 }
        )
        let clampedTimeMS = await clampedClock.currentAudioTimeMs()

        XCTAssertEqual(clampedTimeMS, 180_000)
    }

    func testAmbientGeneratedBackendPlayUsesHostClockWithoutPlayingDefaultAudio() async throws {
        let defaultClock = TrackingMania4KAudioClock()
        let endpoint = ScriptedInferenceEndpoint(
            objects: [
                tap(.left, 3_000),
                tap(.right, 8_500)
            ],
            completeThroughMS: 10_000
        )
        let endpointFactory = ScriptedInferenceEndpointFactory(endpoint: endpoint)
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: defaultClock,
            inferenceEndpointClientFactory: endpointFactory.makeClient(configuration:)
        )
        let anchorHostTimeMS = EnsomiHostClock.currentTimeMS()

        let started = await model.startAmbientGeneratedBackendPlay(
            audioFileURL: URL(fileURLWithPath: "/tmp/ambient.mp3"),
            isMock: true,
            referenceTimeMS: 2_000,
            anchorHostTimeMS: anchorHostTimeMS,
            durationMS: 10_000,
            title: "Ambient Test",
            musicSource: .background
        )

        XCTAssertTrue(started)
        XCTAssertEqual(model.phase, .playing)
        XCTAssertEqual(model.activeConfiguration?.chartSource, .generated(displayName: "Ambient: Ambient Test"))
        XCTAssertEqual(model.backendReferenceTimeMS, 2_000)
        XCTAssertGreaterThanOrEqual(model.playFrame?.gameplayChartTimeMs ?? -1, 2_000)
        let prepareCallCount = await defaultClock.prepareCallCount()
        let playCallCount = await defaultClock.playCallCount()
        let audioPathMusicSources = await endpoint.audioPathCalls().map(\.musicSource)
        let referenceTimes = await endpoint.referenceTimeCalls().map(\.refTimeMS)

        XCTAssertEqual(prepareCallCount, 0)
        XCTAssertEqual(playCallCount, 0)
        XCTAssertEqual(endpointFactory.recordedConfigurations, [
            InferenceEndpointConfiguration(difficulty: 4.0, isMock: true)
        ])
        XCTAssertEqual(audioPathMusicSources, [.background])
        XCTAssertEqual(referenceTimes, [2_000])
    }

    func testKeyBindingSetNormalizesKeysAndRejectsInvalidUpdates() {
        let custom = Mania4KKeyBindingSet(keysByLane: [
            .left: "A",
            .innerLeft: "S",
            .innerRight: "L",
            .right: ";"
        ])

        XCTAssertEqual(custom?.key(for: .left), "a")
        XCTAssertEqual(custom?.displayLabel(for: .left), "A")
        XCTAssertEqual(custom?.key(for: .right), ";")

        XCTAssertNil(custom?.updating(lane: .left, key: " "))
        XCTAssertNil(custom?.updating(lane: .left, key: "s"))

        let rebound = custom?.updating(lane: .left, key: "q")
        XCTAssertEqual(rebound?.key(for: .left), "q")
        XCTAssertEqual(rebound?.displayLabel(for: .left), "Q")
    }

    func testKeyBindingSetRestoresFromStorageValue() {
        let bindings = Mania4KKeyBindingSet(storageValue: "a\ts\tl\t;")

        XCTAssertEqual(bindings, Mania4KKeyBindingSet(keysByLane: [
            .left: "a",
            .innerLeft: "s",
            .innerRight: "l",
            .right: ";"
        ]))
        XCTAssertEqual(bindings?.storageValue, "a\ts\tl\t;")
        XCTAssertNil(Mania4KKeyBindingSet(storageValue: "a\ts\ta\t;"))
    }

    func testKeyboardRouterUsesCustomBindings() {
        let bindings = Mania4KKeyBindingSet(keysByLane: [
            .left: "a",
            .innerLeft: "s",
            .innerRight: "l",
            .right: ";"
        ])!
        var router = Mania4KKeyboardInputRouter(keyBindings: bindings)

        let oldDefaultPress = router.route(key: "d", isPressed: true, isRepeat: false, chartTimeMs: 90, sequenceNumber: 0)
        let customPress = router.route(key: "A", isPressed: true, isRepeat: false, chartTimeMs: 100, sequenceNumber: 1)
        let customRelease = router.route(key: "a", isPressed: false, isRepeat: false, chartTimeMs: 120, sequenceNumber: 2)

        XCTAssertNil(oldDefaultPress)
        XCTAssertEqual(customPress?.lane, .left)
        XCTAssertEqual(customPress?.phase, .press)
        XCTAssertEqual(customRelease?.lane, .left)
        XCTAssertEqual(customRelease?.phase, .release)
    }

    func testStartRequiresBeatmapAndAudioSelections() async throws {
        let model = modelWithInMemoryChart(objects: [tap(.left, 1_000)])
        let beatmapURL = URL(fileURLWithPath: "/tmp/mock.osu")
        let audioURL = URL(fileURLWithPath: "/tmp/mock.mp3")

        var started = await model.startPlay()
        XCTAssertFalse(started)
        XCTAssertNil(model.activeConfiguration)

        model.selectBeatmapFile(beatmapURL)
        started = await model.startPlay()
        XCTAssertFalse(started)
        XCTAssertNil(model.activeConfiguration)

        model.selectAudioFile(audioURL)
        started = await model.startPlay()
        XCTAssertTrue(started)
        XCTAssertEqual(model.activeConfiguration?.beatmapFileURL, beatmapURL)
        XCTAssertEqual(model.activeConfiguration?.audioFileURL, audioURL)
        XCTAssertEqual(model.phase, .playing)
    }

    func testBeatmapSelectionOnlyAcceptsOsuExtension() {
        let model = Mania4KPlaySessionModel()
        let nonBeatmapURL = URL(fileURLWithPath: "/tmp/song.mp3")
        let beatmapURL = URL(fileURLWithPath: "/tmp/chart.OSU")

        model.selectBeatmapFile(nonBeatmapURL)

        XCTAssertNil(model.beatmapFileURL)
        XCTAssertFalse(model.isReadyToStart)
        XCTAssertEqual(model.beatmapSelectionErrorMessage, "Choose a .osu beatmap file.")

        model.selectBeatmapFile(beatmapURL)

        XCTAssertEqual(model.beatmapFileURL, beatmapURL)
        XCTAssertNil(model.beatmapSelectionErrorMessage)
    }

    func testClearSetupSelectionsClearsFilesAndImportErrors() {
        let model = Mania4KPlaySessionModel()

        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/song.mp3"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/song.mp3"))
        model.clearSetupSelections()

        XCTAssertNil(model.beatmapFileURL)
        XCTAssertNil(model.audioFileURL)
        XCTAssertNil(model.beatmapSelectionErrorMessage)
        XCTAssertNil(model.audioSelectionErrorMessage)
        XCTAssertFalse(model.isReadyToStart)
        XCTAssertEqual(model.phase, .setup)
    }

    func testQuitReturnsToSetupWithoutClearingSelections() async throws {
        let model = modelWithInMemoryChart(objects: [tap(.left, 1_000)])
        let beatmapURL = URL(fileURLWithPath: "/tmp/mock.osu")
        let audioURL = URL(fileURLWithPath: "/tmp/mock.mp3")

        model.selectBeatmapFile(beatmapURL)
        model.selectAudioFile(audioURL)
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await model.quitToSetup()

        XCTAssertEqual(model.phase, .setup)
        XCTAssertNil(model.activeConfiguration)
        XCTAssertEqual(model.beatmapFileURL, beatmapURL)
        XCTAssertEqual(model.audioFileURL, audioURL)
        XCTAssertTrue(model.isReadyToStart)
    }

    func testStartPlayIgnoresStalePreparationAfterQuit() async throws {
        let stream = SuspendedPrepareMania4KHitObjectStream()
        let clock = FakeMania4KAudioClock()
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/mock.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/mock.mp3"))

        let startTask = Task {
            await model.startPlay()
        }
        await stream.waitForPrepareToStart()

        await model.quitToSetup()
        await stream.completePrepare()
        let started = await startTask.value

        XCTAssertFalse(started)
        XCTAssertEqual(model.phase, .setup)
        XCTAssertNil(model.activeConfiguration)
        let isRunning = await clock.isRunning()
        XCTAssertFalse(isRunning)
    }

    func testBeatmapStreamPreparesMetadataFromTheCoreLoaderAndReadsTokens() async throws {
        let url = try writeOsuFile(
            """
            [General]
            Mode: 3

            [Metadata]
            Artist: Test Artist

            [Difficulty]
            CircleSize: 4

            [HitObjects]
            0,192,1000,1,0,0:0:0:0:
            511,192,1400,128,0,1800:0:0:0:0:
            """
        )
        let stream = OsuMania4KBeatmapStream(beatmapFileURL: url)

        let metadata = try await stream.prepare()
        let batch = try await stream.read(after: nil, throughChartTimeMs: 5_000, limit: 512)

        XCTAssertEqual(metadata.title, url.deletingPathExtension().lastPathComponent)
        XCTAssertEqual(metadata.artist, "Test Artist")
        XCTAssertEqual(metadata.sourceDescription, url.lastPathComponent)
        XCTAssertEqual(metadata.objectCount, 2)
        XCTAssertEqual(metadata.durationMs, 1_800)
        XCTAssertEqual(batch.objects, [tap(.left, 1_000), holdStart(.right, 1_400), holdEnd(.right, 1_800)])
        XCTAssertTrue(batch.isEndOfStream)
    }

    func testBeatmapStreamReportsWhatTheCoreLoaderRejectsAsReadableErrors() async throws {
        let rejected: [(url: URL, error: Mania4KChartValidationError, message: String)] = [
            (try writeOsuFile(mode: 0), .osu(.UnsupportedMode), "Only osu!mania mode is supported."),
            (
                try writeOsuFile(circleSize: 5),
                .osu(.UnsupportedKeyCount(keys: 5)),
                "Only 4-key osu!mania charts are supported; this chart has 5 keys."
            ),
            (
                try writeOsuFile(hitObjects: "0,192,1000,1,0,0:0:0:0:\n10,192,1000,1,0,0:0:0:0:"),
                .osu(.InvalidChart(error: .SameLaneOverlap(object: 1))),
                "Object 1 overlaps the previous object in its lane."
            )
        ]

        for (url, expected, message) in rejected {
            do {
                _ = try await OsuMania4KBeatmapStream(beatmapFileURL: url).prepare()
                XCTFail("Expected \(expected)")
            } catch let error as Mania4KChartValidationError {
                XCTAssertEqual(error, expected)
                XCTAssertEqual(error.errorDescription, message)
            }
        }
    }

    func testLocalBeatmapPlaysThroughTheCore() async throws {
        let clock = FakeMania4KAudioClock()
        let model = Mania4KPlaySessionModel(
            scrollSpeed: 10, audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0, audioClock: clock
        )
        model.selectBeatmapFile(try writeOsuFile())
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()

        XCTAssertTrue(started)
        XCTAssertEqual(model.hud.title, "Test")
        XCTAssertEqual(model.playFrame?.visibleObjects.map(\.startTimeMs), [1_000])

        model.handleInput(input(.left, .press, 1_000, 0))
        await clock.setAudioTimeMs(1_000)
        await model.tick()

        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
        await model.quitToSetup()
    }

    func testStartFailsWithAReadableMessageForAnUnsupportedBeatmap() async throws {
        let model = Mania4KPlaySessionModel(audioClock: FakeMania4KAudioClock())
        model.selectBeatmapFile(try writeOsuFile(circleSize: 5))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()

        XCTAssertFalse(started)
        guard case .failed(let failure) = model.phase else {
            return XCTFail("Expected failed phase, got \(model.phase)")
        }
        XCTAssertEqual(
            failure.errorDescription,
            "Chart could not be prepared: Only 4-key osu!mania charts are supported; this chart has 5 keys."
        )
    }

    func testSessionPublishesFramesWithAudioAndVisualOffsetsAndFinishesOnGameplayTime() async throws {
        let clock = FakeMania4KAudioClock(metadata: Mania4KAudioMetadata(durationMs: 1_000, title: "Fake"))
        let model = modelWithInMemoryChart(
            objects: [tap(.left, 100)],
            clock: clock,
            audioOffset: 25,
            visualOffset: -10
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()
        XCTAssertTrue(started)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 25)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 15)

        await clock.setAudioTimeMs(100)
        let tickedAtOffset = await model.tick()
        XCTAssertTrue(tickedAtOffset)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 125)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 115)

        let handledInput = model.handleInput(input(.left, .press, 100, 0))
        XCTAssertTrue(handledInput)
        await clock.setAudioTimeMs(1_000)
        await clock.setRunning(false)
        let finalTick = await model.tick()
        XCTAssertTrue(finalTick)

        guard case .finished(let result) = model.phase else {
            return XCTFail("Expected finished phase, got \(model.phase)")
        }
        XCTAssertEqual(result.score.tierCounts.perfectCount, 1)
        XCTAssertEqual(result.finishedChartTimeMs, 1_025)
    }

    func testSessionUsesPreparedClockTimeForInitialFrame() async throws {
        let clock = PreparedTimeMania4KAudioClock(audioTimeMs: 72_000)
        let stream = InMemoryMania4KHitObjectStream(objects: [tap(.left, 72_500)])
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()

        XCTAssertTrue(started)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 72_000)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 72_000)
        XCTAssertEqual(model.playFrame?.visibleObjects.first?.startTimeMs, 72_500)
    }

    func testAudioOffsetChangesKeyboardJudgementChartTimeAndHitResult() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(
            objects: [tap(.left, 180)],
            clock: clock,
            audioOffset: 80
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await clock.setAudioTimeMs(100)
        let handledInput = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false)
        let ticked = await model.tick()

        XCTAssertTrue(handledInput)
        XCTAssertTrue(ticked)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 180)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
        XCTAssertEqual(model.playFrame?.latestJudgement?.offsetMs ?? .nan, 0, accuracy: 0.001)
        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 1)
    }

    func testKeyboardInputAfterRenderedFrameUsesGameplayTimeNotVisualRenderTime() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(
            objects: [tap(.left, 100)],
            clock: clock,
            visualOffset: 300
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await clock.setAudioTimeMs(100)
        let ticked = await model.tick()
        XCTAssertTrue(ticked)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 100)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 400)

        let handledInput = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false)
        let judged = await model.tick()

        XCTAssertTrue(handledInput)
        XCTAssertTrue(judged)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
        XCTAssertEqual(model.playFrame?.latestJudgement?.offsetMs ?? .nan, 0, accuracy: 0.001)
        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 1)
    }

    func testPositiveVisualOffsetReadsFarEnoughAheadToRenderUpcomingNotes() async throws {
        let objectTimeMs = 1_200.0
        let model = modelWithInMemoryChart(
            objects: [tap(.left, objectTimeMs)],
            visualOffset: 500
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()

        XCTAssertTrue(started)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 0)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 500)
        XCTAssertEqual(model.playFrame?.visibleObjects.first?.startTimeMs, objectTimeMs)
    }

    func testSessionRejectsInputBeyondStreamWatermark() async throws {
        let clock = FakeMania4KAudioClock()
        let stream = LaggingMania4KHitObjectStream(completeThroughChartTimeMs: 0)
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        // The press waits for coverage; the play fails once the frame's watermark (its time less the input allowance) passes it.
        await clock.setAudioTimeMs(100 + Mania4KPlaySessionModel.inputDeliveryAllowanceMs)
        let handled = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false)
        let ticked = await model.tick()

        XCTAssertTrue(handled)
        XCTAssertFalse(ticked)
        guard case .failed(let failure) = model.phase else {
            return XCTFail("Expected failed phase, got \(model.phase)")
        }
        XCTAssertEqual(failure, .streamFailed("The chart stream fell behind the judgement clock."))
    }

    func testKeyboardStateCommitsAtOnceAndReachesJudgementAtTheNextFrame() async throws {
        let clock = FakeMania4KAudioClock()
        let stream = DelayedSafeMania4KHitObjectStream()
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        XCTAssertTrue(model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false))
        XCTAssertTrue(model.handleKeyboardInput(key: "f", isPressed: true, isRepeat: false))

        XCTAssertEqual(livePressedLanes(in: model), [.left, .innerLeft])
        XCTAssertEqual(pressedLanes(in: model), [])

        await model.tick()
        XCTAssertEqual(pressedLanes(in: model), [.left, .innerLeft])

        XCTAssertTrue(model.handleKeyboardInput(key: "d", isPressed: false, isRepeat: false))
        XCTAssertEqual(livePressedLanes(in: model), [.innerLeft])
        await model.tick()
        XCTAssertEqual(pressedLanes(in: model), [.innerLeft])

        XCTAssertTrue(model.handleKeyboardInput(key: "f", isPressed: false, isRepeat: false))
        await model.tick()
        XCTAssertEqual(pressedLanes(in: model), [])
    }

    func testKeyEventsAreJudgedAtTheirTimestampsNotWhenTheFrameRuns() async throws {
        let origin = EnsomiHostClock.currentTimeMS() + 10_000
        let clock = LinearMania4KAudioClock(audioMinusHostMs: -origin)
        let model = modelWithInMemoryChart(
            objects: [tap(.left, 1_000)],
            clock: clock,
            audioOffset: -215
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        // Pressed when the offset-adjusted chart time was exactly 1000, handled 100 ms later.
        model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: origin + 1_215)
        await model.tick(atHostTimeMs: origin + 1_315)

        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
        XCTAssertEqual(model.playFrame?.latestJudgement?.offsetMs ?? .nan, 0, accuracy: 0.001)
    }

    func testInputsAreDeliveredInTimestampOrderBeforeTheFrameAdvances() async throws {
        let origin = EnsomiHostClock.currentTimeMS() + 10_000
        let clock = LinearMania4KAudioClock(audioMinusHostMs: -origin)
        let model = modelWithInMemoryChart(
            objects: [tap(.right, 900), tap(.left, 1_000)],
            clock: clock
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: origin + 1_000)
        model.handleKeyboardInput(key: "k", isPressed: true, isRepeat: false, hostTimeMs: origin + 900)
        await model.tick(atHostTimeMs: origin + 1_100)

        XCTAssertEqual(model.gameplayFeedback.judgementEventBatches().map(\.chartTimeMs), [900, 1_000])
        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 2)
    }

    func testPressAndReleaseWithinOneFrameBothReachTheEngineInOrder() async throws {
        let origin = EnsomiHostClock.currentTimeMS() + 10_000
        let clock = LinearMania4KAudioClock(audioMinusHostMs: -origin)
        let model = modelWithInMemoryChart(
            objects: [holdStart(.left, 100), holdEnd(.left, 200)],
            clock: clock
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: origin + 100)
        model.handleKeyboardInput(key: "d", isPressed: false, isRepeat: false, hostTimeMs: origin + 200)
        await model.tick(atHostTimeMs: origin + 216)

        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 1)
        XCTAssertEqual(pressedLanes(in: model), [])
    }

    func testInputStampedAfterTheFrameWaitsForTheNextFrame() async throws {
        let origin = EnsomiHostClock.currentTimeMS() + 10_000
        let clock = LinearMania4KAudioClock(audioMinusHostMs: -origin)
        let model = modelWithInMemoryChart(objects: [tap(.left, 1_000)], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: origin + 1_000)
        await model.tick(atHostTimeMs: origin + 990)
        XCTAssertNil(model.playFrame?.latestJudgement)

        await model.tick(atHostTimeMs: origin + 1_006)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
    }

    func testFramesAdvanceOnlyWhenDrivenAndDrawForThePresentationTime() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [tap(.left, 100)], clock: clock, visualOffset: -10)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await clock.setAudioTimeMs(500)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 0)

        XCTAssertTrue(model.advanceFrame(atHostTimeMs: 0, presentationHostTimeMs: 0))
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 500)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 490)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .miss)
    }

    func testRenderTimeFollowsThePresentationHostTime() async throws {
        let origin = EnsomiHostClock.currentTimeMS() + 10_000
        let clock = LinearMania4KAudioClock(audioMinusHostMs: -origin)
        let model = modelWithInMemoryChart(objects: [tap(.left, 5_000)], clock: clock, visualOffset: -10)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await model.tick(atHostTimeMs: origin + 1_000, presentationHostTimeMs: origin + 1_017)

        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs ?? .nan, 1_000, accuracy: 0.001)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs ?? .nan, 1_007, accuracy: 0.001)
    }

    func testFrameBeyondTheStreamWatermarkFailsTheSession() async throws {
        let clock = FakeMania4KAudioClock()
        let stream = LaggingMania4KHitObjectStream(completeThroughChartTimeMs: 0)
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        // Frames declare inputs delivered up to the input allowance before their time; that watermark passes the coverage.
        await clock.setAudioTimeMs(100 + Mania4KPlaySessionModel.inputDeliveryAllowanceMs)
        XCTAssertFalse(model.advanceFrame(atHostTimeMs: 0, presentationHostTimeMs: 0))
        XCTAssertFalse(model.advanceFrame(atHostTimeMs: 0, presentationHostTimeMs: 0))

        guard case .failed(let failure) = model.phase else {
            return XCTFail("Expected failed phase, got \(model.phase)")
        }
        XCTAssertEqual(failure, .streamFailed("The chart stream fell behind the judgement clock."))
    }

    /// The core rejects a page that reaches back before the coverage it declared (BeforeCoverage); the play fails
    /// as rejected objects, not as a stream failure.
    func testSessionFailsWhenStreamEmitsObjectBehindPreviousWatermark() async throws {
        let clock = FakeMania4KAudioClock()
        let stream = WatermarkViolatingMania4KHitObjectStream()
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await clock.setAudioTimeMs(2_000)
        let ticked = await model.tick()

        XCTAssertFalse(ticked)
        guard case .failed(let failure) = model.phase else {
            return XCTFail("Expected failed phase, got \(model.phase)")
        }
        XCTAssertEqual(failure, .engineRejectedObjects(.chart(.BeforeCoverage(object: 0))))
        XCTAssertEqual(
            failure.errorDescription,
            "Chart was rejected by the engine: Object 0 arrived after the stream had declared its time complete."
        )
    }

    func testConcurrentStreamReadsDoNotTriggerWatermarkFalsePositive() async throws {
        let clock = FakeMania4KAudioClock()
        let stream = DuplicateOnConcurrentCursorReadMania4KHitObjectStream()
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await clock.setAudioTimeMs(29_820)
        let firstTick = Task {
            await model.tick()
        }
        await stream.waitForObjectReadInFlight()
        let secondTick = Task {
            await model.tick()
        }

        let firstTicked = await firstTick.value
        let secondTicked = await secondTick.value

        XCTAssertTrue(firstTicked)
        XCTAssertTrue(secondTicked)
        XCTAssertEqual(model.phase, .playing)
    }

    func testSessionIgnoresGameplayInputWhilePaused() async throws {
        let model = modelWithInMemoryChart(objects: [tap(.left, 100)])
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await model.pause()
        let handled = model.handleInput(input(.left, .press, 100, 0))

        XCTAssertFalse(handled)
        XCTAssertEqual(model.phase, .paused)
        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 0)
    }

    func testSessionDoesNotTickJudgementWhilePaused() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [tap(.left, 100)], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        await model.pause()
        await clock.setAudioTimeMs(301)
        let ticked = await model.tick()

        XCTAssertFalse(ticked)
        XCTAssertEqual(model.phase, .paused)
        XCTAssertEqual(model.playFrame?.score.tierCounts.missCount, 0)
    }

    func testSessionFeedbackKeepsNoteLockMissWhileLatestJudgementIsPerfect() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [
            tap(.left, 1_000),
            tap(.left, 1_100)
        ], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        let handled = model.handleInput(input(.left, .press, 1_100, 0))
        await clock.setAudioTimeMs(1_100)
        await model.tick()

        XCTAssertTrue(handled)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)

        let presentation = model.gameplayFeedback.judgementPresentation(chartTimeMs: 1_100)
        XCTAssertEqual(presentation?.event.judgement, .miss)
        XCTAssertEqual(model.gameplayFeedback.judgementEventBatches().last?.events.map(\.judgement) ?? [], [.miss, .perfect])
    }

    func testSessionFeedbackRecordsAutoAdvancedMissWithoutInput() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [tap(.left, 100)], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        // The miss is due at 300 and decided once the frame's watermark, its time less the input allowance, passes it.
        await clock.setAudioTimeMs(301 + Mania4KPlaySessionModel.inputDeliveryAllowanceMs)
        let ticked = await model.tick()

        XCTAssertTrue(ticked)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .miss)
        let presentation = model.gameplayFeedback.judgementPresentation(
            chartTimeMs: model.playFrame?.gameplayChartTimeMs ?? 0
        )
        XCTAssertEqual(presentation?.event.judgement, .miss)
    }

    /// `GameplayFeedback` is a class and never notifies observers; SwiftUI reads `judgementPresentation`, which each
    /// frame refreshes, and the playfield pulls the feedback itself.
    func testSessionFeedbackExpiresWithoutInputAndTheFramePublishesIt() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [tap(.left, 100)], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        let handled = model.handleInput(input(.left, .press, 100, 0))
        await clock.setAudioTimeMs(100)
        await model.tick()
        XCTAssertTrue(handled)
        XCTAssertEqual(model.gameplayFeedback.judgementEventBatches().count, 1)
        XCTAssertEqual(model.judgementPresentation?.event.judgement, .perfect)

        await clock.setAudioTimeMs(150)
        let idleTicked = await model.tick()

        XCTAssertTrue(idleTicked)
        XCTAssertEqual(model.judgementPresentation?.event.judgement, .perfect)
        XCTAssertEqual(model.gameplayFeedback.judgementPresentation(chartTimeMs: 150)?.event.judgement, .perfect)

        await clock.setAudioTimeMs(380)
        let expiredTicked = await model.tick()

        XCTAssertTrue(expiredTicked)
        XCTAssertNil(model.judgementPresentation)
        XCTAssertNil(model.gameplayFeedback.judgementPresentation(chartTimeMs: 380))
        XCTAssertEqual(model.gameplayFeedback.judgementEventBatches().count, 1)

        await clock.setAudioTimeMs(700)
        _ = await model.tick()
        XCTAssertEqual(model.gameplayFeedback.judgementEventBatches().count, 1)

        await clock.setAudioTimeMs(701)
        _ = await model.tick()
        XCTAssertTrue(model.gameplayFeedback.judgementEventBatches().isEmpty)
        await model.quitToSetup()
    }

    func testSessionKeyboardRepeatAndDuplicatePressDoNotUpdateLaneInputFeedbackTransition() async throws {
        let model = modelWithInMemoryChart(objects: [tap(.right, 10_000)])
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        let pressed = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false)
        XCTAssertTrue(pressed)
        let acceptedSequenceNumber = try XCTUnwrap(
            model.gameplayFeedback.latestLaneInputTransition(lane: Mania4KLane.left.index)?.sequenceNumber
        )

        let repeated = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: true)
        let duplicated = model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false)

        XCTAssertFalse(repeated)
        XCTAssertFalse(duplicated)
        XCTAssertEqual(
            model.gameplayFeedback.latestLaneInputTransition(lane: Mania4KLane.left.index)?.sequenceNumber,
            acceptedSequenceNumber
        )
    }

    func testSessionAudioLifecycleAwaitsClockTransitions() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(objects: [tap(.left, 1_000)], clock: clock)
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))

        let started = await model.startPlay()
        XCTAssertTrue(started)
        var isRunning = await clock.isRunning()
        XCTAssertTrue(isRunning)

        await model.pause()
        XCTAssertEqual(model.phase, .paused)
        isRunning = await clock.isRunning()
        XCTAssertFalse(isRunning)

        await model.resume()
        XCTAssertEqual(model.phase, .playing)
        isRunning = await clock.isRunning()
        XCTAssertTrue(isRunning)

        await model.quitToSetup()
        XCTAssertEqual(model.phase, .setup)
        isRunning = await clock.isRunning()
        XCTAssertFalse(isRunning)
    }

    func testSessionUsesScrollSpeedForScrollTime() throws {
        let model = modelWithInMemoryChart(objects: [tap(.left, 1_000)], scrollSpeed: 10)

        XCTAssertEqual(model.scrollTimeMs, 1_148.5, accuracy: 0.001)
    }

    func testOpenEndedLongNoteRemainsVisibleAfterHeadLeavesSnapshotLowerBound() async throws {
        let clock = FakeMania4KAudioClock()
        let model = modelWithInMemoryChart(
            objects: [
                holdStart(.left, 1_000),
                holdEnd(.left, 10_000)
            ],
            clock: clock
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/chart.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/audio.mp3"))
        let started = await model.startPlay()
        XCTAssertTrue(started)
        let handledPress = model.handleInput(input(.left, .press, 1_000, 0))
        XCTAssertTrue(handledPress)

        await clock.setAudioTimeMs(2_000)
        let ticked = await model.tick()

        XCTAssertTrue(ticked)
        XCTAssertEqual(model.playFrame?.visibleObjects, [
            VisibleObject(id: 0, lane: Mania4KLane.left.index, startTimeMs: 1_000, endTimeMs: nil, state: .holding)
        ])
    }

    func testKeyboardRouterFiltersRepeatsAndDuplicatePresses() {
        var router = Mania4KKeyboardInputRouter()

        let press = router.route(key: "d", isPressed: true, isRepeat: false, chartTimeMs: 100, sequenceNumber: 0)
        let repeatPress = router.route(key: "d", isPressed: true, isRepeat: true, chartTimeMs: 101, sequenceNumber: 1)
        let duplicatePress = router.route(key: "d", isPressed: true, isRepeat: false, chartTimeMs: 102, sequenceNumber: 2)
        let release = router.route(key: "d", isPressed: false, isRepeat: false, chartTimeMs: 120, sequenceNumber: 3)

        XCTAssertEqual(press?.lane, .left)
        XCTAssertEqual(press?.phase, .press)
        XCTAssertNil(repeatPress)
        XCTAssertNil(duplicatePress)
        XCTAssertEqual(release?.phase, .release)
    }

    private func modelWithInMemoryChart(
        objects: [Mania4KHitObject],
        clock: any Mania4KAudioClock = FakeMania4KAudioClock(),
        scrollSpeed: Double = 16,
        audioOffset: Double = 0,
        visualOffset: Double = 0
    ) -> Mania4KPlaySessionModel {
        let stream = InMemoryMania4KHitObjectStream(objects: objects)
        return Mania4KPlaySessionModel(
            scrollSpeed: scrollSpeed,
            audioOffsetMilliseconds: audioOffset,
            visualOffsetMilliseconds: visualOffset,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
    }

    private func writeOsuFile(
        mode: Int = 3,
        circleSize: Int = 4,
        hitObjects: String = "0,192,1000,1,0,0:0:0:0:"
    ) throws -> URL {
        try writeOsuFile(
            """
            [General]
            Mode: \(mode)

            [Metadata]
            Title: Test

            [Difficulty]
            CircleSize: \(circleSize)

            [HitObjects]
            \(hitObjects)
            """
        )
    }

    private func writeOsuFile(_ contents: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("osu")
        try contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}

private func tap(_ lane: Mania4KLane, _ timeMs: Double) -> Mania4KHitObject {
    Mania4KHitObject(lane: lane, timeMs: timeMs, kind: .tap)
}

private func holdStart(_ lane: Mania4KLane, _ timeMs: Double) -> Mania4KHitObject {
    Mania4KHitObject(lane: lane, timeMs: timeMs, kind: .holdStart)
}

private func holdEnd(_ lane: Mania4KLane, _ timeMs: Double) -> Mania4KHitObject {
    Mania4KHitObject(lane: lane, timeMs: timeMs, kind: .holdEnd)
}

private func input(
    _ lane: Mania4KLane,
    _ phase: Phase,
    _ chartTimeMs: Double,
    _ sequenceNumber: UInt64
) -> Mania4KInputEvent {
    Mania4KInputEvent(
        lane: lane,
        phase: phase,
        chartTimeMs: chartTimeMs,
        sequenceNumber: sequenceNumber,
        source: .test
    )
}

@MainActor
private func pressedLanes(in model: Mania4KPlaySessionModel) -> [Mania4KLane] {
    model.playFrame?.laneStates.filter(\.isPressed).compactMap { Mania4KLane(rawValue: Int($0.lane)) } ?? []
}

@MainActor
private func livePressedLanes(in model: Mania4KPlaySessionModel) -> [Mania4KLane] {
    model.liveInputLaneStates.filter(\.isPressed).compactMap { Mania4KLane(rawValue: Int($0.lane)) }
}

private actor LaggingMania4KHitObjectStream: Mania4KHitObjectStreaming {
    private let completeThroughChartTimeMs: Double

    init(completeThroughChartTimeMs: Double) {
        self.completeThroughChartTimeMs = completeThroughChartTimeMs
    }

    func prepare() async throws -> Mania4KChartMetadata {
        Mania4KChartMetadata(
            title: "Lagging chart",
            sourceDescription: "Lagging test stream",
            objectCount: nil,
            durationMs: nil
        )
    }

    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        Mania4KHitObjectBatch(
            objects: [],
            nextCursor: cursor,
            completeThroughChartTimeMs: completeThroughChartTimeMs,
            isEndOfStream: false
        )
    }
}

private actor DelayedSafeMania4KHitObjectStream: Mania4KHitObjectStreaming {
    func prepare() async throws -> Mania4KChartMetadata {
        Mania4KChartMetadata(
            title: "Delayed safe chart",
            sourceDescription: "Delayed safe test stream",
            objectCount: nil,
            durationMs: nil
        )
    }

    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        try await Task.sleep(nanoseconds: 60_000_000)
        return Mania4KHitObjectBatch(
            objects: [],
            nextCursor: cursor,
            completeThroughChartTimeMs: throughChartTimeMs + 10_000,
            isEndOfStream: false
        )
    }
}

/// A clock whose audio time is the host time plus a constant, so key timestamps map to known chart times.
actor LinearMania4KAudioClock: Mania4KAudioClock {
    private let audioMinusHostMs: Double
    private var running = false

    init(audioMinusHostMs: Double) {
        self.audioMinusHostMs = audioMinusHostMs
    }

    func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        Mania4KAudioMetadata(durationMs: 10_000, title: "Linear clock")
    }

    func play() async throws {
        running = true
    }

    func pause() async {
        running = false
    }

    func stop() async {
        running = false
    }

    func currentAudioTimeMs() async -> Double {
        audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
    }

    nonisolated func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        hostTimeMs + audioMinusHostMs
    }

    func isRunning() async -> Bool {
        running
    }
}

private actor PreparedTimeMania4KAudioClock: Mania4KAudioClock {
    private let readingMs: Double
    private var running = false

    init(audioTimeMs: Double) {
        self.readingMs = audioTimeMs
    }

    func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        Mania4KAudioMetadata(durationMs: nil, title: "Prepared clock")
    }

    func play() async throws {
        running = true
    }

    func pause() async {
        running = false
    }

    func stop() async {
        running = false
    }

    func currentAudioTimeMs() async -> Double {
        readingMs
    }

    nonisolated func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        readingMs
    }

    func isRunning() async -> Bool {
        running
    }
}

private actor TrackingMania4KAudioClock: Mania4KAudioClock {
    private var prepareCalls = 0
    private var playCalls = 0
    private var running = false

    func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        prepareCalls += 1
        return Mania4KAudioMetadata(durationMs: 10_000, title: "Tracking")
    }

    func play() async throws {
        playCalls += 1
        running = true
    }

    func pause() async {
        running = false
    }

    func stop() async {
        running = false
    }

    func currentAudioTimeMs() async -> Double {
        0
    }

    nonisolated func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        0
    }

    func isRunning() async -> Bool {
        running
    }

    func prepareCallCount() -> Int {
        prepareCalls
    }

    func playCallCount() -> Int {
        playCalls
    }
}

private final class ScriptedInferenceEndpointFactory: @unchecked Sendable {
    private let endpoint: ScriptedInferenceEndpoint
    private let lock = NSLock()
    private var configurations: [InferenceEndpointConfiguration] = []

    init(endpoint: ScriptedInferenceEndpoint) {
        self.endpoint = endpoint
    }

    var recordedConfigurations: [InferenceEndpointConfiguration] {
        lock.lock()
        defer { lock.unlock() }
        return configurations
    }

    func makeClient(configuration: InferenceEndpointConfiguration) -> any InferenceEndpointClient {
        lock.lock()
        configurations.append(configuration)
        lock.unlock()
        return endpoint
    }
}

private actor ScriptedInferenceEndpoint: InferenceEndpointClient {
    struct AudioPathCall: Equatable, Sendable {
        let audioPath: String
        let sessionID: String
        let musicSource: MusicSource
    }

    struct ReferenceTimeCall: Equatable, Sendable {
        let sessionID: String
        let refTimeMS: Double
        let localHostTimeSendMS: Double
    }

    private let objects: [Mania4KHitObject]
    private let completeThroughMS: Double
    private var audioPathCallLog: [AudioPathCall] = []
    private var referenceTimeCallLog: [ReferenceTimeCall] = []
    private var queuedEvents: [InferenceEndpointEvent] = []
    private var eventWaiters: [CheckedContinuation<InferenceEndpointEvent, Never>] = []

    init(objects: [Mania4KHitObject], completeThroughMS: Double) {
        self.objects = objects
        self.completeThroughMS = completeThroughMS
    }

    func prepare() async throws {}

    func sendAudioPath(_ audioPath: String, sessionID: String, musicSource: MusicSource) async throws {
        audioPathCallLog.append(AudioPathCall(
            audioPath: audioPath,
            sessionID: sessionID,
            musicSource: musicSource
        ))
    }

    func sendReferenceTime(sessionID: String, refTimeMS: Double, localHostTimeSendMS: Double) async throws {
        referenceTimeCallLog.append(ReferenceTimeCall(
            sessionID: sessionID,
            refTimeMS: refTimeMS,
            localHostTimeSendMS: localHostTimeSendMS
        ))
        enqueue(.hitObjectToken(InferenceEndpointHitObjectToken(
            sessionID: sessionID,
            tokenID: 25,
            timeMS: objects.first?.timeMs ?? 0,
            objects: objects
        )))
        enqueue(.endOfStream(InferenceEndpointEndOfStream(
            sessionID: sessionID,
            audioLengthMS: completeThroughMS,
            completeThroughMS: completeThroughMS
        )))
    }

    func stop(sessionID: String) async throws {}

    func nextEvent() async throws -> InferenceEndpointEvent {
        if !queuedEvents.isEmpty {
            return queuedEvents.removeFirst()
        }

        return await withCheckedContinuation { continuation in
            eventWaiters.append(continuation)
        }
    }

    func audioPathCalls() -> [AudioPathCall] {
        audioPathCallLog
    }

    func referenceTimeCalls() -> [ReferenceTimeCall] {
        referenceTimeCallLog
    }

    private func enqueue(_ event: InferenceEndpointEvent) {
        if !eventWaiters.isEmpty {
            let waiter = eventWaiters.removeFirst()
            waiter.resume(returning: event)
        } else {
            queuedEvents.append(event)
        }
    }
}

private actor SuspendedPrepareMania4KHitObjectStream: Mania4KHitObjectStreaming {
    private var didStartPrepare = false
    private var prepareWaiters: [CheckedContinuation<Void, Never>] = []
    private var prepareContinuation: CheckedContinuation<Mania4KChartMetadata, Never>?

    func prepare() async throws -> Mania4KChartMetadata {
        didStartPrepare = true
        prepareWaiters.forEach { $0.resume() }
        prepareWaiters.removeAll()

        return await withCheckedContinuation { continuation in
            prepareContinuation = continuation
        }
    }

    func waitForPrepareToStart() async {
        if didStartPrepare {
            return
        }

        await withCheckedContinuation { continuation in
            prepareWaiters.append(continuation)
        }
    }

    func completePrepare() {
        prepareContinuation?.resume(
            returning: Mania4KChartMetadata(
                title: "Suspended chart",
                sourceDescription: "Suspended test stream",
                objectCount: 0,
                durationMs: 0
            )
        )
        prepareContinuation = nil
    }

    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        Mania4KHitObjectBatch(
            objects: [],
            nextCursor: nil,
            completeThroughChartTimeMs: throughChartTimeMs,
            isEndOfStream: true
        )
    }
}

private actor WatermarkViolatingMania4KHitObjectStream: Mania4KHitObjectStreaming {
    private var readCount = 0

    func prepare() async throws -> Mania4KChartMetadata {
        Mania4KChartMetadata(
            title: "Watermark violation chart",
            sourceDescription: "Watermark violation test stream",
            objectCount: nil,
            durationMs: nil
        )
    }

    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        readCount += 1
        if readCount == 1 {
            return Mania4KHitObjectBatch(
                objects: [],
                nextCursor: Mania4KHitObjectStreamCursor(rawValue: "first"),
                completeThroughChartTimeMs: 2_000,
                isEndOfStream: false
            )
        }

        return Mania4KHitObjectBatch(
            objects: [tap(.left, 1_000)],
            nextCursor: nil,
            completeThroughChartTimeMs: throughChartTimeMs,
            isEndOfStream: true
        )
    }
}

private actor DuplicateOnConcurrentCursorReadMania4KHitObjectStream: Mania4KHitObjectStreaming {
    private let objectTimeMs = 29_793.0
    private var isObjectReadInFlight = false
    private var objectReadWaiters: [CheckedContinuation<Void, Never>] = []

    func prepare() async throws -> Mania4KChartMetadata {
        Mania4KChartMetadata(
            title: "Concurrent read chart",
            sourceDescription: "Concurrent read test stream",
            objectCount: 1,
            durationMs: objectTimeMs
        )
    }

    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        guard throughChartTimeMs >= objectTimeMs else {
            return Mania4KHitObjectBatch(
                objects: [],
                nextCursor: Mania4KHitObjectStreamCursor(rawValue: "0"),
                completeThroughChartTimeMs: throughChartTimeMs,
                isEndOfStream: false
            )
        }

        guard cursor?.rawValue != "1" else {
            return Mania4KHitObjectBatch(
                objects: [],
                nextCursor: cursor,
                completeThroughChartTimeMs: throughChartTimeMs,
                isEndOfStream: false
            )
        }

        if isObjectReadInFlight {
            try await Task.sleep(nanoseconds: 90_000_000)
            return objectBatch(throughChartTimeMs: throughChartTimeMs)
        }

        isObjectReadInFlight = true
        let waiters = objectReadWaiters
        objectReadWaiters.removeAll()
        waiters.forEach { $0.resume() }
        try await Task.sleep(nanoseconds: 40_000_000)
        isObjectReadInFlight = false
        return objectBatch(throughChartTimeMs: throughChartTimeMs)
    }

    func waitForObjectReadInFlight() async {
        guard !isObjectReadInFlight else {
            return
        }

        await withCheckedContinuation { continuation in
            objectReadWaiters.append(continuation)
        }
    }

    private func objectBatch(throughChartTimeMs: Double) -> Mania4KHitObjectBatch {
        Mania4KHitObjectBatch(
            objects: [tap(.left, objectTimeMs)],
            nextCursor: Mania4KHitObjectStreamCursor(rawValue: "1"),
            completeThroughChartTimeMs: throughChartTimeMs,
            isEndOfStream: false
        )
    }
}
