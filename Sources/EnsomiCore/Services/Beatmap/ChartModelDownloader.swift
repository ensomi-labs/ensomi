import CryptoKit
import EnsomiRuntime
import Foundation

/// Where the published model's files are: a listing of a directory (Hugging Face `tree` JSON) and the directory itself.
public struct ChartModelSource: Sendable {
    public var listing: URL
    /// Ends with a slash; each file is at `files/<name>`.
    public var files: URL

    /// Hugging Face `sed-i/ensomi-r2`, directory `onnx/`.
    public static let published = ChartModelSource(
        listing: URL(string: "https://huggingface.co/api/models/sed-i/ensomi-r2/tree/main/onnx")!,
        files: URL(string: "https://huggingface.co/sed-i/ensomi-r2/resolve/main/onnx/")!
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

/// Downloads the model's files into a temporary directory next to the install directory and moves the directory
/// into place only when every file has its listed size and checksum and the directory validates.
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

    /// `progress` hears the fraction of the model's bytes written, a whole percent at a time.
    /// Cancelling the task removes everything it wrote.
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
        let parent = installDirectory.deletingLastPathComponent()
        try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        for stale in try fileManager.contentsOfDirectory(at: parent, includingPropertiesForKeys: nil)
        where stale.lastPathComponent.hasPrefix(".download-") {
            try fileManager.removeItem(at: stale)
        }
        let staging = parent.appendingPathComponent(".download-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? fileManager.removeItem(at: staging) }

        let tracker = ProgressTracker(report: progress)
        try await fetch("runtime.json", entries: entries, into: staging, tracker: tracker)
        let config = try String(contentsOf: staging.appendingPathComponent("runtime.json"), encoding: .utf8)
        var names = try modelFiles(runtimeConfig: config)
        if entries["ensomi-model.json"] != nil { names.append("ensomi-model.json") }
        for name in names {
            tracker.total += try Self.entry(named: name, in: entries).size
        }
        tracker.total += tracker.finished
        for name in names {
            try await fetch(name, entries: entries, into: staging, tracker: tracker)
        }

        try validate(staging)
        if fileManager.fileExists(atPath: installDirectory.path) {
            _ = try fileManager.replaceItemAt(installDirectory, withItemAt: staging)
        } else {
            try fileManager.moveItem(at: staging, to: installDirectory)
        }
        progress(1)
    }

    private func fetch(_ name: String, entries: [String: Entry], into staging: URL, tracker: ProgressTracker) async throws {
        let entry = try Self.entry(named: name, in: entries)
        let (temporary, response) = try await session.download(
            from: source.files.appendingPathComponent(name), delegate: ProgressDelegate(tracker: tracker))
        try Self.checkStatus(response, name: name)
        let file = staging.appendingPathComponent(name)
        try FileManager.default.moveItem(at: temporary, to: file)

        let size = try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? UInt64 ?? 0
        guard size == entry.size else { throw ChartModelDownloadError.size(name: name, expected: entry.size, actual: size) }
        if let checksum = entry.lfs?.oid, try Self.sha256Hex(of: file) != checksum {
            throw ChartModelDownloadError.checksum(name)
        }
        tracker.finished += size
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
/// delegate and the loop never write together.
private final class ProgressTracker: @unchecked Sendable {
    var total: UInt64 = 0
    var finished: UInt64 = 0
    private var lastPercent = -1
    private let report: @Sendable (Double) -> Void

    init(report: @escaping @Sendable (Double) -> Void) {
        self.report = report
    }

    func update(written: Int64) {
        guard total > 0 else { return }
        let fraction = min(Double(finished + UInt64(written)) / Double(total), 1)
        let percent = Int(fraction * 100)
        guard percent != lastPercent else { return }
        lastPercent = percent
        report(fraction)
    }
}

/// Reports the bytes received so far from the task the download creates.
private final class ProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let tracker: ProgressTracker
    private var observation: NSKeyValueObservation?

    init(tracker: ProgressTracker) {
        self.tracker = tracker
    }

    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
        observation = task.observe(\.countOfBytesReceived) { [tracker] task, _ in
            tracker.update(written: task.countOfBytesReceived)
        }
    }
}
