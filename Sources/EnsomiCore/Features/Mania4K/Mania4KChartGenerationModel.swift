import AVFoundation
import EnsomiProtocol
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

/// Setup-screen state of "Generate a Chart": what the service offers and the progress of one generation.
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

    @ObservationIgnored private let client: ChartGenerationClient
    @ObservationIgnored private var durationAudioURL: URL?

    public init(client: ChartGenerationClient = ChartGenerationClient()) {
        self.client = client
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
            let capabilities = try await client.capabilities()
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
            capabilitiesError = "Chart service unavailable: \(error.localizedDescription)"
        }
    }

    /// The generated chart, or nil after a failure (in `state`) or a stop.
    public func generate(audioURL: URL, star: Double, title: String? = nil, artist: String? = nil) async -> Mania4KGeneratedChart? {
        guard !isGenerating else { return nil }
        if durationAudioURL != audioURL { loadAudioDuration(for: audioURL) }
        guard canGenerate(star: star), let audioDurationMS, let starRange else {
            state = .failed(audioDurationError ?? sectionValidationMessage ?? capabilitiesError ?? "Choose audio and a star target within the supported range.")
            return nil
        }
        state = .generating(phase: "Connecting")
        do {
            let controls = try ChartGenerationSection.controls(for: sections, durationMS: audioDurationMS, starRange: starRange)
            for try await event in await client.generate(audioURL: audioURL, star: star, controls: controls, title: title, artist: artist) {
                switch event {
                case .progress(let phase):
                    state = .generating(phase: Self.label(phase))
                case .result(let result):
                    let chart = try Mania4KGeneratedChart(result.chart)
                    state = .ready
                    return chart
                }
            }
            state = .failed("The service ended without a chart.")
        } catch ChartGenerationError.stopped {
            state = .idle
        } catch {
            state = .failed(error.localizedDescription)
        }
        return nil
    }

    public func stop() async {
        await client.stop()
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
