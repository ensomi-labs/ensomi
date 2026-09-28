import EnsomiProtocol
import XCTest
@testable import EnsomiCore

final class InferenceEndpointWebSocketClientTests: XCTestCase {
    private let metadata = InferenceEndpointEnvelopeMetadata(
        sequence: 42,
        sentAtUnixMs: 1_700_000_000_000,
        sourceNodeID: "test-node",
        messageID: "message-1",
        correlationID: "correlation-1"
    )

    func testReadyEnvelopeUsesProtocolPayloadAndMetadata() throws {
        let envelope = InferenceEndpointProtocolCodec.readyEnvelope(metadata: metadata)

        XCTAssertEqual(envelope.sessionID, "")
        XCTAssertEqual(envelope.sequence, 42)
        XCTAssertEqual(envelope.sentAtUnixMs, 1_700_000_000_000)
        XCTAssertEqual(envelope.sourceNodeID, "test-node")
        XCTAssertEqual(envelope.messageID, "message-1")
        XCTAssertEqual(envelope.correlationID, "correlation-1")
        guard case .ready? = envelope.payload else {
            return XCTFail("Expected ready payload.")
        }
    }

    func testAudioPathEnvelopeUsesProtocolFieldsAndDefaultRoute() throws {
        let envelope = InferenceEndpointProtocolCodec.audioPathEnvelope(
            "/audio/song.wav",
            sessionID: "session-1",
            metadata: metadata
        )

        XCTAssertEqual(envelope.sessionID, "session-1")
        guard case .audio(let request)? = envelope.payload else {
            return XCTFail("Expected audio payload.")
        }
        XCTAssertEqual(request.audio.localPath, "/audio/song.wav")
        XCTAssertEqual(request.syncSource, .background)
        XCTAssertTrue(request.hasDifficulty)
        XCTAssertEqual(request.difficulty, 4.0)
        XCTAssertEqual(request.route, .mapper)
    }

    func testAudioPathEnvelopeUsesConfiguredDifficultyAndMockRoute() throws {
        let envelope = InferenceEndpointProtocolCodec.audioPathEnvelope(
            "/audio/song.wav",
            sessionID: "session-1",
            musicSource: .systemAudio,
            configuration: InferenceEndpointConfiguration(difficulty: 5.5, isMock: true),
            metadata: metadata
        )

        guard case .audio(let request)? = envelope.payload else {
            return XCTFail("Expected audio payload.")
        }
        XCTAssertEqual(request.syncSource, .systemAudio)
        XCTAssertTrue(request.hasDifficulty)
        XCTAssertEqual(request.difficulty, 5.5)
        XCTAssertEqual(request.route, .timingMock)
    }

    func testMusicSourceMapsToInputRoute() {
        XCTAssertEqual(MusicSource.background.input, .microphone)
        XCTAssertEqual(MusicSource.systemAudio.input, .screenCaptureKitAudio)
    }

    func testReferenceTimeEnvelopeIncludesLocalHostSendTime() throws {
        let envelope = try InferenceEndpointProtocolCodec.referenceTimeEnvelope(
            sessionID: "session-1",
            refTimeMS: 1_234,
            localHostTimeSendMS: 6_789.25,
            metadata: metadata
        )

        XCTAssertEqual(envelope.sessionID, "session-1")
        guard case .referenceTime(let request)? = envelope.payload else {
            return XCTFail("Expected reference_time payload.")
        }
        XCTAssertEqual(request.refTimeMs, 1_234)
        XCTAssertEqual(request.localHostTimeSendMs, 6_789)
        XCTAssertFalse(request.hasAudioLengthMs)
    }

    func testStopEnvelopeUsesProtocolReason() throws {
        let envelope = InferenceEndpointProtocolCodec.stopEnvelope(
            sessionID: "session-1",
            metadata: metadata
        )

        XCTAssertEqual(envelope.sessionID, "session-1")
        guard case .stopSession(let request)? = envelope.payload else {
            return XCTFail("Expected stop_session payload.")
        }
        XCTAssertEqual(request.reason, "client_stop")
    }

