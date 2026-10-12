import XCTest
@testable import EnsomiCore

/// Installs the published model from a local copy of its listing and files (a `file:` source), so no network is
/// needed. `ENSOMI_MODEL_DOWNLOAD_LIVE=1` also downloads it from Hugging Face.
@MainActor
final class ChartModelDownloaderTests: XCTestCase {
    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = try temporaryDirectory()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
    }

    private func waitForDownload(_ library: ChartModelLibrary) async throws {
        while case .downloading = library.download { try await Task.sleep(nanoseconds: 50_000_000) }
    }

    private var installDirectory: URL { scratch.appendingPathComponent("models/ensomi-r2") }

    private func contents(of directory: URL) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    }

    func testInstallsOnlyTheFilesTheRuntimeNamesAndOpensThem() async throws {
        let model = try OnDeviceFixtures.model()
        let source = try publishedCopy(of: model, in: scratch.appendingPathComponent("source"))
        let library = isolatedLibrary(
            modelsDirectory: scratch.appendingPathComponent("models"),
            downloader: ChartModelDownloader(source: source),
            resamplerDirectory: try OnDeviceFixtures.resampler()
        )
        XCTAssertFalse(library.hasModel)

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .idle)
        XCTAssertTrue(library.isDownloaded)
        XCTAssertEqual(library.activeModel, installDirectory)
        let installed = Set(contents(of: installDirectory))
        XCTAssertTrue(installed.isSuperset(of: ["runtime.json", "beatthis.onnx", "rows_step.onnx"]))
        XCTAssertFalse(installed.contains("rows-smoke.json"), "files the runtime does not name stay out")
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), ["ensomi-r2"])

        let capabilities = try await library.generator().capabilities
        XCTAssertEqual(capabilities.modelName, "Ensomi R2")
        XCTAssertEqual(capabilities.minStar, 2)
        XCTAssertEqual(capabilities.maxStarExclusive, 6)
    }

    func testAFileThatDiffersFromItsListedChecksumLeavesNoModel() async throws {
        let source = try publishedCopy(of: try OnDeviceFixtures.model(), in: scratch.appendingPathComponent("source"), corrupting: "r2_initial_rows.bin")
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: ChartModelDownloader(source: source))

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .failed(ChartModelDownloadError.checksum("r2_initial_rows.bin").localizedDescription))
        XCTAssertFalse(library.hasModel)
        XCTAssertEqual(contents(of: scratch.appendingPathComponent("models")), [])
    }

    func testAFileTheListingLacksLeavesNoModel() async throws {
        let source = try publishedCopy(of: try OnDeviceFixtures.model(), in: scratch.appendingPathComponent("source"), omitting: "rows_step.onnx")
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: ChartModelDownloader(source: source))

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .failed(ChartModelDownloadError.notPublished("rows_step.onnx").localizedDescription))
        XCTAssertEqual(contents(of: scratch.appendingPathComponent("models")), [])
    }

    func testCancellingMidwayRemovesWhatWasWritten() async throws {
        let source = try publishedCopy(of: try OnDeviceFixtures.model(), in: scratch.appendingPathComponent("source"))
        let downloader = ChartModelDownloader(source: source)
        let install = installDirectory
        let download = CancelOnProgress()
        let task = Task {
            try await downloader.install(into: install, validate: { _ in }, progress: { download.progress($0) })
        }
        download.task = task

        let result = await task.result

        guard case .failure(let error) = result else { return XCTFail("The download finished before it was cancelled.") }
        XCTAssertTrue(error is CancellationError || (error as? URLError)?.code == .cancelled, "\(error)")
        XCTAssertEqual(contents(of: install.deletingLastPathComponent()), [])
    }

    func testDownloadsThePublishedModelFromHuggingFace() async throws {
        guard ProcessInfo.processInfo.environment["ENSOMI_MODEL_DOWNLOAD_LIVE"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_MODEL_DOWNLOAD_LIVE=1 to download the model from Hugging Face.")
        }
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), resamplerDirectory: try OnDeviceFixtures.resampler())
        var fractions: [Double] = []
        let started = Date()
        library.startDownload()
        while case .downloading(let fraction) = library.download {
            if fractions.last != fraction { fractions.append(fraction) }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        print("DOWNLOAD finished in \(String(format: "%.1f", Date().timeIntervalSince(started))) s, state \(library.download), \(fractions.count) progress steps, last \(fractions.last ?? -1)")

        XCTAssertEqual(library.download, .idle)
        XCTAssertTrue(library.isDownloaded)
        XCTAssertEqual(fractions, fractions.sorted())
        XCTAssertGreaterThan(fractions.count, 10, "progress is reported while the files come in")
        let capabilities = try await library.generator().capabilities
        XCTAssertEqual(capabilities.modelName, "Ensomi R2")
    }
}

/// Cancels the download once 5 % of the bytes are written, while the largest file is coming in.
private final class CancelOnProgress: @unchecked Sendable {
    var task: Task<Void, Error>?

    func progress(_ fraction: Double) {
        if fraction >= 0.05 { task?.cancel() }
    }
}
