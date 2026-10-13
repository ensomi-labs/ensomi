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
                if isDownloading {
                    Button("Cancel Download", action: library.cancelDownload)
                } else {
                    Button(downloadTitle, systemImage: "arrow.down.circle", action: library.startDownload)
                }
                Button("Choose Folder…", systemImage: "folder") { isChoosingFolder = true }
                if library.chosenFolder != nil {
                    Button("Use Downloaded Model", action: library.forgetChosenFolder)
                        .disabled(!library.isDownloaded)
                } else if (library.isDownloaded || library.hasPartialDownload) && !isDownloading {
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

    private var isDownloading: Bool {
        if case .downloading = library.download { return true }
        return false
    }

    private var downloadTitle: String {
        if library.hasPartialDownload { return "Resume Download" }
        return library.isDownloaded ? "Download Again" : "Download Model"
    }

    private var status: String {
        if isDownloading {
            return "Downloading the Ensomi R2 model."
        }
        if let folder = library.chosenFolder {
            return "Using the model in the folder “\(folder.lastPathComponent)”."
        }
        if library.isDownloaded {
            return "The Ensomi R2 model is downloaded."
        }
        if library.hasPartialDownload {
            return "The download of Ensomi R2 stopped before it finished. Resume Download continues from the files already here."
        }
        return "No model is installed. Download Ensomi R2 (about 120 MB) or choose a folder that holds its onnx files."
    }
}

/// The model's licence as its model card states it.
struct ChartModelLicenseView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Ensomi R2 is licensed CC BY-NC-SA 4.0: you may use, share and adapt it with credit, not for commercial purposes, and adaptations must carry the same licence. The exception is BeatThis (CPJKU): its beat-tracking graph and front-end arrays keep BeatThis's MIT licence. See the [model card](https://huggingface.co/sed-i/ensomi-r2).")
            // BeatThis's licence text goes here: the text of each file the runtime's `noticeFiles(runtimeConfig:)` names
            // in the model in use. The runtime version project.yml pins (0.2.0) has no such function.
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
    }
}
