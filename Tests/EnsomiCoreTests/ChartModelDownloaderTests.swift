import XCTest
@testable import EnsomiCore

/// Installs the published model from a local copy of its listing and files, as `file:` URLs or through `LocalServer`,
/// an HTTP server inside the test process, so no network is needed. `ENSOMI_MODEL_DOWNLOAD_LIVE=1` also downloads it
/// from Hugging Face.
@MainActor
final class ChartModelDownloaderTests: XCTestCase {
    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = try temporaryDirectory()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
        LocalServer.reset()
    }

    private func waitForDownload(_ library: ChartModelLibrary) async throws {
        while case .downloading = library.download { try await Task.sleep(nanoseconds: 50_000_000) }
    }

    private var installDirectory: URL { scratch.appendingPathComponent("models/ensomi-r2") }
    private var staging: URL { ChartModelDownloader.stagingDirectory(for: installDirectory) }

    private func contents(of directory: URL) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    }

    func testInstallsOnlyTheFilesTheRuntimeNamesAndOpensThem() async throws {
        let model = try OnDeviceFixtures.model()
        let source = try publishedCopy(of: model, in: scratch.appendingPathComponent("source"))
        let library = isolatedLibrary(
            modelsDirectory: scratch.appendingPathComponent("models"),
            downloader: ChartModelDownloader(source: source)
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
        XCTAssertFalse(FileManager.default.fileExists(atPath: installDirectory.path))
        XCTAssertFalse(contents(of: staging).contains { $0.hasPrefix("r2_initial_rows.bin") }, "the file that failed is not kept")
    }

    func testAFileTheListingLacksLeavesNoModel() async throws {
        let source = try publishedCopy(of: try OnDeviceFixtures.model(), in: scratch.appendingPathComponent("source"), omitting: "rows_step.onnx")
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: ChartModelDownloader(source: source))

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .failed(ChartModelDownloadError.notPublished("rows_step.onnx").localizedDescription))
        XCTAssertFalse(library.hasModel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installDirectory.path))
    }

    /// The connection drops 3 MB into the largest file. The next start asks for the rest of that file with a Range
    /// request and fetches no finished file again; Remove Download then deletes the model and nothing is left.
    func testAFailedDownloadResumesWithARangeRequest() async throws {
        let model = try OnDeviceFixtures.model()
        let source = LocalServer.serve(try publishedCopy(of: model, in: scratch.appendingPathComponent("source")))
        LocalServer.cut("beatthis.onnx", after: 3_000_000, .fail)
        let library = isolatedLibrary(
            modelsDirectory: scratch.appendingPathComponent("models"),
            downloader: ChartModelDownloader(source: source, session: LocalServer.session)
        )

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .failed(URLError(.networkConnectionLost).localizedDescription))
        XCTAssertTrue(library.hasPartialDownload)
        XCTAssertFalse(library.hasModel)
        XCTAssertTrue(contents(of: staging).contains("runtime.json"))
        XCTAssertEqual(try Data(contentsOf: staging.appendingPathComponent("beatthis.onnx.part")).count, 3_000_000)

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .idle)
        XCTAssertTrue(library.isDownloaded)
        XCTAssertFalse(library.hasPartialDownload)
        let requests = LocalServer.requests.filter { $0.path != "listing.json" }
        XCTAssertEqual(requests.filter { $0.path == "onnx/beatthis.onnx" }.map(\.range), [nil, "bytes=3000000-"])
        let others = requests.filter { $0.path != "onnx/beatthis.onnx" }
        XCTAssertEqual(others.count, Set(others.map(\.path)).count, "a finished file is not fetched again")
        XCTAssertTrue(others.allSatisfy { $0.range == nil })
        XCTAssertEqual(
            try Data(contentsOf: installDirectory.appendingPathComponent("beatthis.onnx")),
            try Data(contentsOf: model.appendingPathComponent("beatthis.onnx"))
        )
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), ["ensomi-r2"])

        library.removeDownload()
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), [])
    }

    /// The published file changes while half of it is on disk: the resumed file fails its checksum, so it is fetched
    /// again from the start and the new version is installed.
    func testAFileChangedOnTheServerFailsItsCheckAndStartsAgain() async throws {
        let name = "rows_step.onnx"
        let model = try OnDeviceFixtures.model()
        let original = scratch.appendingPathComponent("model")
        try FileManager.default.createDirectory(at: original, withIntermediateDirectories: true)
        for file in try FileManager.default.contentsOfDirectory(atPath: model.path) {
            try FileManager.default.createSymbolicLink(at: original.appendingPathComponent(file), withDestinationURL: model.appendingPathComponent(file))
        }
        let downloader = ChartModelDownloader(
            source: LocalServer.serve(try publishedCopy(of: original, in: scratch.appendingPathComponent("source"))),
            session: LocalServer.session
        )
        LocalServer.cut(name, after: 5_000_000, .fail)
        let install = installDirectory
        let first = await Task { try await downloader.install(into: install, validate: { _ in }, progress: { _ in }) }.result
        guard case .failure = first else { return XCTFail("The first download finished.") }
        XCTAssertEqual(try Data(contentsOf: staging.appendingPathComponent("\(name).part")).count, 5_000_000)

        var changed = try Data(contentsOf: model.appendingPathComponent(name))
        changed[0] ^= 0xFF
        let updated = scratch.appendingPathComponent("updated")
        try FileManager.default.copyItem(at: original, to: updated)
        try FileManager.default.removeItem(at: updated.appendingPathComponent(name))
        try changed.write(to: updated.appendingPathComponent(name))
        _ = LocalServer.serve(try publishedCopy(of: updated, in: scratch.appendingPathComponent("source-updated")))

        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })

        XCTAssertEqual(LocalServer.requests.filter { $0.path == "onnx/\(name)" }.map(\.range), [nil, "bytes=5000000-", nil])
        XCTAssertEqual(try Data(contentsOf: install.appendingPathComponent(name)), changed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path))
    }

    /// Cancelling while a file is coming in keeps the finished files and the partial one; the next start continues it.
    func testCancellingKeepsWhatWasWrittenForTheNextStart() async throws {
        let model = try OnDeviceFixtures.model()
        let downloader = ChartModelDownloader(
            source: LocalServer.serve(try publishedCopy(of: model, in: scratch.appendingPathComponent("source"))),
            session: LocalServer.session
        )
        LocalServer.cut("beatthis.onnx", after: 2_000_000, .stall)
        let install = installDirectory
        let partial = staging.appendingPathComponent("beatthis.onnx.part")
        let task = Task { try await downloader.install(into: install, validate: { _ in }, progress: { _ in }) }
        let deadline = Date().addingTimeInterval(60)
        while (try? Data(contentsOf: partial).count) != 2_000_000, Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        task.cancel()

        let result = await task.result

        guard case .failure(let error) = result else { return XCTFail("The download finished before it was cancelled.") }
        XCTAssertTrue(error is CancellationError || (error as? URLError)?.code == .cancelled, "\(error)")
        XCTAssertFalse(FileManager.default.fileExists(atPath: install.path))
        XCTAssertTrue(contents(of: staging).contains("runtime.json"))
        XCTAssertEqual(try Data(contentsOf: partial).count, 2_000_000)

        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })

        XCTAssertEqual(LocalServer.requests.filter { $0.path == "onnx/beatthis.onnx" }.map(\.range), [nil, "bytes=2000000-"])
        XCTAssertEqual(
            try Data(contentsOf: install.appendingPathComponent("beatthis.onnx")),
            try Data(contentsOf: model.appendingPathComponent("beatthis.onnx"))
        )
    }

    func testDownloadsThePublishedModelFromHuggingFace() async throws {
        guard ProcessInfo.processInfo.environment["ENSOMI_MODEL_DOWNLOAD_LIVE"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_MODEL_DOWNLOAD_LIVE=1 to download the model from Hugging Face.")
        }
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"))
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

/// An HTTP server inside the test process: `http://model.test/<path>` answers with the file at `<root>/<path>`, and a
/// `Range: bytes=<n>-` request with a 206 from byte n. A response can be cut once after a number of body bytes: it then
/// fails with a lost connection, or stalls until its task is cancelled.
private final class LocalServer: URLProtocol {
    enum Cut { case fail, stall }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var root: URL?
    nonisolated(unsafe) private static var cuts: [String: (bytes: Int, cut: Cut)] = [:]
    nonisolated(unsafe) private static var log: [(path: String, range: String?)] = []

    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [LocalServer.self]
        return URLSession(configuration: configuration)
    }()

    /// Serves the directory `publishedCopy` laid out for `published` from now on.
    static func serve(_ published: ChartModelSource) -> ChartModelSource {
        lock.withLock { root = published.listing.deletingLastPathComponent() }
        return ChartModelSource(listing: URL(string: "http://model.test/listing.json")!, files: URL(string: "http://model.test/onnx/")!)
    }

    static func cut(_ name: String, after bytes: Int, _ cut: Cut) {
        lock.withLock { cuts[name] = (bytes, cut) }
    }

    /// Every request so far, in order.
    static var requests: [(path: String, range: String?)] {
        lock.withLock { log }
    }

    static func reset() {
        lock.withLock {
            root = nil
            cuts = [:]
            log = []
        }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == "model.test"
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let url = request.url!
        let path = String(url.path.dropFirst())
        let range = request.value(forHTTPHeaderField: "Range")
        let (root, cut) = Self.lock.withLock {
            Self.log.append((path, range))
            return (Self.root!, Self.cuts.removeValue(forKey: url.lastPathComponent))
        }
        guard let data = try? Data(contentsOf: root.appendingPathComponent(path)) else {
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        let start = range.flatMap { Int($0.dropFirst("bytes=".count).dropLast()) } ?? 0
        let body = data.subdata(in: start..<data.count)
        var headers = ["Content-Length": String(body.count)]
        if range != nil { headers["Content-Range"] = "bytes \(start)-\(data.count - 1)/\(data.count)" }
        let response = HTTPURLResponse(url: url, statusCode: range == nil ? 200 : 206, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        guard let cut else {
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        client?.urlProtocol(self, didLoad: body.prefix(cut.bytes))
        if cut.cut == .fail {
            // A moment later, so the bytes reach the task first, as they do before a connection drops.
            perform(#selector(loseConnection), with: nil, afterDelay: 0.2, inModes: [.common])
        }
    }

    @objc private func loseConnection() {
        client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
    }

    override func stopLoading() {}
}
