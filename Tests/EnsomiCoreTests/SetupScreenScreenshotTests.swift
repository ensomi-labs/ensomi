import AppKit
import SwiftUI
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

/// Renders the Settings form offscreen into PNGs, for judging the Chart model section in its states: none, a
/// download starting, a refused folder, a chosen folder. No window is shown. Runs only with
/// `TEST_RUNNER_ENSOMI_SCREENSHOT_DIR` set to the directory to write into.
@MainActor
final class SetupScreenScreenshotTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        guard let path = ProcessInfo.processInfo.environment["ENSOMI_SCREENSHOT_DIR"] else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_SCREENSHOT_DIR to the directory for the PNGs.")
        }
        directory = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func testSettingsScreens() async throws {
        let empty = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: empty) }
        let library = isolatedLibrary(modelsDirectory: empty.appendingPathComponent("models"), downloader: ChartModelDownloader(
            source: try publishedCopy(of: try OnDeviceFixtures.model(), in: empty.appendingPathComponent("source"))))
        let play = Mania4KPlaySessionModel()

        try render(EnsomiSettingsView(model: play, chartModels: library), size: CGSize(width: 560, height: 760), name: "settings-1-no-model")
        library.startDownload()
        try render(EnsomiSettingsView(model: play, chartModels: library), size: CGSize(width: 560, height: 760), name: "settings-2-downloading")
        library.cancelDownload()
        while case .downloading = library.download { try await Task.sleep(nanoseconds: 20_000_000) }
        await library.choose(folder: empty)
        try render(EnsomiSettingsView(model: play, chartModels: library), size: CGSize(width: 560, height: 760), name: "settings-3-folder-refused")
        await library.choose(folder: try OnDeviceFixtures.model())
        try render(EnsomiSettingsView(model: play, chartModels: library), size: CGSize(width: 560, height: 760), name: "settings-4-folder")
    }

    private func host(_ view: NSView, size: CGSize) -> NSWindow {
        view.frame = NSRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: view.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view
        view.layoutSubtreeIfNeeded()
        return window
    }

    private func render<V: View>(_ view: V, size: CGSize, name: String) throws {
        let hosting = NSHostingView(rootView: view)
        let window = host(hosting, size: size)
        try write(hosting, name: name)
        window.close()
    }

    private func write(_ view: NSView, name: String) throws {
        view.layoutSubtreeIfNeeded()
        let representation = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: representation)
        let url = directory.appendingPathComponent("\(name).png")
        try XCTUnwrap(representation.representation(using: .png, properties: [:])).write(to: url)
    }
}
