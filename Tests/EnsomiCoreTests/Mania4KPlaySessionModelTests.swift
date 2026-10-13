import XCTest
@testable import EnsomiCore

@MainActor
final class Mania4KPlaySessionModelTests: XCTestCase {
    func testNewSessionStartsInSetupWithDefaultSettings() {
        let model = Mania4KPlaySessionModel()

        XCTAssertEqual(model.scrollSpeed, Mania4KDefaultPlaySettings.scrollSpeed)
        XCTAssertEqual(model.audioOffsetMilliseconds, Double(Mania4KDefaultPlaySettings.audioOffsetMilliseconds))
        XCTAssertEqual(model.visualOffsetMilliseconds, Double(Mania4KDefaultPlaySettings.visualOffsetMilliseconds))
        XCTAssertEqual(model.keyBindings, .default)
        XCTAssertEqual(model.liveInputLaneStates.filter(\.isPressed), [])
        XCTAssertEqual(model.phase, .setup)
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

    /// Ambient play runs on the host-time clock from the heard position and never prepares or plays the default audio.
    /// It joins the scheduler's chart past that position, so a note before it is never judged.
    func testAmbientChartPlayJoinsPastTheHeardPositionOnTheHostClock() async throws {
        let defaultClock = TrackingMania4KAudioClock()
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: defaultClock
        )
        let scheduler = FakeChartScheduler()
        scheduler.finish(Chart(durationMs: 10_000, objects: [
            ChartObject(lane: 0, startMs: 1_000, endMs: nil),
            ChartObject(lane: 3, startMs: 8_500, endMs: nil)
        ]))
        let chart = ScheduledChart { scheduler }
        let anchorHostTimeMS = EnsomiHostClock.currentTimeMS()

        let started = await model.startAmbientChartPlay(
            audioFileURL: URL(fileURLWithPath: "/tmp/ambient.mp3"),
            referenceTimeMS: 2_000,
            anchorHostTimeMS: anchorHostTimeMS,
            title: "Ambient Test",
            chart: chart
        )

        XCTAssertTrue(started)
        XCTAssertEqual(model.phase, .playing)
        XCTAssertEqual(model.activeConfiguration?.chartSource, .generated(displayName: "Ambient Test"))
        XCTAssertGreaterThanOrEqual(model.playFrame?.gameplayChartTimeMs ?? -1, 2_000)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(chart.joinMs), 2_000)
        let prepareCallCount = await defaultClock.prepareCallCount()
        let playCallCount = await defaultClock.playCallCount()
        XCTAssertEqual(prepareCallCount, 0)
        XCTAssertEqual(playCallCount, 0)

        // Both notes are due by 11 s; only the one past the join point is judged, as a miss.
        await model.tick(atHostTimeMs: anchorHostTimeMS + 9_000)

        XCTAssertEqual(model.playFrame?.score.tierCounts.judgedObjectCount, 1)
        XCTAssertEqual(model.playFrame?.score.tierCounts.missCount, 1)
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

    func testClearSetupSelectionsClearsFilesAndImportErrors() {
        let model = Mania4KPlaySessionModel()

        model.selectAudioFile(URL(fileURLWithPath: "/tmp/song.mp3"))
        model.recordAudioImportFailure(CocoaError(.fileReadNoPermission))
        model.clearSetupSelections()

        XCTAssertNil(model.audioFileURL)
        XCTAssertNil(model.audioSelectionErrorMessage)
        XCTAssertEqual(model.phase, .setup)
    }

    func testQuitReturnsToSetupWithoutClearingTheSong() async throws {
        let (model, chart) = modelWithChart(objects: [tap(.left, 1_000)])

        let started = await start(model, chart)
        XCTAssertTrue(started)
        XCTAssertEqual(model.activeConfiguration?.audioFileURL, testAudioURL)

        await model.quitToSetup()

        XCTAssertEqual(model.phase, .setup)
        XCTAssertNil(model.activeConfiguration)
        XCTAssertEqual(model.audioFileURL, testAudioURL)
    }

    func testQuittingWhileTheChartIsMadeAbandonsTheStart() async throws {
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: FakeMania4KAudioClock()
        )
        let scheduler = FakeChartScheduler()
        let chart = ScheduledChart { scheduler }
        let startTask = Task {
            await model.startAmbientChartPlay(
                audioFileURL: URL(fileURLWithPath: "/tmp/ambient.mp3"), referenceTimeMS: 0,
                anchorHostTimeMS: EnsomiHostClock.currentTimeMS(), chart: chart
            )
        }
        while model.phase != .loading {
            await Task.yield()
        }

