import EnsomiCore
import Metal
import MetalKit
import SwiftUI

/// What the playfield draws: a frame made for each display refresh, and the feedback that lights it. The play model
/// judges in its frames; chart inspection only draws.
@MainActor
protocol Mania4KPlayfieldSource: AnyObject, Sendable {
    var playFrame: Mania4KPlayFrame? { get }
    var gameplayFeedback: GameplayFeedback { get }
    @discardableResult
    func advanceFrame(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) -> Bool
    /// The window is no longer visible and its display link has stopped.
    func pause() async
}

extension Mania4KPlaySessionModel: Mania4KPlayfieldSource {}
extension Mania4KChartInspectionModel: Mania4KPlayfieldSource {}

/// The playfield: lanes, notes, hold bodies, the key tray, the judgement line, the lane-press lights, the hit
/// feedback and the song progress, drawn with Metal. Its display link is the source's only source of frames; each
/// one advances the source, then draws it.
struct Mania4KPlayfieldMetalView {
    let model: any Mania4KPlayfieldSource
    let reduceMotion: Bool
}

/// Draws the playfield into an image in place of the live Metal view, so the play screen can be rendered offscreen
/// (`ImageRenderer` does not draw platform views). The closure gets the playfield's size in points.
struct Mania4KPlayfieldSnapshot: Sendable {
    let image: @MainActor @Sendable (CGSize) -> CGImage?
}

extension EnvironmentValues {
    @Entry var mania4kPlayfieldSnapshot: Mania4KPlayfieldSnapshot? = nil
}

@MainActor
final class Mania4KPlayfieldView: MTKView {
    var model: any Mania4KPlayfieldSource
    var reduceMotion = false
    private var renderer: Mania4KMetalRenderer?
    private var displayLink: Mania4KDisplayLink?
    #if os(macOS)
    private var visibilityObserver: Mania4KWindowVisibilityObserver?
    #endif

    init(model: any Mania4KPlayfieldSource) {
        self.model = model
        super.init(frame: .zero, device: MTLCreateSystemDefaultDevice())
        colorPixelFormat = .bgra8Unorm
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        framebufferOnly = true
        isPaused = true
        enableSetNeedsDisplay = false
        if let metalLayer = layer as? CAMetalLayer {
            metalLayer.isOpaque = false
            metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
        }

        if let device {
            do {
                renderer = try Mania4KMetalRenderer(device: device, pixelFormat: colorPixelFormat)
            } catch {
                NSLog("Ensomi playfield renderer unavailable: \(error)")
            }
        }
    }

    required init(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    private func attachDisplayLink() {
        displayLink?.invalidate()
        displayLink = window == nil ? nil : Mania4KDisplayLink(view: self) { [weak self] link in
            self?.step(link)
        }
    }

    private func step(_ link: CADisplayLink) {
        model.advanceFrame(for: link)
        draw(atUITimeMs: link.targetTimestamp * 1_000)
    }

    private func draw(atUITimeMs uiTimeMs: Double) {
        guard let renderer, bounds.width > 0, bounds.height > 0, let pass = currentRenderPassDescriptor else {
            return
        }

        let quads = Self.quads(model: model, uiTimeMs: uiTimeMs, size: bounds.size, reduceMotion: reduceMotion)
        renderer.draw(quads, viewportSize: bounds.size, pass: pass, drawable: currentDrawable)
    }

    /// The quads of the model's current frame, with the lane lights at `uiTimeMs`.
    static func quads(
        model: any Mania4KPlayfieldSource,
        uiTimeMs: Double,
        size: CGSize,
        reduceMotion: Bool = false
    ) -> [Mania4KQuad] {
        let feedback = model.gameplayFeedback
        return Mania4KPlayfieldLayout.quads(
            frame: model.playFrame,
            brightness: { feedback.laneBrightness(lane: $0.index, uiTimeMs: uiTimeMs) },
            judgements: feedback.judgementEventBatches(),
            size: size,
            reduceMotion: reduceMotion
        )
    }

    #if os(macOS)
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        attachDisplayLink()
        // The display link stops with the window, so frames and stream reads would too: pause for the player to resume.
        visibilityObserver?.invalidate()
        visibilityObserver = window.map { window in
            Mania4KWindowVisibilityObserver(window: window) { [weak self] isVisible in
                guard !isVisible, let model = self?.model else {
                    return
                }

                Task { await model.pause() }
            }
        }
    }
    #else
    override func didMoveToWindow() {
        super.didMoveToWindow()
        attachDisplayLink()
    }
    #endif
}

#if os(macOS)
extension Mania4KPlayfieldMetalView: NSViewRepresentable {
    func makeNSView(context: Context) -> Mania4KPlayfieldView {
        Mania4KPlayfieldView(model: model)
    }

    func updateNSView(_ nsView: Mania4KPlayfieldView, context: Context) {
        nsView.model = model
        nsView.reduceMotion = reduceMotion
    }
}
#else
extension Mania4KPlayfieldMetalView: UIViewRepresentable {
    func makeUIView(context: Context) -> Mania4KPlayfieldView {
        Mania4KPlayfieldView(model: model)
    }

    func updateUIView(_ uiView: Mania4KPlayfieldView, context: Context) {
        uiView.model = model
        uiView.reduceMotion = reduceMotion
    }
}
#endif
