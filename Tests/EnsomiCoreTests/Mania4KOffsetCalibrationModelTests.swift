import XCTest
@testable import EnsomiCore

@MainActor
final class Mania4KOffsetCalibrationModelTests: XCTestCase {
    func testDefaultPlayPresetSeedsStoredCalibrationState() {
        let state = Mania4KOffsetCalibrationStoredState.defaultPlayState

        XCTAssertEqual(state.appliedAudioOffsetMilliseconds, Mania4KDefaultPlaySettings.audioOffsetMilliseconds)
        XCTAssertEqual(state.appliedVisualOffsetMilliseconds, Mania4KDefaultPlaySettings.visualOffsetMilliseconds)
        XCTAssertEqual(state.activePresetID, Mania4KDefaultPlaySettings.offsetPresetID)
        XCTAssertEqual(state.presets, [
            Mania4KOffsetPreset(
                id: Mania4KDefaultPlaySettings.offsetPresetID,
                name: Mania4KDefaultPlaySettings.offsetPresetName,
                audioOffsetMilliseconds: Mania4KDefaultPlaySettings.audioOffsetMilliseconds,
                visualOffsetMilliseconds: Mania4KDefaultPlaySettings.visualOffsetMilliseconds
            )
        ])
    }

    // Beat 0 is at chart time 1_000: a tick's chart time is its audio time, whatever offset the screen starts from.
    func testPairedOffsetsSeedAudioTimingAndVisualTiming() {
        let model = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 75,
            originalVisualOffsetMilliseconds: -20
        )

        XCTAssertEqual(model.appliedAudioOffsetMilliseconds, 0)
        XCTAssertEqual(model.appliedVisualOffsetMilliseconds, 0)
        XCTAssertEqual(model.noteTimeMs(forBeatIndex: 0), 1_000, accuracy: 0.001)
        XCTAssertEqual(model.gameplayChartTimeMs, 75)
        XCTAssertEqual(model.renderedChartTimeMs, 55)

        model.advanceClock(rawClockTimeMs: 1_000)

        model.stepPendingAudioOffset(by: 100)
        XCTAssertEqual(model.renderedAudioOffsetMilliseconds, 175)
        XCTAssertEqual(model.renderedVisualOffsetMilliseconds, -20)

        model.stepPendingVisualOffset(by: -10)
        XCTAssertEqual(model.pendingVisualOffsetMilliseconds, -30)
        XCTAssertEqual(model.renderedVisualOffsetMilliseconds, -20)