        await model.quitToSetup()
        scheduler.finish(Chart(durationMs: 1_000, objects: [ChartObject(lane: 0, startMs: 500, endMs: nil)]))
        let started = await startTask.value

        XCTAssertFalse(started)
        XCTAssertEqual(model.phase, .setup)
        XCTAssertNil(model.activeConfiguration)
    }

    func testSessionPublishesFramesWithAudioAndVisualOffsetsAndFinishesOnGameplayTime() async throws {
        let clock = FakeMania4KAudioClock(metadata: Mania4KAudioMetadata(durationMs: 1_000, title: "Fake"))
        let (model, chart) = modelWithChart(
            objects: [tap(.left, 100)],
            clock: clock,
            audioOffset: 25,
            visualOffset: -10
        )

        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 72_500)], clock: clock)

        let started = await start(model, chart)

        XCTAssertTrue(started)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 72_000)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 72_000)
        XCTAssertEqual(model.playFrame?.visibleObjects.first?.startTimeMs, 72_500)
    }

    func testAudioOffsetChangesKeyboardJudgementChartTimeAndHitResult() async throws {
        let clock = FakeMania4KAudioClock()
        let (model, chart) = modelWithChart(
            objects: [tap(.left, 180)],
            clock: clock,
            audioOffset: 80
        )
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(
            objects: [tap(.left, 100)],
            clock: clock,
            visualOffset: 300
        )
        let started = await start(model, chart)
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

    func testPositiveVisualOffsetDrawsUpcomingNotes() async throws {
        let objectTimeMs = 1_200.0
        let (model, chart) = modelWithChart(
            objects: [tap(.left, objectTimeMs)],
            visualOffset: 500
        )

        let started = await start(model, chart)

        XCTAssertTrue(started)
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 0)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 500)
        XCTAssertEqual(model.playFrame?.visibleObjects.first?.startTimeMs, objectTimeMs)
    }

    func testKeyboardStateCommitsAtOnceAndReachesJudgementAtTheNextFrame() async throws {
        let (model, chart) = modelWithChart(objects: [tap(.right, 10_000)])
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(
            objects: [tap(.left, 1_000)],
            clock: clock,
            audioOffset: -215
        )
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(
            objects: [tap(.right, 900), tap(.left, 1_000)],
            clock: clock
        )
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(
            objects: [hold(.left, 100, 200)],
            clock: clock
        )
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 1_000)], clock: clock)
        let started = await start(model, chart)
        XCTAssertTrue(started)

        model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: origin + 1_000)
        await model.tick(atHostTimeMs: origin + 990)
        XCTAssertNil(model.playFrame?.latestJudgement)

        await model.tick(atHostTimeMs: origin + 1_006)
        XCTAssertEqual(model.playFrame?.latestJudgement?.judgement, .perfect)
    }

    func testFramesAdvanceOnlyWhenDrivenAndDrawForThePresentationTime() async throws {
        let clock = FakeMania4KAudioClock()
        let (model, chart) = modelWithChart(objects: [tap(.left, 100)], clock: clock, visualOffset: -10)
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 5_000)], clock: clock, visualOffset: -10)
        let started = await start(model, chart)
        XCTAssertTrue(started)

        await model.tick(atHostTimeMs: origin + 1_000, presentationHostTimeMs: origin + 1_017)

        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs ?? .nan, 1_000, accuracy: 0.001)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs ?? .nan, 1_007, accuracy: 0.001)
    }

    func testSessionIgnoresGameplayInputWhilePaused() async throws {
        let (model, chart) = modelWithChart(objects: [tap(.left, 100)])
        let started = await start(model, chart)
        XCTAssertTrue(started)

        await model.pause()
        let handled = model.handleInput(input(.left, .press, 100, 0))

        XCTAssertFalse(handled)
        XCTAssertEqual(model.phase, .paused)
        XCTAssertEqual(model.playFrame?.score.tierCounts.perfectCount, 0)
    }

    func testSessionDoesNotTickJudgementWhilePaused() async throws {
        let clock = FakeMania4KAudioClock()
        let (model, chart) = modelWithChart(objects: [tap(.left, 100)], clock: clock)
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [
            tap(.left, 1_000),
            tap(.left, 1_100)
        ], clock: clock)
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 100)], clock: clock)
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 100)], clock: clock)
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.right, 10_000)])
        let started = await start(model, chart)
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
        let (model, chart) = modelWithChart(objects: [tap(.left, 1_000)], clock: clock)

        let started = await start(model, chart)
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
        let (model, _) = modelWithChart(objects: [tap(.left, 1_000)], scrollSpeed: 10)

        XCTAssertEqual(model.scrollTimeMs, 1_148.5, accuracy: 0.001)
    }

    func testHeldLongNoteRemainsVisibleAfterHeadLeavesSnapshotLowerBound() async throws {
        let clock = FakeMania4KAudioClock()
        let (model, chart) = modelWithChart(
            objects: [hold(.left, 1_000, 10_000)],
            clock: clock
        )
        let started = await start(model, chart)
        XCTAssertTrue(started)
        let handledPress = model.handleInput(input(.left, .press, 1_000, 0))
        XCTAssertTrue(handledPress)

        await clock.setAudioTimeMs(2_000)
        let ticked = await model.tick()

        XCTAssertTrue(ticked)
        XCTAssertEqual(model.playFrame?.visibleObjects, [
            VisibleObject(id: 0, lane: Mania4KLane.left.index, startTimeMs: 1_000, endTimeMs: 10_000, state: .holding)
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

    private func modelWithChart(
        objects: [ChartObject],
        clock: any Mania4KAudioClock = FakeMania4KAudioClock(),
        scrollSpeed: Double = 16,
        audioOffset: Double = 0,
        visualOffset: Double = 0
    ) -> (Mania4KPlaySessionModel, Mania4KGeneratedChart) {
        let model = Mania4KPlaySessionModel(
            scrollSpeed: scrollSpeed,
            audioOffsetMilliseconds: audioOffset,
            visualOffsetMilliseconds: visualOffset,
            audioClock: clock
        )
        // The chart lasts until its last object ends.
        let durationMs = objects.map { $0.endMs ?? $0.startMs }.max() ?? 0
        return (model, try! Mania4KGeneratedChart(Chart(durationMs: durationMs, objects: objects)))
    }

    private var testAudioURL: URL {
        URL(fileURLWithPath: "/tmp/audio.mp3")
    }

    /// Plays `chart` with a stand-in audio file, as a generated chart is played.
    private func start(_ model: Mania4KPlaySessionModel, _ chart: Mania4KGeneratedChart) async -> Bool {
        await model.startGeneratedChartPlay(audioFileURL: testAudioURL, chart: chart, title: "Test chart")
    }
}

private func tap(_ lane: Mania4KLane, _ timeMs: Double) -> ChartObject {
    ChartObject(lane: lane.index, startMs: UInt32(timeMs), endMs: nil)
}

private func hold(_ lane: Mania4KLane, _ startMs: Double, _ endMs: Double) -> ChartObject {
    ChartObject(lane: lane.index, startMs: UInt32(startMs), endMs: UInt32(endMs))
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

/// Stands in for the runtime's scheduler: its chart is made when the test says, and a play started on it joins at the
/// time asked, without the objects before it.
final class FakeChartScheduler: ChartScheduler, @unchecked Sendable {
    let request: GenerateChartRequest
    private let lock = NSLock()
    private let made = DispatchSemaphore(value: 0)
    private var chart: Chart?
    private var stopped = false
    private var play: (atMs: Double, session: GameplaySession)?

    init(request: GenerateChartRequest = GenerateChartRequest(star: 3)) {
        self.request = request
        super.init(noHandle: NoHandle())
    }

    required init(unsafeFromHandle handle: UInt64) {
        fatalError("A fake scheduler has no handle.")
    }

    /// Makes `chart`: a `work()` waiting for it goes on.
    func finish(_ chart: Chart) {
        lock.withLock { self.chart = chart }
        made.signal()
    }

    override func work() throws -> SchedulerAction {
        made.wait()
        made.signal()
        let (chart, play) = lock.withLock {
            defer { self.play = nil }
            return (stopped ? nil : self.chart, self.play)
        }
        guard let chart, let play else { return .done }
        _ = try play.session.append(
            objects: chart.objects.filter { Double($0.startMs) >= play.atMs },
            coverageMs: Double(chart.durationMs), endOfStream: true
        )
        return .ready(joinMs: play.atMs)
    }

    override func start(atMs: Double, session: GameplaySession) {
        lock.withLock { play = (atMs, session) }
    }

    override func heard(chartMs: Double, wallMs: Double) {}

    override func stop() {
        lock.withLock { stopped = true }
        made.signal()
    }

    override func result() -> ChartResult? {
        lock.withLock {
            chart.map {
                ChartResult(chart: $0, osu: "", receipt: GenerationReceipt(
                    modelName: "Fake", modelVersion: "0", seed: request.seed, star: request.star, receiptJson: "{}"))
            }
        }
    }

    override func prepared() -> Prepared? {
        nil
    }
}
