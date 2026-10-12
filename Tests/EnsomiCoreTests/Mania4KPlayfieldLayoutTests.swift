import CoreGraphics
import simd
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

final class Mania4KPlayfieldLayoutTests: XCTestCase {
    private let size = CGSize(width: 400, height: 700)
    // Lanes are 100 wide; the judgement line is 76 above the bottom.
    private let lineY: Float = 624
    private let dark = { (_: Mania4KLane) in LaneBrightness(receptor: 0, lane: 0) }

    func testQuadIsTheGPUInstanceLayout() {
        XCTAssertEqual(MemoryLayout<Mania4KQuad>.stride, 80)
    }

    func testEmptyPlayfieldIsFourAdjacentLanesOverTheKeyTrayWithNothingLit() {
        let quads = Mania4KPlayfieldLayout.quads(frame: nil, brightness: dark, size: size)

        XCTAssertTrue(quads.allSatisfy { $0.flags == .zero })
        let lanes = Array(quads.prefix(4))
        XCTAssertEqual(lanes.map(\.rect.x), [0, 100, 200, 300])
        XCTAssertTrue(lanes.allSatisfy { $0.rect.z == 100 && $0.rect.y == 0 && $0.rect.w == lineY })
        XCTAssertEqual(lanes.map(\.color.x), [laneA.x, laneB.x, laneA.x, laneB.x])
        let line = try? XCTUnwrap(quads.first { $0.color == SIMD4(Mania4KPlayPalette.action, 1) })
        XCTAssertEqual(line?.rect, SIMD4(0, lineY - 1.5, 400, 3))
    }