        model.advanceClock(rawClockTimeMs: 1_500)
        XCTAssertEqual(model.renderedVisualOffsetMilliseconds, -30)
    }

    func testVisibleObjectsUseVisualTimingForRangeAndAudioTimingForMissState() throws {
        let positiveVisualModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 0,
            originalVisualOffsetMilliseconds: 200
        )
        positiveVisualModel.advanceClock(rawClockTimeMs: 950)

        let earlyObject = try XCTUnwrap(
            positiveVisualModel.visibleObjects(travelTimeMs: 0, postLineVisibleMs: 300, lookaheadPaddingMs: 0).first
        )
        XCTAssertEqual(earlyObject.startTimeMs, 1_000)
        XCTAssertEqual(earlyObject.state, .waiting)

        let negativeVisualModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 0,
            originalVisualOffsetMilliseconds: -300
        )
        negativeVisualModel.advanceClock(rawClockTimeMs: 1_300)

        let missedObject = try XCTUnwrap(
            negativeVisualModel.visibleObjects(travelTimeMs: 0, postLineVisibleMs: 500, lookaheadPaddingMs: 0).first
        )
        XCTAssertEqual(missedObject.state, .missedButVisible)
    }

    func testRecordInputAndSuggestionUseAudioOffsetOnly() throws {
        let audioOnlyModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 20,
            originalVisualOffsetMilliseconds: 0
        )
        let visualShiftedModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 20,
            originalVisualOffsetMilliseconds: 120
        )

        // The note is at chart time 1_000, so a 120 ms hit error under the 20 ms offset is a key at audio time 1_100.
        let audioOnlySample = try XCTUnwrap(audioOnlyModel.recordInput(rawInputTimeMs: 1_100))
        let visualShiftedSample = try XCTUnwrap(visualShiftedModel.recordInput(rawInputTimeMs: 1_100))

        XCTAssertEqual(audioOnlySample.hitErrorMs, 120, accuracy: 0.001)
        XCTAssertEqual(visualShiftedSample.hitErrorMs, 120, accuracy: 0.001)
        XCTAssertEqual(audioOnlySample.sampleRenderedAudioOffsetMilliseconds, 20)
        XCTAssertEqual(visualShiftedSample.sampleRenderedAudioOffsetMilliseconds, 20)
        XCTAssertEqual(audioOnlySample.sampleSuggestedAudioOffsetMilliseconds, -100)
        XCTAssertEqual(visualShiftedSample.sampleSuggestedAudioOffsetMilliseconds, -100)
        XCTAssertEqual(visualShiftedModel.renderedChartTimeMs, 140)

        let suggestionModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 0,
            originalVisualOffsetMilliseconds: 80
        )
        let earlySample = try XCTUnwrap(suggestionModel.recordInput(rawInputTimeMs: 990))
        XCTAssertEqual(earlySample.sampleSuggestedAudioOffsetMilliseconds, 10)

        suggestionModel.stepPendingAudioOffset(by: 40)
        suggestionModel.stepPendingVisualOffset(by: -40)
        XCTAssertEqual(suggestionModel.renderedAudioOffsetMilliseconds, 40)
        XCTAssertEqual(suggestionModel.renderedVisualOffsetMilliseconds, 80)

        let lateSample = try XCTUnwrap(suggestionModel.recordInput(rawInputTimeMs: 1_515))
        let laterSample = try XCTUnwrap(suggestionModel.recordInput(rawInputTimeMs: 2_030))

        XCTAssertEqual(lateSample.hitErrorMs, 55, accuracy: 0.001)
        XCTAssertEqual(lateSample.sampleSuggestedAudioOffsetMilliseconds, -15)
        XCTAssertEqual(laterSample.sampleSuggestedAudioOffsetMilliseconds, -30)
        XCTAssertEqual(suggestionModel.suggestedAudioOffsetMilliseconds, -15)
        XCTAssertEqual(suggestionModel.suggestedAudioAdjustmentMilliseconds, -55)
        XCTAssertEqual(suggestionModel.pendingAudioOffsetMilliseconds, 40)
    }

    /// A key `d` ms after the tick suggests -d whatever offset the screen started from, so calibrating again from the
    /// result measures no error instead of drifting by another -d.
    func testSuggestionDoesNotDependOnTheStartingOffset() throws {
        let delaysMs = [30.0, 10, 50]

        for startingOffset in [-60, 0, 60] {
            let model = Mania4KOffsetCalibrationModel(
                originalAudioOffsetMilliseconds: startingOffset,
                originalVisualOffsetMilliseconds: 0
            )

            for (beatIndex, delayMs) in delaysMs.enumerated() {
                let sample = try XCTUnwrap(
                    model.recordInput(rawInputTimeMs: model.noteTimeMs(forBeatIndex: beatIndex) + delayMs)
                )
                XCTAssertEqual(sample.hitErrorMs, delayMs + Double(startingOffset), accuracy: 0.001)
                XCTAssertEqual(sample.sampleSuggestedAudioOffsetMilliseconds, -Int(delayMs))
            }
            XCTAssertEqual(model.suggestedAudioOffsetMilliseconds, -30)

            XCTAssertTrue(model.useSuggestedAudioOffset())
            XCTAssertEqual(model.renderedAudioOffsetMilliseconds, -30)
            let again = try XCTUnwrap(
                model.recordInput(rawInputTimeMs: model.noteTimeMs(forBeatIndex: 4) + 30)
            )
            XCTAssertEqual(again.hitErrorMs, 0, accuracy: 0.001)
            XCTAssertEqual(again.sampleSuggestedAudioOffsetMilliseconds, -30)
        }
    }

    /// The clock is read at the host time of the frame, of the display's presentation of it and of the key event, as
    /// play reads it.
    func testFramesAndKeysAreJudgedAtTheAudioTimeOfTheirHostTime() throws {
        // Audio time is host time minus 5 s, so only a conversion through the clock lands a key on the note.
        let model = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: -20,
            originalVisualOffsetMilliseconds: -15,
            audioClock: LinearMania4KAudioClock(audioMinusHostMs: -5_000)
        )

        model.advanceClock(atHostTimeMs: 6_500, presentationHostTimeMs: 6_517)
        XCTAssertEqual(model.rawClockTimeMs, 1_500)
        XCTAssertEqual(model.gameplayChartTimeMs, 1_480)
        XCTAssertEqual(model.renderedChartTimeMs, 1_482)

        let sample = try XCTUnwrap(model.recordInput(atHostTimeMs: 6_040))
        XCTAssertEqual(sample.rawInputTimeMs, 1_040, accuracy: 0.001)
        XCTAssertEqual(sample.beatIndex, 0)
        XCTAssertEqual(sample.hitErrorMs, 20, accuracy: 0.001)
        XCTAssertEqual(sample.sampleSuggestedAudioOffsetMilliseconds, -40)
    }

    func testAcceptedHitSamplesRemainBoundedAndResolvedStatePrunes() throws {
        let model = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 0,
            originalVisualOffsetMilliseconds: 0
        )

        for beatIndex in 0..<20 {
            let rawInputTimeMs = 1_000 + beatIndex * 500
            model.advanceClock(rawClockTimeMs: rawInputTimeMs)
            XCTAssertNotNil(model.recordInput(rawInputTimeMs: rawInputTimeMs))
        }

        XCTAssertEqual(model.hitSamples.count, 16)
        XCTAssertEqual(model.hitSamples.first?.beatIndex, 4)
        XCTAssertEqual(model.hitSamples.last?.beatIndex, 19)

        let allPastObjects = model.visibleObjects(
            travelTimeMs: 0,
            postLineVisibleMs: Double(model.renderedChartTimeMs),
            lookaheadPaddingMs: 0
        )
        let resolvedObjectCount = allPastObjects.filter { $0.state == .resolved }.count

        XCTAssertLessThanOrEqual(resolvedObjectCount, 2)
    }

    func testPresetAddSelectDetachAndDeleteBehaviorStoresBothOffsets() {
        let firstID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
        let secondID = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
        let model = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 0,
            originalVisualOffsetMilliseconds: 0
        )

        model.setPendingAudioOffsetMilliseconds(25)
        model.setPendingVisualOffsetMilliseconds(-15)
        let blankPreset = model.addPreset(name: "  ", id: firstID)
        let namedPreset = model.addPreset(
            name: "  Speakers  ",
            audioOffsetMilliseconds: 80,
            visualOffsetMilliseconds: -40,
            id: secondID
        )

        XCTAssertEqual(blankPreset.name, "preset 1")
        XCTAssertEqual(blankPreset.audioOffsetMilliseconds, 25)
        XCTAssertEqual(blankPreset.visualOffsetMilliseconds, -15)
        XCTAssertEqual(namedPreset.name, "Speakers")
        XCTAssertEqual(namedPreset.audioOffsetMilliseconds, 80)
        XCTAssertEqual(namedPreset.visualOffsetMilliseconds, -40)

        XCTAssertTrue(model.selectPreset(id: secondID))
        XCTAssertEqual(model.activePresetID, secondID)
        XCTAssertEqual(model.pendingAudioOffsetMilliseconds, 80)
        XCTAssertEqual(model.pendingVisualOffsetMilliseconds, -40)

        model.stepPendingVisualOffset(by: 1)
        XCTAssertNil(model.activePresetID)
        XCTAssertEqual(model.pendingVisualOffsetMilliseconds, -39)

        XCTAssertTrue(model.selectPreset(id: secondID))
        XCTAssertTrue(model.deletePreset(id: secondID))
        XCTAssertNil(model.activePresetID)
        XCTAssertEqual(model.presets.map(\.id), [firstID])
    }

    func testStorageUsesNewRequiredAudioAndVisualShapeWithoutOldFallback() throws {
        let presetID = UUID(uuidString: "00000000-0000-0000-0000-000000000003")!
        let storedState = Mania4KOffsetCalibrationStoredState(
            appliedAudioOffsetMilliseconds: 12,
            appliedVisualOffsetMilliseconds: -18,
            presets: [
                Mania4KOffsetPreset(
                    id: presetID,
                    name: "Headphones",
                    audioOffsetMilliseconds: 85,
                    visualOffsetMilliseconds: -45
                )
            ],
            activePresetID: presetID
        )

        let reloadedState = try XCTUnwrap(Mania4KOffsetCalibrationStoredState(storageValue: storedState.storageValue))
        XCTAssertEqual(reloadedState.appliedAudioOffsetMilliseconds, 12)
        XCTAssertEqual(reloadedState.appliedVisualOffsetMilliseconds, -18)
        XCTAssertEqual(reloadedState.presets.first?.audioOffsetMilliseconds, 85)
        XCTAssertEqual(reloadedState.presets.first?.visualOffsetMilliseconds, -45)

        let oldStorageValue = """
        {"appliedGlobalOffsetMilliseconds":12,"presets":[{"id":"\(presetID.uuidString)","name":"old","presetMs":85}],"activePresetID":"\(presetID.uuidString)"}
        """
        XCTAssertNil(Mania4KOffsetCalibrationStoredState(storageValue: oldStorageValue))
    }

    func testStoredStateNormalizationClampsBothAppliedAndPresetOffsets() {
        let presetID = UUID(uuidString: "00000000-0000-0000-0000-000000000004")!
        let storedState = Mania4KOffsetCalibrationStoredState(
            appliedAudioOffsetMilliseconds: 10_000,
            appliedVisualOffsetMilliseconds: -10_000,
            presets: [
                Mania4KOffsetPreset(
                    id: presetID,
                    name: "Bad",
                    audioOffsetMilliseconds: -10_000,
                    visualOffsetMilliseconds: 10_000
                )
            ],
            activePresetID: presetID
        )

        let normalizedState = Mania4KOffsetCalibrationModel.normalizedStoredState(
            storedState,
            fallbackAppliedAudioOffsetMilliseconds: 0,
            fallbackAppliedVisualOffsetMilliseconds: 0
        )

        XCTAssertEqual(normalizedState.appliedAudioOffsetMilliseconds, 500)
        XCTAssertEqual(normalizedState.appliedVisualOffsetMilliseconds, -500)
        XCTAssertEqual(normalizedState.presets.first?.audioOffsetMilliseconds, -500)
        XCTAssertEqual(normalizedState.presets.first?.visualOffsetMilliseconds, 500)
        XCTAssertEqual(normalizedState.activePresetID, presetID)
    }

    // The model no longer stops ticks on apply and cancel (there is no tick player); the screen stops its clock.
    func testApplyAndCancelReturnBothOffsetsAndOnlyApplyMutatesAppliedState() {
        let presetID = UUID(uuidString: "00000000-0000-0000-0000-000000000005")!
        let storedState = Mania4KOffsetCalibrationStoredState(
            appliedAudioOffsetMilliseconds: 12,
            appliedVisualOffsetMilliseconds: -8,
            presets: [
                Mania4KOffsetPreset(
                    id: presetID,
                    name: "wired",
                    audioOffsetMilliseconds: 40,
                    visualOffsetMilliseconds: -30
                )
            ],
            activePresetID: presetID
        )
        let model = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: 12,
            originalVisualOffsetMilliseconds: -8,
            storedState: storedState
        )

        XCTAssertEqual(model.activePresetID, presetID)
        XCTAssertEqual(model.pendingAudioOffsetMilliseconds, 40)
        XCTAssertEqual(model.pendingVisualOffsetMilliseconds, -30)
        model.setPendingAudioOffsetMilliseconds(44)
        XCTAssertNil(model.activePresetID)

        XCTAssertEqual(
            model.cancel(),
            Mania4KOffsetCalibrationOffsets(audioOffsetMilliseconds: 12, visualOffsetMilliseconds: -8)
        )
        XCTAssertEqual(model.appliedAudioOffsetMilliseconds, 12)
        XCTAssertEqual(model.appliedVisualOffsetMilliseconds, -8)

        XCTAssertEqual(
            model.apply(),
            Mania4KOffsetCalibrationOffsets(audioOffsetMilliseconds: 44, visualOffsetMilliseconds: -30)
        )
        XCTAssertEqual(model.appliedAudioOffsetMilliseconds, 44)
        XCTAssertEqual(model.appliedVisualOffsetMilliseconds, -30)
    }
}
