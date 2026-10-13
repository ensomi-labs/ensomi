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

/// The licence of the model in use, from its package, with the notices that come with it; then ONNX Runtime's licence
/// and notices, since the app ships ONNX Runtime inside the runtime. Each text opens in a sheet.
struct ChartModelLicenseView: View {
    let library: ChartModelLibrary
    @State private var shown: ChartModelLicense.Notice?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let license = library.license {
                if let expression = license.expression {
                    Text("Model licence: \(expression). The [model card](https://huggingface.co/sed-i/ensomi-r2) says what each part covers.")
                } else {
                    Text("The [model card](https://huggingface.co/sed-i/ensomi-r2) gives the model's licence.")
                }
                noticeButtons(license.notices)
            }
            Text("ONNX Runtime runs the model on this device.")
            noticeButtons(ChartModelLicense.onnxRuntimeNotices)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .sheet(item: $shown) { LicenseTextView(notice: $0) }
    }

    private func noticeButtons(_ notices: [ChartModelLicense.Notice]) -> some View {
        HStack(spacing: 12) {
            ForEach(notices) { notice in
                Button(notice.name) { shown = notice }
            }
        }
        .buttonStyle(.borderless)
    }
}

/// A licence text in full, a paragraph at a time so that long notices scroll without laying out at once.
private struct LicenseTextView: View {
    let notice: ChartModelLicense.Notice
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    Text(notice.name)
                        .font(.headline)
                    ForEach(Array(notice.text.components(separatedBy: "\n\n").enumerated()), id: \.offset) { _, paragraph in
                        Text(paragraph)
                    }
                }
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, idealWidth: 640, minHeight: 480, idealHeight: 640)
        #endif
    }
}
