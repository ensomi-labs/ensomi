import XCTest
@testable import EnsomiCore

final class ControlledGenerationClientTests: XCTestCase {
    private func window(_ rows: String, coverage: Int = 200) throws -> GenerationWindow {
        try JSONDecoder().decode(GenerationWindow.self, from: Data("""
        {"protocol":"ensomi-demo/v1","session_id":"test","sequence":1,"coverage_ms":\(coverage),"end_of_stream":false,"rows":\(rows)}
        """.utf8))
    }

    func testCompleteRowTranslationPreservesTimeLanesAndRealHoldEndpoints() throws {
        let value = try window("""
        [{"time_ms":100,"actions":[1,2,0,0]},{"time_ms":190,"actions":[0,3,0,1]}]
        """)
        XCTAssertEqual(try value.objects(after: 50), [
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 100, kind: .holdStart),
            Mania4KHitObject(lane: .innerLeft, timeMs: 190, kind: .holdEnd),
            Mania4KHitObject(lane: .right, timeMs: 190, kind: .tap)
        ])
    }

    func testInvalidAndBackdatedRowsAreRejected() throws {
        for rows in [
            "[{\"time_ms\":100,\"actions\":[1,0,0]}]",
            "[{\"time_ms\":100,\"actions\":[4,0,0,0]}]",
            "[{\"time_ms\":201,\"actions\":[1,0,0,0]}]",
            "[{\"time_ms\":100,\"actions\":[0,0,0,0]}]"
        ] {
            XCTAssertThrowsError(try window(rows).objects(after: 50))
        }
        XCTAssertThrowsError(try window("[{\"time_ms\":100,\"actions\":[1,0,0,0]}]").objects(after: 100))
        XCTAssertThrowsError(try window("[{\"time_ms\":-1,\"actions\":[1,0,0,0]}]").objects(after: -.infinity))
        XCTAssertEqual(try window("[]").objects(after: 100), [])
    }

    func testAutomaticControlsOmitOptionalTargets() throws {
        let data = try JSONEncoder().encode(GenerationControls())
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(object["difficulty"])
        XCTAssertNil(object["ln_fraction"])
        let specified = GenerationControls(difficulty: 4.5, style: ["tech": 1])
        XCTAssertEqual(try JSONDecoder().decode(GenerationControls.self, from: JSONEncoder().encode(specified)), specified)
    }
}
