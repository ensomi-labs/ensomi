import CryptoKit
import EnsomiRuntime
import Foundation

/// Where the published model package is: a directory holding its manifest, `ensomi-package.json`, and the files the
/// manifest lists.
public struct ChartModelSource: Sendable {
    /// Ends with a slash; the manifest and each file are at `directory/<path>`.
    public var directory: URL

    /// The tag of `sed-i/ensomi-r2` the app downloads, so the manifest and the files it lists do not change under it.
    public static let revision = "r2-1.0.1"

    /// Hugging Face `sed-i/ensomi-r2` at `revision`, directory `onnx/`.
    public static let published = ChartModelSource(
        directory: URL(string: "https://huggingface.co/sed-i/ensomi-r2/resolve/\(revision)/onnx/")!
    )
}

public enum ChartModelDownloadError: Error, Equatable, LocalizedError {
    case http(status: Int, name: String)
    case size(name: String, expected: UInt64, actual: UInt64)
    case checksum(String)

    public var errorDescription: String? {
        switch self {
        case .http(let status, let name): return "The server answered \(status) for \(name)."
        case .size(let name, let expected, let actual): return "\(name) has \(actual) bytes; \(expected) were expected."
        case .checksum(let name): return "\(name) does not match its published checksum."
        }
    }
}

/// Downloads the package's manifest, checks it with the runtime's `checkPackage`, then downloads exactly the files it
/// lists into a directory beside the install directory and moves that into place only when every file has the size and
/// SHA-256 the manifest gives and the directory validates. A download that fails or is cancelled leaves that directory
/// with the files finished so far and the one in progress (`<path>.part`), and the next install continues from there:
/// finished files are checked again and the partial one is resumed with an HTTP Range request. A file the install
/// directory already holds with its listed size and SHA-256 is copied rather than fetched, so installing over an older
/// install, with or without a manifest, fetches only the manifest and the files it lacks or that changed.
public struct ChartModelDownloader: Sendable {
    public var source: ChartModelSource
    public var session: URLSession

    public init(source: ChartModelSource = .published, session: URLSession = .shared) {
        self.source = source
        self.session = session
    }

    /// The package manifest's name, in the published directory and in an install.
    public static let manifestName = "ensomi-package.json"

    /// Where the download of `installDirectory` collects its files: `.download-<name>` beside it.
    public static func stagingDirectory(for installDirectory: URL) -> URL {
        installDirectory.deletingLastPathComponent()
            .appendingPathComponent(".download-\(installDirectory.lastPathComponent)", isDirectory: true)
    }

    /// `progress` hears the fraction of the model's bytes on disk, a whole percent at a time. Cancelling the task
    /// stops the download and keeps what it wrote for the next install.
    public func install(
        into installDirectory: URL,
        validate: @Sendable (URL) throws -> Void,
        progress: @escaping @Sendable (Double) -> Void
    ) async throws {
        let (manifest, response) = try await session.data(from: source.directory.appendingPathComponent(Self.manifestName))
        try Self.checkStatus(response, name: Self.manifestName)
        let package = try checkPackage(manifestJson: String(decoding: manifest, as: UTF8.self))

        let fileManager = FileManager.default
        let staging = Self.stagingDirectory(for: installDirectory)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)

        let tracker = ProgressTracker(report: progress)
        tracker.total = package.totalBytes
        for file in package.files {
            try await fetch(file, into: staging, installed: installDirectory, tracker: tracker)
        }
        try manifest.write(to: staging.appendingPathComponent(Self.manifestName))