    func testBinaryHitObjectTokenEnvelopeDecodesAndParsesMapperEventTokenID() throws {
        var token = Ensomi_Protocol_V1_HitObjectTokenEvent()
        token.tokenID = 30
        token.msInRefAudio = 1_234
        let envelope = InferenceEndpointProtocolCodec.envelope(
            sessionID: "session-1",
            payload: .hitObjectToken(token),
            metadata: metadata
        )
        let data = try InferenceEndpointProtocolCodec.serializedData(for: envelope)
        let decoded = try InferenceEndpointProtocolCodec.decodeEvent(from: .data(data))

        XCTAssertEqual(decoded, .hitObjectToken(InferenceEndpointHitObjectToken(
            sessionID: "session-1",
            tokenID: 30,
            timeMS: 1_234,
            objects: [
                Mania4KHitObject(lane: .left, timeMs: 1_234, kind: .holdStart),
                Mania4KHitObject(lane: .innerLeft, timeMs: 1_234, kind: .tap)
            ]
        )))
    }

    func testBinaryEndOfStreamEnvelopeDecodesAudioLengthAndCompleteThroughTime() throws {
        var endOfStream = Ensomi_Protocol_V1_EndOfStreamEvent()
        endOfStream.audioLengthMs = 94_277
        endOfStream.completeThroughMs = 94_277
        let envelope = InferenceEndpointProtocolCodec.envelope(
            sessionID: "session-1",
            payload: .endOfStream(endOfStream),
            metadata: metadata
        )
        let data = try InferenceEndpointProtocolCodec.serializedData(for: envelope)
        let decoded = try InferenceEndpointProtocolCodec.decodeEvent(from: .data(data))

        XCTAssertEqual(decoded, .endOfStream(InferenceEndpointEndOfStream(
            sessionID: "session-1",
            audioLengthMS: 94_277,
            completeThroughMS: 94_277
        )))
    }

    func testErrorEnvelopeThrowsServerError() throws {
        var error = Ensomi_Protocol_V1_ErrorEvent()
        error.code = "inference_failed"
        error.message = "mapper unavailable"
        let envelope = InferenceEndpointProtocolCodec.envelope(
            sessionID: "session-1",
            payload: .error(error),
            metadata: metadata
        )

        XCTAssertThrowsError(try InferenceEndpointProtocolCodec.decodeEvent(from: envelope)) { thrown in
            XCTAssertEqual(thrown as? InferenceEndpointProtocolError, .serverError("inference_failed: mapper unavailable"))
        }
    }

    func testTextWebSocketFrameIsRejectedForBinaryProtocol() {
        XCTAssertThrowsError(try InferenceEndpointProtocolCodec.decodeEvent(from: .string("{}"))) { error in
            XCTAssertEqual(error as? InferenceEndpointProtocolError, .invalidBinaryFrame)
        }
    }

    func testHitObjectTokenRejectsIdsOutsideMapperEventRange() {
        XCTAssertThrowsError(try InferenceEndpointHitObjectTokenParser.hitObjects(
            from: InferenceEndpointTokenPayload(tokenID: 24, timeMS: 100)
        )) { error in
            XCTAssertEqual(error as? InferenceEndpointProtocolError, .invalidHitObjectTokenID(24))
        }
    }

