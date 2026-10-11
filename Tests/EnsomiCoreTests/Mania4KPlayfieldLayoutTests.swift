import CoreGraphics
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

final class Mania4KPlayfieldLayoutTests: XCTestCase {
    private let size = CGSize(width: 360, height: 700)
    private let dark = { (_: Mania4KLane) in LaneBrightness(receptor: 0, lane: 0) }

    func testQuadIsTheGPUInstanceLayout() {
        XCTAssertEqual(MemoryLayout<Mania4KQuad>.stride, 80)
    }

    func testEmptyPlayfieldIsFourLanesWithReceptorsAndNothingLit() {
        let quads = Mania4KPlayfieldLayout.quads(frame: nil, brightness: dark, size: size)

        XCTAssertEqual(quads.count, 24)
        XCTAssertTrue(quads.allSatisfy { $0.flags.x == 0 })
        let laneBackgrounds = quads.filter { $0.shape.x == 8 && $0.shape.y == 0 }
        XCTAssertEqual(laneBackgrounds.count, 8)
        XCTAssertEqual(Set(laneBackgrounds.map(\.rect.x)), [0, 92, 184, 276])
        XCTAssertTrue(laneBackgrounds.allSatisfy { $0.rect.z == 84 && $0.rect.w == 700 && $0.clip == $0.rect })
    }

    func testTapCentersOnTheReceptorLineAtItsTime() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [object(.innerLeft, 1_000)])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)

        let note = try XCTUnwrap(quads.first { $0.shape.z == 10 })
        XCTAssertEqual(note.rect.x, 92 + 8)
        XCTAssertEqual(note.rect.y, 622 - 12, accuracy: 0.001)
        XCTAssertEqual(note.rect.z, 68)
        XCTAssertEqual(note.rect.w, 24)
        XCTAssertEqual(note.color.w, 0.96, accuracy: 0.0001)
        XCTAssertEqual(note.shape.w, 0.45 * 0.96, accuracy: 0.0001)
        XCTAssertEqual(note.clip, SIMD4<Float>(92, 0, 84, 700))
    }

    func testResolvedAndMissedNotesAreDimmed() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [
            object(.left, 1_000, state: .resolved),
            object(.right, 1_000, state: .missedButVisible)
        ])

        let notes = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size).filter { $0.shape.z == 10 }

        XCTAssertEqual(notes.map(\.color.w), [0.28, 0.36])
    }

    func testHoldIsABodyBetweenTailAndHeadDrawnBodyFirst() throws {
        let frame = playFrame(renderChartTimeMs: 1_000, objects: [object(.left, 1_000, endTimeMs: 1_500)])

        let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)
        let hold = quads.filter { $0.shape.x == 4 || $0.shape.z == 10 }

        XCTAssertEqual(hold.count, 3)
        let body = hold[0], tail = hold[1], head = hold[2]
        XCTAssertEqual(body.shape.x, 4)
        XCTAssertEqual(body.rect.z, 84 * 0.52, accuracy: 0.001)
        XCTAssertEqual(body.rect.y, 622 - 302, accuracy: 0.001)
        XCTAssertEqual(body.rect.w, 302, accuracy: 0.001)
        XCTAssertEqual(body.color.w, 0.42 * 0.96, accuracy: 0.0001)
        XCTAssertEqual(tail.rect.y + 9, 622 - 302, accuracy: 0.001)
        XCTAssertEqual(tail.rect.w, 18)
        XCTAssertEqual(head.rect.y + 11, 622, accuracy: 0.001)
        XCTAssertEqual(head.rect.w, 22)
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
        XCTAssertEqual(additive.map(\.color.w), [0.13, 0.5])
        XCTAssertTrue(additive.allSatisfy { $0.rect.x == 184 })
    }

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

    private func playFrame(renderChartTimeMs: Double, objects: [VisibleObject]) -> Mania4KPlayFrame {
        Mania4KPlayFrame(
            gameplayChartTimeMs: renderChartTimeMs,
            renderChartTimeMs: renderChartTimeMs,
            scrollTimeMs: 1_000,
            metadata: Mania4KChartMetadata(title: "Test", sourceDescription: "Test"),
            visibleObjects: objects,
            score: .zero,
            laneStates: [],
            latestJudgement: nil
        )
    }
}
