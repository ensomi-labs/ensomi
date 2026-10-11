import CoreGraphics
import EnsomiCore
import simd

/// One rounded rectangle for the GPU, the primitive the playfield is drawn from. Five float vectors, 80 bytes, so a
/// layout from another source can fill the same array.
struct Mania4KQuad: Equatable {
    /// x, y, width, height in points from the playfield's top left.
    var rect: SIMD4<Float>
    /// Straight (not premultiplied) RGBA.
    var color: SIMD4<Float>
    /// Corner radius, stroke width (0 fills the shape), glow radius, glow alpha.
    var shape: SIMD4<Float>
    /// x, y, width, height; nothing is drawn outside.
    var clip: SIMD4<Float>
    /// x is 1 to add the colour to what is below instead of blending over it.
    var flags: SIMD4<Float>

    init(
        rect: SIMD4<Float>,
        color: SIMD4<Float>,
        cornerRadius: Float = 0,
        strokeWidth: Float = 0,
        glowRadius: Float = 0,
        glowAlpha: Float = 0,
        clip: SIMD4<Float>,
        additive: Bool = false
    ) {
        self.rect = rect
        self.color = color
        self.shape = SIMD4(cornerRadius, strokeWidth, glowRadius, glowAlpha)
        self.clip = clip
        self.flags = SIMD4(additive ? 1 : 0, 0, 0, 0)
    }
}

/// Lays the four lanes, their notes and the receptors out as quads, with the proportions of the SwiftUI playfield
/// it replaced. Key labels are drawn by SwiftUI over the receptors (`receptorHeight` tall at the bottom of each lane).
enum Mania4KPlayfieldLayout {
    static let laneSpacing: Float = 8
    static let receptorHeight: Float = 58
    static let laneCornerRadius: Float = 8

    private static let laneFill = SIMD3<Float>(0.070, 0.080, 0.110)
    private static let receptorFill = SIMD3<Float>(0.150, 0.165, 0.205)

    static func laneColor(_ lane: Mania4KLane) -> SIMD3<Float> {
        switch lane {
        case .left:
            return SIMD3(0.96, 0.67, 0.21)
        case .innerLeft:
            return SIMD3(0.26, 0.70, 0.64)
        case .innerRight:
            return SIMD3(0.89, 0.35, 0.37)
        case .right:
            return SIMD3(0.56, 0.63, 0.94)
        }
    }

    static func quads(
        frame: Mania4KPlayFrame?,
        brightness: (Mania4KLane) -> LaneBrightness,
        size: CGSize
    ) -> [Mania4KQuad] {
        let laneWidth = max(0, Float(size.width) - laneSpacing * 3) / 4
        let laneHeight = Float(size.height)
        let receptorY = max(0, laneHeight - 78)
        var quads: [Mania4KQuad] = []

        for lane in Mania4KLane.allCases {
            let laneX = Float(lane.rawValue) * (laneWidth + laneSpacing)
            let laneRect = SIMD4<Float>(laneX, 0, laneWidth, laneHeight)
            let color = laneColor(lane)
            let laneBrightness = brightness(lane)

            func rgba(_ rgb: SIMD3<Float>, _ alpha: Float) -> SIMD4<Float> {
                SIMD4(rgb, alpha)
            }

            // The lane is a background fill and a second fill inside it, both at 0.86.
            for _ in 0..<2 {
                quads.append(Mania4KQuad(
                    rect: laneRect, color: rgba(laneFill, 0.86), cornerRadius: laneCornerRadius, clip: laneRect
                ))
            }
            if laneBrightness.lane > 0 {
                quads.append(Mania4KQuad(
                    rect: laneRect, color: rgba(color, Float(laneBrightness.lane)),
                    cornerRadius: laneCornerRadius, clip: laneRect, additive: true
                ))
            }

            if let frame {
                for object in frame.visibleObjects where object.lane == lane.index {
                    appendObject(
                        object, frame: frame, color: color, laneRect: laneRect, receptorY: receptorY, to: &quads
                    )
                }
            }

            let receptorRect = SIMD4<Float>(laneX, max(0, laneHeight - receptorHeight), laneWidth, receptorHeight)
            quads.append(Mania4KQuad(
                rect: SIMD4(laneX, laneHeight - 79, laneWidth, 3), color: rgba(color, 0.82), clip: laneRect
            ))
            quads.append(Mania4KQuad(
                rect: receptorRect, color: rgba(receptorFill, 1), cornerRadius: 5, clip: laneRect
            ))
            if laneBrightness.receptor > 0 {
                quads.append(Mania4KQuad(
                    rect: receptorRect, color: rgba(color, Float(laneBrightness.receptor)),
                    cornerRadius: 5, clip: laneRect, additive: true
                ))
            }
            quads.append(Mania4KQuad(
                rect: receptorRect, color: rgba(color, 0.62), cornerRadius: 5, strokeWidth: 1, clip: laneRect
            ))
            quads.append(Mania4KQuad(
                rect: laneRect, color: rgba(color, 0.22),
                cornerRadius: laneCornerRadius, strokeWidth: 1, clip: laneRect
            ))
        }

        return quads
    }

    private static func appendObject(
        _ object: VisibleObject,
        frame: Mania4KPlayFrame,
        color: SIMD3<Float>,
        laneRect: SIMD4<Float>,
        receptorY: Float,
        to quads: inout [Mania4KQuad]
    ) {
        let opacity: Float = object.state == .resolved ? 0.28 : (object.state == .missedButVisible ? 0.36 : 0.96)
        let laneWidth = laneRect.z
        let noteWidth = max(0, laneWidth - 16)
        let noteX = laneRect.x + (laneWidth - noteWidth) / 2

        func appendNote(centerY: Float, height: Float) {
            quads.append(Mania4KQuad(
                rect: SIMD4(noteX, centerY - height / 2, noteWidth, height),
                color: SIMD4(color, opacity),
                cornerRadius: 5,
                glowRadius: 10,
                glowAlpha: 0.45 * opacity,
                clip: laneRect
            ))
        }

        let geometry = noteGeometry(
            object: object,
            renderTimeMs: frame.renderChartTimeMs,
            scrollTimeMs: frame.scrollTimeMs,
            receptorY: Double(receptorY)
        )
        switch geometry {
        case .hold(let bodyTopY, let bodyBottomY, let headY, let tailY):
            let bodyWidth = max(laneWidth * 0.52, 24)
            quads.append(Mania4KQuad(
                rect: SIMD4(laneRect.x + (laneWidth - bodyWidth) / 2, Float(bodyTopY), bodyWidth, Float(bodyBottomY - bodyTopY)),
                color: SIMD4(color, 0.42 * opacity),
                cornerRadius: 4,
                clip: laneRect
            ))
            if let tailY {
                appendNote(centerY: Float(tailY), height: 18)
            }
            appendNote(centerY: Float(headY), height: 22)
        case .tap(let y):
            appendNote(centerY: Float(y), height: 24)
        }
    }
}
