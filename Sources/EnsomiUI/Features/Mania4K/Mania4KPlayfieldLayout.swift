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
    /// x is 1 to add the colour to what is below instead of blending over it; y is 1 to fade the quad out towards
    /// its top edge.
    var flags: SIMD4<Float>

    init(
        rect: SIMD4<Float>,
        color: SIMD4<Float>,
        cornerRadius: Float = 0,
        strokeWidth: Float = 0,
        glowRadius: Float = 0,
        glowAlpha: Float = 0,
        clip: SIMD4<Float>,
        additive: Bool = false,
        fadesUp: Bool = false
    ) {
        self.rect = rect
        self.color = color
        self.shape = SIMD4(cornerRadius, strokeWidth, glowRadius, glowAlpha)
        self.clip = clip
        self.flags = SIMD4(additive ? 1 : 0, fadesUp ? 1 : 0, 0, 0)
    }
}

/// Lays the playfield out as quads: four adjacent lanes, the key tray under the judgement line with the lane-press
/// lights, the notes, the hit feedback at the line and the song's progress along the top. Key labels are drawn by
/// SwiftUI over the key caps (`keyAreaHeight` tall at the bottom).
///
/// Everything that reacts to play follows what the core reports: note states, the judgement events of the last
/// moments and the lane brightness from key transitions.
enum Mania4KPlayfieldLayout {
    static let keyAreaHeight: Float = 76
    static let trayCornerRadius: Float = 14
    static let noteInset: Float = 5
    static let tapHeight: Float = 20
    static let progressHeight: Float = 3
    /// Space between the judgement line and the key caps.
    static let capInset: Float = 10
    /// How long a hit's flash stays at the line, and a miss's mark.
    static let hitFeedbackMs = 200.0
    static let missFeedbackMs = 360.0

    private typealias Palette = Mania4KPlayPalette

    static func quads(
        frame: Mania4KPlayFrame?,
        brightness: (Mania4KLane) -> LaneBrightness,
        judgements: [JudgementEventBatch] = [],
        size: CGSize,
        reduceMotion: Bool = false
    ) -> [Mania4KQuad] {
        let width = Float(size.width)
        let height = Float(size.height)
        let laneWidth = width / 4
        let lineY = max(0, height - keyAreaHeight)
        let field = SIMD4<Float>(0, 0, width, height)
        var quads: [Mania4KQuad] = []
        quads.reserveCapacity(128)

        // Lanes alternate like the web player's and end at the judgement line, where the key tray begins.
        for lane in Mania4KLane.allCases {
            let laneRect = SIMD4<Float>(Float(lane.rawValue) * laneWidth, 0, laneWidth, lineY)
            let fill = lane.rawValue.isMultiple(of: 2) ? Palette.laneA : Palette.laneB
            quads.append(Mania4KQuad(rect: laneRect, color: SIMD4(fill, 1), clip: laneRect))
        }

        for lane in Mania4KLane.allCases {
            let light = Float(brightness(lane).lane)
            if light > 0 {
                let lightHeight = lineY * 0.45
                quads.append(Mania4KQuad(
                    rect: SIMD4(Float(lane.rawValue) * laneWidth, lineY - lightHeight, laneWidth, lightHeight),
                    color: SIMD4(Palette.hold, min(1, light * 2)),
                    clip: field,
                    additive: true,
                    fadesUp: true
                ))
            }
        }

        // The tray: a rectangle taller than its clip keeps its top corners square and rounds the bottom ones.
        let tray = SIMD4<Float>(0, lineY, width, height - lineY)
        quads.append(Mania4KQuad(
            rect: SIMD4(0, lineY - trayCornerRadius, width, height - lineY + trayCornerRadius),
            color: SIMD4(Palette.keyTray, 1),
            cornerRadius: trayCornerRadius,
            clip: tray
        ))
        for lane in Mania4KLane.allCases {
            let cap = SIMD4<Float>(
                Float(lane.rawValue) * laneWidth + 6, lineY + capInset, laneWidth - 12, keyAreaHeight - capInset * 2
            )
            quads.append(Mania4KQuad(rect: cap, color: SIMD4(Palette.surface, 1), cornerRadius: 12, clip: tray))
            let light = Float(brightness(lane).receptor)
            if light > 0 {
                quads.append(Mania4KQuad(
                    rect: cap, color: SIMD4(Palette.hold, light), cornerRadius: 12, clip: tray, additive: true
                ))
            }
        }

        // Notes go over the tray so one on the line shows whole, and slip under the caps' top edge.
        if let frame {
            for object in frame.visibleObjects {
                appendObject(
                    object, frame: frame, laneX: Float(object.lane) * laneWidth, laneWidth: laneWidth, lineY: lineY,
                    to: &quads
                )
            }
        }

        quads.append(Mania4KQuad(rect: SIMD4(0, lineY - 1.5, width, 3), color: SIMD4(Palette.action, 1), clip: field))

        if let frame {
            appendHitFeedback(
                judgements, nowMs: frame.gameplayChartTimeMs, laneWidth: laneWidth, lineY: lineY, field: field,
                reduceMotion: reduceMotion, to: &quads
            )
            if let durationMs = frame.metadata.durationMs, durationMs > 0 {
                let progress = Float(min(max(frame.gameplayChartTimeMs / durationMs, 0), 1))
                quads.append(Mania4KQuad(
                    rect: SIMD4(0, 0, width, progressHeight), color: SIMD4(Palette.surface, 1), clip: field
                ))
                quads.append(Mania4KQuad(
                    rect: SIMD4(0, 0, width * progress, progressHeight), color: SIMD4(Palette.action, 1), clip: field
                ))
            }
        }

        return quads
    }

