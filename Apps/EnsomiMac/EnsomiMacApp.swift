import Foundation
import EnsomiCore
import EnsomiUI
import SwiftUI

@main
@MainActor
struct EnsomiMacApp: App {
    @Environment(\.openWindow) private var openWindow

    @State private var demo = LiveGenerationDemoModel()
    @State private var model: Mania4KPlaySessionModel
    #if DEBUG
    @State private var recognitionModel: LiveRecognitionSyncModel
    #endif

    init() {
        _model = State(initialValue: Mania4KPlaySessionModel())
        #if DEBUG
        _recognitionModel = State(initialValue: .liveDebug())
        #endif
    }

    var body: some Scene {
        WindowGroup {
            EnsomiWorkbenchView(
                maniaModel: model,
                initialSelection: model.isReadyToStart ? .play : .localLibrary,
                onAmbientRecognitionRequested: openRecognitionSyncFromMode3(isMock:)
            )
            .frame(minWidth: 640, minHeight: 480)
        }
        .defaultSize(width: 1100, height: 760)
        #if DEBUG
        .commands {
            CommandMenu("Debug") {
                Button("Open Recognition Sync Flow") {
                    openRecognitionSync()
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
            }
        }
        #endif

        Settings {
            EnsomiSettingsView(model: model)
        }
        .defaultSize(width: 560, height: 680)

        #if DEBUG
        Window(LiveRecognitionSyncWindow.windowTitle, id: LiveRecognitionSyncWindow.windowID) {
            LiveRecognitionSyncWindow(model: recognitionModel)
                .onAppear {
                    recognitionModel.setPlaySessionRequestHandler(openPlaySession(from:))
                }
        }
        .defaultSize(width: 980, height: 760)
        #endif

        Window("Listen & Play", id: LiveGenerationDemoView.windowID) {
            LiveGenerationDemoView(model: demo)
        }
        .defaultSize(width: 960, height: 760)

        Window(Mania4KPlaySessionWindow.windowTitle, id: Mania4KPlaySessionWindow.windowID) {
            Mania4KPlayExperienceView(
                model: model,
                onAmbientRecognitionRequested: openRecognitionSyncFromMode3(isMock:)
            )
        }
        .defaultSize(width: 1120, height: 780)
    }

    #if DEBUG
    private func openRecognitionSyncFromMode3(isMock: Bool) {
        recognitionModel.inferenceIsMock = isMock
        openRecognitionSync()
    }

    private func openRecognitionSync() {
        recognitionModel.setPlaySessionRequestHandler(openPlaySession(from:))
        openWindow(id: LiveRecognitionSyncWindow.windowID)
    }

    private func openPlaySession(from request: LiveRecognitionPlaySessionRequest) {
        openWindow(id: Mania4KPlaySessionWindow.windowID)
        Task {
            await model.startAmbientGeneratedBackendPlay(
                audioFileURL: request.audioFileURL,
                isMock: request.isMock,
                referenceTimeMS: request.referenceTimeMS,
                anchorHostTimeMS: request.anchorHostTimeMS,
                durationMS: request.durationMS,
                title: request.title,
                musicSource: request.musicSource
            )
        }
    }
    #else
    private func openRecognitionSyncFromMode3(isMock: Bool) {}
    #endif

}
