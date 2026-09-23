import EnsomiCore
import EnsomiUI
import SwiftUI

@main
@MainActor
struct EnsomiIOSApp: App {
    @State private var model = Mania4KPlaySessionModel()

    var body: some Scene {
        WindowGroup {
            EnsomiWorkbenchView(maniaModel: model)
        }
    }
}