    func testTapCentersOnTheJudgementLineAtItsTime() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [object(.innerLeft, 1_000)])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)

        let note = try XCTUnwrap(quads.first { $0.color == SIMD4(Mania4KPlayPalette.tap, 1) })
        XCTAssertEqual(note.rect, SIMD4(105, lineY - 10, 90, 20))
        XCTAssertEqual(note.clip, SIMD4<Float>(100, 0, 100, lineY + Mania4KPlayfieldLayout.capInset))
    }

    func testHitNotesAreGoneAndMissedNotesGreyed() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [
            object(.left, 1_000, state: .resolved),
            object(.right, 900, state: .missedButVisible)
        ])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)

        XCTAssertFalse(quads.contains { $0.rect.x == 5 && $0.rect.w == 20 })
        let missed = try XCTUnwrap(quads.first { $0.rect.x == 305 && $0.rect.w == 20 })
        XCTAssertEqual(missed.color, SIMD4(Mania4KPlayPalette.missedNote, 0.5))
    }

    func testHoldIsANarrowerBodyWithATailCapDrawnBeforeItsHead() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [object(.left, 1_000, endTimeMs: 1_500)])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)
        let hold = quads.filter(isHoldPart)

        XCTAssertEqual(hold.count, 3)
        let body = hold[0], tail = hold[1], head = hold[2]
        let tailY = Float(noteYPosition(objectTimeMs: 1_500, renderTimeMs: 1_000, scrollTimeMs: 1_000, receptorY: Double(lineY)))
        XCTAssertEqual(body.color, SIMD4(Mania4KPlayPalette.holdBody, 0.6))
        XCTAssertEqual(body.rect.z, 90 * 0.72, accuracy: 0.001)
        XCTAssertEqual(body.rect.y, tailY, accuracy: 0.01)
        XCTAssertEqual(body.rect.w, lineY - tailY, accuracy: 0.01)
        XCTAssertEqual(tail.color, SIMD4(Mania4KPlayPalette.hold, 1))
        XCTAssertEqual(tail.rect.y + 4, tailY, accuracy: 0.01)
        XCTAssertEqual(head.rect.y + 10, lineY, accuracy: 0.001)
        XCTAssertEqual(head.shape.w, 0)
    }

    func testAHeldHoldKeepsItsHeadOnTheLineAndGlows() throws {
        let frame = playFrame(renderChartTimeMs: 1_200, objects: [object(.left, 1_000, endTimeMs: 1_500, state: .holding)])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)
        let hold = quads.filter(isHoldPart)

        XCTAssertEqual(hold.count, 3)
        XCTAssertEqual(hold[0].color.w, 0.85, accuracy: 0.0001)
        XCTAssertEqual(hold[0].rect.y + hold[0].rect.w, lineY, accuracy: 0.001)
        XCTAssertEqual(hold[2].rect.y + 10, lineY, accuracy: 0.001)
        XCTAssertGreaterThan(hold[2].shape.w, 0)
        XCTAssertGreaterThan(hold[2].clip.z, 100)
    }

    func testLaneLightsAreAdditiveAndOnlyPresentWhenLit() throws {
        let lit = { (lane: Mania4KLane) in
            lane == .innerRight
                ? LaneBrightness(receptor: 0.5, lane: 0.13)
                : LaneBrightness(receptor: 0, lane: 0)
        }

        let quads = Mania4KPlayfieldLayout.quads(frame: nil, brightness: lit, size: size)
        let additive = quads.filter { $0.flags.x == 1 }

        XCTAssertEqual(additive.count, 2)
        XCTAssertTrue(additive.allSatisfy { $0.rect.x >= 200 && $0.rect.x < 300 })
        let laneLight = try XCTUnwrap(additive.first { $0.flags.y == 1 })
        XCTAssertEqual(laneLight.color.w, 0.26, accuracy: 0.0001)
        XCTAssertEqual(laneLight.rect.y + laneLight.rect.w, lineY)
        let capLight = try XCTUnwrap(additive.first { $0.flags.y == 0 })
        XCTAssertEqual(capLight.color.w, 0.5)
        XCTAssertGreaterThan(capLight.rect.y, lineY)
    }

    func testHitFeedbackFollowsTheReportedEventsInTheirLanesAndFades() throws {
        let batches = [
            JudgementEventBatch(id: 0, chartTimeMs: 950, events: [event(lane: 2, tier: .bigP, atMs: 950)]),
            JudgementEventBatch(id: 1, chartTimeMs: 990, events: [event(lane: 0, tier: .p1, atMs: 990), event(lane: 3, tier: .m, atMs: 990)])
        ]
        func feedback(atMs timeMs: Double, reduceMotion: Bool = false) -> [Mania4KQuad] {
            Mania4KPlayfieldLayout.quads(
                frame: playFrame(renderChartTimeMs: timeMs, objects: []), brightness: dark, judgements: batches,
                size: size, reduceMotion: reduceMotion
            )
            .filter { $0.flags.x == 1 || simd_make_float3($0.color) == Mania4KPlayPalette.judgement(.miss) }
        }

        let early = feedback(atMs: 1_000)
        let perfect = early.filter { $0.rect.x >= 200 && $0.rect.x < 300 }
        XCTAssertEqual(perfect.count, 2)
        XCTAssertTrue(perfect.allSatisfy { simd_make_float3($0.color) == Mania4KPlayPalette.judgement(.perfect) })
        let flash = try XCTUnwrap(perfect.first { $0.flags.y == 0 })
        XCTAssertEqual(flash.rect.y + flash.rect.w / 2, lineY, accuracy: 0.001)
        let good = early.filter { $0.rect.x < 100 }
        XCTAssertTrue(good.allSatisfy { simd_make_float3($0.color) == Mania4KPlayPalette.judgement(.good) })
        XCTAssertEqual(good.count, 2)
        let miss = early.filter { $0.rect.x >= 300 }
        XCTAssertEqual(miss.count, 2)
        XCTAssertTrue(miss.allSatisfy { simd_make_float3($0.color) == Mania4KPlayPalette.judgement(.miss) })

        let later = feedback(atMs: 1_000 + 200)
        XCTAssertTrue(later.filter { $0.rect.x >= 200 && $0.rect.x < 300 }.isEmpty)
        XCTAssertFalse(later.filter { $0.rect.x >= 300 }.isEmpty)
        XCTAssertTrue(feedback(atMs: 1_400).isEmpty)

        let still = feedback(atMs: 1_000, reduceMotion: true).filter { $0.rect.x >= 200 && $0.rect.x < 300 }
        XCTAssertEqual(still.count, 1)
        XCTAssertEqual(still[0].rect, flash.rect)
    }

    func testProgressFollowsChartTimeOverTheDuration() throws {
        let frame = playFrame(renderChartTimeMs: 3_000, objects: [], durationMs: 12_000)

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)

        let progress = try XCTUnwrap(quads.last)
        XCTAssertEqual(progress.color, SIMD4(Mania4KPlayPalette.action, 1))
        XCTAssertEqual(progress.rect, SIMD4(0, 0, 100, Mania4KPlayfieldLayout.progressHeight))
        XCTAssertFalse(
            Mania4KPlayfieldLayout.quads(frame: playFrame(renderChartTimeMs: 3_000, objects: []), brightness: dark, size: size)
                .contains { $0.rect.y == 0 && $0.rect.w == Mania4KPlayfieldLayout.progressHeight }
        )
    }

    private func isHoldPart(_ quad: Mania4KQuad) -> Bool {
        let rgb = simd_make_float3(quad.color)
        return quad.flags == .zero && (rgb == Mania4KPlayPalette.hold || rgb == Mania4KPlayPalette.holdBody)
    }

    private var laneA: SIMD3<Float> { Mania4KPlayPalette.laneA }
    private var laneB: SIMD3<Float> { Mania4KPlayPalette.laneB }

    private func object(
        _ lane: Mania4KLane,
        _ startTimeMs: Double,
        endTimeMs: Double? = nil,
        state: VisibleState = .waiting
    ) -> VisibleObject {
        VisibleObject(
            id: 0,
            lane: lane.index,
            startTimeMs: startTimeMs,
            endTimeMs: endTimeMs,
            state: state
        )
    }

    private func event(lane: UInt32, tier: Tier, atMs timeMs: Double) -> JudgementEvent {
        JudgementEvent(id: 0, object: 0, lane: lane, part: .head, tier: tier, offsetMs: 0, timeMs: timeMs, objectTimeMs: timeMs)
    }

    private func playFrame(renderChartTimeMs: Double, objects: [VisibleObject], durationMs: Double? = nil) -> Mania4KPlayFrame {
        Mania4KPlayFrame(
            gameplayChartTimeMs: renderChartTimeMs,
            renderChartTimeMs: renderChartTimeMs,
            scrollTimeMs: 1_000,
            metadata: Mania4KChartMetadata(title: "Test", sourceDescription: "Test", durationMs: durationMs),
            visibleObjects: objects,
            score: .zero,
            laneStates: [],
            latestJudgement: nil
        )
    }
}
