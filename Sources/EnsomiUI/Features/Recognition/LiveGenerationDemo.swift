#if os(macOS)
import Foundation
import Observation
import SwiftUI
import EnsomiCore

/// Listen & Play: recognizes the music playing nearby, finds its recording in the library, makes the whole chart from
/// that recording with R2 on this device while sync locks, and plays it from the heard position after final lock.
@MainActor
@Observable
public final class LiveGenerationDemoModel {
    /// Makes the whole chart of a recording at a star and seed.
    typealias MakeChart = @MainActor @Sendable (URL, Double, UInt64) async throws -> Mania4KGeneratedChart

    public let recognition: LiveRecognitionSyncModel
    /// Plays the chart; its `starDifficulty` is the star the chart is made at.
    public let gameplay: Mania4KPlaySessionModel
    public let models: ChartModelLibrary
    public private(set) var checkingModel = false
    public private(set) var modelReady = false
    /// Selectable stars in 0.1 steps; nil until the model has answered.
    public private(set) var starRange: ClosedRange<Double>?
    /// Blank for a new chart each time, or the seed of a chart to make again.
    public var seedText = ""
    /// The seed of the chart being made for the identified song.
    public private(set) var chartSeed: UInt64?
    /// The note count of that chart once it is made.
    public private(set) var chartNoteCount: Int?
    public private(set) var showingGameplay = false
    public private(set) var errorMessage: String?
    @ObservationIgnored private let stars: @MainActor @Sendable () async throws -> ClosedRange<Double>
    @ObservationIgnored private let makeChart: MakeChart
    /// The chart of the identified song: started when its recording is resolved, played at final lock.
    @ObservationIgnored private(set) var chart: Task<Mania4KGeneratedChart, Error>?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var teardown: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()

    /// Generates with the model `models` holds, as Generate a Chart does on this device.
    public convenience init(recognition: LiveRecognitionSyncModel = .liveDebug(), gameplay: Mania4KPlaySessionModel = .init(),
                            models: ChartModelLibrary = .shared) {
        self.init(recognition: recognition, gameplay: gameplay, models: models,
            stars: { try await models.generator().selectableStars },
            makeChart: { try await models.generator().chart(audioURL: $0, star: $1, seed: $2) })
    }

    /// `stars` gives the model's selectable stars; `makeChart` makes a chart with it.
    init(recognition: LiveRecognitionSyncModel, gameplay: Mania4KPlaySessionModel, models: ChartModelLibrary,
         stars: @escaping @MainActor @Sendable () async throws -> ClosedRange<Double>, makeChart: @escaping MakeChart) {
        self.recognition = recognition
        self.gameplay = gameplay
        self.models = models
        self.stars = stars
        self.makeChart = makeChart
        recognition.startsPrototypeInference = false
        recognition.setReferenceHandler { [weak self] audioURL in self?.referenceResolved(audioURL) }
        recognition.setPlaySessionRequestHandler { [weak self] request in self?.locked(request) }
    }

    public var starValidationMessage: String? {
        guard let starRange, !starRange.contains(gameplay.starDifficulty) else { return nil }
        return "Choose a star target within the supported range (\(starRange.lowerBound)–\(starRange.upperBound))."
    }

    public var seedValidationMessage: String? {
        ChartGenerationSeed.validationMessage(seedText)
    }

    /// Opens the model, then starts recognition. Without an installed model nothing starts; the window offers the
    /// install.
    public func enter() {
        invalidate()
        let identity = generation
        let cleanup = teardown
        errorMessage = nil
        guard models.hasModel else { return }
        checkingModel = true
        task = Task { [weak self] in
            guard let self else { return }
            await cleanup?.value
            guard generation == identity, !Task.isCancelled else { return }
            do {
                let range = try await stars()
                try Task.checkCancellation()
                guard generation == identity else { return }
                starRange = range
                gameplay.starDifficulty = min(max(gameplay.starDifficulty, range.lowerBound), range.upperBound)
                modelReady = true
                checkingModel = false
                recognition.refreshLocalLibraryStatus()
                recognition.startFlow()
            } catch {
                guard generation == identity, !Task.isCancelled else { return }
                checkingModel = false
                errorMessage = "The on-device model could not be opened: \(OnDeviceChartGenerator.message(for: error))"
            }
        }
    }

    private func invalidate() {
        generation = UUID()
        let oldTask = task
        oldTask?.cancel()
        task = nil
        replaceChart(with: nil)
        let previousCleanup = teardown
        teardown = Task {
            await previousCleanup?.value
            await oldTask?.value
            await recognition.stopAndWait()
            await gameplay.quitToSetup()
        }
        modelReady = false
        checkingModel = false
        showingGameplay = false
    }

    public func stop() {
        invalidate()
    }

