#if os(macOS)
import Foundation
import Observation
import SwiftUI
import EnsomiCore

@MainActor
@Observable
public final class LiveGenerationDemoModel {
    public let recognition: LiveRecognitionSyncModel
    public let gameplay: Mania4KPlaySessionModel
    public private(set) var checkingBackend = false
    public private(set) var backendReady = false
    public private(set) var showingGameplay = false
    public private(set) var errorMessage: String?
    public private(set) var availableStyles: [String] = []
    public var backendAddress = ControlledGenerationClient.defaultURL.absoluteString
    public var difficultyEnabled = false
    public var difficulty = 3.0
    public var style = ""
    @ObservationIgnored private let checkBackend: @Sendable (URL) async throws -> GenerationServiceHealth
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var teardown: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var activeEndpoint = ControlledGenerationClient.defaultURL

    public init(recognition: LiveRecognitionSyncModel = .liveDebug(), gameplay: Mania4KPlaySessionModel = .init(),
                checkBackend: @escaping @Sendable (URL) async throws -> GenerationServiceHealth = {
                    try await ControlledGenerationClient(baseURL: $0).health()
                }) {
        self.checkBackend = checkBackend
        self.recognition = recognition
        self.gameplay = gameplay
        recognition.startsPrototypeInference = false
        recognition.setPlaySessionRequestHandler { [weak self] request in self?.locked(request) }
    }

    public func enter() {
        invalidate()
        let identity = generation
        let cleanup = teardown
        checkingBackend = true
        errorMessage = nil
        task = Task { [weak self] in
            guard let self else { return }
            await cleanup?.value
            guard generation == identity, !Task.isCancelled else { return }
            do {
                guard let url = URL(string: backendAddress.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                    throw GenerationServiceFailure("Enter a valid backend address.")
                }
                let health = try await checkBackend(url)
                try Task.checkCancellation()
                guard generation == identity else { return }
                availableStyles = health.styleNames
                if !availableStyles.contains(style) { style = "" }
                activeEndpoint = url
                backendReady = true
                checkingBackend = false
                recognition.refreshLocalLibraryStatus()
                recognition.startFlow()
            } catch {
                guard generation == identity, !Task.isCancelled else { return }
                checkingBackend = false
                errorMessage = "Backend unavailable: \(error.localizedDescription)"
            }
        }
    }

    private func invalidate() {
        generation = UUID()
        let oldTask = task
        oldTask?.cancel()
        task = nil
        let previousCleanup = teardown
        teardown = Task {
            await previousCleanup?.value
            await oldTask?.value
            await recognition.stopAndWait()
            await gameplay.quitToSetup()
        }
        backendReady = false
        checkingBackend = false
        showingGameplay = false
    }

    public func stop() {
        invalidate()
    }

    private func locked(_ request: LiveRecognitionPlaySessionRequest) {
        guard backendReady, !showingGameplay else { return }
        showingGameplay = true
        let identity = generation
        let controls = GenerationControls(difficulty: difficultyEnabled ? difficulty : nil,
            style: style.isEmpty ? [:] : [style: 1])
        task = Task { [weak self] in
            guard let self, generation == identity else { return }
            _ = await gameplay.startAmbientControlledPlay(audioFileURL: request.audioFileURL,
                referenceTimeMS: request.referenceTimeMS, anchorHostTimeMS: request.anchorHostTimeMS,
                title: request.title, controls: controls, endpointURL: activeEndpoint)
        }
    }
}

public struct LiveGenerationDemoView: View {
    public static let windowID = "live-generation-demo"
    @State private var model: LiveGenerationDemoModel
    @AppStorage("mania4k.keyBindings") private var storedKeyBindings = Mania4KKeyBindingSet.default.storageValue
    @AppStorage("mania4k.offsetCalibrationState") private var storedCalibration = ""
    public init(model: LiveGenerationDemoModel) { _model = State(initialValue: model) }

