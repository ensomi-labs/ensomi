import AVFoundation
import CryptoKit
import EnsomiProtocol
import EnsomiRuntime
import Foundation

/// A song as the generator takes it: mono Float PCM at the file's own sample rate.
public struct DecodedAudio: Sendable {
    public let samples: [Float]
    public let sampleRate: Double
}

public enum OnDeviceAudioDecoder {
    /// Decodes the whole file with `AVAudioFile` at its own sample rate and averages the channels in Double. The PCM
    /// is on the release engine's time: an MP3 with a lead (`MP3Lead`) starts with that many frames of silence and
    /// keeps AVFoundation's length, so as many frames go from its end.
    public static func decode(_ url: URL) throws -> DecodedAudio {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let channelCount = Int(format.channelCount)
        let chunkFrames: AVAudioFrameCount = 65_536
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkFrames) else {
            throw CocoaError(.fileReadUnknown)
        }
        let lead = Int(MP3Lead.leadFrames(of: file))
        var samples = [Float](repeating: 0, count: lead)
        samples.reserveCapacity(lead + Int(file.length))
        while file.framePosition < file.length {
            try Task.checkCancellation()
            try file.read(into: buffer, frameCount: chunkFrames)
            guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { break }
            for frame in 0..<Int(buffer.frameLength) {
                var sum = 0.0
                for channel in 0..<channelCount { sum += Double(channels[channel][frame]) }
                samples.append(Float(sum / Double(channelCount)))
            }
        }
        samples.removeLast(lead)
        return DecodedAudio(samples: samples, sampleRate: format.sampleRate)
    }
}

/// The Rust core's chart generator, opened once on a model directory, with the audio stages of the songs it prepared.
/// Every chart it makes comes from a `ChartScheduler`.
public actor OnDeviceChartGenerator {
    /// How the app's chart schedulers commit a chart: whole, before play starts. Play while generating (`.aheadOfPlay`)
    /// waits for a streaming R2.
    public static let scheduleMode = ScheduleMode.wholeChart
    /// How many songs' audio stages are kept: they take about 50 KB per second of song.
    static let preparedSongLimit = 4

    /// The model's capabilities in the protocol type the service answers with.
    public nonisolated let capabilities: ChartGenerationCapabilities
    private let generator: ChartGenerator
    /// Songs' audio stages by the audio file's SHA-256, oldest first, in memory only. They hold for this model alone,
    /// so the key is the file's hash together with the model.
    private var preparedSongs: [(sha256: String, prepared: Prepared)] = []

    /// Reads the model and its files (a fraction of a second), so call it off the main actor. Without
    /// `resamplerDirectory`, PCM at a rate other than 22050 Hz is resampled with the tables the runtime package carries.
    public init(modelDirectory: URL, resamplerDirectory: URL? = nil) throws {
        let opened = try ChartGenerator.open(
            modelDirectory: modelDirectory.path,
            options: GeneratorOptions(provider: .cpu, resamplerDirectory: resamplerDirectory?.path)
        )
        generator = opened
        capabilities = ChartGenerationCapabilities(opened.capabilities())
    }

    /// The song's audio stages (PCM preparation, the spectrogram, BeatThis, the features and the beat grid), which take
    /// most of a generation; any star, seed and sections generate from them. A song this model prepared before, found by
    /// the file's SHA-256 (`sha256`, or read from the file), returns at once. `progress` hears `.loading` while the file
    /// decodes and `.timing` while the stages run. Cancelling the task throws and keeps nothing.
    public func prepare(
        audioURL: URL,
        sha256: String? = nil,
        progress: @escaping @Sendable (ChartGenerationPhase) -> Void = { _ in }
    ) async throws -> Prepared {
        let accessed = audioURL.startAccessingSecurityScopedResource()
        defer { if accessed { audioURL.stopAccessingSecurityScopedResource() } }
        let sha256 = try sha256 ?? SHA256.hash(data: Data(contentsOf: audioURL, options: .mappedIfSafe))
            .map { String(format: "%02x", $0) }.joined()
        if let prepared = preparedSong(sha256: sha256) { return prepared }
        progress(.loading)
        let audio = try OnDeviceAudioDecoder.decode(audioURL)
        progress(.timing)
        let prepared = try await generator.prepare(pcm: audio.samples, sampleRate: audio.sampleRate)
        try Task.checkCancellation()
        keep(prepared, sha256: sha256)
        return prepared
    }

    /// The whole chart for `request` from a song's audio stages. Cancelling the task throws `GeneratorError.Cancelled`.
    public func generate(prepared: Prepared, request: GenerateChartRequest) async throws -> ChartResult {
        // No play starts from this scheduler, so its lead is never used.
        let scheduler = try ChartScheduler.fromPrepared(
            generator: generator, prepared: prepared, request: request, mode: Self.scheduleMode,
            lead: LeadPolicy(ruleset: Ruleset(judge: .c), renderLeadMs: 0, marginMs: 0)
        )
        _ = try await scheduler.work()
        guard let result = scheduler.result() else { throw GeneratorError.Cancelled }
        return result
    }

    /// A scheduler for play along with the song at `audioURL`, whose file has the SHA-256 `sha256`: from the song's audio
    /// stages when this model holds them, else from its PCM. `keep` holds what a scheduler prepared.
    public func scheduler(audioURL: URL, sha256: String, request: GenerateChartRequest, lead: LeadPolicy) throws -> ChartScheduler {
        if let prepared = preparedSong(sha256: sha256) {
            return try ChartScheduler.fromPrepared(
                generator: generator, prepared: prepared, request: request, mode: Self.scheduleMode, lead: lead
            )
        }
        let accessed = audioURL.startAccessingSecurityScopedResource()
        defer { if accessed { audioURL.stopAccessingSecurityScopedResource() } }
        let audio = try OnDeviceAudioDecoder.decode(audioURL)
        return try ChartScheduler.fromPcm(
            generator: generator, pcm: audio.samples, sampleRate: audio.sampleRate, request: request,
            mode: Self.scheduleMode, lead: lead
        )
    }

    /// Holds a song's audio stages, made with this model, dropping the oldest beyond `preparedSongLimit`.
    public func keep(_ prepared: Prepared, sha256: String) {
        preparedSongs.removeAll { $0.sha256 == sha256 }
        preparedSongs.append((sha256, prepared))
        if preparedSongs.count > Self.preparedSongLimit {
            preparedSongs.removeFirst()
        }
    }

    func preparedSong(sha256: String) -> Prepared? {
        preparedSongs.first { $0.sha256 == sha256 }?.prepared
    }

    /// The stars a person can choose with this model.
    public nonisolated var selectableStars: ClosedRange<Double> {
        capabilities.selectableStars
    }
}