    func testReadyWindowEndsAtLatestTimeWithNoOpenHold() {
        let objects = [
            Mania4KHitObject(lane: .left, timeMs: 1_000, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 1_200, kind: .holdStart),
            Mania4KHitObject(lane: .right, timeMs: 1_350, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 1_800, kind: .holdEnd),
            Mania4KHitObject(lane: .innerRight, timeMs: 2_100, kind: .holdStart)
        ]

        let readyWindow = InferenceHitObjectTokenBuffer.readyWindow(for: objects)

        XCTAssertEqual(readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 1_800))
        XCTAssertEqual(readyWindow?.lengthMS, 800)
    }

    func testRenderReadinessUsesReferenceTimeLeadAndFiveSecondBuffer() {
        let buffer = InferenceHitObjectTokenBuffer(objects: [
            Mania4KHitObject(lane: .left, timeMs: 74_500, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 75_000, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 80_100, kind: .tap)
        ])

        let readiness = buffer.renderReadiness(
            referenceTimeMS: 74_000,
            minimumBufferedDurationMS: 5_000,
            firstObjectLeadTimeMS: 1_000
        )

        XCTAssertTrue(readiness.isReady)
        XCTAssertEqual(readiness.requiredFirstObjectTimeMS, 75_000)
        XCTAssertEqual(readiness.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 75_000, endTimeMS: 80_100))
        XCTAssertEqual(readiness.bufferedDurationAfterFirstObjectMS, 5_100)
    }

    func testRenderReadinessWaitsForCleanBoundaryAfterOpenHold() {
        let buffer = InferenceHitObjectTokenBuffer(objects: [
            Mania4KHitObject(lane: .left, timeMs: 74_000, kind: .holdStart),
            Mania4KHitObject(lane: .left, timeMs: 76_000, kind: .holdEnd),
            Mania4KHitObject(lane: .innerLeft, timeMs: 77_000, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 82_100, kind: .tap)
        ])

        let readiness = buffer.renderReadiness(
            referenceTimeMS: 74_000,
            minimumBufferedDurationMS: 5_000,
            firstObjectLeadTimeMS: 1_000
        )

        XCTAssertTrue(readiness.isReady)
        XCTAssertEqual(readiness.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 77_000, endTimeMS: 82_100))
    }

    func testRenderReadinessRejectsShortFutureBuffer() {
        let buffer = InferenceHitObjectTokenBuffer(objects: [
            Mania4KHitObject(lane: .left, timeMs: 75_000, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 79_900, kind: .tap)
        ])

        let readiness = buffer.renderReadiness(
            referenceTimeMS: 74_000,
            minimumBufferedDurationMS: 5_000,
            firstObjectLeadTimeMS: 1_000
        )

        XCTAssertFalse(readiness.isReady)
        XCTAssertEqual(readiness.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 75_000, endTimeMS: 79_900))
    }

    func testBufferedInferenceStreamStartsAtReadyWindowAndThenAcceptsDirectTokens() async throws {
        let referenceTime = ManualInferenceReferenceTime(timeMS: 74_000)
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Generated", sourceDescription: "Test"),
            minimumBufferedDurationMS: 5_000,
            firstObjectLeadTimeMS: 1_000,
            referenceTimeProvider: {
                await referenceTime.value()
            }
        )

        await stream.append(contentsOf: [
            Mania4KHitObject(lane: .left, timeMs: 74_500, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 75_000, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 80_100, kind: .tap)
        ])

        var batch = try await stream.read(after: nil, throughChartTimeMs: 75_500, limit: 10)
        XCTAssertEqual(batch.objects, [
            Mania4KHitObject(lane: .innerLeft, timeMs: 75_000, kind: .tap)
        ])
        XCTAssertEqual(batch.completeThroughChartTimeMs, 75_500)

        let accepted = await stream.append(contentsOf: [
            Mania4KHitObject(lane: .innerRight, timeMs: 80_250, kind: .tap)
        ])
        XCTAssertTrue(accepted)
        batch = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 80_500, limit: 10)
        XCTAssertEqual(batch.objects.map(\.timeMs), [80_100, 80_250])
        XCTAssertEqual(batch.completeThroughChartTimeMs, Double(80_250).nextDown)

        let lateAccepted = await stream.append(contentsOf: [
            Mania4KHitObject(lane: .right, timeMs: 75_100, kind: .tap)
        ])
        XCTAssertFalse(lateAccepted)
        do {
            _ = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 80_500, limit: 10)
            XCTFail("Published coverage forbids retroactive insertion")
        } catch is Mania4KPlayFailure { }
    }

    func testBufferRejectsTokensAtOrBeforeReadyWindowEndWithoutChangingReadyWindow() {
        var buffer = InferenceHitObjectTokenBuffer()

        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .left, timeMs: 1_000, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .innerLeft, timeMs: 1_200, kind: .holdStart)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .innerLeft, timeMs: 1_800, kind: .holdEnd)))
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 1_800))

        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .right, timeMs: 1_500, kind: .tap)))
        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .right, timeMs: 1_800, kind: .tap)))
        XCTAssertEqual(buffer.objects.count, 3)
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 1_800))

        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .right, timeMs: 2_100, kind: .tap)))
        XCTAssertEqual(buffer.objects.count, 4)
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 2_100))
    }

    func testBufferRejectsTokensBeforeMinimumAcceptedTime() {
        var buffer = InferenceHitObjectTokenBuffer(minimumAcceptedTimeMS: 1_500)

        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .left, timeMs: 1_499, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .innerLeft, timeMs: 1_500, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .right, timeMs: 1_700, kind: .tap)))

        XCTAssertEqual(buffer.objects, [
            Mania4KHitObject(lane: .innerLeft, timeMs: 1_500, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 1_700, kind: .tap)
        ])
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_500, endTimeMS: 1_700))
    }

    func testBufferPrunesExistingObjectsWhenMinimumAcceptedTimeIsSet() {
        var buffer = InferenceHitObjectTokenBuffer(objects: [
            Mania4KHitObject(lane: .left, timeMs: 1_000, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 1_500, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 1_900, kind: .tap)
        ])

        buffer.setMinimumAcceptedTimeMS(1_500)

        XCTAssertEqual(buffer.objects, [
            Mania4KHitObject(lane: .innerLeft, timeMs: 1_500, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 1_900, kind: .tap)
        ])
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_500, endTimeMS: 1_900))
    }

    func testBufferRejectsTokensOutsideAudioLength() {
        var buffer = InferenceHitObjectTokenBuffer(maximumAcceptedTimeMS: 2_000)

        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .left, timeMs: -1, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .innerLeft, timeMs: 0, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .innerRight, timeMs: 2_000, kind: .tap)))
        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .right, timeMs: 2_001, kind: .tap)))

        XCTAssertEqual(buffer.objects, [
            Mania4KHitObject(lane: .innerLeft, timeMs: 0, kind: .tap),
            Mania4KHitObject(lane: .innerRight, timeMs: 2_000, kind: .tap)
        ])
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 0, endTimeMS: 2_000))
    }

    func testBufferPrunesExistingObjectsWhenMaximumAcceptedTimeIsSet() {
        var buffer = InferenceHitObjectTokenBuffer(objects: [
            Mania4KHitObject(lane: .left, timeMs: 1_000, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 2_000, kind: .tap),
            Mania4KHitObject(lane: .right, timeMs: 2_001, kind: .tap)
        ])

        buffer.setMaximumAcceptedTimeMS(2_000)

        XCTAssertEqual(buffer.objects, [
            Mania4KHitObject(lane: .left, timeMs: 1_000, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 2_000, kind: .tap)
        ])
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 2_000))
    }

    func testPublishedCoverageDoesNotFollowReadRequestAndAcceptsSettledSilence() async throws {
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Coverage", sourceDescription: "Test"),
            referenceTimeProvider: { 0 })
        try await stream.publish([], completeThroughTimeMS: 8_000)
        let batch = try await stream.read(after: nil, throughChartTimeMs: 10_000, limit: 512)
        XCTAssertTrue(batch.objects.isEmpty)
        XCTAssertEqual(batch.completeThroughChartTimeMs, 8_000)
        XCTAssertFalse(batch.isEndOfStream)
        try await stream.publish([Mania4KHitObject(lane: .left, timeMs: 9_000, kind: .tap)], completeThroughTimeMS: 10_000)
        let next = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 10_000, limit: 512)
        XCTAssertEqual(next.objects.count, 1)
        XCTAssertEqual(next.completeThroughChartTimeMs, 10_000)
    }

    func testLimitedPageCannotSettleUndeliveredSimultaneousEvents() async throws {
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Paging", sourceDescription: "Test"),
            minimumBufferedDurationMS: 0, firstObjectLeadTimeMS: 0, referenceTimeProvider: { 0 })
        try await stream.publish([
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 100, kind: .tap)
        ], completeThroughTimeMS: 200)
        let first = try await stream.read(after: nil, throughChartTimeMs: 200, limit: 1)
        XCTAssertEqual(first.objects.count, 1)
        XCTAssertLessThan(first.completeThroughChartTimeMs, 100)
        let second = try await stream.read(after: first.nextCursor, throughChartTimeMs: 200, limit: 1)
        XCTAssertEqual(second.objects.count, 1)
        XCTAssertEqual(second.completeThroughChartTimeMs, 200)
        do {
            try await stream.publish([Mania4KHitObject(lane: .right, timeMs: 150, kind: .tap)], completeThroughTimeMS: 300)
            XCTFail("Late events must be rejected")
        } catch { }
    }

    func testCancelledInitialReadDoesNotWaitForAnotherProducerEvent() async throws {
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Cancel", sourceDescription: "Test"), referenceTimeProvider: { 0 })
        let reader = Task { try await stream.read(after: nil, throughChartTimeMs: 1000, limit: 512) }
        reader.cancel()
        do { _ = try await reader.value; XCTFail("Expected cancellation") }
        catch is CancellationError { }
    }

    func testBufferedInferenceStreamMarksEndOfStreamAtCompleteThroughTime() async throws {
        let referenceTime = ManualInferenceReferenceTime(timeMS: 0)
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Generated", sourceDescription: "Test"),
            minimumBufferedDurationMS: 0,
            firstObjectLeadTimeMS: 0,
            referenceTimeProvider: {
                await referenceTime.value()
            }
        )

        await stream.append(contentsOf: [
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap)
        ])

        var batch = try await stream.read(after: nil, throughChartTimeMs: 100, limit: 10)
        XCTAssertEqual(batch.objects, [
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap)
        ])
        XCTAssertFalse(batch.isEndOfStream)

        await stream.finish(completeThroughTimeMS: 200)
        let appendedAfterEnd = await stream.append(contentsOf: [
            Mania4KHitObject(lane: .innerLeft, timeMs: 150, kind: .tap)
        ])
        XCTAssertFalse(appendedAfterEnd)

        batch = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 199, limit: 10)
        XCTAssertFalse(batch.isEndOfStream)

        batch = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 200, limit: 10)
        XCTAssertTrue(batch.isEndOfStream)
    }

    func testBufferedInferenceStreamDoesNotEndWhileLimitedBatchMayHaveMoreObjects() async throws {
        let referenceTime = ManualInferenceReferenceTime(timeMS: 0)
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Generated", sourceDescription: "Test"),
            minimumBufferedDurationMS: 0,
            firstObjectLeadTimeMS: 0,
            referenceTimeProvider: {
                await referenceTime.value()
            }
        )

        await stream.append(contentsOf: [
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap),
            Mania4KHitObject(lane: .innerLeft, timeMs: 120, kind: .tap)
        ])
        await stream.finish(completeThroughTimeMS: 200)

        var batch = try await stream.read(after: nil, throughChartTimeMs: 200, limit: 1)
        XCTAssertEqual(batch.objects, [
            Mania4KHitObject(lane: .left, timeMs: 100, kind: .tap)
        ])
        XCTAssertFalse(batch.isEndOfStream)

        batch = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 200, limit: 1)
        XCTAssertEqual(batch.objects, [
            Mania4KHitObject(lane: .innerLeft, timeMs: 120, kind: .tap)
        ])
        XCTAssertFalse(batch.isEndOfStream)

        batch = try await stream.read(after: batch.nextCursor, throughChartTimeMs: 200, limit: 1)
        XCTAssertEqual(batch.objects, [])
        XCTAssertTrue(batch.isEndOfStream)
    }

    func testBufferedInferenceStreamCanFinishWithoutInitialReadyWindow() async throws {
        let referenceTime = ManualInferenceReferenceTime(timeMS: 0)
        let stream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: "Generated", sourceDescription: "Test"),
            minimumBufferedDurationMS: 5_000,
            firstObjectLeadTimeMS: 1_000,
            referenceTimeProvider: {
                await referenceTime.value()
            }
        )

        await stream.finish(completeThroughTimeMS: 800)
        let batch = try await stream.read(after: nil, throughChartTimeMs: 800, limit: 10)

        XCTAssertEqual(batch.objects, [])
        XCTAssertEqual(batch.completeThroughChartTimeMs, 800)
        XCTAssertTrue(batch.isEndOfStream)
    }

    func testBufferRejectsNonFiniteTokenTimes() {
        var buffer = InferenceHitObjectTokenBuffer(maximumAcceptedTimeMS: 2_000)

        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .left, timeMs: .nan, kind: .tap)))
        XCTAssertFalse(buffer.append(Mania4KHitObject(lane: .innerLeft, timeMs: .infinity, kind: .tap)))
        XCTAssertTrue(buffer.append(Mania4KHitObject(lane: .right, timeMs: 1_000, kind: .tap)))

        XCTAssertEqual(buffer.objects, [
            Mania4KHitObject(lane: .right, timeMs: 1_000, kind: .tap)
        ])
        XCTAssertEqual(buffer.readyWindow, InferenceHitObjectReadyWindow(startTimeMS: 1_000, endTimeMS: 1_000))
    }

}

private actor ManualInferenceReferenceTime {
    private var timeMS: Double

    init(timeMS: Double) {
        self.timeMS = timeMS
    }

    func value() -> Double? {
        timeMS
    }

    func setTimeMS(_ timeMS: Double) {
        self.timeMS = timeMS
    }
}
