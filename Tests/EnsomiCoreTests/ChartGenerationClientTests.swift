import EnsomiProtocol
import XCTest
@testable import EnsomiCore

private typealias Envelope = Ensomi_Protocol_V1_Envelope

/// A transport whose replies come from a closure called with each frame the client sends.
private final class ScriptedChartTransport: ChartGenerationTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var frames: [Envelope] = []
    private let replies: AsyncStream<Data>
    private let continuation: AsyncStream<Data>.Continuation
    private var iterator: AsyncStream<Data>.Iterator
    private let script: @Sendable (Envelope) -> [Envelope.OneOf_Payload]

    init(script: @escaping @Sendable (Envelope) -> [Envelope.OneOf_Payload]) {
        self.script = script
        (replies, continuation) = AsyncStream.makeStream()
        iterator = replies.makeAsyncIterator()
    }

    var sent: [Envelope] { lock.withLock { frames } }

    func send(_ data: Data) async throws {
        let request = try Envelope(serializedBytes: data)
        lock.withLock { frames.append(request) }
        for payload in script(request) {
            var reply = Envelope()
            reply.sessionID = request.sessionID
            reply.correlationID = request.messageID
            reply.payload = payload
            continuation.yield(try reply.serializedData())
        }
    }

    func receive() async throws -> Data {
        guard let data = await iterator.next() else { throw CancellationError() }
        return data
    }

    func close() { continuation.finish() }
}

private func progress(_ phase: ChartGenerationPhase) -> Envelope.OneOf_Payload {
    var message = Ensomi_Protocol_V1_GenerationProgress()
    message.phase = phase
    return .generationProgress(message)
}

private func error(_ code: String, _ message: String) -> Envelope.OneOf_Payload {
    var event = Ensomi_Protocol_V1_ErrorEvent()
    event.code = code
    event.message = message
    return .error(event)
}

final class ChartGenerationClientTests: XCTestCase {
    private func collect(_ stream: AsyncThrowingStream<ChartGenerationEvent, Error>) async throws -> [ChartGenerationEvent] {
        var events: [ChartGenerationEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }

    func testCapabilitiesSendsNodeScopedRequest() async throws {
        let transport = ScriptedChartTransport { _ in
            var capabilities = Ensomi_Protocol_V1_ModelCapabilities()
            capabilities.modelName = "Ensomi R2"
            capabilities.modelVersion = "r2-1.0"
            capabilities.minStar = 2
            capabilities.maxStarExclusive = 6
            return [.modelCapabilities(capabilities)]
        }
        let client = ChartGenerationClient { transport }

        let capabilities = try await client.capabilities()

        XCTAssertEqual(capabilities.modelName, "Ensomi R2")
        XCTAssertEqual(capabilities.maxStarExclusive, 6)
        XCTAssertEqual(transport.sent.count, 1)
        XCTAssertEqual(transport.sent[0].sessionID, "")
        guard case .capabilitiesRequest? = transport.sent[0].payload else { return XCTFail("Expected capabilities request.") }
    }

    func testGenerateYieldsProgressThenResult() async throws {
        let transport = ScriptedChartTransport { _ in
            var result = Ensomi_Protocol_V1_ChartResult()
            result.chart = makeChart(duration: 4_000, [(0, 100, nil)])
            result.receipt.seed = 7
            return [progress(.loading), progress(.rows), .chartResult(result)]
        }
        let client = ChartGenerationClient { transport }

        let events = try await collect(await client.generate(
            audioURL: URL(fileURLWithPath: "/music/song.mp3"), star: 3.5, seed: 7, title: "Song", artist: "Band"))

        XCTAssertEqual(events.count, 3)
        XCTAssertEqual(events[0], .progress(.loading))
        XCTAssertEqual(events[1], .progress(.rows))
        guard case .result(let result) = events[2] else { return XCTFail("Expected a result.") }
        XCTAssertEqual(result.chart.objects.count, 1)
        guard case .generateChart(let request)? = transport.sent[0].payload else { return XCTFail("Expected a request.") }
        XCTAssertFalse(transport.sent[0].sessionID.isEmpty)
        XCTAssertEqual(request.audio.localPath, "/music/song.mp3")
        XCTAssertEqual(request.star, 3.5)
        XCTAssertEqual(request.seed, 7)
        XCTAssertEqual(request.title, "Song")
        XCTAssertEqual(request.artist, "Band")
    }

    func testServiceErrorIsThrownWithItsCode() async throws {
        let transport = ScriptedChartTransport { _ in [progress(.loading), error("audio_unreadable", "No such file")] }
        let client = ChartGenerationClient { transport }

        do {
            _ = try await collect(await client.generate(audioURL: URL(fileURLWithPath: "/x.mp3"), star: 3))
            XCTFail("Expected an error.")
        } catch {
            XCTAssertEqual(error as? ChartGenerationError, .service(code: "audio_unreadable", message: "No such file"))
        }
    }

    func testStopSendsStopSessionAndEndsWithStopped() async throws {
        let transport = ScriptedChartTransport { request in
            switch request.payload {
            case .generateChart?:
                return [progress(.timing)]
            case .stopSession?:
                var status = Ensomi_Protocol_V1_StatusEvent()
                status.status = .stopped
                return [.status(status)]
            default:
                return []
            }
        }
        let client = ChartGenerationClient { transport }
        var iterator = await client.generate(audioURL: URL(fileURLWithPath: "/x.mp3"), star: 3).makeAsyncIterator()

        let first = try await iterator.next()
        XCTAssertEqual(first, .progress(.timing))
        await client.stop()
        do {
            _ = try await iterator.next()
            XCTFail("Expected the stream to throw.")
        } catch {
            XCTAssertEqual(error as? ChartGenerationError, .stopped)
        }

        XCTAssertEqual(transport.sent.count, 2)
        guard case .stopSession(let stop)? = transport.sent[1].payload else { return XCTFail("Expected a stop request.") }
        XCTAssertEqual(stop.reason, "client_stop")
        XCTAssertEqual(transport.sent[1].sessionID, transport.sent[0].sessionID)
    }

    @MainActor
    func testGenerationModelReportsProgressAndConvertsResult() async throws {
        let script: @Sendable (Envelope) -> [Envelope.OneOf_Payload] = { request in
            switch request.payload {
            case .capabilitiesRequest?:
                var capabilities = Ensomi_Protocol_V1_ModelCapabilities()
                capabilities.modelName = "Ensomi R2"
                capabilities.modelVersion = "r2-1.0"
                capabilities.minStar = 2
                capabilities.maxStarExclusive = 6
                return [.modelCapabilities(capabilities)]
            default:
                var result = Ensomi_Protocol_V1_ChartResult()
                result.chart = makeChart(duration: 4_000, [(0, 100, 600)])
                return [progress(.loading), .chartResult(result)]
            }
        }
        let model = Mania4KChartGenerationModel(client: ChartGenerationClient { ScriptedChartTransport(script: script) })

        await model.loadCapabilities()
        let range = model.starRange
        let description = model.modelDescription
        XCTAssertEqual(range, 2...5.9)
        XCTAssertEqual(description, "Ensomi R2 r2-1.0")

        let chart = await model.generate(audioURL: URL(fileURLWithPath: "/x.mp3"), star: 3)
        XCTAssertEqual(chart?.objects.map(\.kind), [.holdStart, .holdEnd])
        let state = model.state
        XCTAssertEqual(state, .idle)
    }
}
