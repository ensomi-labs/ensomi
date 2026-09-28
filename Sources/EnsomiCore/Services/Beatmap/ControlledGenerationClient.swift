import Foundation

public struct GenerationControls: Codable, Equatable, Sendable {
    public var difficulty: Double?
    public var style: [String: Double]
    public var lnFraction: Double?

    public init(difficulty: Double? = nil, style: [String: Double] = [:], lnFraction: Double? = nil) {
        self.difficulty = difficulty
        self.style = style
        self.lnFraction = lnFraction
    }
    enum CodingKeys: String, CodingKey { case difficulty, style; case lnFraction = "ln_fraction" }
}

public struct GenerationServiceHealth: Decodable, Sendable {
    public let protocolVersion: String
    public let ready: Bool
    public let styleNames: [String]
    public let checkpointSHA256: String
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol", ready
        case styleNames = "style_names", checkpointSHA256 = "checkpoint_sha256"
    }
}

struct GenerationSessionReceipt: Decodable, Sendable {
    let protocolVersion: String
    let sessionID: String
    let durationMS: Double
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol", sessionID = "session_id", durationMS = "duration_ms"
    }
}

struct GenerationWindow: Decodable, Sendable {
    struct Row: Decodable, Sendable {
        let timeMS: Double
        let actions: [Int]
        enum CodingKeys: String, CodingKey { case timeMS = "time_ms", actions }
    }
    let protocolVersion: String
    let sessionID: String
    let sequence: Int
    let coverageMS: Double
    let endOfStream: Bool
    let rows: [Row]
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol", sessionID = "session_id", sequence, rows
        case coverageMS = "coverage_ms", endOfStream = "end_of_stream"
    }

    func objects(after previousCoverage: Double) throws -> [Mania4KHitObject] {
        guard protocolVersion == ControlledGenerationClient.protocolVersion,
              coverageMS.isFinite, coverageMS >= 0, coverageMS >= previousCoverage else {
            throw GenerationServiceFailure("Invalid generation coverage.")
        }
        var previous = previousCoverage
        var result: [Mania4KHitObject] = []
        for row in rows {
            guard row.timeMS.isFinite, row.timeMS >= 0, row.timeMS.rounded() == row.timeMS,
                  row.timeMS > previous, row.timeMS <= coverageMS,
                  row.actions.count == 4, row.actions.contains(where: { $0 != 0 }),
                  row.actions.allSatisfy({ (0...3).contains($0) }) else {
                throw GenerationServiceFailure("Invalid complete row in generation window.")
            }
            for (index, action) in row.actions.enumerated() where action != 0 {
                let kind: Mania4KHitObjectKind = action == 1 ? .tap : (action == 2 ? .holdStart : .holdEnd)
                result.append(Mania4KHitObject(lane: Mania4KLane(rawValue: index)!, timeMs: row.timeMS, kind: kind))
            }
            previous = row.timeMS
        }
        return result
    }
}

public struct GenerationServiceFailure: LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

public struct ControlledGenerationClient: Sendable {
    public static let protocolVersion = "ensomi-demo/v1"
    public static let defaultURL = URL(string: "http://127.0.0.1:8766")!
    public let baseURL: URL
    private let session: URLSession
    private struct ErrorBody: Decodable { let error: String }

