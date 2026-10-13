import XCTest
@testable import EnsomiCore

final class WholeChartStreamTests: XCTestCase {
    func testTheWholeChartIsPublishedAsOneFinishedPage() async throws {
        let chart = try sampleChart()
        let stream = WholeChartStream(title: "Song", chart: Task { chart }, referenceTime: { -1_000 })

        let metadata = try await stream.prepare()
        let page = try await stream.read(after: nil, throughChartTimeMs: chart.durationMs, limit: 100)

        XCTAssertEqual(metadata.durationMs, chart.durationMs)
        XCTAssertEqual(metadata.objectCount, chart.objectCount)
        XCTAssertEqual(page.objects, chart.objects)
        XCTAssertEqual(page.completeThroughChartTimeMs, chart.durationMs)
        XCTAssertTrue(page.isEndOfStream)
    }

    /// Heard at 10 s, the join point a second later falls inside the hold from 10.5 to 11.5 s, so play starts after
    /// it; the earlier notes and the hold are not played.
    func testAJoinMidSongStartsPastTheHeardPositionOutsideAHoldAndDropsEarlierObjects() async throws {
        let chart = try sampleChart()
        let stream = WholeChartStream(title: "Song", chart: Task { chart }, referenceTime: { 10_000 })
        _ = try await stream.prepare()

        let page = try await stream.read(after: nil, throughChartTimeMs: chart.durationMs, limit: 100)

        XCTAssertEqual(page.objects, chart.objects.filter { $0.timeMs >= 12_000 })
        XCTAssertTrue(page.isEndOfStream)
    }

    func testCancelStopsTheChartBeingMade() async throws {
        let chart = Task<Mania4KGeneratedChart, Error> {
            try await Task.sleep(for: .seconds(60))
            return try sampleChart()
        }
        let stream = WholeChartStream(title: "Song", chart: chart, referenceTime: { 0 })

        await stream.cancel()

        XCTAssertTrue(chart.isCancelled)
        do {
            _ = try await stream.prepare()
            XCTFail("A cancelled chart has nothing to play.")
        } catch {}
    }
}

/// Taps at 2 s and 9 s, a hold from 10.5 to 11.5 s, taps at 12 s and 20 s, in a 30 s song.
private func sampleChart() throws -> Mania4KGeneratedChart {
    try Mania4KGeneratedChart(makeChart(duration: 30_000, [
        (0, 2_000, nil), (2, 9_000, nil), (1, 10_500, 11_500), (3, 12_000, nil), (0, 20_000, nil)
    ]))
}
