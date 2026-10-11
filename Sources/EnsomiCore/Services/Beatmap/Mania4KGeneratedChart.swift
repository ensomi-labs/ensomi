import EnsomiProtocol
import Foundation

/// A complete chart from the chart service as the stream events the judgement engine takes.
public struct Mania4KGeneratedChart: Equatable, Sendable {
    public let objects: [Mania4KHitObject]
    public let objectCount: Int
    public let durationMs: Double

    /// Throws `Mania4KChartValidationError` unless the chart is sorted, in range and has valid lanes and holds.
    public init(_ chart: Ensomi_Protocol_V1_Chart) throws {
        let duration = Double(chart.durationMs)
        var events: [(object: Mania4KHitObject, order: Int)] = []
        var busyUntil: [Int: (ms: UInt32, index: Int)] = [:]
        var previous: (start: UInt32, lane: UInt32, index: Int)?

        for (index, source) in chart.objects.enumerated() {
            guard let lane = Mania4KLane(rawValue: Int(source.lane)) else {
                throw Mania4KChartValidationError.invalidLane(Int(source.lane))
            }
            let end = source.hasEndMs ? source.endMs : nil
            guard source.startMs <= chart.durationMs, end.map({ $0 > source.startMs && $0 <= chart.durationMs }) ?? true else {
                throw Mania4KChartValidationError.invalidTime(streamIndex: index)
            }
            if let previous, (source.startMs, source.lane) < (previous.start, previous.lane) {
                throw Mania4KChartValidationError.nonMonotonicObjectOrder(
                    previousStreamIndex: previous.index, nextStreamIndex: index)
            }
            previous = (source.startMs, source.lane, index)
            if let busy = busyUntil[lane.rawValue], source.startMs <= busy.ms {
                throw Mania4KChartValidationError.sameLaneOverlap(
                    previousStreamIndex: busy.index, nextStreamIndex: index)
            }
            busyUntil[lane.rawValue] = (end ?? source.startMs, index)

            let start = Double(source.startMs)
            if let end {
                events.append((Mania4KHitObject(lane: lane, timeMs: start, kind: .holdStart), events.count))
                events.append((Mania4KHitObject(lane: lane, timeMs: Double(end), kind: .holdEnd), events.count))
            } else {
                events.append((Mania4KHitObject(lane: lane, timeMs: start, kind: .tap), events.count))
            }
        }

        let sorted = events.sorted {
            ($0.object.timeMs, $0.object.lane, $0.order) < ($1.object.timeMs, $1.object.lane, $1.order)
        }.map(\.object)
        self.objects = try Mania4KStreamValidator.validated(sorted)
        self.objectCount = chart.objects.count
        self.durationMs = duration
    }
}
