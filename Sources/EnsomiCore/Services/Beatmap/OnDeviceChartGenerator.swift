import AVFoundation
import EnsomiProtocol
import EnsomiRuntime
import Foundation

/// A song as the generator takes it: mono Float PCM at the file's own sample rate.
public struct DecodedAudio: Sendable {
    public let samples: [Float]
    public let sampleRate: Double
}

public enum OnDeviceAudioDecoder {
    /// Decodes the whole file with `AVAudioFile` at its own sample rate and averages the channels in Double.
    public static func decode(_ url: URL) throws -> DecodedAudio {
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let channelCount = Int(format.channelCount)
        let chunkFrames: AVAudioFrameCount = 65_536
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkFrames) else {
            throw CocoaError(.fileReadUnknown)
        }
        var samples: [Float] = []
        samples.reserveCapacity(Int(file.length))
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
        return DecodedAudio(samples: samples, sampleRate: format.sampleRate)
    }
}

/// The Rust core's chart generator, opened once on a model directory. Decoding and generation run one song at a time.
public actor OnDeviceChartGenerator {
    /// The model's capabilities in the protocol type the service answers with.
    public nonisolated let capabilities: ChartGenerationCapabilities
    private let generator: ChartGenerator

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

    /// `progress` hears each phase on a thread of the core's. Cancelling the task throws `GeneratorError.Cancelled`.
    public func generate(
        audioURL: URL,
        star: Double,
        seed: UInt64,
        controls: ChartGenerationControls = .init(),
        title: String? = nil,
        artist: String? = nil,
        progress: @escaping @Sendable (ChartGenerationPhase) -> Void = { _ in }
    ) async throws -> ChartResult {
        let accessed = audioURL.startAccessingSecurityScopedResource()
        defer { if accessed { audioURL.stopAccessingSecurityScopedResource() } }
        let audio = try OnDeviceAudioDecoder.decode(audioURL)
        let request = GenerateChartRequest(
            star: star, seed: seed, controls: ChartControls(controls),
            title: title, artist: artist, audioName: audioURL.lastPathComponent
        )
        return try await generator.generate(pcm: audio.samples, sampleRate: audio.sampleRate, request: request) {
            progress(ChartGenerationPhase($0))
        }
    }

    /// The stars a person can choose with this model.
    public nonisolated var selectableStars: ClosedRange<Double> {
        capabilities.selectableStars
    }

    /// The whole chart of `audioURL` as play takes it. Cancelling the task throws `GeneratorError.Cancelled`.
    public func chart(audioURL: URL, star: Double, seed: UInt64) async throws -> Mania4KGeneratedChart {
        try Mania4KGeneratedChart(await generate(audioURL: audioURL, star: star, seed: seed).chart)
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

extension ChartGenerationPhase {
    init(_ source: GenerationPhase) {
        switch source {
        case .loading: self = .loading
        case .timing: self = .timing
        case .rows: self = .rows
        case .arrangement: self = .arrangement
        case .export: self = .export
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
