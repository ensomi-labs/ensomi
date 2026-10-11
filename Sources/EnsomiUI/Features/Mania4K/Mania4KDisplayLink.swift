import EnsomiCore
import QuartzCore
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
