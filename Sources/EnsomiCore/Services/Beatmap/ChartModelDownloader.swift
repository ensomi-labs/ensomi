import CryptoKit
import EnsomiRuntime
import Foundation

/// Where the published model's files are: a listing of a directory (Hugging Face `tree` JSON) and the directory itself.
public struct ChartModelSource: Sendable {
    public var listing: URL
    /// Ends with a slash; each file is at `files/<name>`.
    public var files: URL

    /// The tag of `sed-i/ensomi-r2` the app downloads, so the files it lists and fetches do not change under it.
    public static let revision = "r2-1.0"

    /// Hugging Face `sed-i/ensomi-r2` at `revision`, directory `onnx/`.
    public static let published = ChartModelSource(
        listing: URL(string: "https://huggingface.co/api/models/sed-i/ensomi-r2/tree/\(revision)/onnx")!,
        files: URL(string: "https://huggingface.co/sed-i/ensomi-r2/resolve/\(revision)/onnx/")!
    )
}

public enum ChartModelDownloadError: Error, Equatable, LocalizedError {
    case http(status: Int, name: String)
    case notPublished(String)
    case size(name: String, expected: UInt64, actual: UInt64)
    case checksum(String)

    public var errorDescription: String? {
        switch self {
        case .http(let status, let name): return "The server answered \(status) for \(name)."
        case .notPublished(let name): return "The published model does not list \(name)."
        case .size(let name, let expected, let actual): return "\(name) has \(actual) bytes; \(expected) were expected."
        case .checksum(let name): return "\(name) does not match its published checksum."
        }
    }
}

/// Downloads the model's files into a directory beside the install directory and moves it into place only when every
/// file has its listed size and checksum and the directory validates. A download that fails or is cancelled leaves that
/// directory with the files finished so far and the one in progress (`<name>.part`), and the next install continues
/// from there: finished files are checked again and the partial one is resumed with an HTTP Range request. A file the
/// install directory already holds with its listed size and checksum is copied rather than fetched, so installing
/// over an older install fetches only the files it lacks or that changed.
public struct ChartModelDownloader: Sendable {
    public var source: ChartModelSource
    public var session: URLSession

    public init(source: ChartModelSource = .published, session: URLSession = .shared) {
        self.source = source
        self.session = session
    }

    private struct Entry: Decodable {
        struct LFS: Decodable { let oid: String }
        let type: String
        let path: String
        let size: UInt64
        let lfs: LFS?
    }

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
        let (listingData, listingResponse) = try await session.data(from: source.listing)
        try Self.checkStatus(listingResponse, name: "the model listing")
        var entries: [String: Entry] = [:]
        for entry in try JSONDecoder().decode([Entry].self, from: listingData) where entry.type == "file" {
            entries[URL(fileURLWithPath: entry.path).lastPathComponent] = entry
        }

        let fileManager = FileManager.default
        let staging = Self.stagingDirectory(for: installDirectory)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)

        let tracker = ProgressTracker(report: progress)
        try await fetch("runtime.json", entries: entries, into: staging, installed: installDirectory, tracker: tracker)
        let config = try String(contentsOf: staging.appendingPathComponent("runtime.json"), encoding: .utf8)
        var names = try modelFiles(runtimeConfig: config)
        if entries["ensomi-model.json"] != nil { names.append("ensomi-model.json") }
        for name in names {
            tracker.total += try Self.entry(named: name, in: entries).size
        }
        tracker.total += tracker.finished
        for name in names {
            try await fetch(name, entries: entries, into: staging, installed: installDirectory, tracker: tracker)
        }

        try validate(staging)
        if fileManager.fileExists(atPath: installDirectory.path) {
            _ = try fileManager.replaceItemAt(installDirectory, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: installDirectory)
        }
        progress(1)
    }

    /// Brings `name` into `staging` and checks its size and checksum. A copy finished at an earlier start is kept if
    /// it still passes, and so is the installed copy. A partial one is continued, and started again if the whole then
    /// fails, as it does when the published file changed in between.
    private func fetch(
        _ name: String, entries: [String: Entry], into staging: URL, installed: URL, tracker: ProgressTracker
    ) async throws {
        let entry = try Self.entry(named: name, in: entries)
        let fileManager = FileManager.default
        let file = staging.appendingPathComponent(name)
        if fileManager.fileExists(atPath: file.path) {
            if (try? Self.check(file, against: entry, name: name)) != nil {
                tracker.finished += entry.size
                return
            }
            try fileManager.removeItem(at: file)
        }
        let installedFile = installed.appendingPathComponent(name)
        if (try? Self.check(installedFile, against: entry, name: name)) != nil {
            try fileManager.copyItem(at: installedFile, to: file)
            tracker.finished += entry.size
            return
        }

        let partial = staging.appendingPathComponent(name + ".part")
        let resuming = Self.size(of: partial) > 0
        try await download(name, size: entry.size, to: partial, tracker: tracker)
        do {
            try Self.check(partial, against: entry, name: name)
        } catch {
            try fileManager.removeItem(at: partial)
            guard resuming else { throw error }
            try await download(name, size: entry.size, to: partial, tracker: tracker)
            try Self.check(partial, against: entry, name: name)
        }
        try fileManager.moveItem(at: partial, to: file)
        tracker.finished += entry.size
    }

    /// Brings `partial` up to `size` bytes: continues it with a Range request, or writes it from the start when the
    /// server sends the whole file (as a `file:` URL does).
    private func download(_ name: String, size: UInt64, to partial: URL, tracker: ProgressTracker) async throws {
        let fileManager = FileManager.default
        if Self.size(of: partial) > size { try fileManager.removeItem(at: partial) }
        if !fileManager.fileExists(atPath: partial.path) { fileManager.createFile(atPath: partial.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: partial)
        defer { try? handle.close() }
        let offset = try handle.seekToEnd()
        guard offset < size else { return }

        var request = URLRequest(url: source.files.appendingPathComponent(name))
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        try await BodyWriter(name: name, handle: handle, offset: offset, tracker: tracker)
            .receive(session.dataTask(with: request))
    }

    private static func entry(named name: String, in entries: [String: Entry]) throws -> Entry {
        guard let entry = entries[name] else { throw ChartModelDownloadError.notPublished(name) }
        return entry
    }

    /// A file URL has no HTTP status.
    private static func checkStatus(_ response: URLResponse, name: String) throws {
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            throw ChartModelDownloadError.http(status: http.statusCode, name: name)
        }
    }

    private static func size(of file: URL) -> UInt64 {
        (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? UInt64) ?? 0
    }

    private static func check(_ file: URL, against entry: Entry, name: String) throws {
        let actual = Self.size(of: file)
        guard actual == entry.size else { throw ChartModelDownloadError.size(name: name, expected: entry.size, actual: actual) }
        if let checksum = entry.lfs?.oid, try sha256Hex(of: file) != checksum {
            throw ChartModelDownloadError.checksum(name)
        }
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
