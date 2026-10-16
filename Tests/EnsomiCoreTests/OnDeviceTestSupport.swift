import CryptoKit
import EnsomiRuntime
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

    /// The runtime's resampler tables, which the runtime package carries.
    static func resampler() throws -> URL {
        try existing("ensomi-runtime/assets/resampler", environment: "ENSOMI_RESAMPLER")
    }

    static func song() throws -> URL {
        try existing("ensomi-model/dataset/0/1403084/audio.mp3", environment: "ENSOMI_SONG")
    }
}

/// The file's SHA-256 in lowercase hex, as the library stores it.
func sha256Hex(of url: URL) throws -> String {
    SHA256.hash(data: try Data(contentsOf: url, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
}

func temporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// A library with its own defaults and models directory, and no model unless `modelsDirectory` has one. Unless a test
/// passes `downloader`, a download reads a directory that does not exist and fails without the network.
@MainActor
func isolatedLibrary(
    defaults: UserDefaults = UserDefaults(suiteName: UUID().uuidString)!,
    modelsDirectory: URL? = nil,
    downloader: ChartModelDownloader = ChartModelDownloader(
        source: ChartModelSource(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true))
    )
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

/// A model package's files in a directory, before the manifest that lists them is written.
struct PackageFixture {
    let directory: URL
    /// The paths the manifest lists.
    let files: [String]
    let notices: [String]

    /// The published R2 package: `runtime.json` and the files it names, in `OnDeviceFixtures.model()`.
    static func r2() throws -> PackageFixture {
        let directory = try OnDeviceFixtures.model()
        let config = try String(contentsOf: directory.appendingPathComponent("runtime.json"), encoding: .utf8)
        return PackageFixture(
            directory: directory, files: ["runtime.json"] + (try modelFiles(runtimeConfig: config)), notices: try noticeFiles(runtimeConfig: config)
        )
    }

    /// Three small files whose manifest `checkPackage` accepts and which the generator cannot open, for the checks
    /// that come before the generator opens a download and so run without the model.
    static func small(in directory: URL) throws -> PackageFixture {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("BeatThis licence text\n".utf8).write(to: directory.appendingPathComponent("BeatThis-LICENSE.txt"))
        try Data("{}".utf8).write(to: directory.appendingPathComponent("runtime.json"))
        try Data((0..<4_096).map { UInt8(truncatingIfNeeded: $0) }).write(to: directory.appendingPathComponent("weights.bin"))
        return PackageFixture(directory: directory, files: ["BeatThis-LICENSE.txt", "runtime.json", "weights.bin"], notices: ["BeatThis-LICENSE.txt"])
    }

    /// The manifest the exporter writes for these files: each with its size and SHA-256, sorted by path.
    func manifest() throws -> [String: Any] {
        let entries = try files.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }.map { path -> [String: Any] in
            let data = try Data(contentsOf: directory.appendingPathComponent(path), options: .mappedIfSafe)
            return ["path": path, "size": data.count, "sha256": SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()]
        }
        return [
            "format": "ensomi-package/1",
            "model": ["id": "ensomi-r2", "name": "Ensomi R2", "version": "r2-1.0"],
            "license": "CC-BY-NC-SA-4.0 AND MIT",
            "notices": notices,
            "pipeline": ["id": "r2", "version": 1] as [String: Any],
            "min_runtime": "0.3.0",
            "config": "runtime.json",
            "files": entries,
        ]
    }

    /// Writes the manifest beside the files, and the record of an install from the pinned revision, as an install made
    /// from it holds them, so a library treats the install as current.
    func writeManifest() throws {
        try write(manifest: try manifest(), in: directory)
        let manifestSHA256 = try sha256Hex(of: directory.appendingPathComponent(ChartModelDownloader.manifestName))
        try ChartModelInstallRecord(revision: ChartModelSource.pinnedRevision, manifestSHA256: manifestSHA256).write(in: directory)
    }
}

func write(manifest: [String: Any], in directory: URL) throws {
    try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
        .write(to: directory.appendingPathComponent(ChartModelDownloader.manifestName))
}

/// A directory shaped like the published one: `onnx/` with symbolic links to the package's files and the manifest
/// that lists them. `corrupting` serves a file whose first byte differs from the one the manifest describes,
/// `omitting` leaves a file out of the manifest and the directory, and `edit` changes the manifest.
func publishedCopy(
    of package: PackageFixture, in directory: URL, corrupting corrupted: String? = nil, omitting omitted: String? = nil,
    manifest edit: (inout [String: Any]) -> Void = { _ in }
) throws -> ChartModelSource {
    let fileManager = FileManager.default
    let published = directory.appendingPathComponent("onnx", isDirectory: true)
    try fileManager.createDirectory(at: published, withIntermediateDirectories: true)
    let listed = PackageFixture(directory: package.directory, files: package.files.filter { $0 != omitted }, notices: package.notices)
    var manifest = try listed.manifest()
    edit(&manifest)
    try write(manifest: manifest, in: published)
    for name in listed.files {
        let original = package.directory.appendingPathComponent(name)
        if name == corrupted {
            var changed = try Data(contentsOf: original)
            changed[0] ^= 0xFF
            try changed.write(to: published.appendingPathComponent(name))
        } else {
            try fileManager.createSymbolicLink(at: published.appendingPathComponent(name), withDestinationURL: original)
        }
    }
    return ChartModelSource(directory: URL(fileURLWithPath: published.path, isDirectory: true))
}
