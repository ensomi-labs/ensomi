import EnsomiCore
import Metal
import MetalKit
import SwiftUI
import os

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
    /// Records the drawn frames for the hosted drawing test; nil otherwise.
    var drawableProbe: Mania4KDrawableProbe?
    private var renderer: Mania4KMetalRenderer?
    private var displayLink: Mania4KDisplayLink?
    /// The display time of the display link's last frame, in ms, at which `draw(_:)` lights the lanes.
    private var frameUITimeMs = 0.0
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
        frameUITimeMs = link.targetTimestamp * 1_000
        // `draw()` runs `draw(_:)`, then releases the drawable. Drawing outside it would keep the drawable already
        // presented, and every frame would render into the one on screen.
        draw()
    }

    /// Draws the source's current frame. The display link calls it through `draw()` once per refresh; AppKit or UIKit
    /// may also call it, which draws the same frame again.
    override func draw(_ rect: CGRect) {
        guard let renderer, bounds.width > 0, bounds.height > 0 else {
            return
        }

        let quads = Self.quads(model: model, uiTimeMs: frameUITimeMs, size: bounds.size, reduceMotion: reduceMotion)
        // Reading the render pass takes a drawable, and waits when none is free; the renderer reads it only for a frame
        // it does not drop.
        renderer.draw(quads, viewportSize: bounds.size) {
            guard let pass = currentRenderPassDescriptor else {
                return nil
            }

            let drawable = currentDrawable
            if let drawable, let drawableProbe {
                drawableProbe.record(drawable, uiTimeMs: frameUITimeMs)
            }
            return (pass, drawable)
        }
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

/// The frames a playfield drew: each one's drawable, the display time it was drawn for, and when Metal reports the
/// drawable reached the screen. Presented handlers run off the main thread, hence the lock.
final class Mania4KDrawableProbe: Sendable {
    struct Frame: Sendable {
        let drawableID: Int
        let uiTimeMs: Double
        /// The host time in seconds at which the drawable was shown, 0 when its frame was dropped; nil until Metal
        /// reports it.
        var presentedTime: CFTimeInterval?
    }

    private let recorded = OSAllocatedUnfairLock<[Frame]>(initialState: [])

    var frames: [Frame] {
        recorded.withLock { $0 }
    }

    /// Records a frame drawn into `drawable`; call it before the drawable is presented.
    func record(_ drawable: any MTLDrawable, uiTimeMs: Double) {
        let frame = Frame(drawableID: drawable.drawableID, uiTimeMs: uiTimeMs)
        let index = recorded.withLock { frames in
            frames.append(frame)
            return frames.count - 1
        }
        drawable.addPresentedHandler { [recorded] presented in
            let presentedTime = presented.presentedTime
            recorded.withLock { $0[index].presentedTime = presentedTime }
        }
    }
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
