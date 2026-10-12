import CryptoKit
import XCTest
@testable import EnsomiCore

/// Files of the sibling checkouts that on-device tests need. Each test skips when its files are absent.
enum OnDeviceFixtures {
    /// `.../ensomi/Tests/EnsomiCoreTests/OnDeviceTestSupport.swift` -> the app root, then its parent.
    static let workspace = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func existing(_ relativePath: String, environment: String? = nil) throws -> URL {
        let override = environment.flatMap { ProcessInfo.processInfo.environment[$0] }
        let url = override.map { URL(fileURLWithPath: $0) } ?? workspace.appendingPathComponent(relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else { throw XCTSkip("\(url.path) is not here.") }
        return url
    }

    /// The published model's `onnx/` directory.
    static func model() throws -> URL {
        try existing("ensomi-model/artifacts/release-r2/bulk/hf/ensomi-r2/onnx", environment: "ENSOMI_R2_ONNX")
    }

    /// The runtime's resampler tables, which the runtime package carries; the command line generator reads them here.
    static func resampler() throws -> URL {
        try existing("ensomi-runtime/assets/resampler", environment: "ENSOMI_RESAMPLER")
    }

    static func song() throws -> URL {
        try existing("ensomi-model/dataset/0/1403084/audio.mp3", environment: "ENSOMI_SONG")
    }

    /// The native command line generator.
    static func cli() throws -> URL {
        try existing("ensomi-runtime/target/apple/release/generate", environment: "ENSOMI_GENERATE_CLI")
    }
}

func temporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// A library with its own defaults and models directory, and no model unless `modelsDirectory` has one.
@MainActor
func isolatedLibrary(
    defaults: UserDefaults = UserDefaults(suiteName: UUID().uuidString)!,
    modelsDirectory: URL? = nil,
    downloader: ChartModelDownloader = ChartModelDownloader()
) -> ChartModelLibrary {
    ChartModelLibrary(
        defaults: defaults,
        modelsDirectory: modelsDirectory ?? FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString),
        downloader: downloader
    )
}

/// A generation model that talks to `client` because it has no on-device model.
@MainActor
func serverModel(_ client: ChartGenerationClient) -> Mania4KChartGenerationModel {
    Mania4KChartGenerationModel(client: client, models: isolatedLibrary(), defaults: UserDefaults(suiteName: UUID().uuidString)!)
}

/// A directory shaped like the published one: symbolic links to the model's files, `listing.json` in the shape of
/// Hugging Face's tree API (an `lfs.oid` for every file but the JSON ones) and the files under `onnx/`.
func publishedCopy(
    of model: URL, in directory: URL, corrupting corrupted: String? = nil, omitting omitted: String? = nil
) throws -> ChartModelSource {
    let fileManager = FileManager.default
    let files = directory.appendingPathComponent("onnx", isDirectory: true)
    try fileManager.createDirectory(at: files, withIntermediateDirectories: true)
    var listing: [[String: Any]] = []
    for name in try fileManager.contentsOfDirectory(atPath: model.path).sorted() where name != omitted {
        let original = model.appendingPathComponent(name)
        let data = try Data(contentsOf: original, options: .mappedIfSafe)
        var entry: [String: Any] = ["type": "file", "path": "onnx/\(name)", "size": data.count]
        if !name.hasSuffix(".json") {
            entry["lfs"] = ["oid": SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), "size": data.count]
        }
        listing.append(entry)
        let published = files.appendingPathComponent(name)
        if name == corrupted {
            var changed = data
            changed[0] ^= 0xFF
            try changed.write(to: published)
        } else {
            try fileManager.createSymbolicLink(at: published, withDestinationURL: original)
        }
    }
    let listingFile = directory.appendingPathComponent("listing.json")
    try JSONSerialization.data(withJSONObject: listing).write(to: listingFile)
    return ChartModelSource(listing: listingFile, files: URL(fileURLWithPath: files.path, isDirectory: true))
}
