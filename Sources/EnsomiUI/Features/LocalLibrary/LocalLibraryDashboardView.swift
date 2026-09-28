import Observation
import EnsomiCore
import SwiftUI
import UniformTypeIdentifiers

@MainActor
@Observable
public final class LocalLibraryDashboardModel {
    public var directories: [MusicLibraryDirectory] = []
    public var status: LocalAudioLibraryStatus = .empty
    public var resolveTitle = ""
    public var resolveArtist = ""
    public var resolveAlbum = ""
    public var resolveISRC = ""
    public var resolveDurationMS = ""
    public var resolveResults: [LocalResolveResult] = []
    public var errorMessage: String?

    #if os(macOS)
    public var ambientFixtureRecorderModel: AmbientSyncFixtureRecorderModel?
    #endif

    @ObservationIgnored
    private let directoryStore: any LocalMusicDirectoryManaging

    @ObservationIgnored
    private let indexer: any LocalAudioLibraryIndexing

    @ObservationIgnored
    private let resolver: any LocalTrackResolving

    public init(
        directoryStore: any LocalMusicDirectoryManaging,
        indexer: any LocalAudioLibraryIndexing,
        resolver: any LocalTrackResolving
    ) {
        self.directoryStore = directoryStore
        self.indexer = indexer
        self.resolver = resolver
    }

    public static func livePrototype() -> LocalLibraryDashboardModel {
        livePrototype(databaseOpener: LocalAudioLibraryDatabase.openDefault)
    }

    public static func livePrototype(databaseOpener: () throws -> LocalAudioLibraryDatabase) -> LocalLibraryDashboardModel {
        do {
            return livePrototype(database: try databaseOpener())
        } catch {
            let model = livePrototype(database: try! LocalAudioLibraryDatabase.openInMemory())
            model.errorMessage = "Could not open persistent local audio library: \(error.localizedDescription)"
            return model
        }
    }

    private static func livePrototype(database: LocalAudioLibraryDatabase) -> LocalLibraryDashboardModel {
        let model = LocalLibraryDashboardModel(
            directoryStore: LocalMusicDirectoryStore(database: database),
            indexer: LocalAudioLibraryIndexer(database: database),
            resolver: LocalTrackResolver(database: database)
        )
        #if os(macOS)
        model.ambientFixtureRecorderModel = AmbientSyncFixtureRecorderModel {
            await database.listAssets()
        }
        #endif
        return model
    }

    public func refresh() {
        Task {
            await refreshNow()
        }
    }

    public func addDirectory(_ url: URL) {
        Task {
            do {
                try await directoryStore.addDirectory(url, recursive: true)
                await refreshNow()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    public func removeDirectory(_ directory: MusicLibraryDirectory) {
        Task {
            do {
                try await directoryStore.removeDirectory(id: directory.id)
                await refreshNow()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    public func rescanAll() {
        Task {
            await indexer.rescanAll()
            errorMessage = nil
            await refreshNow()
        }
    }

    public func resolveManualTrack() {
        let artists = resolveArtist
            .components(separatedBy: CharacterSet(charactersIn: ",;&"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let duration = Int(resolveDurationMS.trimmingCharacters(in: .whitespacesAndNewlines))
        let track = CanonicalTrack(
            title: resolveTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            artists: artists,
            album: resolveAlbum.trimmedNilIfEmpty,
            durationMS: duration,
            isrc: resolveISRC.trimmedNilIfEmpty,
            providerIDs: [.init(provider: .manual, value: "manual-debug")]
        )

        Task {
            resolveResults = await resolver.resolve(track)
        }
    }

    private func refreshNow() async {
        directories = await directoryStore.listDirectories()
        status = await indexer.status()
    }
}

public struct LocalLibraryDashboardView: View {
    @Bindable public var model: LocalLibraryDashboardModel
    @State private var isImportingDirectory = false

    public init(model: LocalLibraryDashboardModel) {
        self.model = model
    }

    public var body: some View {
        Form {
            Section {
                if model.directories.isEmpty {
                    ContentUnavailableView {
                        Label("Add Your Music", systemImage: "music.note.list")
                    } description: {
                        Text("Choose a folder of audio files to use for recognition and play.")
                    } actions: {
                        addFolderButton
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    ForEach(model.directories) { directory in
                        directoryRow(directory)
                    }
                    addFolderButton
                }
            } header: {
                Text("Music Folders")
            } footer: {
                Text("Removing a folder from the library keeps its audio files on disk.")
            }

            if let error = model.errorMessage {
                Section("Library Error") {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                }
            }

            Section("Scan Status") {
                statusRow("Discovered", value: model.status.discoveredCount.formatted())
                statusRow("Indexed", value: model.status.indexedCount.formatted())
                statusRow("Failed", value: model.status.failedCount.formatted())
                statusRow("Missing", value: model.status.missingCount.formatted())
                statusRow("Last scan", value: model.status.lastScanFinishedAt?.formatted(date: .abbreviated, time: .shortened) ?? "Never")
                Button("Rescan Library", systemImage: "arrow.clockwise", action: model.rescanAll)
                    .disabled(model.directories.isEmpty)
                Button("Refresh Status", systemImage: "list.bullet.rectangle", action: model.refresh)
            }

            Section {
                DisclosureGroup("Library Tools") {
                    LocalResolveDebugView(model: model)
                    #if os(macOS)
                    if let recorder = model.ambientFixtureRecorderModel {
                        AmbientSyncFixtureRecorderView(model: recorder)
                    }
                    #endif
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Library")
        .task { model.refresh() }
        .fileImporter(
            isPresented: $isImportingDirectory,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { model.addDirectory(url) }
            case .failure(let error):
                let cocoaError = error as NSError
                if cocoaError.domain != NSCocoaErrorDomain || cocoaError.code != NSUserCancelledError {
                    model.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func statusRow(_ title: String, value: String) -> some View {
        LabeledContent(title, value: value)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(value)
    }

    private var addFolderButton: some View {
        Button("Add Music Folder…", systemImage: "folder.badge.plus") {
            isImportingDirectory = true
        }
    }

    private func directoryRow(_ directory: MusicLibraryDirectory) -> some View {
        HStack(spacing: 12) {
            Label {
                Text(directory.displayPath)
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
            } icon: {
                Image(systemName: "folder")
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(role: .destructive) {
                model.removeDirectory(directory)
            } label: {
                Label("Remove", systemImage: "minus.circle")
            }
            .labelStyle(.iconOnly)
            .buttonStyle(.bordered)
            .accessibilityLabel("Remove \(directory.displayPath) from library")
            .help("Remove from library; audio files stay on disk")
        }
    }
}

private extension String {
    var trimmedNilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

#Preview {
    LocalLibraryDashboardView(model: .livePrototype())
}
