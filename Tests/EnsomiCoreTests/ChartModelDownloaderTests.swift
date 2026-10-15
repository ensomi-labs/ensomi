import EnsomiRuntime
import XCTest
@testable import EnsomiCore

/// Installs a model package from a local copy of its manifest and files, as `file:` URLs or through `LocalServer`, an
/// HTTP server inside the test process, so no network is needed. The checks that come before the generator opens the
/// download run on a small package; the rest need the published model (`OnDeviceFixtures.model()`).
/// `ENSOMI_MODEL_DOWNLOAD_LIVE=1` also downloads it from Hugging Face.
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
    private var manifestRequest: String { "onnx/\(ChartModelDownloader.manifestName)" }

    private func contents(of directory: URL) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    }

    /// Serves `package` as the package revision `revision`, with the manifest changed by `edit`.
    private func served(
        _ package: PackageFixture, in name: String = "source", revision: String = ChartModelSource.pinnedRevision,
        manifest edit: (inout [String: Any]) -> Void = { _ in }
    ) throws -> ChartModelDownloader {
        var source = LocalServer.serve(try publishedCopy(of: package, in: scratch.appendingPathComponent(name), manifest: edit))
        source.revision = revision
        return ChartModelDownloader(source: source, session: LocalServer.session)
    }

    private func manifestSHA256(in directory: URL) throws -> String {
        try sha256Hex(of: directory.appendingPathComponent(ChartModelDownloader.manifestName))
    }

    func testInstallsExactlyTheFilesTheManifestListsAndOpensThem() async throws {
        let package = try PackageFixture.r2()
        let source = try publishedCopy(of: package, in: scratch.appendingPathComponent("source"))
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
        XCTAssertEqual(
            Set(contents(of: installDirectory)), Set(package.files + [ChartModelDownloader.manifestName, ChartModelInstallRecord.fileName])
        )
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), ["ensomi-r2"])
        XCTAssertEqual(
            ChartModelInstallRecord.read(in: installDirectory),
            ChartModelInstallRecord(revision: ChartModelSource.pinnedRevision, manifestSHA256: try manifestSHA256(in: source.directory))
        )
        XCTAssertEqual(library.installedRevision, ChartModelSource.pinnedRevision)
        XCTAssertFalse(library.isUpdatePending)

        let capabilities = try await library.generator().capabilities
        XCTAssertEqual(capabilities.modelName, "Ensomi R2")
        XCTAssertEqual(capabilities.minStar, 2)
        XCTAssertEqual(capabilities.maxStarExclusive, 6)

        let deadline = Date().addingTimeInterval(10)
        while library.license == nil, Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(library.license?.expression, try package.manifest()["license"] as? String)
        XCTAssertEqual(library.license?.notices.map(\.name), package.notices)
    }

    /// The manifest asks for a later runtime: the download stops before fetching any file, with the runtime's reason.
    func testAManifestTheRuntimeRefusesFetchesNoFile() async throws {
        let package = try PackageFixture.small(in: scratch.appendingPathComponent("package"))
        let source = LocalServer.serve(try publishedCopy(of: package, in: scratch.appendingPathComponent("source")) { $0["min_runtime"] = "9.0.0" })
        let library = isolatedLibrary(
            modelsDirectory: scratch.appendingPathComponent("models"),
            downloader: ChartModelDownloader(source: source, session: LocalServer.session)
        )

        library.startDownload()
        try await waitForDownload(library)

        guard case .failed(let message) = library.download else { return XCTFail("\(library.download)") }
        XCTAssertTrue(message.contains("9.0.0"), message)
        XCTAssertEqual(LocalServer.requests.map(\.path), [manifestRequest])
        XCTAssertFalse(library.hasModel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installDirectory.path))
    }

    func testAFileThatDiffersFromItsManifestChecksumLeavesNoModel() async throws {
        let package = try PackageFixture.small(in: scratch.appendingPathComponent("package"))
        let source = try publishedCopy(of: package, in: scratch.appendingPathComponent("source"), corrupting: "weights.bin")
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: ChartModelDownloader(source: source))

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .failed(ChartModelDownloadError.checksum("weights.bin").localizedDescription))
        XCTAssertFalse(library.hasModel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installDirectory.path))
        XCTAssertFalse(contents(of: staging).contains { $0.hasPrefix("weights.bin") }, "the file that failed is not kept")
    }

    /// The runtime refuses to open a package whose manifest leaves out a file `runtime.json` names, so the download is
    /// not installed.
    func testAManifestThatLeavesOutAFileTheConfigNamesLeavesNoModel() async throws {
        let source = try publishedCopy(of: try PackageFixture.r2(), in: scratch.appendingPathComponent("source"), omitting: "rows_step.onnx")
        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: ChartModelDownloader(source: source))

        library.startDownload()
        try await waitForDownload(library)

        guard case .failed(let message) = library.download else { return XCTFail("\(library.download)") }
        XCTAssertTrue(message.contains("rows_step.onnx"), message)
        XCTAssertFalse(library.hasModel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installDirectory.path))
    }

    /// An install made before the app downloaded the manifest: one file as published, one changed, one missing, and
    /// `ensomi-model.json`, which the package no longer lists. The download fetches the manifest, the changed file and
    /// the missing one, copies the other, and the install ends with exactly the manifest's files.
    func testAnInstallWithoutAManifestIsCompletedFromIt() async throws {
        let package = try PackageFixture.small(in: scratch.appendingPathComponent("package"))
        let downloader = try served(package)
        let install = installDirectory
        try FileManager.default.createDirectory(at: install, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: package.directory.appendingPathComponent("runtime.json"), to: install.appendingPathComponent("runtime.json"))
        try Data("an older notice\n".utf8).write(to: install.appendingPathComponent("BeatThis-LICENSE.txt"))
        try Data("{}".utf8).write(to: install.appendingPathComponent("ensomi-model.json"))

        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })

        XCTAssertEqual(LocalServer.requests.map(\.path), [manifestRequest, "onnx/BeatThis-LICENSE.txt", "onnx/weights.bin"])
        XCTAssertEqual(Set(contents(of: install)), Set(package.files + [ChartModelDownloader.manifestName, ChartModelInstallRecord.fileName]))
        for name in package.files {
            XCTAssertEqual(try Data(contentsOf: install.appendingPathComponent(name)), try Data(contentsOf: package.directory.appendingPathComponent(name)), name)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path))
    }

    /// The library starts that completion by itself when the install has no manifest, and not once the install is
    /// complete.
    func testTheLibraryCompletesAnInstallWithoutAManifestWhenItStarts() async throws {
        let name = "rows_step.onnx"
        let package = try PackageFixture.r2()
        let downloader = try served(package)
        let install = installDirectory
        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })
        try FileManager.default.removeItem(at: install.appendingPathComponent(ChartModelDownloader.manifestName))
        try FileManager.default.removeItem(at: install.appendingPathComponent(name))
        let earlier = LocalServer.requests.count

        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: downloader)
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .idle)
        XCTAssertTrue(library.isDownloaded)
        XCTAssertEqual(LocalServer.requests.dropFirst(earlier).map(\.path), [manifestRequest, "onnx/\(name)"])
        XCTAssertEqual(try Data(contentsOf: install.appendingPathComponent(name)), try Data(contentsOf: package.directory.appendingPathComponent(name)))
        XCTAssertEqual(contents(of: install.deletingLastPathComponent()), ["ensomi-r2"])

        let completed = LocalServer.requests.count
        let later = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: downloader)
        XCTAssertEqual(later.download, .idle)
        XCTAssertEqual(LocalServer.requests.count, completed)
    }

    /// An install from the earlier revision, made before the app recorded revisions, while the app pins a revision whose
    /// manifest differs only in its licence: the library refreshes it when it starts, fetching only the manifest and
    /// copying every file from the install. The library then has the new licence and revision, and the model's name on
    /// this device carries the revision.
    func testTheLibraryRefreshesAnInstallFromAnotherRevisionWhenItStarts() async throws {
        let package = try PackageFixture.r2()
        let install = installDirectory
        try await served(package, revision: "r2-1.0").install(into: install, validate: { _ in }, progress: { _ in })
        try FileManager.default.removeItem(at: install.appendingPathComponent(ChartModelInstallRecord.fileName))
        let downloader = try served(package, in: "source-new", revision: "r2-1.0.1") { $0["license"] = "MIT" }
        let earlier = LocalServer.requests.count

        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: downloader)
        XCTAssertNil(library.installedRevision)
        XCTAssertTrue(library.isUpdatePending)
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .idle)
        XCTAssertEqual(LocalServer.requests.dropFirst(earlier).map(\.path), [manifestRequest])
        XCTAssertEqual(library.installedRevision, "r2-1.0.1")
        XCTAssertFalse(library.isUpdatePending)
        XCTAssertEqual(
            ChartModelInstallRecord.read(in: install),
            ChartModelInstallRecord(revision: "r2-1.0.1", manifestSHA256: try manifestSHA256(in: scratch.appendingPathComponent("source-new/onnx")))
        )
        let deadline = Date().addingTimeInterval(10)
        while library.license?.expression != "MIT", Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(library.license?.expression, "MIT")

        let generation = Mania4KChartGenerationModel(models: library, defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)))
        await generation.loadCapabilities()
        XCTAssertEqual(generation.modelDescription, "Ensomi R2 r2-1.0 · package r2-1.0.1")
    }

    /// An install whose record names the revision the app pins is current: the library fetches nothing.
    func testTheLibraryFetchesNothingForAnInstallOfThePinnedRevision() async throws {
        let package = try PackageFixture.small(in: scratch.appendingPathComponent("package"))
        let downloader = try served(package, revision: "r2-1.0.1")
        try await downloader.install(into: installDirectory, validate: { _ in }, progress: { _ in })
        let earlier = LocalServer.requests.count

        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: downloader)

        XCTAssertEqual(library.download, .idle)
        XCTAssertEqual(library.installedRevision, "r2-1.0.1")
        XCTAssertEqual(library.activeRevision, "r2-1.0.1")
        XCTAssertFalse(library.isUpdatePending)
        XCTAssertEqual(LocalServer.requests.count, earlier)
    }

    /// The refresh to the pinned revision loses its connection: the install from the earlier revision stays in place
    /// and in use with its licence, and the library says an update is pending.
    func testAnInstallStaysInUseWhenItsRefreshFails() async throws {
        let package = try PackageFixture.small(in: scratch.appendingPathComponent("package"))
        let install = installDirectory
        try await served(package, revision: "r2-1.0").install(into: install, validate: { _ in }, progress: { _ in })
        var installed: [String: Data] = [:]
        for name in contents(of: install) { installed[name] = try Data(contentsOf: install.appendingPathComponent(name)) }
        let downloader = try served(package, in: "source-new", revision: "r2-1.0.1") { $0["license"] = "MIT" }
        LocalServer.cut(ChartModelDownloader.manifestName, after: 0, .fail)
        let earlier = LocalServer.requests.count

        let library = isolatedLibrary(modelsDirectory: scratch.appendingPathComponent("models"), downloader: downloader)
        try await waitForDownload(library)

        guard case .failed = library.download else { return XCTFail("\(library.download)") }
        XCTAssertEqual(LocalServer.requests.dropFirst(earlier).map(\.path), [manifestRequest])
        XCTAssertTrue(library.isUpdatePending)
        XCTAssertEqual(library.installedRevision, "r2-1.0")
        XCTAssertEqual(library.activeModel?.standardizedFileURL.path, install.standardizedFileURL.path)
        XCTAssertFalse(library.hasPartialDownload)
        XCTAssertEqual(Set(contents(of: install)), Set(installed.keys))
        for (name, data) in installed {
            XCTAssertEqual(try Data(contentsOf: install.appendingPathComponent(name)), data, name)
        }
        let deadline = Date().addingTimeInterval(10)
        while library.license == nil, Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(library.license?.expression, try package.manifest()["license"] as? String)
    }

    /// The installed package's licence and notice texts reach the library, and ONNX Runtime's licence files reach the
    /// app from the runtime package.
    func testTheLicenceComesFromTheInstalledManifest() async throws {
        let package = try PackageFixture.small(in: installDirectory)
        try package.writeManifest()

        let license = try ChartModelLicense(modelDirectory: package.directory)

        XCTAssertEqual(license.expression, try package.manifest()["license"] as? String)
        XCTAssertEqual(license.notices.map(\.name), package.notices)
        XCTAssertEqual(license.notices.first?.text, try String(contentsOf: package.directory.appendingPathComponent("BeatThis-LICENSE.txt"), encoding: .utf8))
        XCTAssertEqual(ChartModelLicense.onnxRuntimeNotices.map(\.name), ChartGenerator.onnxRuntimeLicenseFiles.map(\.lastPathComponent))
        XCTAssertFalse(ChartModelLicense.onnxRuntimeNotices.isEmpty)
    }

    /// The connection drops 3 MB into the largest file. The next start asks for the rest of that file with a Range
    /// request and fetches no finished file again; Remove Download then deletes the model and nothing is left.
    func testAFailedDownloadResumesWithARangeRequest() async throws {
        let package = try PackageFixture.r2()
        let source = LocalServer.serve(try publishedCopy(of: package, in: scratch.appendingPathComponent("source")))
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
        XCTAssertTrue(contents(of: staging).contains("BeatThis-LICENSE.txt"))
        XCTAssertEqual(try Data(contentsOf: staging.appendingPathComponent("beatthis.onnx.part")).count, 3_000_000)

        library.startDownload()
        try await waitForDownload(library)

        XCTAssertEqual(library.download, .idle)
        XCTAssertTrue(library.isDownloaded)
        XCTAssertFalse(library.hasPartialDownload)
        let requests = LocalServer.requests.filter { $0.path != manifestRequest }
        XCTAssertEqual(requests.filter { $0.path == "onnx/beatthis.onnx" }.map(\.range), [nil, "bytes=3000000-"])
        let others = requests.filter { $0.path != "onnx/beatthis.onnx" }
        XCTAssertEqual(others.count, Set(others.map(\.path)).count, "a finished file is not fetched again")
        XCTAssertTrue(others.allSatisfy { $0.range == nil })
        XCTAssertEqual(
            try Data(contentsOf: installDirectory.appendingPathComponent("beatthis.onnx")),
            try Data(contentsOf: package.directory.appendingPathComponent("beatthis.onnx"))
        )
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), ["ensomi-r2"])

        library.removeDownload()
        XCTAssertEqual(contents(of: installDirectory.deletingLastPathComponent()), [])
    }

    /// The published file changes while half of it is on disk: the resumed file fails its checksum, so it is fetched
    /// again from the start and the new version is installed.
    func testAFileChangedOnTheServerFailsItsCheckAndStartsAgain() async throws {
        let name = "rows_step.onnx"
        let package = try PackageFixture.r2()
        let model = package.directory
        let original = scratch.appendingPathComponent("model")
        try FileManager.default.createDirectory(at: original, withIntermediateDirectories: true)
        for file in package.files {
            try FileManager.default.createSymbolicLink(at: original.appendingPathComponent(file), withDestinationURL: model.appendingPathComponent(file))
        }
        let downloader = try served(PackageFixture(directory: original, files: package.files, notices: package.notices))
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
        _ = try served(PackageFixture(directory: updated, files: package.files, notices: package.notices), in: "source-updated")

        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })

        XCTAssertEqual(LocalServer.requests.filter { $0.path == "onnx/\(name)" }.map(\.range), [nil, "bytes=5000000-", nil])
        XCTAssertEqual(try Data(contentsOf: install.appendingPathComponent(name)), changed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path))
    }

    /// Cancelling while a file is coming in keeps the finished files and the partial one; the next start continues it.
    func testCancellingKeepsWhatWasWrittenForTheNextStart() async throws {
        let package = try PackageFixture.r2()
        let downloader = try served(package)
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
        XCTAssertTrue(contents(of: staging).contains("BeatThis-LICENSE.txt"))
        XCTAssertEqual(try Data(contentsOf: partial).count, 2_000_000)

        try await downloader.install(into: install, validate: { _ in }, progress: { _ in })

        XCTAssertEqual(LocalServer.requests.filter { $0.path == "onnx/beatthis.onnx" }.map(\.range), [nil, "bytes=2000000-"])
        XCTAssertEqual(
            try Data(contentsOf: install.appendingPathComponent("beatthis.onnx")),
            try Data(contentsOf: package.directory.appendingPathComponent("beatthis.onnx"))
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

/// The build record Settings shows under Chart model, read from an Info.plist as `Tools/mac.sh` and Xcode leave it.
final class AppBuildInfoTests: XCTestCase {
    /// A build the script did not install has the keys with their build settings expanded to nothing.
    func testABuildWithoutARecordSaysSo() {
        let unrecorded = AppBuildInfo(infoDictionary: [
            "EnsomiBuildCommit": "", "EnsomiBuildDirty": "", "EnsomiBuildChannel": "", "EnsomiRuntimeVersion": "",
        ])

        XCTAssertNil(unrecorded.commit)
        XCTAssertEqual(unrecorded.summary, "App build not recorded")
        XCTAssertEqual(AppBuildInfo(infoDictionary: [:]), unrecorded)
    }

    func testARecordedBuildNamesItsCommitChannelAndRuntime() {
        var info: [String: Any] = [
            "EnsomiBuildCommit": "39673d0c1bf77425c4ab87eaa678b3d9640bdf92",
            "EnsomiBuildDirty": "NO",
            "EnsomiBuildChannel": "play",
            "EnsomiRuntimeVersion": "0.3.1",
        ]
        XCTAssertEqual(AppBuildInfo(infoDictionary: info).summary, "App build 39673d0 · play · runtime 0.3.1")

        info["EnsomiBuildDirty"] = "YES"
        XCTAssertEqual(AppBuildInfo(infoDictionary: info).summary, "App build 39673d0 (modified) · play · runtime 0.3.1")
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
        lock.withLock { root = published.directory.deletingLastPathComponent() }
        return ChartModelSource(directory: URL(string: "http://model.test/onnx/")!)
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
