import EnsomiCore
import EnsomiUI
import SwiftUI

@main
@MainActor
struct EnsomiIOSApp: App {
    @State private var model: Mania4KPlaySessionModel

    init() {
        let model = Mania4KPlaySessionModel(settings: .standard)
        model.followSystemOutputDevice()
        _model = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup {
            EnsomiWorkbenchView(maniaModel: model)
        }
    }
}
