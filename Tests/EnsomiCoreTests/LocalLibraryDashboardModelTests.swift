import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

final class LocalLibraryDashboardModelTests: XCTestCase {
    @MainActor
    func testLivePrototypeSurfacesPersistentDatabaseOpenFailure() {
        let model = LocalLibraryDashboardModel.livePrototype(databaseOpener: {
            throw PersistentDatabaseOpenFailure()
        })

        XCTAssertEqual(model.errorMessage, "Could not open persistent local audio library: disk unavailable")
    }
}

private struct PersistentDatabaseOpenFailure: LocalizedError {
    var errorDescription: String? {
        "disk unavailable"
    }
}
