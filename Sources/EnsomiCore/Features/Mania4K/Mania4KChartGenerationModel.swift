import AVFoundation
import EnsomiProtocol
import EnsomiRuntime
import Foundation
import Observation

public struct ChartGenerationSection: Identifiable, Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case jumpstream, chordstream, stream, jack, chordjack
        case star = "section_star"

        public var title: String {
            self == .star ? "Star target" : rawValue.capitalized
        }

        private var pattern: Ensomi_Protocol_V1_PatternKind {
            switch self {
            case .jumpstream: return .jumpstream
            case .chordstream: return .chordstream
            case .stream: return .stream
            case .jack: return .jack
            case .chordjack: return .chordjack
            case .star: return .unspecified
            }
        }

        fileprivate func target(strength: Double, star: Double) -> Ensomi_Protocol_V1_SectionRequest.OneOf_Target {
            if self == .star { return .star(star) }
            return .pattern(.with {
                $0.kind = pattern
                $0.strength = strength
            })
        }
    }

    public let id = UUID()
    public var start = "00:00"
    public var end = "00:08"
    public var kind: Kind
    public var strength = 0.5
    public var star: Double

    public init(kind: Kind, star: Double) {
        self.kind = kind
        self.star = star
    }

    public static func timeLabel(milliseconds: Double) -> String {
        let seconds = Int(milliseconds / 1_000)
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private static func milliseconds(_ text: String) -> UInt32? {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[1].count == 2,
              parts.allSatisfy({ $0.allSatisfy { $0 >= "0" && $0 <= "9" } }),
              let minutes = UInt64(parts[0]), let seconds = UInt64(parts[1]), seconds < 60,
              minutes <= UInt64(UInt32.max) / 60_000
        else { return nil }
        return UInt32(exactly: minutes * 60_000 + seconds * 1_000)
    }

    static func controls(
        for sections: [Self], durationMS: Double, starRange: ClosedRange<Double>
    ) throws -> ChartGenerationControls {
        var controls = ChartGenerationControls()
        for (index, section) in sections.enumerated() {
            let name = "Section \(index + 1)"
            guard let start = milliseconds(section.start), let end = milliseconds(section.end) else {
                throw ValidationError(message: "\(name): enter start and end as mm:ss, with seconds from 00 to 59.")
            }
            guard start < end else {
                throw ValidationError(message: "\(name): end must be after start.")
            }
            guard Double(end) <= durationMS else {
                throw ValidationError(message: "\(name): keep the span inside the song.")
            }
            guard (8_000...32_000).contains(end - start) else {
                throw ValidationError(message: "\(name): a section must last 8–32 seconds.")
            }
            if section.kind == .star {
                guard starRange.contains(section.star) else {
                    throw ValidationError(message: "\(name): use the song's star range (\(starRange.lowerBound)–\(starRange.upperBound)).")
                }
            } else {
                guard (0...1).contains(section.strength) else {
                    throw ValidationError(message: "\(name): strength must be from 0 to 1.")
                }
            }
            controls.sections.append(.with {
                $0.startMs = start
                $0.endMs = end
                $0.target = section.kind.target(strength: section.strength, star: section.star)
            })
        }
        let sorted = controls.sections.sorted { $0.startMs < $1.startMs }
        for (previous, next) in zip(sorted, sorted.dropFirst()) where previous.endMs > next.startMs {
            throw ValidationError(message: "Sections must not overlap. One may start where another ends.")
        }
        return controls
    }

    struct ValidationError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
}

public struct ChartGenerationSectionOption: Identifiable, Equatable, Sendable {
    public let kind: ChartGenerationSection.Kind
    public let isExperimental: Bool
    public let note: String
    public var id: ChartGenerationSection.Kind { kind }
    public var title: String { kind.title + (isExperimental ? " (Experimental)" : "") }
}