    /// Starts the chart as soon as the song's recording is known, with the star and seed set then, so that it is
    /// ready or nearly so at final lock.
    func referenceResolved(_ audioURL: URL) {
        guard modelReady, !showingGameplay else { return }
        replaceChart(with: nil)
        if let message = starValidationMessage ?? seedValidationMessage {
            errorMessage = message
            return
        }
        let star = gameplay.starDifficulty
        let seed = ChartGenerationSeed.parse(seedText) ?? ChartGenerationSeed.random()
        let makeChart = self.makeChart
        let chart = Task { try await makeChart(audioURL, star, seed) }
        replaceChart(with: chart)
        chartSeed = seed
        errorMessage = nil
        Task { [weak self] in
            do {
                let made = try await chart.value
                guard let self, self.chart == chart else { return }
                chartNoteCount = made.objectCount
            } catch {
                guard let self, self.chart == chart, !chart.isCancelled else { return }
                errorMessage = OnDeviceChartGenerator.message(for: error)
            }
        }
    }

    private func replaceChart(with chart: Task<Mania4KGeneratedChart, Error>?) {
        self.chart?.cancel()
        self.chart = chart
        chartSeed = nil
        chartNoteCount = nil
    }

    /// Plays the chart started at resolution, waiting for it if it is not made yet.
    func locked(_ request: LiveRecognitionPlaySessionRequest) {
        guard modelReady, !showingGameplay, let chart else { return }
        showingGameplay = true
        let identity = generation
        task = Task { [weak self] in
            guard let self, generation == identity else { return }
            _ = await gameplay.startAmbientChartPlay(audioFileURL: request.audioFileURL,
                referenceTimeMS: request.referenceTimeMS, anchorHostTimeMS: request.anchorHostTimeMS,
                title: request.title, chart: chart)
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
        .onChange(of: model.models.activeModel) {
            if !model.showingGameplay { model.enter() }
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
                status("Model", complete: model.modelReady, active: model.checkingModel)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                status("Recognize", complete: model.recognition.latestMatch != nil,
                    active: model.modelReady && model.recognition.latestMatch == nil)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                status("Sync", complete: model.recognition.latestAmbientSnapshot?.phase == .final,
                    active: model.recognition.phase == .ambientSyncing)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                status("Chart", complete: model.chartNoteCount != nil,
                    active: model.chartSeed != nil && model.chartNoteCount == nil)
            }
            Text(headline).font(.headline)
            if let title = model.recognition.latestMatch?.title { Text(title).font(.title2) }
            if let error = model.errorMessage ?? model.recognition.errorMessage {
                Text(error).foregroundStyle(.red).textSelection(.enabled)
            }
            if !model.models.hasModel {
                ChartModelManagementView(library: model.models)
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
            if let range = model.starRange {
                chartControls(range: range)
            }
            DisclosureGroup("Audio input") {
                Picker("Listen to", selection: Binding(get: { model.recognition.musicSource }, set: { model.recognition.musicSource = $0 })) {
                    Text("Microphone").tag(MusicSource.background)
                    Text("System audio").tag(MusicSource.systemAudio)
                }.disabled(!model.recognition.canChangeMusicSource)
                Text("Import this recording in Library first. Recognition credentials are read from .env.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button("Retry / Start") { model.enter() }.buttonStyle(.borderedProminent)
                    .disabled(model.checkingModel || !model.models.hasModel)
                Button("Stop") { model.stop() }
                Spacer()
                Text("4-key gameplay").foregroundStyle(.secondary)
            }
        }.padding(32).frame(maxWidth: 760).frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var headline: String {
        if model.checkingModel { return "Opening the on-device model…" }
        if !model.models.hasModel { return "Install the on-device model to play. Listen & Play makes the chart on this device." }
        return model.recognition.statusMessage
    }

    /// The star and seed, as Generate a Chart takes them. They are fixed once the chart starts.
    private func chartControls(range: ClosedRange<Double>) -> some View {
        let stars = Binding(get: { model.gameplay.starDifficulty }, set: { model.gameplay.starDifficulty = $0 })
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Stars")
                Stepper(value: stars, in: range, step: 0.1) {
                    TextField("Stars", value: stars, format: .number.precision(.fractionLength(1)))
                        .labelsHidden()
                        .multilineTextAlignment(.trailing)
                        .frame(width: 64)
                }
                .disabled(model.chartSeed != nil)
                Text("Seed").padding(.leading, 16)
                TextField("Seed", text: $model.seedText, prompt: Text("Random"))
                    .labelsHidden()
                    .frame(maxWidth: 200)
                    .disabled(model.chartSeed != nil)
                if let seed = model.chartSeed {
                    Text("This chart: seed \(seed)").monospacedDigit().textSelection(.enabled)
                    Button("Use Seed") { model.seedText = String(seed) }
                }
            }
            if let message = model.starValidationMessage ?? model.seedValidationMessage {
                Text(message).font(.caption).foregroundStyle(.red)
            }
            Text("The chart is made when the song is identified, with the stars and seed set then. A blank seed makes a new chart each time; the same recording, stars and seed make the same chart again.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func status(_ title: String, complete: Bool, active: Bool) -> some View {
        Label(title, systemImage: complete ? "checkmark.circle.fill" : (active ? "circle.dotted" : "circle"))
            .foregroundStyle(complete ? Color.green : (active ? Color.accentColor : Color.secondary))
    }
}
#endif
