import Foundation
import Observation

/// Setup-screen state of "Generate a Chart": what the service offers and the progress of one generation.
@MainActor
@Observable
public final class Mania4KChartGenerationModel {
    public enum State: Equatable {
        case idle
        case generating(phase: String)
        case failed(String)
    }

    public private(set) var state = State.idle
    public private(set) var modelDescription: String?
    /// Selectable stars in 0.1 steps; nil until the service has answered.
    public private(set) var starRange: ClosedRange<Double>?
    public private(set) var capabilitiesError: String?

    @ObservationIgnored private let client: ChartGenerationClient

    public init(client: ChartGenerationClient = ChartGenerationClient()) {
        self.client = client
    }

    public var isGenerating: Bool {
        if case .generating = state { return true }
        return false
    }

    public func loadCapabilities() async {
        do {
            let capabilities = try await client.capabilities()
            modelDescription = "\(capabilities.modelName) \(capabilities.modelVersion)"
            starRange = capabilities.minStar...max(capabilities.minStar, capabilities.maxStarExclusive - 0.1)
            capabilitiesError = nil
        } catch {
            starRange = nil
            capabilitiesError = "Chart service unavailable: \(error.localizedDescription)"
        }
    }

    /// The generated chart, or nil after a failure (in `state`) or a stop.
    public func generate(audioURL: URL, star: Double, title: String? = nil, artist: String? = nil) async -> Mania4KGeneratedChart? {
        state = .generating(phase: "Connecting")
        do {
            for try await event in await client.generate(audioURL: audioURL, star: star, title: title, artist: artist) {
                switch event {
                case .progress(let phase):
                    state = .generating(phase: Self.label(phase))
                case .result(let result):
                    let chart = try Mania4KGeneratedChart(result.chart)
                    state = .idle
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
