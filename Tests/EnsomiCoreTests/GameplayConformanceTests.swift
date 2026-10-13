import XCTest
@testable import EnsomiCore

/// Runs every fixture of the runtime repository's `conformance/gameplay` through `Mania4KGameplay`, the adapter the
/// play session model drives, and compares events, score, late inputs and the decided-through assertions. Pages go
/// into the session itself, as a `ChartScheduler` appends them.
final class GameplayConformanceTests: XCTestCase {
    func testEveryFixtureReproducesItsExpectedOutputThroughTheAdapter() throws {
        let fixtures = try fixtureFiles()
        XCTAssertFalse(fixtures.isEmpty)
        for url in fixtures {
            try run(url)
        }
    }

    private func fixtureFiles() throws -> [URL] {
        // .../ensomi/Tests/EnsomiCoreTests/GameplayConformanceTests.swift -> the app root, then the sibling checkout.
        let appRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let directory = appRoot.deletingLastPathComponent()
            .appendingPathComponent("ensomi-runtime/conformance/gameplay")
        guard FileManager.default.fileExists(atPath: directory.path) else {
            throw XCTSkip("No gameplay fixtures at \(directory.path): the runtime repository checked out beside the app provides them.")
        }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func run(_ url: URL) throws {
        let name = url.lastPathComponent
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let fixture = try decoder.decode(GameplayFixture.self, from: Data(contentsOf: url))

        let judge: Judge = ["A": .a, "B": .b, "C": .c, "D": .d, "E": .e][fixture.ruleset.judge]!
        let gameplay: Mania4KGameplay
        if let chart = fixture.chart {
            gameplay = Mania4KGameplay(session: try GameplaySession(
                chart: Chart(durationMs: chart.durationMs, objects: chart.objects.map(chartObject)),
                ruleset: Ruleset(judge: judge)
            ))
        } else {
            gameplay = Mania4KGameplay(session: .streaming(ruleset: Ruleset(judge: judge)))
        }

        let kinds: [String: TokenKind] = ["tap": .tap, "hold_start": .holdStart, "hold_end": .holdEnd]
        var events: [(event: JudgementEvent, step: Int)] = []
        for (index, step) in fixture.steps.enumerated() {
            var produced: [JudgementEvent] = []
            if let input = step.input {
                let phase: Phase = input.phase == "press" ? .press : .release
                produced = gameplay.input(InputEvent(lane: input.lane, phase: phase, timeMs: input.timeMs))
            } else if let watermark = step.advance {
                produced = gameplay.advance(to: watermark, frameTimeMs: watermark)
            } else if let page = step.page {
                // A page of objects becomes a start and an end token per hold.
                let tokens = page.tokens?.map { ChartToken(lane: $0.lane, timeMs: $0.timeMs, kind: kinds[$0.kind]!) }
                    ?? (page.objects ?? []).flatMap(objectTokens)
                let decided = try gameplay.session.appendTokens(tokens: tokens, coverageMs: page.coverageMs, endOfStream: page.endOfStream)
                events += decided.map { ($0, index) }
                continue
            } else if let decidedThrough = step.decidedThroughMs {
                XCTAssertEqual(gameplay.session.decidedThroughMs(), decidedThrough, "\(name): step \(index) decided through")
            }
            events += produced.map { ($0, index) }
            if let last = produced.last {
                XCTAssertEqual(gameplay.latestJudgement, last, "\(name): step \(index) latest judgement")
            }
        }

        XCTAssertEqual(events.count, fixture.expected.events.count, "\(name): event count")
        for (index, ((actual, step), expected)) in zip(events, fixture.expected.events).enumerated() {
            let label = "\(name): event \(index)"
            if let expectedStep = expected.step {
                XCTAssertEqual(step, expectedStep, label)
            }
            XCTAssertEqual(actual.object, expected.object, label)
            XCTAssertEqual(actual.lane, expected.lane, label)
            XCTAssertEqual(actual.part == .head ? "head" : "tail", expected.part, label)
            XCTAssertEqual(tierName(actual.tier), expected.tier, label)
            XCTAssertEqual(actual.timeMs, expected.timeMs, accuracy: 1e-6, label)
            XCTAssertEqual(actual.offsetMs == nil, expected.offsetMs == nil, label)
            if let actualOffset = actual.offsetMs, let expectedOffset = expected.offsetMs {
                XCTAssertEqual(actualOffset, expectedOffset, accuracy: 1e-6, label)
            }
        }

        let score = gameplay.session.score()
        let counts = fixture.expected.score.tierCounts
        XCTAssertEqual(
            [score.tierCounts.bigP, score.tierCounts.p1, score.tierCounts.p2, score.tierCounts.p3, score.tierCounts.g, score.tierCounts.m],
            [counts.bigP, counts.p1, counts.p2, counts.p3, counts.g, counts.m],
            "\(name): tier counts"
        )
        XCTAssertEqual(score.accuracy, fixture.expected.score.accuracy, accuracy: 1e-9, "\(name): accuracy")
        XCTAssertEqual(score.combo, fixture.expected.score.combo, "\(name): combo")
        XCTAssertEqual(score.maxCombo, fixture.expected.score.maxCombo, "\(name): max combo")
        XCTAssertEqual(
            score.suggestedAudioOffsetMs == nil,
            fixture.expected.score.suggestedAudioOffsetMs == nil,
            "\(name): suggested offset presence"
        )
        if let actualOffset = score.suggestedAudioOffsetMs, let expectedOffset = fixture.expected.score.suggestedAudioOffsetMs {
            XCTAssertEqual(actualOffset, expectedOffset, accuracy: 1e-9, "\(name): suggested offset")
        }
        XCTAssertEqual(gameplay.session.lateInputs(), fixture.expected.lateInputs, "\(name): late inputs")
        XCTAssertTrue(gameplay.session.isResolved(), "\(name): resolved")
    }

    private func chartObject(_ object: FixtureObject) -> ChartObject {
        ChartObject(lane: object.lane, startMs: object.startMs, endMs: object.endMs)
    }

    private func objectTokens(_ object: FixtureObject) -> [ChartToken] {
        let start = Double(object.startMs)
        guard let end = object.endMs else {
            return [ChartToken(lane: object.lane, timeMs: start, kind: .tap)]
        }
        return [
            ChartToken(lane: object.lane, timeMs: start, kind: .holdStart),
            ChartToken(lane: object.lane, timeMs: Double(end), kind: .holdEnd)
        ]
    }

    private func tierName(_ tier: Tier) -> String {
        switch tier {
        case .bigP: "bigP"
        case .p1: "p1"
        case .p2: "p2"
        case .p3: "p3"
        case .g: "g"
        case .m: "m"
        }
    }
}

private struct GameplayFixture: Decodable {
    var chart: FixtureChart?
    var ruleset: FixtureRuleset
    var steps: [FixtureStep]
    var expected: FixtureExpected
}

private struct FixtureChart: Decodable {
    var durationMs: UInt32
    var objects: [FixtureObject]
}

private struct FixtureObject: Decodable {
    var lane: UInt32
    var startMs: UInt32
    var endMs: UInt32?
}

private struct FixtureRuleset: Decodable {
    var judge: String
}

private struct FixtureStep: Decodable {
    var input: FixtureInput?
    var advance: Double?
    var page: FixturePage?
    var decidedThroughMs: Double?
}

private struct FixturePage: Decodable {
    var objects: [FixtureObject]?
    var tokens: [FixtureToken]?
    var coverageMs: Double
    var endOfStream: Bool
}

private struct FixtureToken: Decodable {
    var lane: UInt32
    var timeMs: Double
    var kind: String
}

private struct FixtureInput: Decodable {
    var lane: UInt32
    var phase: String
    var timeMs: Double
}

private struct FixtureExpected: Decodable {
    var events: [FixtureEvent]
    var score: FixtureScore
    var lateInputs: UInt32
}

private struct FixtureEvent: Decodable {
    var object: UInt32
    var lane: UInt32
    var part: String
    var tier: String
    var offsetMs: Double?
    var timeMs: Double
    var step: Int?
}

private struct FixtureScore: Decodable {
    var tierCounts: FixtureTierCounts
    var accuracy: Double
    var combo: UInt32
    var maxCombo: UInt32
    var suggestedAudioOffsetMs: Double?
}

private struct FixtureTierCounts: Decodable {
    var bigP: UInt32
    var p1: UInt32
    var p2: UInt32
    var p3: UInt32
    var g: UInt32
    var m: UInt32
}
