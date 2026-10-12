import EnsomiCore
import SwiftUI
import UniformTypeIdentifiers

/// Installs the model the on-device generator runs: downloads the published one or uses a folder that holds it.
struct ChartModelManagementView: View {
    let library: ChartModelLibrary
    @State private var isChoosingFolder = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(status, systemImage: library.hasModel ? "checkmark.circle" : "arrow.down.circle")
                .fixedSize(horizontal: false, vertical: true)

            switch library.download {
            case .idle:
                EmptyView()
            case .downloading(let fraction):
                ProgressView(value: fraction) {
                    EmptyView()
                } currentValueLabel: {
                    Text(fraction, format: .percent.precision(.fractionLength(0)))
                }
                .accessibilityLabel("Model download")
            case .failed(let message):
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .textSelection(.enabled)
            }
            if let message = library.folderError {
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .textSelection(.enabled)
            }

            HStack {
                if case .downloading = library.download {
                    Button("Cancel Download", action: library.cancelDownload)
                } else {
                    Button(library.isDownloaded ? "Download Again" : "Download Model", systemImage: "arrow.down.circle", action: library.startDownload)
                }
                Button("Choose Folder…", systemImage: "folder") { isChoosingFolder = true }
                if library.chosenFolder != nil {
                    Button("Use Downloaded Model", action: library.forgetChosenFolder)
                        .disabled(!library.isDownloaded)
                } else if library.isDownloaded {
                    Button("Remove Download", role: .destructive, action: library.removeDownload)
                }
            }
            .buttonStyle(.bordered)
        }
        .fileImporter(isPresented: $isChoosingFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result {
                Task { await library.choose(folder: url) }
            }
        }
    }

    private var status: String {
        if case .downloading = library.download {
            return "Downloading the Ensomi R2 model."
        }
        if let folder = library.chosenFolder {
            return "Using the model in the folder “\(folder.lastPathComponent)”."
        }
        if library.isDownloaded {
            return "The Ensomi R2 model is downloaded."
        }
        return "No model is installed. Download Ensomi R2 (about 120 MB) or choose a folder that holds its onnx files."
    }
}