/// A chart "Generate a Chart" made, with what reproduces it and what exports it.
public struct Mania4KChartGenerationResult: Equatable, Sendable {
    public let chart: Mania4KGeneratedChart
    public let audioURL: URL
    public let engine: ChartGenerationEngine
    /// The model's name and version, such as "Ensomi R2 r2-1.0".
    public let model: String
    public let star: Double
    /// The seed the engine used: the same song, model, star, sections and seed give the same chart.
    public let seed: UInt64
    public let title: String
    public let artist: String?
    /// The chart as an osu!mania beatmap. The chart server returns none.
    public let osu: String?
    public let receiptJSON: String
    /// The `.osz` the chart server saved on its own machine, when it did.
    public let serverOszPath: String?

    /// The name the web app gives the chart's files, without extension: "Artist - Title (ensomi R2 s<seed>)".
    public var exportName: String {
        let name = "\(artist.map { "\($0) - " } ?? "")\(title) (ensomi R2 s\(seed))"
        let kept = String(name.unicodeScalars.filter { $0.value >= 0x20 && !"\\/:*?\"<>|".unicodeScalars.contains($0) })
        return String(kept.trimmingCharacters(in: .whitespaces).prefix(120))
    }

    /// The chart's `.osz`, made by the Rust core from the `.osu`, the song file and the receipt. Nil without a `.osu`.
    public func osz() throws -> Data? {
        guard let osu else { return nil }
        let accessed = audioURL.startAccessingSecurityScopedResource()
        defer { if accessed { audioURL.stopAccessingSecurityScopedResource() } }
        return exportOsz(osu: osu, audio: try Data(contentsOf: audioURL), receiptJson: receiptJSON)
    }
}

/// Where "Generate a Chart" makes the chart: in the app with the Rust core, or at the chart service.
public enum ChartGenerationEngine: String, CaseIterable, Identifiable, Sendable {
    case device, server

    public var id: String { rawValue }

    public var title: String {
        self == .device ? "On this device" : "Chart server"
    }
}

/// Setup-screen state of "Generate a Chart": what the engine offers and the progress of one generation.
@MainActor
@Observable
public final class Mania4KChartGenerationModel {
    public enum State: Equatable {
        case idle
        /// `phase` is nil until the engine reports one, while the app connects to the chart server.
        case generating(phase: ChartGenerationPhase?)
        case finished(Mania4KChartGenerationResult)
        /// The person stopped the generation.
        case stopped
        case failed(String)
    }

    /// The phases every generation goes through, in order.
    public static let phases: [ChartGenerationPhase] = [.loading, .timing, .rows, .arrangement, .export]
    /// What the screen calls each of `phases`.
    public static let phaseTitles = phases.map(title(of:))

    /// Set only by the model; tests set it to render a state.
    public internal(set) var state = State.idle
    public private(set) var modelDescription: String?
    /// Selectable stars in 0.1 steps; nil until the service has answered.
    public private(set) var starRange: ClosedRange<Double>?
    public private(set) var capabilitiesError: String?
    public private(set) var sectionOptions: [ChartGenerationSectionOption] = []
    public var sections: [ChartGenerationSection] = []
    /// Blank for a new chart each time, or the seed of a chart to make again.
    public var seedText = ""
    public private(set) var audioDurationMS: Double?
    public private(set) var audioDurationError: String?

    /// The engine the person chose; nil until they do, when the device is used if it has a model.
    private var chosenEngine: ChartGenerationEngine?

    public let models: ChartModelLibrary
    @ObservationIgnored private let client: ChartGenerationClient
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var durationAudioURL: URL?
    @ObservationIgnored private var deviceTask: Task<ChartResult, Error>?

    private static let engineKey = "mania4k.chartEngine"

    public init(
        client: ChartGenerationClient = ChartGenerationClient(),
        models: ChartModelLibrary = .shared,
        defaults: UserDefaults = .standard
    ) {
        self.client = client
        self.models = models
        self.defaults = defaults
        chosenEngine = defaults.string(forKey: Self.engineKey).flatMap(ChartGenerationEngine.init(rawValue:))
    }

