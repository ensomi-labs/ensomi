import EnsomiCore
import Metal
import MetalKit
import SwiftUI

/// The playfield: lanes, notes, hold bodies, the judgement line and the lane-press lights, drawn with Metal. Its
/// display link is the session's only source of game frames; each one advances the session, then draws it.
struct Mania4KPlayfieldMetalView {
    let model: Mania4KPlaySessionModel
}

@MainActor
final class Mania4KPlayfieldView: MTKView {
    var model: Mania4KPlaySessionModel
    private var renderer: Mania4KMetalRenderer?
    private var displayLink: Mania4KDisplayLink?

    init(model: Mania4KPlaySessionModel) {
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

        let model = model
        let quads = Mania4KPlayfieldLayout.quads(
            frame: model.playFrame,
            brightness: { model.gameplayFeedback.laneBrightness(for: $0, atUITimeMs: uiTimeMs) },
            size: bounds.size
        )
        renderer.draw(quads, viewportSize: bounds.size, pass: pass, drawable: currentDrawable)
    }

    #if os(macOS)
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        attachDisplayLink()
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
    }
}
#else
extension Mania4KPlayfieldMetalView: UIViewRepresentable {
    func makeUIView(context: Context) -> Mania4KPlayfieldView {
        Mania4KPlayfieldView(model: model)
    }

    func updateUIView(_ uiView: Mania4KPlayfieldView, context: Context) {
        uiView.model = model
    }
}
#endif
