import XCTest
@testable import EnsomiCore

/// The music volume and each output device's audio offset, as the play model keeps and stores them.
@MainActor
final class Mania4KAudioSettingsTests: XCTestCase {
    private let speakers = Mania4KAudioOutputDevice(id: "BuiltInSpeakerDevice", name: "Speakers")
    private let headphones = Mania4KAudioOutputDevice(id: "00-00-00-00-00-01:output", name: "Headphones")
    private let defaultAudioOffset = Double(Mania4KDefaultPlaySettings.audioOffsetMilliseconds)

    func testEachOutputDeviceKeepsItsAudioOffsetAndANewOneStartsFromTheDefault() throws {
        let settings = try isolatedSettings()
        let model = Mania4KPlaySessionModel(settings: settings)
        model.outputDeviceChanged(to: speakers)
        XCTAssertEqual(model.audioOffsetMilliseconds, defaultAudioOffset)
        XCTAssertTrue(model.setAudioOffset(-30))
        XCTAssertTrue(model.setVisualOffset(-24))

        model.outputDeviceChanged(to: headphones)
        XCTAssertEqual(model.outputDevice, headphones)
        XCTAssertEqual(model.audioOffsetMilliseconds, defaultAudioOffset)
        XCTAssertTrue(model.setAudioOffset(-50))

        model.outputDeviceChanged(to: speakers)
        XCTAssertEqual(model.audioOffsetMilliseconds, -30)
        XCTAssertEqual(model.visualOffsetMilliseconds, -24)
        XCTAssertEqual(model.offsetState.activePresetID, model.offsetState.preset(for: speakers)?.id)
        model.outputDeviceChanged(to: headphones)
        XCTAssertEqual(model.audioOffsetMilliseconds, -50)
        XCTAssertEqual(model.visualOffsetMilliseconds, -24)
        XCTAssertEqual(model.offsetState.preset(for: headphones)?.name, headphones.name)

        // A calibration opened on the speakers saves their offset; the headphones' stays applied.
        var calibrated = model.offsetState
        calibrated.setAudioOffset(-35, for: speakers)
        XCTAssertTrue(model.updateOffsetState(calibrated))
        XCTAssertEqual(model.audioOffsetMilliseconds, -50)
        XCTAssertEqual(model.offsetState.activePresetID, model.offsetState.preset(for: headphones)?.id)

        // The next launch reads the same offsets and volume back.
        XCTAssertTrue(model.setMusicVolume(0.5))
        let relaunched = Mania4KPlaySessionModel(settings: settings)
        relaunched.outputDeviceChanged(to: speakers)
        XCTAssertEqual(relaunched.audioOffsetMilliseconds, -35)
        XCTAssertEqual(relaunched.visualOffsetMilliseconds, -24)
        XCTAssertEqual(relaunched.musicVolume, 0.5)
    }

    func testMusicVolumeStartsAtTheDefaultAndOnlyScalesDown() throws {
        let model = Mania4KPlaySessionModel(settings: try isolatedSettings())
        XCTAssertEqual(model.musicVolume, Mania4KDefaultPlaySettings.musicVolume)

        model.setMusicVolume(1.5)
        XCTAssertEqual(model.musicVolume, 1)
        model.setMusicVolume(-0.2)
        XCTAssertEqual(model.musicVolume, 0)
    }

    func testVolumeOffsetsAndTheDeviceSwitchWaitForTheGameToEnd() async throws {
        let clock = FakeMania4KAudioClock(metadata: Mania4KAudioMetadata(durationMs: 1_000, title: "Fake"))
        let stream = InMemoryMania4KHitObjectStream(objects: [Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap)])
        let model = Mania4KPlaySessionModel(settings: try isolatedSettings(), audioClock: clock)
        model.outputDeviceChanged(to: headphones)
        model.setAudioOffset(-50)
        model.outputDeviceChanged(to: speakers)
        model.setAudioOffset(-30)
        model.setMusicVolume(0.6)

        let started = await model.startPlay(
            stream: stream, audioFileURL: URL(fileURLWithPath: "/tmp/audio.mp3"), chartSource: .generated(displayName: "Chart")
        )
        XCTAssertTrue(started)
        XCTAssertTrue(model.isGameInProgress)
        let startedVolume = await clock.musicVolume
        XCTAssertEqual(startedVolume, 0.6)

        // The headphones become the output mid-song: the game keeps what it started with.
        model.outputDeviceChanged(to: headphones)
        XCTAssertFalse(model.setMusicVolume(1))
        XCTAssertFalse(model.setAudioOffset(0))
        XCTAssertFalse(model.setVisualOffset(0))
        XCTAssertEqual(model.outputDevice, speakers)
        XCTAssertEqual(model.musicVolume, 0.6)
        await clock.setAudioTimeMs(100)
        _ = await model.tick()
        XCTAssertEqual(model.playFrame?.gameplayChartTimeMs, 70)

        // When the game ends, the headphones' offset applies and the settings can change again.
        await clock.setAudioTimeMs(1_000)
        await clock.setRunning(false)
        _ = await model.tick()
        guard case .finished = model.phase else {
            return XCTFail("Expected finished phase, got \(model.phase)")
        }
        XCTAssertEqual(model.outputDevice, headphones)
        XCTAssertEqual(model.audioOffsetMilliseconds, -50)
        XCTAssertTrue(model.setMusicVolume(1))
    }

    /// A state stored before devices had presets: a preset with both offsets, applied offsets and no active preset.
    func testOffsetsStoredBeforeDevicePresetsKeepTheirPresetsAndVisualOffset() throws {
        let settings = try isolatedSettings()
        let presetID = UUID()
        settings.set("""
            {"presets":[{"audioOffsetMilliseconds":-215,"name":"old headphones","visualOffsetMilliseconds":-15,\
            "id":"\(presetID.uuidString)"}],"appliedVisualOffsetMilliseconds":-24,"appliedAudioOffsetMilliseconds":-20}
            """, forKey: Mania4KPlaySessionModel.offsetStateKey)
        let model = Mania4KPlaySessionModel(settings: settings)
        model.outputDeviceChanged(to: speakers)

        let oldPreset = Mania4KOffsetPreset(id: presetID, name: "old headphones", audioOffsetMilliseconds: -215, visualOffsetMilliseconds: -15)
        XCTAssertEqual(model.offsetState.presets, [oldPreset])
        XCTAssertEqual(model.audioOffsetMilliseconds, defaultAudioOffset)
        XCTAssertEqual(model.visualOffsetMilliseconds, -24)

        // The old preset still loads both its offsets in the calibration, and applying saves the audio one for the device.
        let calibration = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: Int(model.audioOffsetMilliseconds),
            originalVisualOffsetMilliseconds: Int(model.visualOffsetMilliseconds),
            storedState: model.offsetState,
            outputDevice: speakers,
            audioClock: FakeMania4KAudioClock()
        )
        XCTAssertTrue(calibration.selectPreset(id: presetID))
        calibration.apply()
        XCTAssertTrue(model.updateOffsetState(calibration.storedState))
        XCTAssertEqual(model.audioOffsetMilliseconds, -215)
        XCTAssertEqual(model.visualOffsetMilliseconds, -15)
        XCTAssertEqual(model.offsetState.preset(for: speakers)?.audioOffsetMilliseconds, -215)
        XCTAssertEqual(model.offsetState.presets.first, oldPreset)
    }

    private func isolatedSettings() throws -> UserDefaults {
        try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
    }
}
