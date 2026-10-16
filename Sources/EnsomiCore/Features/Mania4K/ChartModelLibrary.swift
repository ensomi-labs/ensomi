import EnsomiRuntime
import Foundation
import Observation

/// The model the on-device generator runs: the published one downloaded into Application Support, or a folder the
/// person chose, which takes precedence. Holds the generator once opened.
@MainActor
@Observable
public final class ChartModelLibrary {
    /// The app's library. In a test process it does not bring the install up to date when it starts, so tests that make
    /// a view or a generation model with it never download into the person's install.
    public static let shared = ChartModelLibrary(updatesInstallAtStart: NSClassFromString("XCTestCase") == nil)

    public enum Download: Equatable {
        case idle
        case downloading(Double)
        case failed(String)
    }

    public struct NoModelError: LocalizedError {
        public var errorDescription: String? { "No on-device model is installed." }
    }

    public private(set) var download = Download.idle
    public private(set) var chosenFolder: URL?
    public private(set) var isDownloaded = false
    /// A download stopped before it finished; the next one continues from its files.
    public private(set) var hasPartialDownload = false
    /// Why the last chosen folder was refused.
    public private(set) var folderError: String?
    /// The licence of the model in use, read off the main actor when that model changes.
    public private(set) var license: ChartModelLicense?
    /// The package revision the download records (`ChartModelInstallRecord`); nil without a download or for one made
    /// before the app recorded it.
    public private(set) var installedRevision: String?

    private let defaults: UserDefaults
    private let downloader: ChartModelDownloader
    private let installDirectory: URL
    @ObservationIgnored private var downloadTask: Task<Void, Never>?
    @ObservationIgnored private var scopedFolder: URL?
    @ObservationIgnored private var opened: (directory: URL, generator: OnDeviceChartGenerator)?

    private static let folderKey = "mania4k.chartModelFolder"

    #if os(macOS)
    private static let bookmarkCreation: URL.BookmarkCreationOptions = [.withSecurityScope]
    private static let bookmarkResolution: URL.BookmarkResolutionOptions = [.withSecurityScope]
    #else
    private static let bookmarkCreation: URL.BookmarkCreationOptions = []
    private static let bookmarkResolution: URL.BookmarkResolutionOptions = []
    #endif

    /// `~/Library/Application Support/Ensomi/Models` on a Mac.
    public static var defaultModelsDirectory: URL {
        URL.applicationSupportDirectory.appendingPathComponent("Ensomi/Models", isDirectory: true)
    }

    /// `updatesInstallAtStart` starts the download when the install is incomplete or `isUpdatePending`.
    public init(
        defaults: UserDefaults = .standard,
        modelsDirectory: URL = ChartModelLibrary.defaultModelsDirectory,
        downloader: ChartModelDownloader = ChartModelDownloader(),
        updatesInstallAtStart: Bool = true
    ) {
        self.defaults = defaults
        self.downloader = downloader
        installDirectory = modelsDirectory.appendingPathComponent("ensomi-r2", isDirectory: true)
        isDownloaded = FileManager.default.fileExists(atPath: installDirectory.path)
        installedRevision = ChartModelInstallRecord.read(in: installDirectory)?.revision
        hasPartialDownload = FileManager.default.fileExists(atPath: stagingDirectory.path)
        restoreChosenFolder()
        if updatesInstallAtStart, isDownloaded, !installIsComplete || isUpdatePending { startDownload() }
        readLicense()
    }

    /// The revision a download fetches: `ChartModelSource.pinnedRevision` for the published package.
    public var pinnedRevision: String {
        downloader.source.revision
    }

    /// The download is from another revision than `pinnedRevision`, or records none, and no download has replaced it
    /// yet. A library starts that download when it is created, as `shared` does at launch; the installed model stays in
    /// use while it runs and after it fails.
    public var isUpdatePending: Bool {
        isDownloaded && installedRevision != pinnedRevision
    }

    /// The package revision of the model in use when that is the download; nil for a chosen folder, which records
    /// none.
    public var activeRevision: String? {
        chosenFolder == nil ? installedRevision : nil
    }

    /// The install holds its manifest and every file the manifest lists, at its listed size. An install made before
    /// the app downloaded the manifest has none; the download then fetches the manifest and the files that are missing
    /// or changed, and copies the rest from the install.
    private var installIsComplete: Bool {
        let manifest = installDirectory.appendingPathComponent(ChartModelDownloader.manifestName)
        guard let text = try? String(contentsOf: manifest, encoding: .utf8), let package = try? checkPackage(manifestJson: text) else {
            return false
        }
        return package.files.allSatisfy {
            ChartModelDownloader.size(of: installDirectory.appendingPathComponent($0.path)) == $0.size
        }
    }

    private var stagingDirectory: URL {
        ChartModelDownloader.stagingDirectory(for: installDirectory)
    }

    /// The directory of the model in use, if any.
    public var activeModel: URL? {
        chosenFolder ?? (isDownloaded ? installDirectory : nil)
    }

    public var hasModel: Bool {
        activeModel != nil
    }

    /// The generator for the model in use, opened on first use and kept.
    public func generator() async throws -> OnDeviceChartGenerator {
        guard let directory = activeModel else { throw NoModelError() }
        if let opened, opened.directory == directory { return opened.generator }
        let generator = try await openGenerator(directory)
        opened = (directory, generator)
        return generator
    }