    /// Notes hit are gone; a miss still on screen is greyed. A hold being held keeps its head on the line and glows.
    /// Notes are clipped to their lane down to the top of the key caps.
    private static func appendObject(
        _ object: VisibleObject,
        frame: Mania4KPlayFrame,
        laneX: Float,
        laneWidth: Float,
        lineY: Float,
        to quads: inout [Mania4KQuad]
    ) {
        guard object.state != .resolved else {
            return
        }

        let clip = SIMD4<Float>(laneX, 0, laneWidth, lineY + capInset)
        // A glowing head's clip is wider than its lane so the glow is not cut at the lane's edges.
        let glowClip = SIMD4<Float>(laneX - 24, 0, laneWidth + 48, lineY + capInset)
        let noteX = laneX + noteInset
        let noteWidth = max(0, laneWidth - noteInset * 2)
        let isMissed = object.state == .missedButVisible
        let isHeld = object.state == .holding

        func note(centerY: Float, color: SIMD3<Float>, alpha: Float, glows: Bool = false) -> Mania4KQuad {
            Mania4KQuad(
                rect: SIMD4(noteX, centerY - tapHeight / 2, noteWidth, tapHeight),
                color: SIMD4(color, alpha),
                cornerRadius: 7,
                glowRadius: glows ? 14 : 0,
                glowAlpha: glows ? 0.55 : 0,
                clip: glows ? glowClip : clip
            )
        }

        let geometry = noteGeometry(
            object: object,
            renderTimeMs: frame.renderChartTimeMs,
            scrollTimeMs: frame.scrollTimeMs,
            receptorY: Double(lineY)
        )
        switch geometry {
        case .hold(let bodyTopY, let bodyBottomY, let headY, let tailY):
            let color = isMissed ? Palette.missedNote : Palette.hold
            let alpha: Float = isMissed ? 0.5 : 1
            let bodyWidth = noteWidth * 0.72
            let bodyX = noteX + (noteWidth - bodyWidth) / 2
            quads.append(Mania4KQuad(
                rect: SIMD4(bodyX, Float(bodyTopY), bodyWidth, Float(bodyBottomY - bodyTopY)),
                color: isMissed ? SIMD4(color, 0.25) : SIMD4(Palette.holdBody, isHeld ? 0.85 : 0.6),
                cornerRadius: 6,
                clip: clip
            ))
            if let tailY {
                quads.append(Mania4KQuad(
                    rect: SIMD4(bodyX, Float(tailY) - 4, bodyWidth, 8),
                    color: SIMD4(color, alpha),
                    cornerRadius: 4,
                    clip: clip
                ))
            }
            quads.append(note(centerY: Float(headY), color: color, alpha: alpha, glows: isHeld))
        case .tap(let y):
            quads.append(note(
                centerY: Float(y), color: isMissed ? Palette.missedNote : Palette.tap, alpha: isMissed ? 0.5 : 1
            ))
        }
    }

    /// A hit lights the judgement line in its lane in the tier's colour, with a short flare up the lane; a miss tints
    /// the lane above the line. Each lasts a fraction of a second from the chart time the core recorded its event at. With Reduce Motion
    /// the flash only fades.
    private static func appendHitFeedback(
        _ judgements: [JudgementEventBatch],
        nowMs: Double,
        laneWidth: Float,
        lineY: Float,
        field: SIMD4<Float>,
        reduceMotion: Bool,
        to quads: inout [Mania4KQuad]
    ) {
        for batch in judgements {
            let ageMs = nowMs - batch.chartTimeMs
            guard ageMs >= 0 else {
                continue
            }

            for event in batch.events {
                let laneX = Float(event.lane) * laneWidth
                let color = Palette.judgement(event.judgement)
                if event.judgement == .miss {
                    guard ageMs < missFeedbackMs else {
                        continue
                    }
                    let fade = Float(1 - ageMs / missFeedbackMs)
                    quads.append(Mania4KQuad(
                        rect: SIMD4(laneX, lineY - 96, laneWidth, 96),
                        color: SIMD4(color, 0.34 * fade),
                        clip: field,
                        fadesUp: true
                    ))
                    quads.append(Mania4KQuad(
                        rect: SIMD4(laneX, lineY - 2, laneWidth, 4), color: SIMD4(color, fade), clip: field
                    ))
                    continue
                }

                guard ageMs < hitFeedbackMs else {
                    continue
                }
                let progress = Float(ageMs / hitFeedbackMs)
                let fade = (1 - progress) * (1 - progress)
                quads.append(Mania4KQuad(
                    rect: SIMD4(laneX + 3, lineY - 3, laneWidth - 6, 6),
                    color: SIMD4(color, fade),
                    cornerRadius: 3,
                    glowRadius: 24,
                    glowAlpha: 0.8 * fade,
                    clip: field,
                    additive: true
                ))
                if !reduceMotion {
                    let flareHeight = 40 + 70 * (1 - fade)
                    quads.append(Mania4KQuad(
                        rect: SIMD4(laneX + noteInset * 3, lineY - flareHeight, laneWidth - noteInset * 6, flareHeight),
                        color: SIMD4(color, 0.24 * fade),
                        clip: field,
                        additive: true,
                        fadesUp: true
                    ))
                }
            }
        }
    }
}
