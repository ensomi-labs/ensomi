import EnsomiProtocol
import EnsomiRuntime
import Foundation

/// A complete chart from the chart service as the stream tokens the play session takes.
public struct Mania4KGeneratedChart: Equatable, Sendable {
    public let objects: [Mania4KHitObject]
    public let objectCount: Int
    public let durationMs: Double

    /// Throws `Mania4KChartValidationError` unless the gameplay core accepts the chart.
    public init(_ source: Ensomi_Protocol_V1_Chart) throws {
        let chart = Chart(durationMs: source.durationMs, objects: source.objects.map {
            ChartObject(lane: $0.lane, startMs: $0.startMs, endMs: $0.hasEndMs ? $0.endMs : nil)
        })
        do {
            _ = try GameplaySession(chart: chart, ruleset: Ruleset(judge: .c))
        } catch let error as ChartError {
            throw Mania4KChartValidationError.chart(error)
        }

        self.objects = Mania4KHitObject.objects(of: chart)
        self.objectCount = chart.objects.count
        self.durationMs = Double(chart.durationMs)
    }
}