    public init(baseURL: URL = Self.defaultURL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    public func health() async throws -> GenerationServiceHealth {
        let health: GenerationServiceHealth = try await request("health", timeout: 5)
        guard health.ready, health.protocolVersion == Self.protocolVersion else {
            throw GenerationServiceFailure("The backend has not loaded a compatible generation model.")
        }
        return health
    }

    func create(id: String, audioURL: URL, controls: GenerationControls) async throws -> GenerationSessionReceipt {
        struct Body: Encodable {
            let session_id: String
            let audio_path: String
            let controls: GenerationControls
        }
        let receipt: GenerationSessionReceipt = try await request("sessions", method: "POST",
            body: JSONEncoder().encode(Body(session_id: id, audio_path: audioURL.path, controls: controls)))
        guard receipt.sessionID == id, receipt.protocolVersion == Self.protocolVersion,
              receipt.durationMS.isFinite, receipt.durationMS > 0 else {
            throw GenerationServiceFailure("Invalid generation session receipt.")
        }
        return receipt
    }

    func window(id: String, sequence: Int, throughMS: Double) async throws -> GenerationWindow {
        struct Body: Encodable { let after_sequence: Int; let through_ms: Double }
        return try await request("sessions/\(id)/window", method: "POST",
            body: JSONEncoder().encode(Body(after_sequence: sequence, through_ms: throughMS)))
    }

    func cancel(id: String) async {
        struct Receipt: Decodable { let cancelled: Bool }
        let _: Receipt? = try? await request("sessions/\(id)", method: "DELETE", timeout: 5)
    }

    private func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil,
                                       timeout: TimeInterval = 90) async throws -> T {
        guard ["http", "https"].contains(baseURL.scheme), baseURL.host != nil else {
            throw GenerationServiceFailure("Enter an HTTP backend address.")
        }
        var request = URLRequest(url: baseURL.appendingPathComponent(path), timeoutInterval: timeout)
        request.httpMethod = method
        request.httpBody = body
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw GenerationServiceFailure("The backend returned no HTTP response.")
        }
        guard response.statusCode == 200 else {
            let detail = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.error
            throw GenerationServiceFailure(detail ?? "Backend HTTP \(response.statusCode).")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}

/// Owns one producer session. Reads use confirmed windows; refill runs independently of rendering.
public actor ControlledGenerationStream: Mania4KHitObjectStreaming {
    private let client: ControlledGenerationClient
    private let audioURL: URL
    private let controls: GenerationControls
    private let title: String
    private let referenceTime: BufferedInferenceMania4KHitObjectStream.ReferenceTimeProvider
    private let buffer: BufferedInferenceMania4KHitObjectStream
    private let identity = UUID().uuidString
    private var pumpTask: Task<Void, Never>?
    private var cancelled = false
    private var sequence = 0
    private var coverage = -Double.infinity
    private var durationMS: Double = 0

    public init(client: ControlledGenerationClient, audioURL: URL, title: String, controls: GenerationControls,
                referenceTime: @escaping BufferedInferenceMania4KHitObjectStream.ReferenceTimeProvider) {
        self.client = client
        self.audioURL = audioURL
        self.title = title
        self.controls = controls
        self.referenceTime = referenceTime
        self.buffer = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(title: title, sourceDescription: "H/R/R1 generation"),
            referenceTimeProvider: referenceTime)
    }

    public func prepare() async throws -> Mania4KChartMetadata {
        do {
            let receipt = try await client.create(id: identity, audioURL: audioURL, controls: controls)
            try Task.checkCancellation()
            guard !cancelled else { throw CancellationError() }
            durationMS = receipt.durationMS
            pumpTask = Task { [weak self] in await self?.pump() }
            return Mania4KChartMetadata(title: title, sourceDescription: "H/R/R1 generation", durationMs: durationMS)
        } catch {
            await cancel()
            throw error
        }
    }

    public func read(after cursor: Mania4KHitObjectStreamCursor?, throughChartTimeMs: Double, limit: Int) async throws -> Mania4KHitObjectBatch {
        try await buffer.read(after: cursor, throughChartTimeMs: throughChartTimeMs, limit: limit)
    }

    public func cancel() async {
        cancelled = true
        pumpTask?.cancel()
        pumpTask = nil
        await buffer.cancel()
        await client.cancel(id: identity)
    }

    private func pump() async {
        var refilling = true
        do {
            while !cancelled {
                try Task.checkCancellation()
                guard let now = await referenceTime(), now.isFinite else {
                    throw GenerationServiceFailure("The synchronized audio clock is unavailable.")
                }
                if !refilling, coverage - now > 6_000 {
                    try await Task.sleep(for: .milliseconds(100))
                    continue
                }
                refilling = true
                let target = min(durationMS, max(0, now) + 8_000)
                let window = try await client.window(id: identity, sequence: sequence, throughMS: target)
                try Task.checkCancellation()
                guard !cancelled, window.sessionID == identity, window.sequence == sequence + 1,
                      window.coverageMS <= durationMS,
                      window.coverageMS > coverage || window.endOfStream,
                      !window.endOfStream || window.coverageMS == durationMS else {
                    throw GenerationServiceFailure("The generation window belongs to an invalid session or sequence.")
                }
                let objects = try window.objects(after: coverage)
                try await buffer.publish(objects, completeThroughTimeMS: window.coverageMS)
                coverage = window.coverageMS
                sequence = window.sequence
                if window.endOfStream {
                    await buffer.finish(completeThroughTimeMS: coverage)
                    await client.cancel(id: identity)
                    return
                }
                if coverage >= target { refilling = false }
            }
        } catch is CancellationError {
            return
        } catch {
            if !cancelled { await buffer.fail(error.localizedDescription) }
            await client.cancel(id: identity)
        }
    }
}
