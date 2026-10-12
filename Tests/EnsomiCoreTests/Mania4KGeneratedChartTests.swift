import EnsomiProtocol
import XCTest
@testable import EnsomiCore

final class Mania4KGeneratedChartTests: XCTestCase {
    func testConvertsTapsAndHoldsToSortedStreamEvents() throws {
        let chart = try Mania4KGeneratedChart(makeChart(duration: 5_000, [
            (0, 100, nil), (1, 100, 900), (2, 500, nil), (1, 1_000, nil), (3, 2_000, 2_500)
        ]))

        XCTAssertEqual(chart.objectCount, 5)
        XCTAssertEqual(chart.durationMs, 5_000)
        XCTAssertEqual(chart.objects.map(\.timeMs), [100, 100, 500, 900, 1_000, 2_000, 2_500])
        XCTAssertEqual(
            chart.objects.map(\.kind),
            [.tap, .holdStart, .tap, .holdEnd, .tap, .holdStart, .holdEnd]
        )
        XCTAssertEqual(chart.objects.map(\.lane.rawValue), [0, 1, 2, 1, 1, 3, 3])
    }

    func testRejectsInvalidChartsWithTheCoresErrors() {
        assertRejects([(4, 100, nil)], .InvalidLane(object: 0))
        assertRejects([(0, 100, 50)], .InvalidHold(object: 0))
        assertRejects([(0, 100, 6_000)], .OutsideDuration(object: 0))
        assertRejects([(0, 6_000, nil)], .OutsideDuration(object: 0))
        assertRejects([(0, 200, nil), (1, 100, nil)], .NotSorted(object: 1))
        assertRejects([(0, 100, 900), (0, 500, nil)], .SameLaneOverlap(object: 1))
        assertRejects([(2, 100, nil), (2, 100, nil)], .SameLaneOverlap(object: 1))
    }

    func testRejectionReadsAsText() {
        XCTAssertThrowsError(try Mania4KGeneratedChart(makeChart(duration: 5_000, [(4, 100, nil)]))) {
            XCTAssertEqual($0.localizedDescription, "Object 0 is not in one of the four lanes.")
        }
    }

    /// The core accepts a zero-length hold and a hold that ends where the next note of its lane starts; the Swift
    /// generated-chart check rejected both.
    func testAcceptsAZeroLengthHoldAndAHoldEndingWhereTheNextNoteStarts() throws {
        let chart = try Mania4KGeneratedChart(makeChart(duration: 5_000, [(0, 100, 100), (1, 100, 900), (1, 900, nil)]))

        XCTAssertEqual(chart.objectCount, 3)
        XCTAssertEqual(chart.objects.map(\.kind), [.holdStart, .holdEnd, .holdStart, .holdEnd, .tap])
        XCTAssertEqual(chart.objects.map(\.timeMs), [100, 100, 100, 900, 900])
    }

    @MainActor
    func testPlaysFromZeroWithEveryNote() async throws {
        let chart = try Mania4KGeneratedChart(makeChart(duration: 8_000, [(0, 300, nil), (1, 600, 1_200), (2, 5_000, nil)]))
        let model = Mania4KPlaySessionModel(
            scrollSpeed: 16, audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0,
            audioClock: FakeMania4KAudioClock()
        )
        let audioURL = URL(fileURLWithPath: "/tmp/song.mp3")

        let started = await model.startGeneratedChartPlay(audioFileURL: audioURL, chart: chart, title: "Song")

        XCTAssertTrue(started)
        XCTAssertEqual(model.phase, .playing)
        XCTAssertEqual(model.activeConfiguration?.audioFileURL, audioURL)
        XCTAssertEqual(model.activeConfiguration?.chartSource, .generated(displayName: "Generated: Song"))
        XCTAssertEqual(model.playFrame?.metadata.durationMs, 8_000)
        XCTAssertEqual(model.playFrame?.visibleObjects.map(\.startTimeMs), [300, 600])
        await model.quitToSetup()
    }

    private func assertRejects(
        _ objects: [(UInt32, UInt32, UInt32?)], _ expected: ChartError,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        XCTAssertThrowsError(try Mania4KGeneratedChart(makeChart(duration: 5_000, objects)), file: file, line: line) {
            XCTAssertEqual($0 as? Mania4KChartValidationError, .chart(expected), file: file, line: line)
        }
    }
}

func makeChart(duration: UInt32, _ objects: [(UInt32, UInt32, UInt32?)]) -> Ensomi_Protocol_V1_Chart {
    var chart = Ensomi_Protocol_V1_Chart()
    chart.durationMs = duration
    chart.objects = objects.map { lane, start, end in
        var object = Ensomi_Protocol_V1_ChartObject()
        object.lane = lane
        object.startMs = start
        if let end { object.endMs = end }
        return object
    }
    return chart
}
