import AVFoundation
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
        XCTAssertTrue(request.controls.sections.isEmpty)
        XCTAssertEqual(request.controls.longNotes, .unspecified)
    }

    func testSectionsMapToChartControlsInSentRequest() async throws {
        let transport = ScriptedChartTransport { _ in [.chartResult(.init())] }
        let client = ChartGenerationClient { transport }
        var sections: [ChartGenerationSection] = []
        let kinds: [ChartGenerationSection.Kind] = [.jumpstream, .chordstream, .stream, .jack, .star]
        for (index, kind) in kinds.enumerated() {
            var section = ChartGenerationSection(kind: kind, star: 4.2)
            section.start = ChartGenerationSection.timeLabel(milliseconds: Double(index * 8_000))
            section.end = ChartGenerationSection.timeLabel(milliseconds: Double((index + 1) * 8_000))
            section.strength = Double(index) / 3
            sections.append(section)
        }
        let controls = try ChartGenerationSection.controls(for: sections, durationMS: 40_000, starRange: 2...5.9)
        _ = try await collect(await client.generate(audioURL: URL(fileURLWithPath: "/song.wav"), star: 3, controls: controls))

        guard case .generateChart(let request)? = transport.sent.first?.payload else { return XCTFail("Expected a request") }
        XCTAssertEqual(request.controls.longNotes, .unspecified)
        XCTAssertEqual(request.controls.sections.map(\.startMs), [0, 8_000, 16_000, 24_000, 32_000])
        XCTAssertEqual(request.controls.sections.map(\.endMs), [8_000, 16_000, 24_000, 32_000, 40_000])
        let patterns: [Ensomi_Protocol_V1_PatternKind] = [.jumpstream, .chordstream, .stream, .jack]
        for (index, kind) in patterns.enumerated() {
            guard case .pattern(let pattern)? = request.controls.sections[index].target else { return XCTFail("Expected pattern target") }
            XCTAssertEqual(pattern.kind, kind)
            XCTAssertEqual(pattern.strength, Double(index) / 3)
        }
        XCTAssertEqual(request.controls.sections.last?.target, .star(4.2))
    }

    func testSectionValidationAcceptsLengthBoundsAndAdjacentUnsortedSpans() throws {
        for kind in [ChartGenerationSection.Kind.jumpstream, .star] {
            let sections = [section("00:08", "00:40", kind: kind), section("00:00", "00:08", kind: kind)]
            XCTAssertNoThrow(try ChartGenerationSection.controls(for: sections, durationMS: 40_000, starRange: 2...5.9), "\(kind)")
        }
    }

    /// The runtime refuses a section outside 8–32 seconds whatever its target, so the app refuses it before sending.
    func testSectionValidationRefusesAnyTargetOutsideEightToThirtyTwoSeconds() {
        for kind in [ChartGenerationSection.Kind.jumpstream, .star] {
            for (start, end) in [("00:00", "00:07"), ("00:00", "00:33"), ("00:00", "01:00")] {
                XCTAssertThrowsError(try ChartGenerationSection.controls(for: [section(start, end, kind: kind)], durationMS: 60_000, starRange: 2...5.9)) {
                    XCTAssertEqual($0.localizedDescription, "Section 1: a section must last 8–32 seconds.", "\(kind) \(start)–\(end)")
                }
            }
        }
    }

    func testSectionValidationRejectsMalformedTimesLengthsAndSongBounds() {
        let invalid = [
            section("", "00:08"), section("-1:00", "00:08"), section("00:60", "01:08"),
            section("00:0x", "00:08"), section("0.5", "00:08"),
            section("999999999999999999999:00", "00:08"),
            section("00:08", "00:08"), section("00:09", "00:08"),
            section("00:00", "00:07"), section("00:00", "00:33"),
            section("00:53", "01:01"), section("00:59", "01:01", kind: .star)
        ]
        for section in invalid {
            XCTAssertThrowsError(try ChartGenerationSection.controls(for: [section], durationMS: 60_000, starRange: 2...5.9), "\(section)")
        }
    }

    func testSectionValidationRejectsOverlapAcrossTargetKinds() {
        for kind in [ChartGenerationSection.Kind.star, .chordstream] {
            XCTAssertThrowsError(try ChartGenerationSection.controls(
                for: [section("00:08", "00:16", kind: kind), section("00:00", "00:09")],
                durationMS: 60_000, starRange: 2...5.9))
        }
    }

    func testSectionValidationChecksStrengthAndSongStarRange() {
        for strength in [-0.1, 1.1, .nan, .infinity] {
            var invalid = section("00:00", "00:08")
            invalid.strength = strength
            XCTAssertThrowsError(try ChartGenerationSection.controls(for: [invalid], durationMS: 60_000, starRange: 2...5.9))
        }
        for star in [1.9, 6, .nan, .infinity] {
            var invalid = section("00:00", "00:08", kind: .star)
            invalid.star = star
            XCTAssertThrowsError(try ChartGenerationSection.controls(for: [invalid], durationMS: 60_000, starRange: 2...5.9))
        }
        for star in [2.0, 5.9] {
            var valid = section("00:00", "00:08", kind: .star)
            valid.star = star
            XCTAssertNoThrow(try ChartGenerationSection.controls(for: [valid], durationMS: 60_000, starRange: 2...5.9))
        }
    }

    private func section(_ start: String, _ end: String, kind: ChartGenerationSection.Kind = .jumpstream) -> ChartGenerationSection {
        var section = ChartGenerationSection(kind: kind, star: 3.5)
        section.start = start
        section.end = end
        return section
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
                result.receipt.modelName = "Ensomi R2"
                result.receipt.modelVersion = "r2-1.0"
                result.receipt.seed = 1_234_567
                result.receipt.star = 3
                result.receipt.oszPath = "/charts/song.osz"
                return [progress(.loading), .chartResult(result)]
            }
        }
        let model = serverModel(ChartGenerationClient { ScriptedChartTransport(script: script) })

        await model.loadCapabilities()
        let range = model.starRange
        let description = model.modelDescription
        XCTAssertEqual(range, 2...5.9)
        XCTAssertEqual(description, "Ensomi R2 r2-1.0")

        let audioURL = try makeAudioFile()
        defer { try? FileManager.default.removeItem(at: audioURL) }
        let generated = await model.generate(audioURL: audioURL, star: 3, artist: "Band")
        let result = try XCTUnwrap(generated)
        XCTAssertEqual(result.chart.objects.map(\.kind), [.holdStart, .holdEnd])
        XCTAssertEqual(model.state, .finished(result))
        XCTAssertEqual(result.seed, 1_234_567, "the seed the server used, though none was asked for")
        XCTAssertEqual(result.model, "Ensomi R2 r2-1.0")
        XCTAssertNil(result.osu)
        XCTAssertEqual(result.serverOszPath, "/charts/song.osz")
        XCTAssertEqual(result.exportName, "Band - \(audioURL.deletingPathExtension().lastPathComponent) (ensomi R2 s1234567)")
        XCTAssertEqual(try XCTUnwrap(model.audioDurationMS), 4_000, accuracy: 1)
    }

    @MainActor
    func testCapabilitiesFilterSectionKindsAndExperimentalLabels() async {
        let transport = ScriptedChartTransport { _ in
            var capabilities = testCapabilities()
            let controls: [(String, Ensomi_Protocol_V1_ControlStatus)] = [
                ("jumpstream", .works), ("chordstream", .works), ("stream", .experimental),
                ("jack", .experimental), ("section_star", .experimental), ("chordjack", .notWorking),
                ("long_notes", .notWorking), ("star", .works), ("unknown", .works)
            ]
            capabilities.controls = controls.map { name, status in
                .with { $0.name = name; $0.status = status; $0.note = "Service caveat" }
            }
            return [.modelCapabilities(capabilities)]
        }
        let model = serverModel(ChartGenerationClient { transport })
        await model.loadCapabilities()
        XCTAssertEqual(model.sectionOptions.map(\.kind), [.jumpstream, .chordstream, .stream, .jack, .star])
        XCTAssertEqual(model.sectionOptions.map(\.isExperimental), [false, false, true, true, true])
        XCTAssertEqual(model.sectionOptions.last?.title, "Star target (Experimental)")
        XCTAssertEqual(model.sectionOptions.last?.note, "Service caveat")
    }

    @MainActor
    func testInvalidSectionsNeverSendGenerationRequest() async throws {
        let transport = ScriptedChartTransport { _ in [.modelCapabilities(testCapabilities())] }
        let model = serverModel(ChartGenerationClient { transport })
        let audioURL = try makeAudioFile()
        defer { try? FileManager.default.removeItem(at: audioURL) }
        await model.loadCapabilities()
        model.loadAudioDuration(for: audioURL)
        model.sections = [section("00:00", "00:08")]
        XCTAssertFalse(model.canGenerate(star: 3))
        XCTAssertTrue(try XCTUnwrap(model.sectionValidationMessage).contains("inside the song"))
        let result = await model.generate(audioURL: audioURL, star: 3)
        XCTAssertNil(result)
        XCTAssertEqual(transport.sent.count, 1)
    }

    @MainActor
    func testGenerationModelForwardsValidatedSections() async throws {
        let capabilitiesTransport = ScriptedChartTransport { _ in [.modelCapabilities(testCapabilities())] }
        let generationTransport = ScriptedChartTransport { _ in [.chartResult(.init())] }
        let model = serverModel(ChartGenerationClient {
            capabilitiesTransport.sent.isEmpty ? capabilitiesTransport : generationTransport
        })
        let audioURL = try makeAudioFile(durationSeconds: 8)
        defer { try? FileManager.default.removeItem(at: audioURL) }
        await model.loadCapabilities()
        model.loadAudioDuration(for: audioURL)
        model.sections = [section("00:00", "00:08")]
        XCTAssertTrue(model.canGenerate(star: 3))
        _ = await model.generate(audioURL: audioURL, star: 3)
        guard case .generateChart(let request)? = generationTransport.sent.first?.payload else { return XCTFail("Expected generation request") }
        XCTAssertEqual(request.controls.sections.count, 1)
        XCTAssertEqual(request.controls.sections[0].endMs, 8_000)
        XCTAssertEqual(request.controls.sections[0].pattern.kind, .jumpstream)
        XCTAssertEqual(request.controls.sections[0].pattern.strength, 0.5)
    }

    @MainActor
    func testGenerationModelDisplaysServiceRejectionMessage() async throws {
        let model = serverModel(ChartGenerationClient {
            ScriptedChartTransport { request in
                if case .capabilitiesRequest? = request.payload { return [.modelCapabilities(testCapabilities())] }
                return [progress(.timing), error("unsupported_control", "These sections cannot be generated.")]
            }
        })
        let audioURL = try makeAudioFile()
        defer { try? FileManager.default.removeItem(at: audioURL) }
        await model.loadCapabilities()
        let result = await model.generate(audioURL: audioURL, star: 3)
        XCTAssertNil(result)
        XCTAssertEqual(model.state, .failed("These sections cannot be generated."))
        XCTAssertFalse(model.isGenerating)
    }

    @MainActor
    func testTheSeedFieldIsSentAndMustBeASeed() async throws {
        let capabilitiesTransport = ScriptedChartTransport { _ in [.modelCapabilities(testCapabilities())] }
        let generationTransport = ScriptedChartTransport { _ in [.chartResult(.init())] }
        let model = serverModel(ChartGenerationClient {
            capabilitiesTransport.sent.isEmpty ? capabilitiesTransport : generationTransport
        })
        let audioURL = try makeAudioFile()
        defer { try? FileManager.default.removeItem(at: audioURL) }
        await model.loadCapabilities()
        model.loadAudioDuration(for: audioURL)

        model.seedText = "-1"
        XCTAssertNil(model.seed)
        XCTAssertNotNil(model.seedValidationMessage)
        XCTAssertFalse(model.canGenerate(star: 3))
        model.seedText = " 18446744073709551615 "
        XCTAssertNil(model.seedValidationMessage)
        _ = await model.generate(audioURL: audioURL, star: 3, seed: model.seed)

        guard case .generateChart(let request)? = generationTransport.sent.first?.payload else { return XCTFail("Expected generation request") }
        XCTAssertEqual(request.seed, .max)
    }

    @MainActor
    func testStoppingEndsInTheStoppedState() async throws {
        let model = serverModel(ChartGenerationClient {
            ScriptedChartTransport { request in
                switch request.payload {
                case .capabilitiesRequest?:
                    return [.modelCapabilities(testCapabilities())]
                case .stopSession?:
                    var status = Ensomi_Protocol_V1_StatusEvent()
                    status.status = .stopped
                    return [.status(status)]
                default:
                    return [progress(.timing)]
                }
            }
        })
        let audioURL = try makeAudioFile()
        defer { try? FileManager.default.removeItem(at: audioURL) }
        await model.loadCapabilities()

        let generation = Task { await model.generate(audioURL: audioURL, star: 3) }
        let deadline = Date().addingTimeInterval(10)
        while model.state != .generating(phase: .timing), Date() < deadline { await Task.yield() }
        await model.stop()

        let result = await generation.value
        XCTAssertNil(result)
        XCTAssertEqual(model.state, .stopped)
    }

    private func makeAudioFile(durationSeconds: UInt32 = 4) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).wav")
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 8_000, channels: 1))
        let frameCount = durationSeconds * 8_000
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount))
        buffer.frameLength = frameCount
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        samples.update(repeating: 0, count: Int(buffer.frameLength))
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        return url
    }
}

private func testCapabilities() -> ChartGenerationCapabilities {
    .with {
        $0.minStar = 2
        $0.maxStarExclusive = 6
        $0.controls = [.with { $0.name = "jumpstream"; $0.status = .works }]
    }
}
