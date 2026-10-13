import EnsomiCore
import SwiftUI

public struct EnsomiWorkbenchView: View {
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    @AppStorage("mania4k.keyBindings") private var storedKeyBindings = Mania4KKeyBindingSet.default.storageValue
    @State private var selection: WorkbenchTab?
    @State private var maniaModel: Mania4KPlaySessionModel
    @State private var localLibraryModel: LocalLibraryDashboardModel
    @State private var isShowingSettings = false
    @State private var didRestoreStoredManiaSettings = false

    public init(
        maniaModel: Mania4KPlaySessionModel = Mania4KPlaySessionModel(),
        localLibraryModel: LocalLibraryDashboardModel = .livePrototype(),
        initialSelection: WorkbenchTab = .localLibrary
    ) {
        _selection = State(initialValue: initialSelection)
        _maniaModel = State(initialValue: maniaModel)
        _localLibraryModel = State(initialValue: localLibraryModel)
    }

    public var body: some View {
        navigation
        .onAppear(perform: restoreStoredManiaSettingsIfNeeded)
        #if os(iOS)
        .sheet(isPresented: $isShowingSettings) {
            NavigationStack {
                EnsomiSettingsView(model: maniaModel)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { isShowingSettings = false }
                        }
                    }
            }
        }
        #endif
        #if os(macOS)
        .toolbar { settingsToolbar }
        #endif
        #if os(macOS)
        .toolbar {
            ToolbarItem {
                Button { openWindow(id: LiveGenerationDemoView.windowID) } label: {
                    Label("Live Demo", systemImage: "play.circle.fill")
                }
                .help("Recognize the music playing, synchronize and play a chart made on this device")
                .keyboardShortcut("d", modifiers: [.command, .shift])
            }
        }
        #endif
        #if os(macOS) && DEBUG
        .toolbar {
            ToolbarItem {
                Button {
                    openWindow(id: LiveRecognitionSyncWindow.windowID)
                } label: {
                    Label("Recognition Flow", systemImage: "waveform.badge.magnifyingglass")
                }
                .help("Open Recognition Sync Flow")
            }
        }
        #endif
    }

    @ToolbarContentBuilder
    private var settingsToolbar: some ToolbarContent {
        ToolbarItem {
            #if os(macOS)
            SettingsLink {
                Label("Settings", systemImage: "gearshape")
            }
            .help("Open Settings")
            #else
            Button {
                isShowingSettings = true
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            #endif
        }
    }

    @ViewBuilder
    private var navigation: some View {
        #if os(macOS)
        NavigationSplitView {
            List(selection: $selection) {
                Label("Library", systemImage: "music.note.list")
                    .tag(WorkbenchTab.localLibrary)
                Label("Play", systemImage: "square.grid.2x2")
                    .tag(WorkbenchTab.play)
            }
            .navigationTitle("Ensomi")
            .navigationSplitViewColumnWidth(min: 160, ideal: 190)
        } detail: {
            switch selection ?? .localLibrary {
            case .localLibrary:
                LocalLibraryDashboardView(model: localLibraryModel)
            case .play:
                playExperience
            }
        }
        #else
        TabView(selection: $selection) {
            NavigationStack {
                LocalLibraryDashboardView(model: localLibraryModel)
                    .toolbar { settingsToolbar }
            }
            .tabItem { Label("Library", systemImage: "music.note.list") }
            .tag(Optional(WorkbenchTab.localLibrary))

            NavigationStack {
                playExperience
                    .toolbar { settingsToolbar }
            }
            .tabItem { Label("Play", systemImage: "square.grid.2x2") }
            .tag(Optional(WorkbenchTab.play))
        }
        #endif
    }

    private var playExperience: some View {
        Mania4KPlayExperienceView(model: maniaModel)
        .navigationTitle("Play")
    }

    private func restoreStoredManiaSettingsIfNeeded() {
        guard !didRestoreStoredManiaSettings else {
            return
        }

        didRestoreStoredManiaSettings = true

        guard let keyBindings = Mania4KKeyBindingSet(storageValue: storedKeyBindings) else {
            storedKeyBindings = Mania4KKeyBindingSet.default.storageValue
            maniaModel.applyKeyBindings(.default)
            return
        }

        maniaModel.applyKeyBindings(keyBindings)
    }
}

public enum WorkbenchTab: Hashable {
    case localLibrary
    case play
}

#Preview {
    EnsomiWorkbenchView()
}
