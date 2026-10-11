import EnsomiProtocol
import Foundation

public typealias ChartGenerationCapabilities = Ensomi_Protocol_V1_ModelCapabilities
public typealias ChartGenerationResult = Ensomi_Protocol_V1_ChartResult
public typealias ChartGenerationPhase = Ensomi_Protocol_V1_GenerationPhase
public typealias ChartGenerationControls = Ensomi_Protocol_V1_ChartControls

public enum ChartGenerationEvent: Equatable, Sendable {
    case progress(ChartGenerationPhase)
    case result(ChartGenerationResult)
}

public enum ChartGenerationError: Error, Equatable, LocalizedError {
    /// The service answered with an `ErrorEvent`; `code` is its string code, such as `audio_unreadable`.
    case service(code: String, message: String)
    case stopped
    case unexpectedFrame(String)

    public var errorDescription: String? {
        switch self {
        case .service(let code, let message):
            return message.isEmpty ? code : message
        case .stopped:
            return "Generation was stopped."
        case .unexpectedFrame(let description):
            return "Unexpected reply from the chart service: \(description)."
        }
    }
}

/// Binary websocket frames; one transport carries one connection.
public protocol ChartGenerationTransport: Sendable {
    func send(_ data: Data) async throws
    func receive() async throws -> Data
    func close()
}

final class WebSocketChartGenerationTransport: ChartGenerationTransport, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(url: URL, urlSession: URLSession = .shared) {
        task = urlSession.webSocketTask(with: url)
        // A chart with its receipt is far below this; the default 1 MiB limit is not a safe bound.
        task.maximumMessageSize = 16 * 1024 * 1024
        task.resume()
    }

    func send(_ data: Data) async throws {
        try await task.send(.data(data))
    }

    func receive() async throws -> Data {
        switch try await task.receive() {
        case .data(let data):
            return data
        default:
            throw InferenceEndpointProtocolError.invalidBinaryFrame
        }
    }

    func close() {
        task.cancel(with: .goingAway, reason: nil)
    }
}

/// Requests a complete chart from the ensomi-model service. Each call uses its own connection.
public actor ChartGenerationClient {
    /// `ENSOMI_SERVICE_URL`, then the `EnsomiServiceURL` default, then `ws://localhost:8765`.
    public static var defaultServiceURL: URL {
        let configured = ProcessInfo.processInfo.environment["ENSOMI_SERVICE_URL"]
            ?? UserDefaults.standard.string(forKey: "EnsomiServiceURL")
        return configured.flatMap { URL(string: $0) } ?? URL(string: "ws://localhost:8765")!
    }

    private let makeTransport: @Sendable () -> any ChartGenerationTransport
    private var activeSession: (id: String, transport: any ChartGenerationTransport)?

    public init(serviceURL: URL = ChartGenerationClient.defaultServiceURL) {
        makeTransport = { WebSocketChartGenerationTransport(url: serviceURL) }
    }

    public init(transportFactory: @escaping @Sendable () -> any ChartGenerationTransport) {
        makeTransport = transportFactory
    }

    public func capabilities() async throws -> ChartGenerationCapabilities {
        let transport = makeTransport()
        defer { transport.close() }
        try await transport.send(Self.frame(sessionID: "", payload: .capabilitiesRequest(.init())))
        while true {
            let envelope = try await Self.receiveEnvelope(transport)
            switch envelope.payload {
            case .modelCapabilities(let capabilities)?:
                return capabilities
            case .error(let error)?:
                throw ChartGenerationError.service(code: error.code, message: error.message)
            default:
                continue
            }
        }
    }

    /// Yields progress phases, then one result, or throws `ChartGenerationError`.
    public func generate(
        audioURL: URL,
        star: Double,
        controls: ChartGenerationControls = .init(),
        seed: UInt64? = nil,
        title: String? = nil,
        artist: String? = nil
    ) -> AsyncThrowingStream<ChartGenerationEvent, Error> {
        let request = Ensomi_Protocol_V1_GenerateChartRequest.with {
            $0.audio.localPath = audioURL.path
            $0.star = star
            $0.controls = controls
            if let seed { $0.seed = seed }
            if let title { $0.title = title }
            if let artist { $0.artist = artist }
        }

        let sessionID = UUID().uuidString
        let transport = makeTransport()
        activeSession = (sessionID, transport)
        return AsyncThrowingStream { continuation in
            let task = Task {
                defer { transport.close() }
                do {
                    try await transport.send(Self.frame(sessionID: sessionID, payload: .generateChart(request)))
                    while true {
                        let envelope = try await Self.receiveEnvelope(transport)
                        guard envelope.sessionID == sessionID else { continue }
                        switch envelope.payload {
                        case .generationProgress(let progress)?:
                            continuation.yield(.progress(progress.phase))
                        case .chartResult(let result)?:
                            continuation.yield(.result(result))
                            continuation.finish()
                            return
                        case .error(let error)?:
                            throw ChartGenerationError.service(code: error.code, message: error.message)
                        case .status(let status)? where status.status == .stopped:
                            throw ChartGenerationError.stopped
                        default:
                            continue
                        }
                    }
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Asks the service to cancel the running generation; the stream then throws `.stopped`.
    public func stop() async {
        guard let (sessionID, transport) = activeSession else { return }
        var request = Ensomi_Protocol_V1_StopSessionRequest()
        request.reason = "client_stop"
        try? await transport.send(Self.frame(sessionID: sessionID, payload: .stopSession(request)))
    }

    private static func frame(
        sessionID: String,
        payload: Ensomi_Protocol_V1_Envelope.OneOf_Payload
    ) throws -> Data {
        try InferenceEndpointProtocolCodec.envelope(
            sessionID: sessionID,
            payload: payload,
            metadata: InferenceEndpointEnvelopeMetadata()
        ).serializedData()
    }

    private static func receiveEnvelope(_ transport: any ChartGenerationTransport) async throws -> Ensomi_Protocol_V1_Envelope {
        try Ensomi_Protocol_V1_Envelope(serializedBytes: await transport.receive())
    }
}
