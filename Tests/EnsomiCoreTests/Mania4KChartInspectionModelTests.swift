import EnsomiRuntime
import XCTest
@testable import EnsomiCore

/// Opening charts for inspection, and moving through them, with the play model's settings.
@MainActor
final class Mania4KChartInspectionModelTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: directory)
    }

    func testAnOszWithSeveralDifficultiesOpensToItsListAndChoosingOneLoadsItWithItsAudio() async throws {
        let audio = Data("song bytes".utf8)
        let osz = try zip([
            ("easy.osu", Data(osu(version: "Easy", notes: 2).utf8)),
            ("seven.osu", Data(osu(version: "7K", keys: 7, notes: 3).utf8)),
            ("hard.osu", Data(osu(version: "Hard", notes: 4).utf8)),
            ("Song.MP3", audio)
        ], named: "set.osz")
        let model = Mania4KChartInspectionModel(settings: Mania4KPlaySessionModel(), audioClock: FakeMania4KAudioClock())

        await model.open([osz])

        XCTAssertNil(model.error)
        XCTAssertNil(model.chart)
        XCTAssertEqual(model.difficulties.map(\.name), ["Easy", "Hard", "7K"])
        XCTAssertEqual(model.difficulties.map(\.id), ["easy.osu", "hard.osu", "seven.osu"])
        XCTAssertEqual(model.difficulties[2].beatmap, .failure(.chart(.osu(.UnsupportedKeyCount(keys: 7)))))

        await model.choose(model.difficulties[1])

        let chart = try XCTUnwrap(model.chart)
        XCTAssertNil(model.error)
        XCTAssertEqual(model.chosenDifficultyID, "hard.osu")
        XCTAssertEqual(chart.title, "Inspection")
        XCTAssertEqual(chart.difficulty, "Hard")
        XCTAssertEqual(chart.objectCount, 4)
        // The .osu names "song.mp3"; the archive's "Song.MP3" is found as osu! finds it.
        XCTAssertEqual(try Data(contentsOf: chart.audioURL), audio)
        XCTAssertEqual(model.difficulties.count, 3)
    }

    /// The runtime's own `.osz` (a deflated `.osu` and stored audio) opens straight to its only chart.
    func testAnOszWithOneDifficultyOpensIt() async throws {
        let audio = Data((0..<4_096).map { UInt8($0 % 251) })
        let url = directory.appendingPathComponent("generated.osz")
        try exportOsz(osu: osu(version: "Generated", notes: 3), audio: audio, receiptJson: "{}").write(to: url)
        let model = Mania4KChartInspectionModel(settings: Mania4KPlaySessionModel(), audioClock: FakeMania4KAudioClock())

        await model.open([url])

        let chart = try XCTUnwrap(model.chart, "\(String(describing: model.error))")
        XCTAssertEqual(chart.difficulty, "Generated")
        XCTAssertEqual(chart.objectCount, 3)
        XCTAssertEqual(try Data(contentsOf: chart.audioURL), audio)
        XCTAssertEqual(model.chosenDifficultyID, "generated.osu")
    }

    func testAnAudioFilePairsWithAnOsuInEitherOrder() async throws {
        let osuURL = try write(osu(version: "Normal", notes: 5), named: "chart.osu")
        let audioURL = try write("audio", named: "track.ogg")
        let model = Mania4KChartInspectionModel(settings: Mania4KPlaySessionModel(), audioClock: FakeMania4KAudioClock())

        await model.open([audioURL, osuURL])

        let chart = try XCTUnwrap(model.chart)
        XCTAssertEqual(chart.audioURL, audioURL)
        XCTAssertEqual(chart.title, "Inspection")
        XCTAssertEqual(chart.artist, "Tester")
        XCTAssertNil(chart.difficulty)
        XCTAssertEqual(chart.objectCount, 5)
        XCTAssertTrue(model.difficulties.isEmpty)
        XCTAssertEqual(model.positionMs, 0)
        XCTAssertFalse(model.isPlaying)

        await model.open([osuURL, audioURL])
        XCTAssertEqual(model.chart?.audioURL, audioURL)
    }

    func testChartsTheCoreRefusesAndOtherSelectionsAreRefusedWithAMessage() async throws {
        let audioURL = try write("audio", named: "song.mp3")
        let model = Mania4KChartInspectionModel(settings: Mania4KPlaySessionModel(), audioClock: FakeMania4KAudioClock())
        let refused: [([URL], Mania4KChartInspectionError, String)] = [
            (
                [try write(osu(version: "Taiko", mode: 1), named: "taiko.osu"), audioURL],
                .chart(.osu(.UnsupportedMode)),
                "Only osu!mania mode is supported."
            ),
            (
                [try write(osu(version: "7K", keys: 7), named: "seven.osu"), audioURL],
                .chart(.osu(.UnsupportedKeyCount(keys: 7))),
                "Only 4-key osu!mania charts are supported; this chart has 7 keys."
            ),
            ([try write(osu(version: "Alone"), named: "alone.osu")], .unsupportedSelection, ""),
            ([audioURL], .unsupportedSelection, ""),
            ([try write("not a zip", named: "broken.osz")], .unreadableArchive, ""),
            ([try zip([("song.mp3", Data("audio".utf8))], named: "no-chart.osz")], .archiveWithoutCharts, ""),
            ([try zip([("only.osu", Data(osu(version: "Only").utf8))], named: "no-audio.osz")], .missingAudio("song.mp3"), "")
        ]

        for (urls, expected, message) in refused {
            await model.open(urls)
            XCTAssertNil(model.chart, "\(urls.map(\.lastPathComponent))")
            XCTAssertEqual(model.error, expected, "\(urls.map(\.lastPathComponent))")
            if !message.isEmpty {
                XCTAssertEqual(model.error?.localizedDescription, message)
            }
        }
    }

    func testSeekPauseStepsAndRateMoveThePosition() async throws {
        let clock = FakeMania4KAudioClock(metadata: Mania4KAudioMetadata(durationMs: 10_000, title: "Fake"))
        let settings = Mania4KPlaySessionModel(audioOffsetMilliseconds: 20, visualOffsetMilliseconds: -5)
        let model = Mania4KChartInspectionModel(settings: settings, audioClock: clock)
        await model.open([try write(osu(version: "Normal", notes: 4), named: "chart.osu"), try write("audio", named: "song.mp3")])
        XCTAssertEqual(model.chart?.durationMs, 10_000)

        await model.seek(toMs: 2_500)
        XCTAssertEqual(model.positionMs, 2_500)
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: 0), 2_500)
        await model.seek(toMs: -40)
        XCTAssertEqual(model.positionMs, 0)
        await model.seek(toMs: 60_000)
        XCTAssertEqual(model.positionMs, 10_000)

        // Playing from the end starts again from the beginning.
        await model.play()
        XCTAssertTrue(model.isPlaying)
        XCTAssertEqual(model.positionMs, 0)
        var running = await clock.isRunning()
        XCTAssertTrue(running)
        await clock.setAudioTimeMs(1_234)
        await model.pause()
        XCTAssertFalse(model.isPlaying)
        XCTAssertEqual(model.positionMs, 1_234)
        running = await clock.isRunning()
        XCTAssertFalse(running)

        await model.step(by: Mania4KChartInspectionModel.stepMs)
        XCTAssertEqual(model.positionMs, 1_234 + Mania4KChartInspectionModel.stepMs)
        await model.step(by: -Mania4KChartInspectionModel.stepMs)
        XCTAssertEqual(model.positionMs, 1_234)

        await model.setRate(0.75)
        XCTAssertEqual(model.rate, 0.75)
        XCTAssertEqual(clock.rate, 0.75)
        XCTAssertEqual(model.positionMs, 1_234)
        XCTAssertFalse(model.isPlaying)

        // Notes start at 1000, 1250, 1500 and 1750 ms; a step by note puts the next one on the judgement line, where
        // the audio time plus both offsets is the note's time.
        await model.stepToNote(forward: true)
        XCTAssertEqual(model.positionMs, 1_250 - 15)
        await model.stepToNote(forward: true)
        XCTAssertEqual(model.positionMs, 1_500 - 15)
        await model.stepToNote(forward: false)
        await model.stepToNote(forward: false)
        XCTAssertEqual(model.positionMs, 1_000 - 15)
        await model.stepToNote(forward: false)
        XCTAssertEqual(model.positionMs, 1_000 - 15)

        model.advanceFrame(atHostTimeMs: 0, presentationHostTimeMs: 0)
        XCTAssertEqual(model.playFrame?.renderChartTimeMs, 1_000)
        XCTAssertEqual(model.playFrame?.visibleObjects.first?.startTimeMs, 1_000)
    }

    /// The inspector times a frame as the play model does: the same clock reading, the audio offset of the current
    /// output device, the visual offset and the scroll speed give the same chart times.
    func testFramesApplyTheOffsetsAndScrollSpeedAsGameplayDoes() async throws {
        let speakers = Mania4KAudioOutputDevice(id: "BuiltInSpeakerDevice", name: "Speakers")
        let playClock = FakeMania4KAudioClock()
        let settings = Mania4KPlaySessionModel(
            scrollSpeed: 12,
            settings: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)),
            audioClock: playClock
        )
        settings.outputDeviceChanged(to: speakers)
        XCTAssertTrue(settings.setAudioOffset(37))
        XCTAssertTrue(settings.setVisualOffset(-11))
        let model = Mania4KChartInspectionModel(settings: settings, audioClock: FakeMania4KAudioClock())
        await model.open([try write(osu(version: "Normal", notes: 4), named: "chart.osu"), try write("audio", named: "song.mp3")])
        await model.seek(toMs: 900)
        model.advanceFrame(atHostTimeMs: 0, presentationHostTimeMs: 0)
        let inspected = try XCTUnwrap(model.playFrame)

        // The same chart played: taps in lane 0 every 250 ms from 1000 ms, with the clock at the same reading.
        let objects = (0..<4).map { Mania4KHitObject(lane: .left, timeMs: 1_000 + 250 * Double($0), kind: .tap) }
        let started = await settings.startPlay(
            stream: InMemoryMania4KHitObjectStream(objects: objects),
            audioFileURL: URL(fileURLWithPath: "/tmp/song.mp3"),
            chartSource: .generated(displayName: "Same chart")
        )
        XCTAssertTrue(started)
        await playClock.setAudioTimeMs(900)
        // A tick reads the stream through its frame first, as the display link's background reads do.
        let ticked = await settings.tick(atHostTimeMs: 0, presentationHostTimeMs: 0)
        XCTAssertTrue(ticked)
        let played = try XCTUnwrap(settings.playFrame)

        XCTAssertEqual(inspected.gameplayChartTimeMs, played.gameplayChartTimeMs)
        XCTAssertEqual(inspected.renderChartTimeMs, played.renderChartTimeMs)
        XCTAssertEqual(inspected.renderChartTimeMs, 900 + 37 - 11)
        XCTAssertEqual(inspected.scrollTimeMs, played.scrollTimeMs)
        XCTAssertEqual(inspected.visibleObjects.map(\.startTimeMs), played.visibleObjects.map(\.startTimeMs))
        await settings.quitToSetup()
    }

    /// An `.osu` of `notes` taps in lane 0, 250 ms apart from 1000 ms, naming `song.mp3` as its audio.
    private func osu(version: String, mode: Int = 3, keys: Int = 4, notes: Int = 1) -> String {
        let objects = (0..<notes).map { "64,192,\(1_000 + 250 * $0),1,0,0:0:0:0:" }.joined(separator: "\n")
        return """
        osu file format v14

        [General]
        AudioFilename: song.mp3
        Mode: \(mode)

        [Metadata]
        Title:Inspection
        Artist:Tester
        Version:\(version)

        [Difficulty]
        CircleSize:\(keys)

        [HitObjects]
        \(objects)
        """
    }

    private func write(_ text: String, named name: String) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try Data(text.utf8).write(to: url)
        return url
    }

    /// A zip made by the system's `zip`, as osu! makes an `.osz`.
    private func zip(_ files: [(name: String, data: Data)], named name: String) throws -> URL {
        let folder = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for file in files {
            try file.data.write(to: folder.appendingPathComponent(file.name))
        }
        let url = directory.appendingPathComponent(name)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        process.currentDirectoryURL = folder
        process.arguments = ["-q", url.path] + files.map(\.name)
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
        return url
    }
}