        try validate(staging)
        if fileManager.fileExists(atPath: installDirectory.path) {
            _ = try fileManager.replaceItemAt(installDirectory, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: installDirectory)
        }
        progress(1)
    }

    /// Brings `entry` into `staging` and checks its size and SHA-256. A copy finished at an earlier start is kept if
    /// it still passes, and so is the installed copy. A partial one is continued, and started again if the whole then
    /// fails, as it does when the published file changed in between.
    private func fetch(_ entry: PackageFile, into staging: URL, installed: URL, tracker: ProgressTracker) async throws {
        let fileManager = FileManager.default
        let file = staging.appendingPathComponent(entry.path)
        if fileManager.fileExists(atPath: file.path) {
            if (try? Self.check(file, against: entry)) != nil {
                tracker.finished += entry.size
                return
            }
            try fileManager.removeItem(at: file)
        }
        try fileManager.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let installedFile = installed.appendingPathComponent(entry.path)
        if (try? Self.check(installedFile, against: entry)) != nil {
            try fileManager.copyItem(at: installedFile, to: file)
            tracker.finished += entry.size
            return
        }

        let partial = staging.appendingPathComponent(entry.path + ".part")
        let resuming = Self.size(of: partial) > 0
        try await download(entry.path, size: entry.size, to: partial, tracker: tracker)
        do {
            try Self.check(partial, against: entry)
        } catch {
            try fileManager.removeItem(at: partial)
            guard resuming else { throw error }
            try await download(entry.path, size: entry.size, to: partial, tracker: tracker)
            try Self.check(partial, against: entry)
        }
        try fileManager.moveItem(at: partial, to: file)
        tracker.finished += entry.size
    }

    /// Brings `partial` up to `size` bytes: continues it with a Range request, or writes it from the start when the
    /// server sends the whole file (as a `file:` URL does).
    private func download(_ path: String, size: UInt64, to partial: URL, tracker: ProgressTracker) async throws {
        let fileManager = FileManager.default
        if Self.size(of: partial) > size { try fileManager.removeItem(at: partial) }
        if !fileManager.fileExists(atPath: partial.path) { fileManager.createFile(atPath: partial.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: partial)
        defer { try? handle.close() }
        let offset = try handle.seekToEnd()
        guard offset < size else { return }

        var request = URLRequest(url: source.directory.appendingPathComponent(path))
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        try await BodyWriter(name: path, handle: handle, offset: offset, tracker: tracker)
            .receive(session.dataTask(with: request))
    }

    /// A file URL has no HTTP status.
    private static func checkStatus(_ response: URLResponse, name: String) throws {
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw ChartModelDownloadError.http(status: http.statusCode, name: name)
        }
    }

    static func size(of file: URL) -> UInt64 {
        (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? UInt64) ?? 0
    }

    private static func check(_ file: URL, against entry: PackageFile) throws {
        let actual = Self.size(of: file)
        guard actual == entry.size else { throw ChartModelDownloadError.size(name: entry.path, expected: entry.size, actual: actual) }
        guard try sha256Hex(of: file) == entry.sha256 else { throw ChartModelDownloadError.checksum(entry.path) }
    }

    private static func sha256Hex(of file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            try Task.checkCancellation()
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Bytes of the files that are complete plus those of the one downloading. Files download one at a time, so the
/// writer and the loop never write together.
private final class ProgressTracker: @unchecked Sendable {
    var total: UInt64 = 0
    var finished: UInt64 = 0
    private var lastPercent = -1
    private let report: @Sendable (Double) -> Void

    init(report: @escaping @Sendable (Double) -> Void) {
        self.report = report
    }

    func update(written: UInt64) {
        guard total > 0 else { return }
        let fraction = min(Double(finished + written) / Double(total), 1)
        let percent = Int(fraction * 100)
        guard percent != lastPercent else { return }
        lastPercent = percent
        report(fraction)
    }
}

/// Writes one response's body into an open file as it arrives: after the bytes already there when the server sends
/// the requested range (206), over them when it sends the whole file.
private final class BodyWriter: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let name: String
    private let handle: FileHandle
    private let tracker: ProgressTracker
    private var written: UInt64
    private var failure: Error?
    private var continuation: CheckedContinuation<Void, Error>?

    init(name: String, handle: FileHandle, offset: UInt64, tracker: ProgressTracker) {
        self.name = name
        self.handle = handle
        self.tracker = tracker
        written = offset
    }

    func receive(_ task: URLSessionDataTask) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                task.delegate = self
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        do {
            switch status {
            case 206:
                break
            case 200:
                try handle.truncate(atOffset: 0)
                written = 0
            default:
                throw ChartModelDownloadError.http(status: status, name: name)
            }
            completionHandler(.allow)
        } catch {
            failure = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do {
            try handle.write(contentsOf: data)
            written += UInt64(data.count)
            tracker.update(written: written)
        } catch {
            failure = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = failure ?? error {
            continuation?.resume(throwing: error)
        } else {
            continuation?.resume()
        }
        continuation = nil
    }
}