extension OnDeviceChartGenerator {
    /// The text for a failure: the core's own message for a `GeneratorError`.
    nonisolated public static func message(for error: Error) -> String {
        switch error {
        case GeneratorError.Model(let message), GeneratorError.Generation(_, let message):
            return message
        default:
            return error.localizedDescription
        }
    }
}

extension ChartGenerationCapabilities {
    init(_ source: ModelCapabilities) {
        self.init()
        modelName = source.modelName
        modelVersion = source.modelVersion
        minStar = source.minStar
        maxStarExclusive = source.maxStarExclusive
        files = source.files.map { file in .with { $0.path = file.path; $0.sha256Hex = file.sha256Hex } }
        controls = source.controls.map { control in
            .with {
                $0.name = control.name
                $0.note = control.note
                switch control.status {
                case .works: $0.status = .works
                case .experimental: $0.status = .experimental
                case .notWorking: $0.status = .notWorking
                }
            }
        }
    }
}

extension ChartControls {
    init(_ source: ChartGenerationControls) {
        let longNotes: LongNoteAmount?
        switch source.longNotes {
        case .tapOnly: longNotes = .tapOnly
        case .half: longNotes = .half
        case .natural: longNotes = .natural
        default: longNotes = nil
        }
        self.init(longNotes: longNotes, sections: source.sections.map { section in
            let target: SectionTarget
            if case .pattern(let pattern)? = section.target {
                let kind: PatternKind
                switch pattern.kind {
                case .jumpstream: kind = .jumpstream
                case .chordstream: kind = .chordstream
                case .jack: kind = .jack
                case .chordjack: kind = .chordjack
                default: kind = .stream
                }
                target = .pattern(kind: kind, strength: pattern.strength)
            } else {
                target = .star(star: section.star)
            }
            return SectionRequest(startMs: section.startMs, endMs: section.endMs, target: target)
        })
    }
}
