import EnsomiRuntime
import Foundation
import Observation

/// Why a chart could not be opened for inspection.
public enum Mania4KChartInspectionError: Error, Equatable, Sendable {
    /// Not one `.osz`, nor one `.osu` with one other file for its audio.
    case unsupportedSelection
    case unreadableArchive
    case archiveWithoutCharts
    /// The `.osz` has no file with the name the `.osu`'s `AudioFilename` gives, or the `.osu` gives none (empty).
    case missingAudio(String)
    /// The gameplay core refused the chart.
    case chart(Mania4KChartValidationError)
    /// A file or the audio could not be read; the system's message.
    case unreadableFile(String)
}

extension Mania4KChartInspectionError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unsupportedSelection:
            return "Choose one .osz, or one .osu file together with its audio file."
        case .unreadableArchive:
            return "The .osz could not be read as a zip archive."
        case .archiveWithoutCharts:
            return "The .osz has no .osu file."
        case .missingAudio(let name):
            return name.isEmpty ? "The .osu names no audio file." : "The .osz has no audio file named “\(name)”."
        case .chart(let error):
            return error.localizedDescription
        case .unreadableFile(let message):
            return message
        }
    }
}

/// One `.osu` of an opened `.osz`: its difficulty name and the chart the gameplay core read from it, or why the core
/// refused it.
public struct Mania4KInspectionDifficulty: Identifiable, Equatable, Sendable {
    /// The `.osu`'s name in the archive.
    public let id: String
    /// The `.osu`'s `Version`, else its file name.
    public let name: String
    public let beatmap: Result<OsuBeatmap, Mania4KChartInspectionError>
    let audioName: String?
}

/// The chart being inspected and its audio.
public struct Mania4KInspectedChart: Equatable, Sendable {
    public let title: String
    public let artist: String?
    /// The difficulty name, when it came from an `.osz`.
    public let difficulty: String?
    public let audioURL: URL
    public let objectCount: Int
    /// The song's length, or the chart's when that is longer, so every note can be reached.
    public let durationMs: Double
}

/// Draws a chart against its audio so the chart can be checked: no judgement and no score, from any position and at
/// any rate. The settings are the play model's, read when they are used, so gameplay's audio offset for the current
/// output device, visual offset, scroll speed and music volume apply here as in play, and the frames are timed the
/// same way (`Mania4KFrameTiming`) on the same kind of clock.
///
/// The gameplay core holds the chart in a session that is never advanced, so every object stays waiting and the
/// session can be asked what to draw at any chart time, earlier or later.
@MainActor
@Observable
public final class Mania4KChartInspectionModel {
    /// The playback rates on offer; a rate other than 1 also changes the pitch.
    public static let rates: [Double] = [0.5, 0.75, 1, 1.25, 1.5]
    /// How far one step moves.
    public static let stepMs = 50.0

    public let settings: Mania4KPlaySessionModel
    public private(set) var chart: Mania4KInspectedChart?
    /// The `.osu` files of the open `.osz`, readable ones first by note count. Empty for an `.osu` opened with its audio.
    public private(set) var difficulties: [Mania4KInspectionDifficulty] = []
    public private(set) var chosenDifficultyID: String?
    public private(set) var error: Mania4KChartInspectionError?
    public private(set) var isLoading = false
    public private(set) var isPlaying = false
    /// The audio time heard, in milliseconds of the song.
    public private(set) var positionMs = 0.0
    public private(set) var rate = 1.0
    public private(set) var playFrame: Mania4KPlayFrame?
    /// Never records anything: no key lights and no hit feedback are drawn.
    public let gameplayFeedback = GameplayFeedback()

    @ObservationIgnored private let audioClock: any Mania4KSeekableAudioClock
    @ObservationIgnored private var session: GameplaySession?
    @ObservationIgnored private var metadata: Mania4KChartMetadata?
    @ObservationIgnored private var archive: OszArchive?
    @ObservationIgnored private var noteTimesMs: [Double] = []
    @ObservationIgnored private var extractedAudioURL: URL?

    public init(settings: Mania4KPlaySessionModel, audioClock: any Mania4KSeekableAudioClock = AVFoundationMania4KAudioClock()) {
        self.settings = settings
        self.audioClock = audioClock
    }

    /// Opens one `.osz`, or one `.osu` with its audio file. An `.osz` with one `.osu` opens it; with several, they
    /// are listed for `choose(_:)`. Anything else, or a chart the gameplay core refuses, sets `error`.
    public func open(_ urls: [URL]) async {
        await pause()
        chart = nil
        session = nil
        metadata = nil
        playFrame = nil
        archive = nil
        difficulties = []
        chosenDifficultyID = nil
        error = nil
        isLoading = true
        defer { isLoading = false }

        let osz = urls.filter { $0.pathExtension.caseInsensitiveCompare("osz") == .orderedSame }
        let osu = urls.filter { $0.pathExtension.caseInsensitiveCompare("osu") == .orderedSame }
        do {
            if urls.count == 1, let url = osz.first {
                try await openArchive(url)
            } else if urls.count == 2, osz.isEmpty, osu.count == 1, let audioURL = urls.first(where: { $0 != osu[0] }) {
                let beatmap = try Self.beatmap(text: Self.text(of: try Self.read(osu[0]))).get()
                try await load(beatmap, difficulty: nil, title: osu[0].deletingPathExtension().lastPathComponent, audioURL: audioURL)
            } else {
                throw Mania4KChartInspectionError.unsupportedSelection
            }
        } catch {
            self.error = Self.inspectionError(error)
        }
    }

