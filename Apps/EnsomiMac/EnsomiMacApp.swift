import Foundation
import EnsomiCore
import EnsomiUI
import os
import SwiftUI

@main
@MainActor
struct EnsomiMacApp: App {
    @Environment(\.openWindow) private var openWindow

    @State private var demo = LiveGenerationDemoModel()
    @State private var model: Mania4KPlaySessionModel
    @State private var inspection: Mania4KChartInspectionModel
    #if DEBUG
    @State private var recognitionModel: LiveRecognitionSyncModel
    #endif

    init() {
        Logger(subsystem: "io.ensomi.mac", category: "build").notice("\(AppBuildInfo.current.summary, privacy: .public)")
        let model = Mania4KPlaySessionModel(settings: .standard)
        model.followSystemOutputDevice()
        _model = State(initialValue: model)
        _inspection = State(initialValue: Mania4KChartInspectionModel(settings: model))
        #if DEBUG
        _recognitionModel = State(initialValue: .liveDebug())
        #endif
    }

    var body: some Scene {
        WindowGroup {
            EnsomiWorkbenchView(maniaModel: model)
            .frame(minWidth: 640, minHeight: 480)
        }
        .defaultSize(width: 1100, height: 760)
        #if DEBUG
        .commands {
            CommandMenu("Debug") {
                Button("Open Recognition Sync Flow") {
                    openWindow(id: LiveRecognitionSyncWindow.windowID)
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
        }
        .defaultSize(width: 980, height: 760)
        #endif

        Window("Listen & Play", id: LiveGenerationDemoView.windowID) {
            LiveGenerationDemoView(model: demo)
        }
        .defaultSize(width: 960, height: 760)

        Window(Mania4KPlaySessionWindow.windowTitle, id: Mania4KPlaySessionWindow.windowID) {
            Mania4KPlayExperienceView(model: model)
        }
        .defaultSize(width: 1120, height: 780)

        Window(Mania4KChartInspectorWindow.windowTitle, id: Mania4KChartInspectorWindow.windowID) {
            Mania4KChartInspectorView(model: inspection)
                .frame(minWidth: 640, minHeight: 480)
        }
        .defaultSize(width: 1120, height: 780)
    }
}
