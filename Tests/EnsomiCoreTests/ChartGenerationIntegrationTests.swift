import XCTest
@testable import EnsomiCore

/// Runs against a live chart service, skipped unless `ENSOMI_SERVICE_URL` is set. The audio is the 121 s song whose
/// chart (star 3.5, seed 0) has 1,052 objects, named by `ENSOMI_SERVICE_AUDIO`. With xcodebuild, pass both as
/// `TEST_RUNNER_ENSOMI_SERVICE_URL=ws://127.0.0.1:PORT TEST_RUNNER_ENSOMI_SERVICE_AUDIO=/path/to/audio.mp3`.
final class ChartGenerationIntegrationTests: XCTestCase {
    func testGeneratesAndConvertsFullSongChart() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let urlText = environment["ENSOMI_SERVICE_URL"], !urlText.isEmpty,
              let audioPath = environment["ENSOMI_SERVICE_AUDIO"]
        else {
            throw XCTSkip("ENSOMI_SERVICE_URL and ENSOMI_SERVICE_AUDIO are not set.")
        }
        let audio = URL(fileURLWithPath: audioPath)
        let client = ChartGenerationClient(serviceURL: try XCTUnwrap(URL(string: urlText)))

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.minStar, 2)
        XCTAssertEqual(capabilities.maxStarExclusive, 6)

        var phases: [ChartGenerationPhase] = []
        var result: ChartGenerationResult?
        for try await event in await client.generate(audioURL: audio, star: 3.5, seed: 0, title: "Catch the Moment") {
            switch event {
            case .progress(let phase): phases.append(phase)
            case .result(let value): result = value
            }
        }

        let finished = try XCTUnwrap(result)
        XCTAssertFalse(phases.isEmpty)
        XCTAssertEqual(finished.receipt.modelName, capabilities.modelName)
        XCTAssertEqual(finished.chart.objects.count, 1_052)
        XCTAssertEqual(Double(finished.chart.durationMs), 121_169, accuracy: 500)

        let chart = try Mania4KGeneratedChart(finished.chart)
        XCTAssertEqual(chart.objectCount, 1_052)
        XCTAssertEqual(chart.chart.objects.filter { $0.endMs != nil }.count, finished.chart.objects.filter(\.hasEndMs).count)
        XCTAssertEqual(chart.durationMs, 121_169, accuracy: 500)
    }
}
