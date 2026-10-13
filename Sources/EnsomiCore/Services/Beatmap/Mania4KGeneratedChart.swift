import EnsomiProtocol
import EnsomiRuntime
import Foundation

/// A complete generated chart, which the gameplay core accepted.
public struct Mania4KGeneratedChart: Equatable, Sendable {
    public let chart: Chart

    /// From the chart service. Throws `Mania4KChartValidationError` unless the gameplay core accepts the chart.
    public init(_ source: Ensomi_Protocol_V1_Chart) throws {
        try self.init(Chart(durationMs: source.durationMs, objects: source.objects.map {
            ChartObject(lane: $0.lane, startMs: $0.startMs, endMs: $0.hasEndMs ? $0.endMs : nil)
        }))
    }

    /// From the on-device generator. Throws `Mania4KChartValidationError` unless the gameplay core accepts the chart.
    public init(_ chart: Chart) throws {
        do {
            _ = try GameplaySession(chart: chart, ruleset: Ruleset(judge: .c))
        } catch let error as ChartError {
            throw Mania4KChartValidationError.chart(error)
        }
        self.chart = chart
    }

    public var objectCount: Int {
        chart.objects.count
    }

    public var durationMs: Double {
        Double(chart.durationMs)
    }
}
