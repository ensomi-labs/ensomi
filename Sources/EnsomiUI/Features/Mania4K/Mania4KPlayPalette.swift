import EnsomiCore
import SwiftUI
import simd

/// The play screen's colours, from the brand's dark plum theme and the web player: rose taps, lavender holds with
/// purple bodies, and the coral action colour for the judgement line and progress. Straight sRGB components, so the Metal playfield and the
/// SwiftUI HUD over it read the same values.
enum Mania4KPlayPalette {
    static let ground = rgb(0x130E1A)
    /// The lighter side of the stage's diagonal division.
    static let groundSecondHalf = rgb(0x1A1322)
    static let laneA = rgb(0x1D1627)
    static let laneB = rgb(0x191220)
    static let keyTray = rgb(0x171020)
    static let surface = rgb(0x2A2036)
    static let border = rgb(0x4A3B5E)
    static let text = rgb(0xF6F1FB)
    static let mutedText = rgb(0xC7BCD6)

    static let tap = rgb(0xEAA0B7)
    static let hold = rgb(0xB5B3EF)
    /// The brand's intersection purple, where the lavender and rose arcs of the mark overlap.
    static let holdBody = rgb(0x9562D7)
    /// A note judged a miss that is still on screen.
    static let missedNote = rgb(0x8F7DAB)
    /// Judgement line and song progress; the gradient's ends colour the primary button.
    static let action = rgb(0xFF879F)
    static let actionStart = rgb(0xF39AAF)
    static let actionEnd = rgb(0xFF708F)
    static let actionInk = rgb(0x2B0F1E)

    static let early = rgb(0xB5B3EF)
    static let late = rgb(0xEAA0B7)

    static func judgement(_ judgement: Judgement) -> SIMD3<Float> {
        switch judgement {
        case .perfect: return text
        case .good: return rgb(0xF3C47E)
        case .miss: return rgb(0xFF9AA5)
        }
    }

    private static func rgb(_ hex: UInt32) -> SIMD3<Float> {
        SIMD3(Float((hex >> 16) & 0xFF), Float((hex >> 8) & 0xFF), Float(hex & 0xFF)) / 255
    }
}

extension Color {
    init(_ rgb: SIMD3<Float>, opacity: Double = 1) {
        self.init(.sRGB, red: Double(rgb.x), green: Double(rgb.y), blue: Double(rgb.z), opacity: opacity)
    }
}
