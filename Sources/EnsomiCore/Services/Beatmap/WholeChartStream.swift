import Foundation

/// A chart made whole before play, for ambient play. `prepare` waits for the chart, then publishes every object into a
/// `BufferedInferenceMania4KHitObjectStream` as one finished page, whose late join picks where play starts: at least
/// its first-object lead past the heard position, outside any hold. Objects before that point are not played.
public actor WholeChartStream: Mania4KHitObjectStreaming {
    private static let sourceDescription = "Ensomi R2 generator"
    private let title: String
    private let chart: Task<Mania4KGeneratedChart, Error>
    private let buffer: BufferedInferenceMania4KHitObjectStream

    /// `chart` may still be running; `cancel` cancels it.
    public init(
        title: String,
        chart: Task<Mania4KGeneratedChart, Error>,
        referenceTime: @escaping BufferedInferenceMania4KHitObjectStream.ReferenceTimeProvider
    ) {
        self.title = title
        self.chart = chart
        buffer = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: title, sourceDescription: Self.sourceDescription),
            referenceTimeProvider: referenceTime
        )
    }

    public func prepare() async throws -> Mania4KChartMetadata {
        let chart = try await chart.value
        try await buffer.publish(chart.objects, completeThroughTimeMS: chart.durationMs)
        await buffer.finish(completeThroughTimeMS: chart.durationMs)
        return Mania4KChartMetadata(
            title: title, sourceDescription: Self.sourceDescription,
            objectCount: chart.objectCount, durationMs: chart.durationMs
        )
    }

    public func read(after cursor: Mania4KHitObjectStreamCursor?, throughChartTimeMs: Double, limit: Int) async throws -> Mania4KHitObjectBatch {
        try await buffer.read(after: cursor, throughChartTimeMs: throughChartTimeMs, limit: limit)
    }

    public func cancel() async {
        chart.cancel()
        await buffer.cancel()
    }
}
