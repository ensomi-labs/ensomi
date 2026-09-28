#if os(macOS) && DEBUG
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

private actor DemoPermissionProbe: MicrophonePermissionProviding {
    var calls = 0
    func currentStatus() -> MicrophonePermissionStatus { calls += 1; return .denied }
    func requestAccess() -> MicrophonePermissionStatus { calls += 1; return .denied }
}

final class LiveGenerationDemoModelTests: XCTestCase {
    @MainActor
    func testBackendFailureNeverStartsCapture() async throws {
        let permission = DemoPermissionProbe()
        let recognition = LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission)
        let model = LiveGenerationDemoModel(recognition: recognition, checkBackend: { _ in
            throw GenerationServiceFailure("Unavailable")
        })
        model.enter()
        for _ in 0..<100 where model.checkingBackend { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertFalse(model.backendReady)
        XCTAssertNotNil(model.errorMessage)
        let calls = await permission.calls
        XCTAssertEqual(calls, 0)
        model.stop()
    }

    @MainActor
    func testHealthyBackendAutomaticallyBeginsRecognitionWithoutPrototypeInference() async throws {
        let permission = DemoPermissionProbe()
        let recognition = LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission)
        let model = LiveGenerationDemoModel(recognition: recognition, checkBackend: { _ in
            try JSONDecoder().decode(GenerationServiceHealth.self, from: Data("""
            {"protocol":"ensomi-demo/v1","ready":true,"style_names":["tech"],"checkpoint_sha256":"test"}
            """.utf8))
        })
        model.enter()
        for _ in 0..<100 {
            if await permission.calls > 0 { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertTrue(model.backendReady)
        XCTAssertFalse(recognition.startsPrototypeInference)
        XCTAssertEqual(model.availableStyles, ["tech"])
        let calls = await permission.calls
        XCTAssertGreaterThan(calls, 0)
        model.stop()
    }

    @MainActor
    func testStopDiscardsLateBackendHealth() async throws {
        let permission = DemoPermissionProbe()
        let recognition = LiveRecognitionSyncModel(database: try .openInMemory(), permissionService: permission)
        let model = LiveGenerationDemoModel(recognition: recognition, checkBackend: { _ in
            try await Task.sleep(for: .milliseconds(100))
            throw GenerationServiceFailure("Late response")
        })
        model.enter()
        model.stop()
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertFalse(model.backendReady)
        XCTAssertFalse(model.checkingBackend)
        XCTAssertNil(model.errorMessage)
        let calls = await permission.calls
        XCTAssertEqual(calls, 0)
    }
}
#endif
