import EnsomiCore
import QuartzCore
import SwiftUI
#if os(macOS)
import AppKit
typealias Mania4KPlatformView = NSView
#else
import UIKit
typealias Mania4KPlatformView = UIView
#endif

/// Calls `onFrame` on the main run loop once per display refresh while `view` is on screen.
@MainActor
final class Mania4KDisplayLink: NSObject {
    private var link: CADisplayLink?
    private let onFrame: (CADisplayLink) -> Void

    init(view: Mania4KPlatformView, onFrame: @escaping (CADisplayLink) -> Void) {
        self.onFrame = onFrame
        super.init()
        #if os(macOS)
        link = view.displayLink(target: self, selector: #selector(step(_:)))
        #else
        link = CADisplayLink(target: self, selector: #selector(step(_:)))
        #endif
        link?.add(to: .main, forMode: .common)
    }

    func invalidate() {
        link?.invalidate()
        link = nil
    }

    @objc private func step(_ link: CADisplayLink) {
        onFrame(link)
    }
}

extension Mania4KPlaySessionModel {
    /// The display link's frame: judge at the current time, draw for the time the display shows the frame.
    func advanceFrame(for link: CADisplayLink) {
        advanceFrame(
            atHostTimeMs: EnsomiHostClock.currentTimeMS(),
            presentationHostTimeMs: link.targetTimestamp * 1_000
        )
    }
}

/// Drives the session from the display link: the one source of game frames.
struct Mania4KFrameDriver {
    let model: Mania4KPlaySessionModel
}

#if os(macOS)
extension Mania4KFrameDriver: NSViewRepresentable {
    func makeNSView(context: Context) -> DriverView {
        DriverView(model: model)
    }

    func updateNSView(_ nsView: DriverView, context: Context) {}

    final class DriverView: NSView {
        private let model: Mania4KPlaySessionModel
        private var displayLink: Mania4KDisplayLink?

        init(model: Mania4KPlaySessionModel) {
            self.model = model
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not supported")
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            displayLink?.invalidate()
            displayLink = window == nil ? nil : Mania4KDisplayLink(view: self) { [model] link in
                model.advanceFrame(for: link)
            }
        }
    }
}
#else
extension Mania4KFrameDriver: UIViewRepresentable {
    func makeUIView(context: Context) -> DriverView {
        DriverView(model: model)
    }

    func updateUIView(_ uiView: DriverView, context: Context) {}

    final class DriverView: UIView {
        private let model: Mania4KPlaySessionModel
        private var displayLink: Mania4KDisplayLink?

        init(model: Mania4KPlaySessionModel) {
            self.model = model
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not supported")
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            displayLink?.invalidate()
            displayLink = window == nil ? nil : Mania4KDisplayLink(view: self) { [model] link in
                model.advanceFrame(for: link)
            }
        }
    }
}
#endif
