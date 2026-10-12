import AppKit
import SwiftUI
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

/// Renders the Settings form and Generate a Chart offscreen into PNGs, for judging them in their states: the Chart
/// model section with no model, a download starting, a refused folder and a chosen folder; Generate a Chart with no
/// model, ready, generating, stopped, finished and failed. No window is shown. Runs only with
/// `TEST_RUNNER_ENSOMI_SCREENSHOT_DIR` set to the directory to write into; Generate a Chart also needs the model and
/// the song (`OnDeviceFixtures`).
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

    /// The play window's default width, tall enough to show the whole Generate a Chart panel.
    private static let generateSize = CGSize(width: 1_120, height: 1_180)

    func testGenerateAChartScreens() async throws {
        let scratch = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: scratch) }
        let song = scratch.appendingPathComponent("Saigetsu.mp3")
        try FileManager.default.createSymbolicLink(at: song, withDestinationURL: try OnDeviceFixtures.song())
        let model = try OnDeviceFixtures.model()
        let resampler = try OnDeviceFixtures.resampler()
        let defaults = try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString))
        let library = isolatedLibrary(defaults: defaults, modelsDirectory: scratch.appendingPathComponent("models"), resamplerDirectory: resampler)
        let generation = Mania4KChartGenerationModel(models: library, defaults: defaults)
        generation.choose(engine: .device)
        let play = Mania4KPlaySessionModel(starDifficulty: 3.5)
        play.selectAudioFile(song)
        let screen = { Mania4KPlayExperienceView(model: play, generation: generation, selectedMode: .generatedSong) }

        try await capture(screen(), size: Self.generateSize, name: "generate-1-no-model")

        await library.choose(folder: model)
        await generation.loadCapabilities()
        var section = ChartGenerationSection(kind: .jumpstream, star: 3.5)
        section.start = "00:32"
        section.end = "00:48"
        generation.sections = [section]
        try await capture(screen(), size: Self.generateSize, name: "generate-2-ready")

        try await capture(screen(), size: Self.generateSize, name: "generate-3-generating") {
            generation.state = .generating(phase: .rows)
        }
        try await capture(screen(), size: Self.generateSize, name: "generate-4-stopped") {
            generation.state = .stopped
        }

        let generated = await generation.generate(audioURL: song, star: 3.5, seed: 2_914)
        let result = try XCTUnwrap(generated, "\(generation.state)")
        try await capture(screen(), size: Self.generateSize, name: "generate-5-finished")

        // A refusal from the core, as a failed generation shows it.
        let refusal: String
        do {
            _ = try await library.generator().generate(audioURL: song, star: 7, seed: result.seed)
            return XCTFail("The core generated at 7 stars.")
        } catch {
            refusal = OnDeviceChartGenerator.message(for: error)
        }
        try await capture(screen(), size: Self.generateSize, name: "generate-6-failed") {
            generation.state = .failed(refusal)
        }
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

    /// Lets the view's tasks (capabilities, the song's length) finish, then applies `state` and draws.
    private func capture<V: View>(_ view: V, size: CGSize, name: String, state: () -> Void = {}) async throws {
        let hosting = NSHostingView(rootView: view)
        let window = host(hosting, size: size)
        window.appearance = NSAppearance(named: .darkAqua)
        try await Task.sleep(nanoseconds: 500_000_000)
        state()
        try await Task.sleep(nanoseconds: 100_000_000)
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