    /// The file panel could not give the files.
    public func recordOpenFailure(_ error: Error) {
        self.error = .unreadableFile(error.localizedDescription)
    }

    /// Opens one difficulty of the open `.osz` with the audio its `.osu` names. On failure the chart open before
    /// stays.
    public func choose(_ difficulty: Mania4KInspectionDifficulty) async {
        await pause()
        error = nil
        isLoading = true
        defer { isLoading = false }
        do {
            try await load(difficulty)
        } catch {
            self.error = Self.inspectionError(error)
        }
    }

    public func play() async {
        guard let chart, !isPlaying else {
            return
        }

        if positionMs >= chart.durationMs {
            await seek(toMs: 0)
        }
        isPlaying = true
        do {
            try await audioClock.play()
        } catch {
            isPlaying = false
            self.error = .unreadableFile(error.localizedDescription)
        }
    }

    /// Stops at the position being heard and holds the player there, so playback resumes from what is drawn.
    public func pause() async {
        guard isPlaying else {
            return
        }

        isPlaying = false
        await audioClock.pause()
        await seek(toMs: audioClock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()))
    }

    public func togglePlayback() async {
        if isPlaying {
            await pause()
        } else {
            await play()
        }
    }

    /// Moves to `positionMs` of the song, kept within it. Playback goes on from there if it was running.
    public func seek(toMs positionMs: Double) async {
        guard let chart else {
            return
        }

        let target = min(max(positionMs, 0), chart.durationMs)
        do {
            try audioClock.seek(toMs: target, rate: rate)
        } catch {
            self.error = .unreadableFile(error.localizedDescription)
            return
        }

        self.positionMs = target
        if isPlaying {
            do {
                try await audioClock.play()
            } catch {
                isPlaying = false
                self.error = .unreadableFile(error.localizedDescription)
            }
        }
    }

    public func step(by milliseconds: Double) async {
        await seek(toMs: positionMs + milliseconds)
    }

    /// Moves so that the start of the next note, or the previous one, is on the judgement line.
    public func stepToNote(forward: Bool) async {
        let offsetsMs = settings.audioOffsetMilliseconds + settings.visualOffsetMilliseconds
        let lineMs = positionMs + offsetsMs
        // Note times are whole milliseconds; half of one keeps the note on the line from counting as next.
        let note = forward ? noteTimesMs.first(where: { $0 > lineMs + 0.5 }) : noteTimesMs.last(where: { $0 < lineMs - 0.5 })
        if let note {
            await seek(toMs: note - offsetsMs)
        }
    }

    /// Plays at `rate` from the position being heard.
    public func setRate(_ rate: Double) async {
        let positionMs = chart == nil ? 0 : audioClock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
        self.rate = rate
        await seek(toMs: positionMs)
    }

    /// Takes the music volume from Settings.
    public func applyMusicVolume() async {
        await audioClock.setMusicVolume(settings.musicVolume)
    }

    /// One frame: the chart at the audio time heard at `hostTimeMs`, drawn for `presentationHostTimeMs`, with the
    /// offsets and scroll speed as they are now. Pauses at the end of the song.
    @discardableResult
    public func advanceFrame(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) -> Bool {
        guard let chart, let session, let metadata else {
            return false
        }

        let timing = Mania4KFrameTiming(
            clock: audioClock,
            audioOffsetMilliseconds: settings.audioOffsetMilliseconds,
            visualOffsetMilliseconds: settings.visualOffsetMilliseconds,
            atHostTimeMs: hostTimeMs,
            presentationHostTimeMs: presentationHostTimeMs
        )
        let scrollTimeMs = settings.scrollTimeMs
        playFrame = Mania4KPlayFrame(
            gameplayChartTimeMs: timing.gameplayChartTimeMs,
            renderChartTimeMs: timing.renderChartTimeMs,
            scrollTimeMs: scrollTimeMs,
            metadata: metadata,
            visibleObjects: timing.visibleObjects(of: session, scrollTimeMs: scrollTimeMs),
            score: .zero,
            laneStates: session.laneStates(),
            latestJudgement: nil
        )

        let positionMs = audioClock.audioTimeMs(atHostTimeMs: hostTimeMs)
        if positionMs != self.positionMs {
            self.positionMs = positionMs
        }
        if isPlaying, positionMs >= chart.durationMs {
            Task { await pause() }
        }
        return true
    }

    private func openArchive(_ url: URL) async throws {
        let archive = try OszArchive(data: try Self.read(url))
        let charts = archive.entries.filter { ($0.name as NSString).pathExtension.caseInsensitiveCompare("osu") == .orderedSame }
        guard !charts.isEmpty else {
            throw Mania4KChartInspectionError.archiveWithoutCharts
        }

        self.archive = archive
        difficulties = try charts.map { entry in
            let text = Self.text(of: try archive.contents(of: entry))
            return Mania4KInspectionDifficulty(
                id: entry.name,
                name: Self.osuValue("Version", in: text) ?? Self.fileTitle(entry.name),
                beatmap: Self.beatmap(text: text),
                audioName: Self.osuValue("AudioFilename", in: text)
            )
        }
        .sorted { first, second in
            switch (first.beatmap, second.beatmap) {
            case (.success(let a), .success(let b)): a.chart.objects.count < b.chart.objects.count
            case (.success, .failure): true
            case (.failure, _): false
            }
        }
        if difficulties.count == 1 {
            try await load(difficulties[0])
        }
    }

    private func load(_ difficulty: Mania4KInspectionDifficulty) async throws {
        guard let archive else {
            return
        }

        let beatmap = try difficulty.beatmap.get()
        let audioName = difficulty.audioName ?? ""
        guard let audio = archive.entry(named: audioName) else {
            throw Mania4KChartInspectionError.missingAudio(audioName)
        }

        let audioURL = try extract(archive.contents(of: audio), named: audioName)
        try await load(beatmap, difficulty: difficulty.name, title: Self.fileTitle(difficulty.id), audioURL: audioURL)
        chosenDifficultyID = difficulty.id
    }

    private func load(_ beatmap: OsuBeatmap, difficulty: String?, title: String, audioURL: URL) async throws {
        let session: GameplaySession
        do {
            session = try GameplaySession(chart: beatmap.chart, ruleset: Ruleset(judge: settings.judgeDifficulty.judge))
        } catch let error as ChartError {
            throw Mania4KChartInspectionError.chart(.chart(error))
        }

        await audioClock.setMusicVolume(settings.musicVolume)
        let audio: Mania4KAudioMetadata
        do {
            audio = try await audioClock.prepare(audioFileURL: audioURL)
            try audioClock.seek(toMs: 0, rate: rate)
        } catch {
            throw Mania4KChartInspectionError.unreadableFile(error.localizedDescription)
        }

        let chart = Mania4KInspectedChart(
            title: beatmap.title ?? title,
            artist: beatmap.artist,
            difficulty: difficulty,
            audioURL: audioURL,
            objectCount: beatmap.chart.objects.count,
            durationMs: max(audio.durationMs ?? 0, Double(beatmap.chart.durationMs))
        )
        self.session = session
        self.chart = chart
        metadata = Mania4KChartMetadata(
            title: chart.title, artist: chart.artist, sourceDescription: difficulty ?? audioURL.lastPathComponent,
            objectCount: chart.objectCount, durationMs: chart.durationMs
        )
        noteTimesMs = Array(Set(beatmap.chart.objects.map { Double($0.startMs) })).sorted()
        positionMs = 0
        let now = EnsomiHostClock.currentTimeMS()
        advanceFrame(atHostTimeMs: now, presentationHostTimeMs: now)
    }

    /// Writes an `.osz`'s audio to a temporary file for the audio engine, replacing the one written before.
    private func extract(_ data: Data, named name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ensomi-inspection-\(UUID().uuidString)")
            .appendingPathExtension((name as NSString).pathExtension)
        do {
            try data.write(to: url)
        } catch {
            throw Mania4KChartInspectionError.unreadableFile(error.localizedDescription)
        }
        if let previous = extractedAudioURL {
            try? FileManager.default.removeItem(at: previous)
        }
        extractedAudioURL = url
        return url
    }

    private static func read(_ url: URL) throws -> Data {
        do {
            return try Data(contentsOf: url)
        } catch {
            throw Mania4KChartInspectionError.unreadableFile(error.localizedDescription)
        }
    }

    private static func text(of data: Data) -> String {
        String(decoding: data, as: UTF8.self)
    }

    /// The chart the gameplay core reads from an `.osu`, or its refusal.
    private static func beatmap(text: String) -> Result<OsuBeatmap, Mania4KChartInspectionError> {
        do {
            return .success(try parseOsu(text: text))
        } catch let error as OsuError {
            return .failure(.chart(.osu(error)))
        } catch {
            return .failure(.unreadableFile(error.localizedDescription))
        }
    }

    private static func fileTitle(_ path: String) -> String {
        ((path as NSString).lastPathComponent as NSString).deletingPathExtension
    }

    /// The value of a `Key: value` line of an `.osu`, which the gameplay core does not report.
    private static func osuValue(_ key: String, in text: String) -> String? {
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: ":", maxSplits: 1)
            if parts.count == 2, parts[0].trimmingCharacters(in: .whitespaces) == key {
                let value = parts[1].trimmingCharacters(in: .whitespaces)
                return value.isEmpty ? nil : value
            }
        }
        return nil
    }

    private static func inspectionError(_ error: Error) -> Mania4KChartInspectionError {
        error as? Mania4KChartInspectionError ?? .unreadableFile(error.localizedDescription)
    }
}