    public func startDownload() {
        guard downloadTask == nil else { return }
        download = .downloading(0)
        downloadTask = Task {
            defer { downloadTask = nil }
            do {
                let record = try await downloader.install(
                    into: installDirectory,
                    validate: { _ = try OnDeviceChartGenerator(modelDirectory: $0) },
                    progress: { fraction in Task { @MainActor in self.report(fraction) } }
                )
                isDownloaded = true
                installedRevision = record.revision
                opened = nil
                download = .idle
                readLicense()
            } catch is CancellationError {
                download = .idle
            } catch let error as URLError where error.code == .cancelled {
                download = .idle
            } catch {
                download = .failed(OnDeviceChartGenerator.message(for: error))
            }
            hasPartialDownload = FileManager.default.fileExists(atPath: stagingDirectory.path)
        }
    }

    public func cancelDownload() {
        downloadTask?.cancel()
    }

    /// Deletes the downloaded model and the files of a download that stopped.
    public func removeDownload() {
        try? FileManager.default.removeItem(at: installDirectory)
        try? FileManager.default.removeItem(at: stagingDirectory)
        isDownloaded = false
        installedRevision = nil
        hasPartialDownload = false
        download = .idle
        opened = nil
        readLicense()
    }

    /// Uses the folder if the generator opens it and the folder holds the model's licence notices, and remembers it
    /// across launches.
    public func choose(folder url: URL) async {
        let accessed = url.startAccessingSecurityScopedResource()
        do {
            let (generator, license) = try await Task.detached {
                try (OnDeviceChartGenerator(modelDirectory: url), ChartModelLicense(modelDirectory: url))
            }.value
            defaults.set(try url.bookmarkData(options: Self.bookmarkCreation), forKey: Self.folderKey)
            releaseFolder()
            scopedFolder = accessed ? url : nil
            chosenFolder = url
            opened = (url, generator)
            folderError = nil
            self.license = license
        } catch {
            if accessed { url.stopAccessingSecurityScopedResource() }
            folderError = OnDeviceChartGenerator.message(for: error)
        }
    }

    /// Goes back to the downloaded model.
    public func forgetChosenFolder() {
        defaults.removeObject(forKey: Self.folderKey)
        releaseFolder()
        chosenFolder = nil
        folderError = nil
        opened = nil
        readLicense()
    }

    private func report(_ fraction: Double) {
        if case .downloading = download { download = .downloading(fraction) }
    }

    private func readLicense() {
        license = nil
        guard let directory = activeModel else { return }
        Task {
            let read = try? await Task.detached { try ChartModelLicense(modelDirectory: directory) }.value
            if activeModel == directory { license = read }
        }
    }

    private func openGenerator(_ directory: URL) async throws -> OnDeviceChartGenerator {
        try await Task.detached {
            try OnDeviceChartGenerator(modelDirectory: directory)
        }.value
    }

    private func releaseFolder() {
        scopedFolder?.stopAccessingSecurityScopedResource()
        scopedFolder = nil
    }

    private func restoreChosenFolder() {
        guard let bookmark = defaults.data(forKey: Self.folderKey) else { return }
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: Self.bookmarkResolution, relativeTo: nil, bookmarkDataIsStale: &isStale),
              FileManager.default.fileExists(atPath: url.path)
        else { return }
        if url.startAccessingSecurityScopedResource() { scopedFolder = url }
        if isStale, let fresh = try? url.bookmarkData(options: Self.bookmarkCreation) {
            defaults.set(fresh, forKey: Self.folderKey)
        }
        chosenFolder = url
    }
}

/// The licence of a model directory and the texts of the notices that come with it.
public struct ChartModelLicense: Equatable, Sendable {
    public struct Notice: Equatable, Sendable, Identifiable {
        public let name: String
        public let text: String
        public var id: String { name }
    }

    /// The package's licence as an SPDX expression; nil for a directory without `ensomi-package.json`.
    public let expression: String?
    public let notices: [Notice]

    /// ONNX Runtime's licence and third-party notices, which the runtime package carries: the app ships ONNX Runtime
    /// inside the runtime.
    public static let onnxRuntimeNotices: [Notice] = ChartGenerator.onnxRuntimeLicenseFiles.compactMap { url in
        (try? String(contentsOf: url, encoding: .utf8)).map { Notice(name: url.lastPathComponent, text: $0) }
    }

    /// Reads the licence and notices the manifest gives, or for a directory without one the notices `runtime.json`
    /// names (`noticeFiles`). Throws when a notice file is missing.
    init(modelDirectory: URL) throws {
        let manifest = modelDirectory.appendingPathComponent(ChartModelDownloader.manifestName)
        let names: [String]
        if FileManager.default.fileExists(atPath: manifest.path) {
            let package = try checkPackage(manifestJson: String(contentsOf: manifest, encoding: .utf8))
            expression = package.license
            names = package.notices
        } else {
            expression = nil
            names = try noticeFiles(runtimeConfig: String(contentsOf: modelDirectory.appendingPathComponent("runtime.json"), encoding: .utf8))
        }
        notices = try names.map { Notice(name: $0, text: try String(contentsOf: modelDirectory.appendingPathComponent($0), encoding: .utf8)) }
    }
}

/// What the running app was built from, as `Tools/mac.sh install` records it in the macOS app's Info.plist
/// (`EnsomiBuild`): the short commit, with `-modified` when the app's sources had uncommitted changes or a local runtime
/// was used. Other builds leave it empty, and then no build is recorded.
public struct AppBuildInfo: Equatable, Sendable {
    /// "39673d0" or "39673d0-modified"; nil when no build is recorded.
    public let build: String?

    public static let current = AppBuildInfo(infoDictionary: Bundle.main.infoDictionary ?? [:])

    public init(infoDictionary: [String: Any]) {
        build = (infoDictionary["EnsomiBuild"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// "App build 39673d0-modified", or "App build not recorded".
    public var summary: String { "App build \(build ?? "not recorded")" }
}
