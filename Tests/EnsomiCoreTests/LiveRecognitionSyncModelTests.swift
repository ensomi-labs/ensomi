#if os(macOS) && DEBUG
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

final class LiveRecognitionSyncModelTests: XCTestCase {
    @MainActor
    func testStopClearsAmbientStateAndRejectsObsoleteStartupCallbacks() throws {
        let model = LiveRecognitionSyncModel(database: try LocalAudioLibraryDatabase.openInMemory())
        let summary = AmbientReferenceSummary(
            assetFileName: "track.wav",
            sourceDisplayPath: "/tmp/track.wav",
            durationMS: 180_000,
            frameCount: 12,
            landmarkCount: 6
        )
        let snapshot = AmbientSyncSnapshot(
            state: .locked,
            phase: .final,
            stage: .tracking,
            confidence: 0.95,
            diagnostics: AmbientSyncDiagnostics(
                queryDurationMS: 5_000,
                activeFrameFraction: 1,
                queryLandmarkCount: 24
            )
        )

        let stoppedSessionID = model.debugInjectAmbientSyncState(
            referenceSummary: summary,
            snapshot: snapshot,
            updateCount: 3,
            frameBatchCount: 8
        )

        model.stop()
        XCTAssertThrowsError(try model.debugCompleteAmbientStart(sessionID: stoppedSessionID))
        model.debugFailAmbientStart(CancellationError(), sessionID: stoppedSessionID)

        XCTAssertEqual(model.phase, .idle)
        XCTAssertEqual(model.statusMessage, "Stopped")
        XCTAssertNil(model.referenceSummary)
        XCTAssertNil(model.latestAmbientSnapshot)
        XCTAssertEqual(model.ambientUpdateCount, 0)
        XCTAssertEqual(model.latestFrameBatchCount, 0)

        let replacementSessionID = model.debugInjectAmbientSyncState(
            referenceSummary: summary, snapshot: snapshot, updateCount: 2, frameBatchCount: 4
        )
        // An older start may finish cleanup after a replacement has begun.
        model.debugFailAmbientStart(NSError(domain: "old startup", code: 1), sessionID: stoppedSessionID)
        XCTAssertThrowsError(try model.debugCompleteAmbientStart(sessionID: stoppedSessionID))
        XCTAssertEqual(model.phase, .ambientSyncing)
        XCTAssertEqual(model.latestAmbientSnapshot, snapshot)
        XCTAssertEqual(model.ambientUpdateCount, 2)

        try model.debugCompleteAmbientStart(sessionID: replacementSessionID)
        XCTAssertEqual(model.statusMessage, "Ambient sync listening")
    }

    func testAmbientReferencePlaybackAnchorClampsAtTrackDuration() {
        let anchorHostTimeMS = 1_000_000.0
        let anchor = AmbientReferencePlaybackAnchor(
            referenceTimeAtAnchorMS: 179_500,
            durationMS: 180_000,
            anchorHostTimeMS: anchorHostTimeMS
        )

        XCTAssertEqual(anchor.referenceTimeMS(atHostTimeMS: anchorHostTimeMS + 250), 179_750)
        XCTAssertEqual(anchor.referenceTimeMS(atHostTimeMS: anchorHostTimeMS + 2_000), 180_000)
    }

    func testAmbientProcessSchedulerThrottlesUntilQueryEndpointCadenceElapses() {
        var scheduler = LiveAmbientSyncProcessScheduler(minimumQueryEndpointIntervalMS: 100)

        XCTAssertTrue(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_000))
        XCTAssertFalse(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_050))
        XCTAssertFalse(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_099.9))
        XCTAssertTrue(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_100))
        XCTAssertFalse(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_150))
        XCTAssertTrue(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 900))
        XCTAssertFalse(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 950))
        XCTAssertTrue(scheduler.shouldProcess(queryEndpointRecordedTimeMS: 1_000))
    }

    @MainActor
    func testFinalAmbientLockRequestsGeneratedPlaySession() throws {
        let recorder = PlaySessionRequestRecorder()
        let model = LiveRecognitionSyncModel(
            database: try LocalAudioLibraryDatabase.openInMemory(),
            onPlaySessionRequested: recorder.record(_:)
        )
        let asset = makeLocalAudioAsset(displayPath: "/tmp/mode-3-final-lock.mp3")

        model.debugApplyAmbientFinalLock(
            asset: asset,
            referenceTimeAtQueryMS: 2_500,
            queryEndpointRecordedTimeMS: 2_000,
            latestRecordedTimeMS: 2_800,
            queryEndpointToReceiveLatencyMS: 25,
            receivedHostTimeMS: 12_000
        )

        XCTAssertEqual(model.phase, .completed)
        XCTAssertEqual(model.statusMessage, "Ambient sync locked")
        XCTAssertEqual(recorder.requests, [
            LiveRecognitionPlaySessionRequest(
                audioFileURL: URL(fileURLWithPath: "/tmp/mode-3-final-lock.mp3"),
                referenceTimeMS: 3_325,
                anchorHostTimeMS: 12_000,
                title: "mode-3-final-lock.mp3"
            )
        ])
    }
}

@MainActor
private final class PlaySessionRequestRecorder {
    private(set) var requests: [LiveRecognitionPlaySessionRequest] = []

    func record(_ request: LiveRecognitionPlaySessionRequest) {
        requests.append(request)
    }
}

/// A library recording at `displayPath`.
func makeLocalAudioAsset(displayPath: String, sha256: String = "abc123") -> LocalAudioAsset {
    LocalAudioAsset(
        id: UUID(),
        directoryID: UUID(),
        fileURLBookmark: nil,
        displayPath: displayPath,
        fileName: URL(fileURLWithPath: displayPath).lastPathComponent,
        fileExtension: URL(fileURLWithPath: displayPath).pathExtension,
        fileSizeBytes: 1_024,
        sha256: sha256,
        durationMS: 180_000,
        title: nil,
        artists: [],
        album: nil,
        albumArtist: nil,
        trackNumber: nil,
        discNumber: nil,
        isrc: nil,
        releaseYear: nil,
        indexedAt: Date(timeIntervalSince1970: 1_710_000_000),
        lastSeenAt: Date(timeIntervalSince1970: 1_710_000_000),
        status: .ready
    )
}
#endif
