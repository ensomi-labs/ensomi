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
            if section.kind == .star {
                guard starRange.contains(section.star) else {
                    throw ValidationError(message: "\(name): use the song's star range (\(starRange.lowerBound)–\(starRange.upperBound)).")
                }
            } else {
                guard (8_000...32_000).contains(end - start) else {
                    throw ValidationError(message: "\(name): a pattern must last 8–32 seconds.")
                }
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
        case generating(phase: String)
        case ready
        case failed(String)
    }

    public private(set) var state = State.idle
    public private(set) var modelDescription: String?
    /// Selectable stars in 0.1 steps; nil until the service has answered.
    public private(set) var starRange: ClosedRange<Double>?
    public private(set) var capabilitiesError: String?
    public private(set) var sectionOptions: [ChartGenerationSectionOption] = []
    public var sections: [ChartGenerationSection] = []
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
    }

    public func loadAudioDuration(for url: URL?) {
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

    /// The generated chart, or nil after a failure (in `state`) or a stop. Without a `seed` the chart is a new one.
    public func generate(
        audioURL: URL, star: Double, seed: UInt64? = nil, title: String? = nil, artist: String? = nil
    ) async -> Mania4KGeneratedChart? {
        guard !isGenerating else { return nil }
        if durationAudioURL != audioURL { loadAudioDuration(for: audioURL) }
        guard canGenerate(star: star), let audioDurationMS, let starRange else {
            state = .failed(audioDurationError ?? sectionValidationMessage ?? capabilitiesError ?? "Choose audio and a star target within the supported range.")
            return nil
        }
        let engine = engine
        state = .generating(phase: engine == .server ? "Connecting" : Self.label(.loading))
        do {
            let controls = try ChartGenerationSection.controls(for: sections, durationMS: audioDurationMS, starRange: starRange)
            let chart: Mania4KGeneratedChart
            switch engine {
            case .server:
                chart = try await generateWithServer(audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist)
            case .device:
                chart = try await generateOnDevice(audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist)
            }
            state = .ready
            return chart
        } catch ChartGenerationError.stopped, is CancellationError, GeneratorError.Cancelled {
            state = .idle
        } catch {
            state = .failed(OnDeviceChartGenerator.message(for: error))
        }
        return nil
    }

    public func stop() async {
        if let deviceTask {
            deviceTask.cancel()
        } else {
            await client.stop()
        }
    }

    private func generateWithServer(
        audioURL: URL, star: Double, seed: UInt64?, controls: ChartGenerationControls, title: String?, artist: String?
    ) async throws -> Mania4KGeneratedChart {
        for try await event in await client.generate(audioURL: audioURL, star: star, controls: controls, seed: seed, title: title, artist: artist) {
            switch event {
            case .progress(let phase):
                state = .generating(phase: Self.label(phase))
            case .result(let result):
                return try Mania4KGeneratedChart(result.chart)
            }
        }
        throw ChartGenerationError.service(code: "no_chart", message: "The service ended without a chart.")
    }

    private func generateOnDevice(
        audioURL: URL, star: Double, seed: UInt64?, controls: ChartGenerationControls, title: String?, artist: String?
    ) async throws -> Mania4KGeneratedChart {
        let generator = try await models.generator()
        // Small enough to read and retype.
        let seed = seed ?? UInt64.random(in: 0..<1 << 32)
        let task = Task {
            try await generator.generate(
                audioURL: audioURL, star: star, seed: seed, controls: controls, title: title, artist: artist
            ) { phase in
                Task { @MainActor in
                    if case .generating = self.state { self.state = .generating(phase: Self.label(phase)) }
                }
            }
        }
        deviceTask = task
        defer { deviceTask = nil }
        return try Mania4KGeneratedChart(try await task.value.chart)
    }

    private static func label(_ phase: ChartGenerationPhase) -> String {
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