    public var engine: ChartGenerationEngine {
        chosenEngine ?? (models.hasModel ? .device : .server)
    }

    public func choose(engine: ChartGenerationEngine) {
        chosenEngine = engine
        defaults.set(engine.rawValue, forKey: Self.engineKey)
    }

    public var isGenerating: Bool {
        if case .generating = state { return true }
        return false
    }

    /// During a generation, the index in `phases` of the phase under way; -1 while connecting to the chart server.
    public var phaseIndex: Int? {
        guard case .generating(let phase) = state else { return nil }
        return phase.flatMap { Self.phases.firstIndex(of: $0) } ?? -1
    }

    public var result: Mania4KChartGenerationResult? {
        if case .finished(let result) = state { return result }
        return nil
    }

    /// The seed in the seed field; nil when it is blank or not a seed.
    public var seed: UInt64? {
        UInt64(seedText.trimmingCharacters(in: .whitespaces))
    }

    public var seedValidationMessage: String? {
        let text = seedText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, UInt64(text) == nil else { return nil }
        return "The seed must be a whole number from 0 to \(UInt64.max), or blank for a new chart."
    }

    public var sectionValidationMessage: String? {
        guard let audioDurationMS, let starRange else { return nil }
        if sections.contains(where: { section in !sectionOptions.contains { $0.kind == section.kind } }) {
            return "Choose a supported kind for each section."
        }
        do {
            _ = try ChartGenerationSection.controls(for: sections, durationMS: audioDurationMS, starRange: starRange)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    public func canGenerate(star: Double) -> Bool {
        !isGenerating && audioDurationMS != nil && starRange?.contains(star) == true && sectionValidationMessage == nil
            && seedValidationMessage == nil
    }

    /// Reads the length of a newly chosen song and clears the last result; the song already read is kept as it is.
    public func loadAudioDuration(for url: URL?) {
        guard url == nil || url != durationAudioURL else { return }
        durationAudioURL = url
        audioDurationMS = nil
        audioDurationError = nil
        state = .idle
        guard let url else { return }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let file = try AVAudioFile(forReading: url)
            let duration = Double(file.length) / file.processingFormat.sampleRate * 1_000
            guard duration.isFinite, duration > 0 else {
                throw ChartGenerationSection.ValidationError(message: "The audio file has no readable duration.")
            }
            audioDurationMS = duration
        } catch {
            audioDurationError = "Could not read audio duration: \(error.localizedDescription)"
        }
    }

    public func loadCapabilities() async {
        do {
            let capabilities: ChartGenerationCapabilities
            switch engine {
            case .server:
                capabilities = try await client.capabilities()
            case .device:
                guard models.hasModel else {
                    starRange = nil
                    sectionOptions = []
                    modelDescription = nil
                    capabilitiesError = nil
                    return
                }
                capabilities = try await models.generator().capabilities
            }
            modelDescription = "\(capabilities.modelName) \(capabilities.modelVersion)"
            starRange = capabilities.minStar...max(capabilities.minStar, capabilities.maxStarExclusive - 0.1)
            sectionOptions = capabilities.controls.compactMap { control in
                guard let kind = ChartGenerationSection.Kind(rawValue: control.name),
                      control.status == .works || control.status == .experimental else { return nil }
                return ChartGenerationSectionOption(kind: kind, isExperimental: control.status == .experimental, note: control.note)
            }
            capabilitiesError = nil
        } catch {
            starRange = nil
            sectionOptions = []
            capabilitiesError = engine == .server
                ? "Chart service unavailable: \(error.localizedDescription)"
                : "The on-device model could not be opened: \(OnDeviceChartGenerator.message(for: error))"
        }
    }

    /// The finished generation, or nil after a failure (in `state`) or a stop. Without a `seed` the chart is a new one.
    /// The title defaults to the song file's name, as play shows it.
    public func generate(
        audioURL: URL, star: Double, seed: UInt64? = nil, title: String? = nil, artist: String? = nil
    ) async -> Mania4KChartGenerationResult? {
        guard !isGenerating else { return nil }
        if durationAudioURL != audioURL { loadAudioDuration(for: audioURL) }
        guard canGenerate(star: star), let audioDurationMS, let starRange else {
            state = .failed(audioDurationError ?? sectionValidationMessage ?? seedValidationMessage ?? capabilitiesError
                ?? "Choose audio and a star target within the supported range.")
            return nil
        }
        let engine = engine
        let title = title ?? audioURL.deletingPathExtension().lastPathComponent
        state = .generating(phase: engine == .server ? nil : .loading)
        do {
            let controls = try ChartGenerationSection.controls(for: sections, durationMS: audioDurationMS, starRange: starRange)
            let made: Made
            switch engine {
            case .server:
                made = try await generateWithServer(audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist)
            case .device:
                made = try await generateOnDevice(audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist)
            }
            let result = Mania4KChartGenerationResult(
                chart: made.chart, audioURL: audioURL, engine: engine,
                model: "\(made.receipt.modelName) \(made.receipt.modelVersion)", star: made.receipt.star, seed: made.receipt.seed,
                title: title, artist: artist, osu: made.osu, receiptJSON: made.receipt.receiptJson, serverOszPath: made.oszPath
            )
            state = .finished(result)
            return result
        } catch ChartGenerationError.stopped, is CancellationError, GeneratorError.Cancelled {
            state = .stopped
        } catch {
            state = .failed(OnDeviceChartGenerator.message(for: error))
        }
        return nil
    }

    /// A chart from either engine, with what the result keeps of it.
    private typealias Made = (chart: Mania4KGeneratedChart, receipt: GenerationReceipt, osu: String?, oszPath: String?)

    public func stop() async {
        if let deviceTask {
            deviceTask.cancel()
        } else {
            await client.stop()
        }
    }

    private func generateWithServer(
        audioURL: URL, star: Double, seed: UInt64?, controls: ChartGenerationControls, title: String?, artist: String?
    ) async throws -> Made {
        for try await event in await client.generate(audioURL: audioURL, star: star, controls: controls, seed: seed, title: title, artist: artist) {
            switch event {
            case .progress(let phase):
                state = .generating(phase: phase)
            case .result(let result):
                let receipt = GenerationReceipt(
                    modelName: result.receipt.modelName, modelVersion: result.receipt.modelVersion,
                    seed: result.receipt.seed, star: result.receipt.star, receiptJson: result.receipt.receiptJson
                )
                return (try Mania4KGeneratedChart(result.chart), receipt, nil, result.receipt.hasOszPath ? result.receipt.oszPath : nil)
            }
        }
        throw ChartGenerationError.service(code: "no_chart", message: "The service ended without a chart.")
    }

    private func generateOnDevice(
        audioURL: URL, star: Double, seed: UInt64?, controls: ChartGenerationControls, title: String?, artist: String?
    ) async throws -> Made {
        let generator = try await models.generator()
        // Small enough to read and retype.
        let seed = seed ?? UInt64.random(in: 0..<1 << 32)
        let task = Task {
            try await generator.generate(
                audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist
            ) { phase in
                Task { @MainActor in
                    if case .generating = self.state { self.state = .generating(phase: phase) }
                }
            }
        }
        deviceTask = task
        defer { deviceTask = nil }
        let result = try await task.value
        return (try Mania4KGeneratedChart(result.chart), result.receipt, result.osu, nil)
    }

    nonisolated private static func title(of phase: ChartGenerationPhase) -> String {
        switch phase {
        case .loading: return "Loading audio"
        case .timing: return "Finding the beat"
        case .rows: return "Placing notes"
        case .arrangement: return "Arranging lanes"
        case .export: return "Finishing"
        default: return "Working"
        }
    }
}
