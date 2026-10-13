import EnsomiRuntime
import Foundation
import Observation

/// The model the on-device generator runs: the published one downloaded into Application Support, or a folder the
/// person chose, which takes precedence. Holds the generator once opened.
@MainActor
@Observable
public final class ChartModelLibrary {
    public static let shared = ChartModelLibrary()

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

    public init(
        defaults: UserDefaults = .standard,
        modelsDirectory: URL = ChartModelLibrary.defaultModelsDirectory,
        downloader: ChartModelDownloader = ChartModelDownloader()
    ) {
        self.defaults = defaults
        self.downloader = downloader
        installDirectory = modelsDirectory.appendingPathComponent("ensomi-r2", isDirectory: true)
        isDownloaded = FileManager.default.fileExists(atPath: installDirectory.path)
        hasPartialDownload = FileManager.default.fileExists(atPath: stagingDirectory.path)
        restoreChosenFolder()
        if isDownloaded, !installIsComplete { startDownload() }
        readLicense()
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
                try await downloader.install(
                    into: installDirectory,
                    validate: { _ = try OnDeviceChartGenerator(modelDirectory: $0) },
                    progress: { fraction in Task { @MainActor in self.report(fraction) } }
                )
                isDownloaded = true
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