    public var body: some View {
        VStack(spacing: 0) {
            if model.showingGameplay {
                VStack(spacing: 0) {
                    HStack {
                        Text("Live Demo").font(.headline)
                        Spacer()
                        Button("Recognize again") { model.enter() }
                    }.padding(12)
                    Mania4KPlayExperienceView(model: model.gameplay)
                }
            } else {
                setup
            }
        }
        .frame(minWidth: 760, minHeight: 560)
        .onAppear {
            if let keys = Mania4KKeyBindingSet(storageValue: storedKeyBindings) { model.gameplay.applyKeyBindings(keys) }
            if let calibration = Mania4KOffsetCalibrationStoredState(storageValue: storedCalibration) {
                model.gameplay.updateOffsetState(calibration)
            }
            model.enter()
        }
        .onDisappear { model.stop() }
        .onChange(of: model.gameplay.phase) { _, phase in
            if model.showingGameplay, phase == .setup { model.stop() }
        }
    }

    private var setup: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack {
                Image(systemName: "waveform.circle.fill").font(.system(size: 42)).foregroundStyle(.tint)
                VStack(alignment: .leading) {
                    Text("Listen & Play").font(.largeTitle.bold())
                    Text("Recognize the music, lock its position, and play.").foregroundStyle(.secondary)
                }
            }
            HStack {
                status("Backend", complete: model.backendReady, active: model.checkingBackend)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                status("Recognize", complete: model.recognition.latestMatch != nil,
                    active: model.backendReady && model.recognition.latestMatch == nil)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                status("Sync", complete: model.recognition.latestAmbientSnapshot?.phase == .final,
                    active: model.recognition.phase == .ambientSyncing)
            }
            Text(model.checkingBackend ? "Checking generation backend…" : model.recognition.statusMessage)
                .font(.headline)
            if let title = model.recognition.latestMatch?.title { Text(title).font(.title2) }
            if let error = model.errorMessage ?? model.recognition.errorMessage {
                Text(error).foregroundStyle(.red).textSelection(.enabled)
            }
            if model.recognition.phase == .awaitingLocalConfirmation {
                Picker("Local recording", selection: Binding(get: { model.recognition.selectedResolveAssetID }, set: { model.recognition.selectedResolveAssetID = $0 })) {
                    ForEach(model.recognition.resolveResults, id: \.asset.id) { result in
                        Text(result.asset.fileName).tag(Optional(result.asset.id))
                    }
                }
                Button("Use this recording") { model.recognition.startAmbientSyncForSelectedResult() }
                    .disabled(!model.recognition.canStartAmbientSync)
            }
            Divider()
            HStack {
                Toggle("Difficulty target", isOn: $model.difficultyEnabled)
                Slider(value: $model.difficulty, in: 1.5...6, step: 0.5).disabled(!model.difficultyEnabled)
                Text(model.difficultyEnabled ? String(format: "%.1f", model.difficulty) : "Auto").monospacedDigit().frame(width: 48)
            }
            Picker("Style", selection: $model.style) {
                Text("Automatic").tag("")
                ForEach(model.availableStyles, id: \.self) { value in Text(styleTitle(value)).tag(value) }
            }
            Text("Optional targets apply when the song locks. Generated difficulty and style may vary.")
                .font(.caption).foregroundStyle(.secondary)
            DisclosureGroup("Connection & audio input") {
                TextField("Backend address", text: $model.backendAddress)
                Picker("Listen to", selection: Binding(get: { model.recognition.musicSource }, set: { model.recognition.musicSource = $0 })) {
                    Text("Microphone").tag(MusicSource.background)
                    Text("System audio").tag(MusicSource.systemAudio)
                }.disabled(!model.recognition.canChangeMusicSource)
                Text("Import this recording in Library first. Recognition credentials are read from .env.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button("Retry / Start") { model.enter() }.buttonStyle(.borderedProminent)
                    .disabled(model.checkingBackend)
                Button("Stop") { model.stop() }
                Spacer()
                Text("4-key gameplay").foregroundStyle(.secondary)
            }
        }.padding(32).frame(maxWidth: 760).frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func status(_ title: String, complete: Bool, active: Bool) -> some View {
        Label(title, systemImage: complete ? "checkmark.circle.fill" : (active ? "circle.dotted" : "circle"))
            .foregroundStyle(complete ? Color.green : (active ? Color.accentColor : Color.secondary))
    }
    private func styleTitle(_ value: String) -> String {
        switch value {
        case "jack-organization": "Jack"
        case "stream-organization": "Stream"
        case "trill-organization": "Trill"
        case "tech": "Tech"
        case "ln-coordination": "Long-note coordination"
        default: value
        }
    }
}
#endif
