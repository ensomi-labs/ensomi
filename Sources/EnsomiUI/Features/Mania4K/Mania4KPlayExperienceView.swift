import Foundation
import AVFoundation
import EnsomiCore
import SwiftUI
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#endif

public enum Mania4KPlaySessionWindow {
    public static let windowID = "mania4k-play-session"
    public static let windowTitle = "Ensomi Play Session"
}

public struct Mania4KPlayExperienceView: View {
    @Bindable public var model: Mania4KPlaySessionModel
    private let onAmbientRecognitionRequested: (@MainActor (Bool) -> Void)?

    public init(
        model: Mania4KPlaySessionModel,
        onAmbientRecognitionRequested: (@MainActor (Bool) -> Void)? = nil
    ) {
        self.model = model
        self.onAmbientRecognitionRequested = onAmbientRecognitionRequested
    }

    public var body: some View {
        ZStack {
            Mania4KBackdrop()

            if model.phase == .setup {
                Mania4KSetupView(
                    model: model,
                    onAmbientRecognitionRequested: onAmbientRecognitionRequested
                )
            } else {
                Mania4KPlaySceneView(model: model)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preferredColorScheme(.dark)
    }
}

private enum Mania4KSetupGameMode: String, CaseIterable, Identifiable {
    case localBeatmap = "1"
    case generatedSong = "2"
    case ambient = "3"

    var id: String {
        rawValue
    }

    var title: String {
        switch self {
        case .localBeatmap:
            return "Imported Chart"
        case .generatedSong:
            return "Generate a Chart"
        case .ambient:
            return "Listen & Play"
        }
    }

    var subtitle: String {
        switch self {
        case .localBeatmap:
            return "Play a song with an .osu beatmap"
        case .generatedSong:
            return "Create a chart from a local song"
        case .ambient:
            return "Recognize nearby music and play along"
        }
    }

    var systemImage: String {
        switch self {
        case .localBeatmap:
            return "doc.text"
        case .generatedSong:
            return "antenna.radiowaves.left.and.right"
        case .ambient:
            return "waveform.badge.magnifyingglass"
        }
    }
}

private struct SessionSummaryRow: Identifiable {
    let title: String
    let value: String

    var id: String {
        title
    }
}

private struct Mania4KSetupView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #if os(macOS) && DEBUG
    @Environment(\.openWindow) private var openWindow
    #endif
    @Bindable var model: Mania4KPlaySessionModel
    @State private var fileImportTarget: Mania4KFileImportTarget?
    @State private var isChoosingFile = false
    @State private var selectedMode: Mania4KSetupGameMode?
    @State private var backendIsMock = false
    @State private var generation = Mania4KChartGenerationModel()
    @State private var ambientModeStatus = "Idle"
    let onAmbientRecognitionRequested: (@MainActor (Bool) -> Void)?

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header(isCompact: geometry.size.width < 600 || dynamicTypeSize.isAccessibilitySize)
                    setupContent(isCompact: geometry.size.width < 800 || dynamicTypeSize.isAccessibilitySize)
                }
                .padding(24)
                .frame(maxWidth: 1080, alignment: .leading)
            }
        }
        .scrollContentBackground(.hidden)
        .fileImporter(
            isPresented: $isChoosingFile,
            // .osu is normally a dynamic UTType; using public.data avoids picker-side rejection and leaves role checks to the model.
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { result in
            handleFileImportResult(result)
        }
    }

    @ViewBuilder
    private func setupContent(isCompact: Bool) -> some View {
        if isCompact {
            VStack(alignment: .leading, spacing: 16) {
                inputPanel
                previewPanel
            }
        } else {
            HStack(alignment: .top, spacing: 16) {
                inputPanel
                    .frame(maxWidth: 460)

                previewPanel
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func header(isCompact: Bool) -> some View {
        let layout = isCompact
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 18))
        return layout {
            VStack(alignment: .leading, spacing: 6) {
                Text("Ensomi")
                    .font(.largeTitle.bold())
                    .foregroundStyle(Mania4KStyle.textPrimary)

                Text("Choose how you want to play")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Mania4KStyle.textSecondary)
            }

            if !isCompact { Spacer() }

            HStack(spacing: 10) {
                Label("4K", systemImage: "square.grid.2x2.fill")
                Text(headerStatusText)
            }
            .font(.caption.monospaced().weight(.bold))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .foregroundStyle(Mania4KStyle.textPrimary)
            .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(isSelectedModeReady ? Mania4KStyle.accentGreen : Mania4KStyle.border, lineWidth: 1)
            )
        }
    }

    private var inputPanel: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                Text("Game Mode")
                    .font(.title2.bold())
                    .foregroundStyle(Mania4KStyle.textPrimary)

                clearSetupButton

                Spacer()
            }

            modeSelectionContent

            if selectedMode != nil {
                Divider()
                    .overlay(Mania4KStyle.border)
            }

            modeSetupContent
        }
        .panelStyle()
    }

    @ViewBuilder
    private var modeSelectionContent: some View {
        if let selectedMode {
            selectedModeSummary(selectedMode)
        } else {
            VStack(spacing: 10) {
                ForEach(Mania4KSetupGameMode.allCases) { mode in
                    modeButton(mode)
                }
            }
        }
    }

    @ViewBuilder
    private var modeSetupContent: some View {
        switch selectedMode {
        case nil:
            EmptyView()
        case .localBeatmap:
            localBeatmapSetup
        case .generatedSong:
            generatedSongSetup
        case .ambient:
            ambientSetup
        }
    }

    private var starDifficultyField: some View {
        starDifficultyField(range: 0.1...12.0)
    }

    private func starDifficultyField(range: ClosedRange<Double>) -> some View {
        numericField(
            title: "osu!mania star difficulty",
            value: $model.starDifficulty,
            range: range,
            step: 0.1,
            suffix: "stars"
        )
    }

    private var localBeatmapSetup: some View {
        VStack(alignment: .leading, spacing: 18) {
            filePickerRow(
                title: "Beatmap",
                value: model.beatmapFileName,
                message: model.beatmapSelectionErrorMessage,
                systemImage: "doc.text",
                action: { presentFileImporter(for: .beatmap) }
            )

            filePickerRow(
                title: "Audio",
                value: model.audioFileName,
                message: model.audioSelectionErrorMessage,
                systemImage: "waveform",
                action: { presentFileImporter(for: .audio) }
            )

            starDifficultyField

            Button {
                Task {
                    await model.startPlay()
                }
            } label: {
                Label("Start Play", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(Mania4KPrimaryButtonStyle())
            .controlSize(.large)
            .disabled(!model.isReadyToStart || model.phase == .loading)
        }
    }

    private var generatedSongSetup: some View {
        VStack(alignment: .leading, spacing: 18) {
            filePickerRow(
                title: "Audio",
                value: model.audioFileName,
                message: model.audioSelectionErrorMessage,
                systemImage: "waveform",
                action: { presentFileImporter(for: .audio) }
            )
            .disabled(generation.isGenerating)

            if let duration = generation.audioDurationMS {
                Text("Song length: \(ChartGenerationSection.timeLabel(milliseconds: duration))")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Mania4KStyle.textSecondary)
            }
            if let message = generation.audioDurationError {
                generationError(message)
            }

            if let description = generation.modelDescription {
                Text(description)
                    .font(.caption.monospaced())
                    .foregroundStyle(Mania4KStyle.textSecondary)
            }
            if let range = generation.starRange {
                starDifficultyField(range: range)
                    .disabled(generation.isGenerating)
                generationSections(range: range)
                    .disabled(generation.isGenerating)
            }
            if let message = generation.capabilitiesError {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(Mania4KStyle.textSecondary)
                Button("Retry") {
                    Task { await loadGenerationCapabilities() }
                }
            }

            switch generation.state {
            case .idle, .ready:
                EmptyView()
            case .generating(let phase):
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(phase)
                        .font(.callout)
                        .foregroundStyle(Mania4KStyle.textSecondary)
                }
            case .failed(let message):
                generationError(message)
            }

            Button {
                if generation.isGenerating {
                    Task { await generation.stop() }
                } else {
                    startGeneratedSongMode()
                }
            } label: {
                Label(
                    generation.isGenerating ? "Stop" : "Generate & Play",
                    systemImage: generation.isGenerating ? "stop.fill" : "play.rectangle.on.rectangle"
                )
                .lineLimit(1)
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(Mania4KPrimaryButtonStyle())
            .controlSize(.large)
            .disabled(!generation.isGenerating && !generation.canGenerate(star: model.starDifficulty))
        }
        .task { await loadGenerationCapabilities() }
        .onChange(of: model.audioFileURL, initial: true) { _, url in
            generation.loadAudioDuration(for: url)
        }
    }

    private func generationSections(range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sections")
                .font(.subheadline.weight(.semibold))
            Text("Use mm:ss. Patterns last 8–32 seconds. Sections must stay inside the song and must not overlap.")
                .font(.caption)
                .foregroundStyle(Mania4KStyle.textSecondary)

            ForEach($generation.sections) { $section in
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .bottom, spacing: 10) {
                        sectionTimeField("Start (mm:ss)", text: $section.start)
                        sectionTimeField("End (mm:ss)", text: $section.end)
                        Button {
                            generation.sections.removeAll { $0.id == section.id }
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .accessibilityLabel("Remove section")
                        .help("Remove section")
                    }
                    Picker("Kind", selection: $section.kind) {
                        ForEach(generation.sectionOptions) { option in
                            Text(option.title).tag(option.kind)
                        }
                    }
                    if let option = generation.sectionOptions.first(where: { $0.kind == section.kind }), !option.note.isEmpty {
                        Text(option.note)
                            .font(.caption)
                            .foregroundStyle(Mania4KStyle.textSecondary)
                    }
                    if section.kind == .star {
                        numericField(title: "Section star target", value: $section.star, range: range, step: 0.1, suffix: "stars")
                    } else {
                        HStack {
                            Text("Strength")
                            Spacer()
                            Text(section.strength, format: .number.precision(.fractionLength(2)))
                                .monospacedDigit()
                        }
                        .font(.caption)
                        Slider(value: $section.strength, in: 0...1) {
                            Text("Pattern strength")
                        } minimumValueLabel: {
                            Text("0")
                        } maximumValueLabel: {
                            Text("1")
                        }
                    }
                }
                .padding(12)
                .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
            }

            if let message = generation.sectionValidationMessage {
                generationError(message)
            }
            Button("Add Section", systemImage: "plus") {
                guard let kind = generation.sectionOptions.first?.kind else { return }
                generation.sections.append(ChartGenerationSection(kind: kind, star: model.starDifficulty))
            }
            .disabled(generation.sectionOptions.isEmpty || generation.audioDurationMS == nil)
        }
    }

    private func sectionTimeField(_ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption)
            TextField(title, text: text)
                .labelsHidden()
                .textFieldStyle(.roundedBorder)
                .monospacedDigit()
        }
    }

    private func generationError(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle")
            .font(.caption)
            .foregroundStyle(Mania4KStyle.accentRed)
            .textSelection(.enabled)
    }

    private func loadGenerationCapabilities() async {
        await generation.loadCapabilities()
        if let range = generation.starRange {
            model.starDifficulty = min(max(model.starDifficulty, range.lowerBound), range.upperBound)
        }
    }

    private var ambientSetup: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                Image(systemName: "waveform.badge.magnifyingglass")
                    .font(.headline)
                    .frame(width: 32, height: 32)
                    .foregroundStyle(Mania4KStyle.accentAmber)
                    .background(Mania4KStyle.accentAmber.opacity(0.14), in: RoundedRectangle(cornerRadius: 7))

                VStack(alignment: .leading, spacing: 3) {
                    Text("Ambient Recognition")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Mania4KStyle.textPrimary)

                    Text(ambientModeStatus)
                        .font(.caption)
                        .foregroundStyle(Mania4KStyle.textSecondary)
                        .lineLimit(2)
                }

                Spacer()
            }
            .padding(12)
            .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Mania4KStyle.border, lineWidth: 1)
            )

            backendMockToggle

            Button {
                startAmbientMode()
            } label: {
                Label("Start Ambient Flow", systemImage: "waveform")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(Mania4KPrimaryButtonStyle())
            .controlSize(.large)
        }
    }

    private var backendMockToggle: some View {
        Toggle(isOn: $backendIsMock) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Mock backend")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Mania4KStyle.textPrimary)

                Text(backendIsMock ? "is_mock true" : "is_mock false")
                    .font(.caption.monospaced())
                    .foregroundStyle(Mania4KStyle.textSecondary)
            }
        }
        .toggleStyle(.switch)
        .tint(Mania4KStyle.accentGreen)
    }

    private func modeButton(_ mode: Mania4KSetupGameMode) -> some View {
        Button {
            selectedMode = mode
        } label: {
            HStack(spacing: 12) {
                Image(systemName: mode.systemImage)
                    .font(.headline)
                    .frame(width: 34, height: 34)
                    .foregroundStyle(selectedMode == mode ? Mania4KStyle.accentGreen : Mania4KStyle.accentBlue)
                    .background(
                        (selectedMode == mode ? Mania4KStyle.accentGreen : Mania4KStyle.accentBlue).opacity(0.14),
                        in: RoundedRectangle(cornerRadius: 7)
                    )

                VStack(alignment: .leading, spacing: 3) {
                    Text(mode.title)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(Mania4KStyle.textPrimary)

                    Text(mode.subtitle)
                        .font(.caption)
                        .foregroundStyle(Mania4KStyle.textSecondary)
                }

                Spacer()

                Text(mode.rawValue)
                    .font(.headline.monospaced().weight(.bold))
                    .foregroundStyle(Mania4KStyle.textMuted)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selectedMode == mode ? Mania4KStyle.accentGreen : Mania4KStyle.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func selectedModeSummary(_ mode: Mania4KSetupGameMode) -> some View {
        HStack(spacing: 12) {
            Image(systemName: mode.systemImage)
                .font(.headline)
                .frame(width: 34, height: 34)
                .foregroundStyle(Mania4KStyle.accentGreen)
                .background(Mania4KStyle.accentGreen.opacity(0.14), in: RoundedRectangle(cornerRadius: 7))

            VStack(alignment: .leading, spacing: 3) {
                Text(mode.title)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(Mania4KStyle.textPrimary)

                Text(mode.subtitle)
                    .font(.caption)
                    .foregroundStyle(Mania4KStyle.textSecondary)
            }

            Spacer()

            Text(mode.rawValue)
                .font(.headline.monospaced().weight(.bold))
                .foregroundStyle(Mania4KStyle.textMuted)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Mania4KStyle.accentGreen, lineWidth: 1)
        )
    }

    private var clearSetupButton: some View {
        Button {
            clearSetupState()
        } label: {
            Label("Clear", systemImage: "arrow.counterclockwise")
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
        }
        .buttonStyle(.plain)
        .foregroundStyle(hasSetupStateToClear ? Mania4KStyle.textSecondary : Mania4KStyle.textMuted)
        .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
        .overlay(
            RoundedRectangle(cornerRadius: 7)
                .stroke(Mania4KStyle.border, lineWidth: 1)
        )
        .disabled(!hasSetupStateToClear || generation.isGenerating)
        .help("Clear game mode setup")
    }

    private var headerStatusText: String {
        switch selectedMode {
        case nil:
            return "MODE"
        case .localBeatmap:
            return model.isReadyToStart ? "READY" : "SETUP"
        case .generatedSong:
            return sessionBadgeText
        case .ambient:
            return "AMBIENT"
        }
    }

    private var isSelectedModeReady: Bool {
        switch selectedMode {
        case nil:
            return false
        case .localBeatmap:
            return model.isReadyToStart
        case .generatedSong:
            if case .failed = generation.state { return false }
            return generation.canGenerate(star: model.starDifficulty)
        case .ambient:
            return true
        }
    }

    private var hasSetupStateToClear: Bool {
        selectedMode != nil
            || model.beatmapFileURL != nil
            || model.audioFileURL != nil
            || backendIsMock
            || ambientModeStatus != "Idle"
    }

    private func clearSetupState() {
        selectedMode = nil
        backendIsMock = false
        ambientModeStatus = "Idle"
        fileImportTarget = nil
        model.clearSetupSelections()
        generation.sections = []
        generation.loadAudioDuration(for: nil)
    }

    private func startGeneratedSongMode() {
        guard let audioFileURL = model.audioFileURL else {
            return
        }

        Task {
            guard let chart = await generation.generate(audioURL: audioFileURL, star: model.starDifficulty) else {
                return
            }
            await model.startGeneratedChartPlay(audioFileURL: audioFileURL, chart: chart)
        }
    }

    private func startAmbientMode() {
        #if os(macOS) && DEBUG
        if let onAmbientRecognitionRequested {
            onAmbientRecognitionRequested(backendIsMock)
        } else {
            openWindow(id: LiveRecognitionSyncWindow.windowID)
        }
        ambientModeStatus = "Recognition flow opened"
        #else
        ambientModeStatus = "Ambient recognition is available in the macOS debug build"
        #endif
    }

    private func presentFileImporter(for target: Mania4KFileImportTarget) {
        fileImportTarget = target
        isChoosingFile = true
    }

    private func handleFileImportResult(_ result: Result<[URL], Error>) {
        guard let fileImportTarget else {
            return
        }

        defer {
            self.fileImportTarget = nil
        }

        switch result {
        case .success(let urls):
            guard let url = urls.first else {
                return
            }

            switch fileImportTarget {
            case .beatmap:
                model.selectBeatmapFile(url)
            case .audio:
                model.selectAudioFile(url)
            }

        case .failure(let error):
            guard !error.isUserCancellation else {
                return
            }

            switch fileImportTarget {
            case .beatmap:
                model.recordBeatmapImportFailure(error)
            case .audio:
                model.recordAudioImportFailure(error)
            }
        }
    }

    private var previewPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Session")
                    .font(.title2.bold())
                    .foregroundStyle(Mania4KStyle.textPrimary)

                Spacer()

                Text(sessionBadgeText)
                    .font(.caption.monospaced().weight(.bold))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .foregroundStyle(isSelectedModeReady ? Mania4KStyle.accentGreen : Mania4KStyle.textMuted)
                    .background(
                        (isSelectedModeReady ? Mania4KStyle.accentGreen : Mania4KStyle.textMuted).opacity(0.12),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
            }

            if selectedMode == .generatedSong, case .generating(let phase) = generation.state {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(phase).font(.callout)
                }
            }

            ViewThatFits(in: .horizontal) {
                VStack(spacing: 10) {
                    HStack(spacing: 10) {
                        ForEach(sessionSummaryRows.prefix(3)) { row in
                            statTile(title: row.title, value: row.value)
                        }
                    }

                    HStack(spacing: 10) {
                        ForEach(sessionSummaryRows.dropFirst(3).prefix(3)) { row in
                            statTile(title: row.title, value: row.value)
                        }
                    }
                }

                VStack(spacing: 10) {
                    ForEach(sessionSummaryRows) { row in
                        statTile(title: row.title, value: row.value)
                    }
                }
            }

            if shouldShowBackendDetails {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Backend")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Mania4KStyle.textPrimary)

                    if selectedMode == .generatedSong {
                        Text(generationStatusText)
                            .font(.callout)
                            .textSelection(.enabled)
                    } else {
                        statTile(title: "Status", value: model.backendSessionStatus)
                        HStack(spacing: 10) {
                            statTile(title: "Tokens", value: String(model.backendReceivedTokenCount))
                            statTile(title: "Ready Window", value: "\(Int(model.backendReadyWindowMS.rounded())) ms")
                        }

                        if let token = model.backendLastTokenDescription {
                            Text(token)
                                .font(.caption.monospaced())
                                .foregroundStyle(Mania4KStyle.textSecondary)
                                .lineLimit(2)
                                .truncationMode(.middle)
                        }
                    }
                }
            }

            SetupLanePreview()
                .frame(minHeight: 360)
        }
        .panelStyle()
    }

    private var sessionBadgeText: String {
        switch selectedMode {
        case nil:
            return "WAITING"
        case .localBeatmap:
            return model.isReadyToStart ? "READY" : "WAITING"
        case .generatedSong:
            switch generation.state {
            case .generating: return "GENERATING"
            case .failed: return "ERROR"
            case .ready: return "READY"
            case .idle: return generation.canGenerate(star: model.starDifficulty) ? "READY" : "WAITING"
            }
        case .ambient:
            return "SYNC"
        }
    }

    private var generationStatusText: String {
        switch generation.state {
        case .generating(let phase): return "Generating: \(phase)"
        case .failed(let message): return message
        case .ready: return "Chart ready"
        case .idle:
            return generation.capabilitiesError ?? generation.audioDurationError ?? generation.sectionValidationMessage
                ?? (generation.canGenerate(star: model.starDifficulty) ? "Ready to generate" : "Choose audio and a supported star target")
        }
    }

    private var sessionSummaryRows: [SessionSummaryRow] {
        [
            SessionSummaryRow(title: "Mode", value: selectedMode?.rawValue ?? "--"),
            SessionSummaryRow(title: "Song", value: model.audioFileURL?.lastPathComponent ?? "--"),
            SessionSummaryRow(title: "Beatmap", value: beatmapSummaryText),
            SessionSummaryRow(title: "Difficulty", value: model.starDifficulty.formatted(.number.precision(.fractionLength(1)))),
            SessionSummaryRow(title: "Mock", value: selectedMode == .generatedSong ? "--" : backendIsMock ? "true" : "false"),
            SessionSummaryRow(title: "Reference", value: referenceSummaryText)
        ]
    }

    private var beatmapSummaryText: String {
        switch selectedMode {
        case nil:
            return "--"
        case .localBeatmap:
            return model.beatmapFileURL?.lastPathComponent ?? "--"
        case .generatedSong:
            return model.activeConfiguration?.chartSource.displayName ?? "Backend generated"
        case .ambient:
            return "Ambient generated"
        }
    }

    private var referenceSummaryText: String {
        guard let referenceTimeMS = model.backendReferenceTimeMS else {
            return selectedMode == .ambient ? "ambient lock" : "0 ms"
        }

        return "\(Int(referenceTimeMS.rounded())) ms"
    }

    private var shouldShowBackendDetails: Bool {
        selectedMode == .generatedSong || selectedMode == .ambient || model.backendSessionID != nil
    }

    private func filePickerRow(
        title: String,
        value: String,
        message: String?,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textPrimary)

            HStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.headline)
                    .frame(width: 32, height: 32)
                    .foregroundStyle(Mania4KStyle.accentBlue)
                    .background(Mania4KStyle.accentBlue.opacity(0.14), in: RoundedRectangle(cornerRadius: 7))

                Text(value)
                    .font(.callout.monospaced())
                    .foregroundStyle(Mania4KStyle.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: action) {
                    Label("Choose", systemImage: "folder")
                        .labelStyle(.titleAndIcon)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                .buttonStyle(Mania4KSecondaryButtonStyle())
            }
            .padding(12)
            .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Mania4KStyle.border, lineWidth: 1)
            )

            if let message {
                Text(message)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Mania4KStyle.accentRed)
            }
        }
    }

    private func numericField(
        title: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        step: Double,
        suffix: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textPrimary)

            HStack(spacing: 10) {
                Stepper(value: value, in: range, step: step) {
                    TextField(title, value: value, format: .number.precision(.fractionLength(step < 1 ? 1 : 0)))
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(Mania4KStyle.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
                        .overlay(
                            RoundedRectangle(cornerRadius: 7)
                                .stroke(Mania4KStyle.border, lineWidth: 1)
                        )
                        .frame(minWidth: 82, maxWidth: 120)
                }

                Text(suffix)
                    .font(.callout.monospaced())
                    .foregroundStyle(Mania4KStyle.textMuted)
            }
        }
    }

    private func statTile(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textMuted)

            Text(value)
                .font(.callout.monospaced())
                .foregroundStyle(Mania4KStyle.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Mania4KStyle.border, lineWidth: 1)
        )
    }
}

public struct EnsomiSettingsView: View {
    @Bindable private var model: Mania4KPlaySessionModel
    @AppStorage("mania4k.offsetCalibrationState") private var storedOffsetCalibrationState = ""
    @AppStorage("mania4k.keyBindings") private var storedKeyBindings = Mania4KKeyBindingSet.default.storageValue
    #if os(macOS)
    @State private var offsetCalibrationModel: Mania4KOffsetCalibrationModel?
    @State private var offsetCalibrationClock: AVFoundationMania4KAudioClock?
    @State private var capturingKeyBindingLane: Mania4KLane?
    #endif

    public init(model: Mania4KPlaySessionModel) {
        self.model = model
    }

    public var body: some View {
        Form {
            Section("Gameplay") {
                numericField(
                    title: "Scroll speed",
                    value: $model.scrollSpeed,
                    range: 1...40,
                    step: 0.1,
                    suffix: "×"
                )
                Picker("Judge difficulty", selection: $model.judgeDifficulty) {
                    ForEach(Mania4KJudgeDifficulty.allCases) { difficulty in
                        Text(difficulty.rawValue).tag(difficulty)
                    }
                }
            }

            Section("Timing") {
                offsetSetting(
                    title: "Audio offset",
                    description: "Moves song timing and judgement timing. Use when hits sound early or late.",
                    binding: audioOffsetBinding
                )
                offsetSetting(
                    title: "Visual offset",
                    description: "Moves note display only. Use when notes look early or late while the sound feels correct.",
                    binding: visualOffsetBinding
                )
                #if os(macOS)
                Button("Calibrate Timing…", systemImage: "slider.horizontal.3", action: openOffsetCalibration)
                #endif
            }

            #if os(macOS)
            Section("Keyboard") {
                keyBindingSettings
            }
            #endif
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 560, minHeight: 560)
        .sheet(isPresented: Binding(
            get: { offsetCalibrationModel != nil },
            set: { if !$0, let calibration = offsetCalibrationModel { cancelOffsetCalibration(calibration) } }
        )) {
            if let calibration = offsetCalibrationModel, let clock = offsetCalibrationClock {
                ScrollView {
                    Mania4KOffsetCalibrationView(
                        model: calibration,
                        audioClock: clock,
                        scrollTimeMs: model.scrollTimeMs,
                        onStateChanged: { persistCalibrationState($0) },
                        onApply: applyOffsetCalibration,
                        onCancel: cancelOffsetCalibration
                    )
                    .padding(24)
                }
                .frame(minWidth: 720, idealWidth: 1000, minHeight: 600, idealHeight: 720)
                .background(Mania4KBackdrop())
                .preferredColorScheme(.dark)
                .interactiveDismissDisabled()
            }
        }
        .onDisappear { capturingKeyBindingLane = nil }
        #endif
    }

    private func offsetSetting(title: String, description: String, binding: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            numericField(title: title, value: binding, range: -500...500, step: 1, suffix: "ms")
            Text(description)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func numericField(
        title: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        step: Double,
        suffix: String
    ) -> some View {
        let boundedValue = Binding<Double>(
            get: { value.wrappedValue },
            set: { if $0.isFinite { value.wrappedValue = min(max($0, range.lowerBound), range.upperBound) } }
        )
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
            HStack {
                TextField(title, value: boundedValue, format: .number.precision(.fractionLength(step < 1 ? 1 : 0)))
                    .textFieldStyle(.roundedBorder)
                    .labelsHidden()
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 120)
                    .accessibilityLabel(title)
                Text(suffix)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Stepper(title, value: boundedValue, in: range, step: step)
                    .labelsHidden()
                    .accessibilityValue("\(value.wrappedValue.formatted()) \(suffix)")
            }
        }
    }

    private var audioOffsetBinding: Binding<Double> {
        Binding(
            get: {
                model.audioOffsetMilliseconds
            },
            set: { value in
                let offsetMilliseconds = clampedOffsetMilliseconds(value)
                model.audioOffsetMilliseconds = Double(offsetMilliseconds)
            }
        )
    }

    private var visualOffsetBinding: Binding<Double> {
        Binding(
            get: {
                model.visualOffsetMilliseconds
            },
            set: { value in
                let offsetMilliseconds = clampedOffsetMilliseconds(value)
                model.visualOffsetMilliseconds = Double(offsetMilliseconds)
            }
        )
    }

    private var calibrationStoredState: Mania4KOffsetCalibrationStoredState? {
        guard !storedOffsetCalibrationState.isEmpty else {
            return Mania4KOffsetCalibrationStoredState.defaultPlayState
        }

        guard let storedState = Mania4KOffsetCalibrationStoredState(storageValue: storedOffsetCalibrationState) else {
            return nil
        }

        return Mania4KOffsetCalibrationModel.normalizedStoredState(
            storedState,
            fallbackAppliedAudioOffsetMilliseconds: 0,
            fallbackAppliedVisualOffsetMilliseconds: 0
        )
    }

    private func persistCalibrationState(
        _ state: Mania4KOffsetCalibrationStoredState,
        createsStateIfNeeded: Bool = false
    ) {
        guard createsStateIfNeeded || calibrationStoredState != nil || !state.presets.isEmpty || state.activePresetID != nil else {
            return
        }

        storedOffsetCalibrationState = state.storageValue
    }

    private func clampedOffsetMilliseconds(_ value: Double) -> Int {
        min(max(Int(value.rounded()), -500), 500)
    }

    #if os(macOS)
    private func openOffsetCalibration() {
        let clock = AVFoundationMania4KAudioClock()
        offsetCalibrationClock = clock
        offsetCalibrationModel = Mania4KOffsetCalibrationModel(
            originalAudioOffsetMilliseconds: clampedOffsetMilliseconds(model.audioOffsetMilliseconds),
            originalVisualOffsetMilliseconds: clampedOffsetMilliseconds(model.visualOffsetMilliseconds),
            storedState: calibrationStoredState,
            audioClock: clock
        )
    }

    private func applyOffsetCalibration(_ calibrationModel: Mania4KOffsetCalibrationModel) {
        let appliedOffsets = calibrationModel.apply()
        model.audioOffsetMilliseconds = Double(appliedOffsets.audioOffsetMilliseconds)
        model.visualOffsetMilliseconds = Double(appliedOffsets.visualOffsetMilliseconds)
        persistCalibrationState(calibrationModel.storedState, createsStateIfNeeded: true)
        offsetCalibrationModel = nil
        offsetCalibrationClock = nil
    }

    private func cancelOffsetCalibration(_ calibrationModel: Mania4KOffsetCalibrationModel) {
        let originalOffsets = calibrationModel.cancel()
        model.audioOffsetMilliseconds = Double(originalOffsets.audioOffsetMilliseconds)
        model.visualOffsetMilliseconds = Double(originalOffsets.visualOffsetMilliseconds)
        offsetCalibrationModel = nil
        offsetCalibrationClock = nil
    }

    private var keyBindingSettings: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Lane keys")
                Spacer()
                Button("Restore Defaults") {
                    model.resetKeyBindingsToDefault()
                    storedKeyBindings = model.keyBindings.storageValue
                    capturingKeyBindingLane = nil
                }
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 90))], spacing: 8) {
                ForEach(Mania4KLane.allCases) { lane in
                    keyBindingButton(for: lane)
                }
            }
            .background(
                Mania4KKeyBindingCaptureView(activeLane: $capturingKeyBindingLane) { lane, key in
                    if model.updateKeyBinding(lane: lane, key: key) {
                        storedKeyBindings = model.keyBindings.storageValue
                    }
                }
            )

            if capturingKeyBindingLane != nil {
                Text("Press a key to assign it. Press Escape to cancel.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let message = model.keyBindingErrorMessage {
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    private func keyBindingButton(for lane: Mania4KLane) -> some View {
        Button {
            capturingKeyBindingLane = lane
        } label: {
            VStack(spacing: 5) {
                Text(laneShortName(for: lane))
                    .font(.caption)
                Text(capturingKeyBindingLane == lane ? "Press a key" : model.keyBindings.displayLabel(for: lane))
                    .font(.body.monospaced())
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(capturingKeyBindingLane == lane ? .accentColor : nil)
        .accessibilityLabel("Lane \(laneShortName(for: lane)) key")
        .accessibilityValue(capturingKeyBindingLane == lane ? "Waiting for a key" : model.keyBindings.displayLabel(for: lane))
        .accessibilityHint("Activate, then press the key you want to use.")
    }

    private func laneShortName(for lane: Mania4KLane) -> String {
        switch lane {
        case .left:
            return "L1"
        case .innerLeft:
            return "L2"
        case .innerRight:
            return "R2"
        case .right:
            return "R1"
        }
    }
    #endif
}

private enum Mania4KFileImportTarget {
    case beatmap
    case audio
}

#if os(macOS)
private enum Mania4KOffsetCalibrationFocusedTextField: Hashable {
    case pendingAudioOffset
    case pendingVisualOffset
    case presetName
}

private struct Mania4KOffsetCalibrationView: View {
    @Bindable var model: Mania4KOffsetCalibrationModel
    let audioClock: AVFoundationMania4KAudioClock
    let scrollTimeMs: Double
    let onStateChanged: (Mania4KOffsetCalibrationStoredState) -> Void
    let onApply: (Mania4KOffsetCalibrationModel) -> Void
    let onCancel: (Mania4KOffsetCalibrationModel) -> Void

    @State private var audioTask: Task<Void, Never>?
    @State private var audioFailure: String?
    @State private var presetName = ""
    @State private var presetIDPendingDeletion: UUID?
    @FocusState private var focusedTextField: Mania4KOffsetCalibrationFocusedTextField?

    private let calibrationLane: Mania4KLane = .innerRight

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header

            HStack(alignment: .top, spacing: 16) {
                calibrationStage
                    .frame(maxWidth: .infinity, minHeight: 520)

                controls
                    .frame(width: 340)
            }
        }
        .background(
            Mania4KOffsetCalibrationFrameLinkView(
                onFrame: { link in
                    model.advanceClock(
                        atHostTimeMs: EnsomiHostClock.currentTimeMS(),
                        presentationHostTimeMs: link.targetTimestamp * 1_000
                    )
                },
                onVisibilityChange: { isVisible in
                    runAudio {
                        if isVisible {
                            try? await audioClock.play()
                        } else {
                            await audioClock.pause()
                        }
                    }
                }
            )
        )
        .background(
            Mania4KOffsetCalibrationKeyboardCaptureView(
                calibrationKey: Mania4KOffsetCalibrationModel.calibrationKey,
                isCaptureEnabled: focusedTextField == nil
            ) { hostTimeMs in
                _ = model.recordInput(atHostTimeMs: hostTimeMs)
            }
        )
        .onAppear(perform: startAudio)
        .onDisappear(perform: stopAudio)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 5) {
                Text("Offset Calibration")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(Mania4KStyle.textPrimary)

                Text("A \(model.pendingAudioOffsetMilliseconds) ms pending / \(model.renderedAudioOffsetMilliseconds) ms rendered  V \(model.pendingVisualOffsetMilliseconds) ms pending / \(model.renderedVisualOffsetMilliseconds) ms rendered")
                    .font(.caption.monospaced())
                    .foregroundStyle(Mania4KStyle.textSecondary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.78)

                if let audioFailure {
                    Text(audioFailure)
                        .font(.caption)
                        .foregroundStyle(Mania4KStyle.accentRed)
                }
            }

            Spacer()

            Text("Key \(Mania4KOffsetCalibrationModel.calibrationKey.uppercased())")
                .font(.caption.monospaced().weight(.bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .foregroundStyle(Mania4KStyle.textPrimary)
                .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
                .overlay(
                    RoundedRectangle(cornerRadius: 7)
                        .stroke(Mania4KStyle.border, lineWidth: 1)
                )

            Button(role: .cancel) {
                onCancel(model)
            } label: {
                Label("Cancel", systemImage: "xmark")
            }
            .buttonStyle(Mania4KSecondaryButtonStyle(tint: Mania4KStyle.accentRed))

            Button {
                onApply(model)
            } label: {
                Label("Apply", systemImage: "checkmark")
            }
            .buttonStyle(Mania4KPrimaryButtonStyle())
        }
    }

    private var calibrationStage: some View {
        GeometryReader { proxy in
            ZStack {
                LinearGradient(
                    colors: [
                        Mania4KStyle.stageFill,
                        Color(red: 0.035, green: 0.040, blue: 0.055)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )

                Mania4KGridOverlay(spacing: 40, opacity: 0.12)

                Mania4KLiveLaneView(
                    lane: calibrationLane,
                    frame: calibrationFrame,
                    brightness: LaneBrightness(receptor: 0, lane: 0),
                    noteColor: Mania4KStyle.accentGreen,
                    keyLabel: Mania4KOffsetCalibrationModel.calibrationKey.uppercased()
                )
                .frame(width: min(max(proxy.size.width * 0.22, 112), 150))
                .padding(.vertical, 22)

                VStack {
                    Spacer()

                    HStack(spacing: 12) {
                        metricPill(title: "Raw", value: "\(model.rawClockTimeMs) ms")
                        metricPill(title: "Suggestion", value: suggestionText)
                        metricPill(title: "Latest", value: latestHitText)
                    }
                    .padding(.bottom, 18)
                }
            }
        }
        .background(Mania4KStyle.stageFill, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Mania4KStyle.borderStrong, lineWidth: 1)
        )
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Audio Offset")
                .font(.title3.bold())
                .foregroundStyle(Mania4KStyle.textPrimary)

            HStack(spacing: 8) {
                offsetStepButton(-100) { model.stepPendingAudioOffset(by: $0) }
                offsetStepButton(-10) { model.stepPendingAudioOffset(by: $0) }
                offsetStepButton(-1) { model.stepPendingAudioOffset(by: $0) }
            }

            HStack(spacing: 10) {
                TextField("Audio Offset", value: pendingAudioOffsetBinding, format: .number)
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(Mania4KStyle.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
                    .overlay(
                        RoundedRectangle(cornerRadius: 7)
                            .stroke(Mania4KStyle.border, lineWidth: 1)
                    )
                    .focused($focusedTextField, equals: .pendingAudioOffset)
                    .onSubmit {
                        focusedTextField = nil
                    }

                Text("ms")
                    .font(.callout.monospaced())
                    .foregroundStyle(Mania4KStyle.textMuted)
            }

            HStack(spacing: 8) {
                offsetStepButton(1) { model.stepPendingAudioOffset(by: $0) }
                offsetStepButton(10) { model.stepPendingAudioOffset(by: $0) }
                offsetStepButton(100) { model.stepPendingAudioOffset(by: $0) }
            }

            Button {
                focusedTextField = nil
                if model.useSuggestedAudioOffset() {
                    onStateChanged(model.storedState)
                }
            } label: {
                Label("Use Suggestion", systemImage: "scope")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(Mania4KSecondaryButtonStyle(tint: Mania4KStyle.accentAmber))
            .disabled(model.suggestedAudioOffsetMilliseconds == nil)

            Divider()
                .overlay(Mania4KStyle.border)

            Text("Visual Offset")
                .font(.title3.bold())
                .foregroundStyle(Mania4KStyle.textPrimary)

            HStack(spacing: 8) {
                offsetStepButton(-100) { model.stepPendingVisualOffset(by: $0) }
                offsetStepButton(-10) { model.stepPendingVisualOffset(by: $0) }
                offsetStepButton(-1) { model.stepPendingVisualOffset(by: $0) }
            }

            HStack(spacing: 10) {
                TextField("Visual Offset", value: pendingVisualOffsetBinding, format: .number)
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(Mania4KStyle.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
                    .overlay(
                        RoundedRectangle(cornerRadius: 7)
                            .stroke(Mania4KStyle.border, lineWidth: 1)
                    )
                    .focused($focusedTextField, equals: .pendingVisualOffset)
                    .onSubmit {
                        focusedTextField = nil
                    }

                Text("ms")
                    .font(.callout.monospaced())
                    .foregroundStyle(Mania4KStyle.textMuted)
            }

            HStack(spacing: 8) {
                offsetStepButton(1) { model.stepPendingVisualOffset(by: $0) }
                offsetStepButton(10) { model.stepPendingVisualOffset(by: $0) }
                offsetStepButton(100) { model.stepPendingVisualOffset(by: $0) }
            }

            Divider()
                .overlay(Mania4KStyle.border)

            Text("Presets")
                .font(.title3.bold())
                .foregroundStyle(Mania4KStyle.textPrimary)

            Text("Active preset")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textMuted)

            Picker("Preset", selection: activePresetBinding) {
                Text("None").tag(Optional<UUID>.none)
                ForEach(model.presets) { preset in
                    Text(presetLabel(for: preset)).tag(Optional(preset.id))
                }
            }
            .labelsHidden()

            HStack(spacing: 8) {
                TextField("Name", text: $presetName)
                    .textFieldStyle(.plain)
                    .foregroundStyle(Mania4KStyle.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Mania4KStyle.controlFill, in: RoundedRectangle(cornerRadius: 7))
                    .overlay(
                        RoundedRectangle(cornerRadius: 7)
                            .stroke(Mania4KStyle.border, lineWidth: 1)
                    )
                    .focused($focusedTextField, equals: .presetName)
                    .onSubmit(addPreset)

                Button(action: addPreset) {
                    Label("Add", systemImage: "plus")
                }
                .buttonStyle(Mania4KSecondaryButtonStyle())
            }

            Text("Delete preset")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textMuted)

            Picker("Delete preset", selection: presetDeletionBinding) {
                Text("None").tag(Optional<UUID>.none)
                ForEach(model.presets) { preset in
                    Text(presetLabel(for: preset)).tag(Optional(preset.id))
                }
            }
            .labelsHidden()

            Button(role: .destructive) {
                deleteSelectedPreset()
            } label: {
                Label("Delete", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(Mania4KSecondaryButtonStyle(tint: Mania4KStyle.accentRed))
            .disabled(!canDeleteSelectedPreset)
        }
        .panelStyle()
        .onChange(of: model.presets.map(\.id)) { _, presetIDs in
            if let presetIDPendingDeletion, !presetIDs.contains(presetIDPendingDeletion) {
                self.presetIDPendingDeletion = nil
            }
        }
    }

    private var calibrationFrame: Mania4KPlayFrame {
        Mania4KPlayFrame(
            gameplayChartTimeMs: Double(model.gameplayChartTimeMs),
            renderChartTimeMs: Double(model.renderedChartTimeMs),
            scrollTimeMs: scrollTimeMs,
            metadata: Mania4KChartMetadata(title: "Offset calibration", sourceDescription: "Synthetic"),
            visibleObjects: model.visibleObjects(
                travelTimeMs: scrollTimeMs,
                postLineVisibleMs: 280,
                lookaheadPaddingMs: 120,
                lane: calibrationLane
            ),
            score: .zero,
            laneStates: [],
            latestJudgement: nil
        )
    }

    private var pendingAudioOffsetBinding: Binding<Int> {
        Binding(
            get: {
                model.pendingAudioOffsetMilliseconds
            },
            set: { value in
                model.setPendingAudioOffsetMilliseconds(value)
                onStateChanged(model.storedState)
            }
        )
    }

    private var pendingVisualOffsetBinding: Binding<Int> {
        Binding(
            get: {
                model.pendingVisualOffsetMilliseconds
            },
            set: { value in
                model.setPendingVisualOffsetMilliseconds(value)
                onStateChanged(model.storedState)
            }
        )
    }

    private var activePresetBinding: Binding<UUID?> {
        Binding(
            get: {
                model.activePresetID
            },
            set: { id in
                focusedTextField = nil
                if let id {
                    _ = model.selectPreset(id: id)
                } else {
                    model.clearActivePreset()
                }
                onStateChanged(model.storedState)
            }
        )
    }

    private var presetDeletionBinding: Binding<UUID?> {
        Binding(
            get: {
                presetIDPendingDeletion
            },
            set: { id in
                focusedTextField = nil
                presetIDPendingDeletion = id
            }
        )
    }

    private var canDeleteSelectedPreset: Bool {
        guard let presetIDPendingDeletion else {
            return false
        }

        return model.presets.contains { $0.id == presetIDPendingDeletion }
    }

    private var suggestionText: String {
        guard let adjustment = model.suggestedAudioAdjustmentMilliseconds else {
            return "--"
        }

        return "\(adjustment >= 0 ? "+" : "")\(adjustment) ms"
    }

    private var latestHitText: String {
        guard let latest = model.hitSamples.last else {
            return "--"
        }

        return "\(latest.hitErrorMs >= 0 ? "+" : "")\(Int(latest.hitErrorMs.rounded())) ms"
    }

    private func offsetStepButton(_ delta: Int, _ apply: @escaping (Int) -> Void) -> some View {
        Button {
            focusedTextField = nil
            apply(delta)
            onStateChanged(model.storedState)
        } label: {
            Text(delta > 0 ? "+\(delta)" : "\(delta)")
                .font(.callout.monospaced().weight(.bold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(Mania4KSecondaryButtonStyle())
    }

    private func presetLabel(for preset: Mania4KOffsetPreset) -> String {
        "\(preset.name)  A \(signedMilliseconds(preset.audioOffsetMilliseconds))  V \(signedMilliseconds(preset.visualOffsetMilliseconds))"
    }

    private func signedMilliseconds(_ milliseconds: Int) -> String {
        "\(milliseconds >= 0 ? "+" : "")\(milliseconds) ms"
    }

    private func metricPill(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Mania4KStyle.textMuted)

            Text(value)
                .font(.callout.monospacedDigit().weight(.semibold))
                .foregroundStyle(Mania4KStyle.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(minWidth: 104, alignment: .leading)
        .background(Mania4KStyle.panelFill.opacity(0.88), in: RoundedRectangle(cornerRadius: 7))
        .overlay(
            RoundedRectangle(cornerRadius: 7)
                .stroke(Mania4KStyle.border, lineWidth: 1)
        )
    }

    private func addPreset() {
        focusedTextField = nil
        _ = model.addPreset(name: presetName)
        presetName = ""
        onStateChanged(model.storedState)
    }

    private func deleteSelectedPreset() {
        focusedTextField = nil
        guard let presetIDPendingDeletion, model.deletePreset(id: presetIDPendingDeletion) else {
            return
        }

        self.presetIDPendingDeletion = nil
        onStateChanged(model.storedState)
    }

    /// Plays the click track on the audio clock. Frames come from the display link, which reads the clock.
    private func startAudio() {
        runAudio {
            do {
                try audioClock.prepare(clickTrack: clickTrack())
                try await audioClock.play()
            } catch {
                audioFailure = error.localizedDescription
            }
        }
    }

    private func stopAudio() {
        runAudio {
            await audioClock.stop()
        }
    }

    /// Runs audio commands one after another, so a stop never overtakes a play still starting.
    private func runAudio(_ command: @escaping @MainActor () async -> Void) {
        let previous = audioTask
        audioTask = Task { @MainActor in
            await previous?.value
            await command()
        }
    }

    private func clickTrack() throws -> Mania4KCalibrationClickTrack {
        let bundle = Bundle(for: Mania4KOffsetCalibrationResourceBundleToken.self)
        guard let url = bundle.url(forResource: "calibration_tick", withExtension: "wav") else {
            throw Mania4KPlayFailure.audioPrepareFailed("The bundled calibration_tick.wav is missing.")
        }

        let file = try AVAudioFile(forReading: url)
        let tick = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
        try file.read(into: tick)
        return Mania4KCalibrationClickTrack(
            tick: tick,
            leadInMs: Mania4KOffsetCalibrationModel.initialLeadInMs,
            intervalMs: Mania4KOffsetCalibrationModel.tickIntervalMs
        )
    }
}

private final class Mania4KOffsetCalibrationResourceBundleToken: NSObject {}

/// Calls `onFrame` once per display refresh, as the play playfield's display link does, and `onVisibilityChange` when
/// the window is hidden or shown.
private struct Mania4KOffsetCalibrationFrameLinkView: NSViewRepresentable {
    let onFrame: (CADisplayLink) -> Void
    let onVisibilityChange: (Bool) -> Void

    func makeNSView(context: Context) -> LinkView {
        let view = LinkView()
        view.onFrame = onFrame
        view.onVisibilityChange = onVisibilityChange
        return view
    }

    func updateNSView(_ nsView: LinkView, context: Context) {
        nsView.onFrame = onFrame
        nsView.onVisibilityChange = onVisibilityChange
    }

    final class LinkView: NSView {
        var onFrame: ((CADisplayLink) -> Void)?
        var onVisibilityChange: ((Bool) -> Void)?
        private var displayLink: Mania4KDisplayLink?
        private var visibilityObserver: Mania4KWindowVisibilityObserver?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            displayLink?.invalidate()
            visibilityObserver?.invalidate()
            displayLink = window == nil ? nil : Mania4KDisplayLink(view: self) { [weak self] link in
                self?.onFrame?(link)
            }
            visibilityObserver = window.map { window in
                Mania4KWindowVisibilityObserver(window: window) { [weak self] isVisible in
                    self?.onVisibilityChange?(isVisible)
                }
            }
        }
    }
}

private struct Mania4KOffsetCalibrationKeyboardCaptureView: NSViewRepresentable {
    let calibrationKey: String
    let isCaptureEnabled: Bool
    let onHit: (Double) -> Void

    func makeNSView(context: Context) -> KeyboardView {
        let view = KeyboardView()
        view.calibrationKey = Mania4KKeyBindingSet.normalizedKey(calibrationKey)
        view.isCaptureEnabled = isCaptureEnabled
        view.onHit = onHit
        return view
    }

    func updateNSView(_ nsView: KeyboardView, context: Context) {
        let wasCaptureEnabled = nsView.isCaptureEnabled
        nsView.calibrationKey = Mania4KKeyBindingSet.normalizedKey(calibrationKey)
        nsView.isCaptureEnabled = isCaptureEnabled
        nsView.onHit = onHit
        nsView.requestFirstResponderIfNeeded(allowTextEditingResponder: !wasCaptureEnabled && isCaptureEnabled)
    }

    final class KeyboardView: NSView {
        var calibrationKey = Mania4KOffsetCalibrationModel.calibrationKey
        var isCaptureEnabled = true
        var onHit: ((Double) -> Void)?
        private var didRequestInitialFirstResponder = false

        override var acceptsFirstResponder: Bool {
            true
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard window != nil, !didRequestInitialFirstResponder else {
                return
            }

            didRequestInitialFirstResponder = true
            requestFirstResponderIfNeeded(allowTextEditingResponder: true)
        }

        override func keyDown(with event: NSEvent) {
            guard isCaptureEnabled,
                  !event.isARepeat,
                  let key = event.charactersIgnoringModifiers,
                  Mania4KKeyBindingSet.normalizedKey(key) == calibrationKey else {
                super.keyDown(with: event)
                return
            }

            onHit?(event.timestamp * 1_000)
        }

        func requestFirstResponderIfNeeded(allowTextEditingResponder: Bool = false) {
            guard isCaptureEnabled,
                  let window,
                  window.firstResponder !== self,
                  allowTextEditingResponder || !Self.isTextEditingResponder(window.firstResponder)
            else {
                return
            }

            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.isCaptureEnabled,
                      let window = self.window,
                      window.firstResponder !== self,
                      allowTextEditingResponder || !Self.isTextEditingResponder(window.firstResponder)
                else {
                    return
                }

                window.makeFirstResponder(self)
            }
        }

        private static func isTextEditingResponder(_ responder: Any?) -> Bool {
            responder is NSText || responder is NSTextField || responder is NSTextView
        }
    }
}
#endif

private struct SetupLanePreview: View {
    var body: some View {
        GeometryReader { proxy in
            let laneWidth = max(0, proxy.size.width - 30) / 4

            ZStack(alignment: .bottom) {
                Mania4KGridOverlay(spacing: 34, opacity: 0.18)

                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(0..<4, id: \.self) { lane in
                        VStack(spacing: 0) {
                            Spacer()

                            RoundedRectangle(cornerRadius: 5)
                                .fill(noteColor(for: lane).opacity(0.86))
                                .frame(height: 46 + CGFloat(lane * 22))
                                .shadow(color: noteColor(for: lane).opacity(0.45), radius: 10, x: 0, y: 0)

                            RoundedRectangle(cornerRadius: 4)
                                .fill(Mania4KStyle.textPrimary.opacity(0.84))
                                .frame(height: 10)
                                .padding(.top, 16)
                        }
                        .frame(width: laneWidth)
                        .frame(maxHeight: .infinity)
                        .background(Mania4KStyle.laneFill, in: RoundedRectangle(cornerRadius: 8))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(noteColor(for: lane).opacity(0.34), lineWidth: 1)
                        )
                    }
                }
            }
        }
        .padding(16)
        .background(Mania4KStyle.stageFill, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Mania4KStyle.borderStrong, lineWidth: 1)
        )
    }

    private func noteColor(for lane: Int) -> Color {
        switch lane {
        case 0:
            return Color(red: 0.96, green: 0.67, blue: 0.21)
        case 1:
            return Color(red: 0.26, green: 0.70, blue: 0.64)
        case 2:
            return Color(red: 0.89, green: 0.35, blue: 0.37)
        default:
            return Color(red: 0.56, green: 0.63, blue: 0.94)
        }
    }
}

/// The play screen: the HUD row, the Metal playfield with its key labels and judgement text, and the loading, paused
/// and result panels. Its colours and type follow the brand (`Mania4KPlayPalette`, SF Pro Rounded); the setup screens
/// keep `Mania4KStyle`.
private struct Mania4KPlaySceneView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.mania4kPlayfieldSnapshot) private var playfieldSnapshot
    @Bindable var model: Mania4KPlaySessionModel

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                Mania4KPlayHUDBar(
                    model: model,
                    isCompact: geometry.size.width < 800 || dynamicTypeSize.isAccessibilitySize
                )

                GeometryReader { proxy in
                    ZStack {
                        playfield
                            .overlay(alignment: .bottom) {
                                Mania4KKeyLabelsView(model: model)
                            }
                            .overlay {
                                Mania4KJudgementTextView(model: model, reduceMotion: reduceMotion)
                            }
                            .overlay {
                                if model.phase == .paused {
                                    UnevenRoundedRectangle(
                                        bottomLeadingRadius: CGFloat(Mania4KPlayfieldLayout.trayCornerRadius),
                                        bottomTrailingRadius: CGFloat(Mania4KPlayfieldLayout.trayCornerRadius)
                                    )
                                    .fill(Color(Mania4KPlayPalette.ground, opacity: 0.6))
                                }
                            }
                            .frame(width: playFieldWidth(for: proxy.size.width))
                            .padding(.bottom, 16)

                        overlayState
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(Mania4KStageBackground())
            #if os(macOS)
            .background(Mania4KKeyboardCaptureView(model: model))
            #endif
        }
    }

    @ViewBuilder
    private var playfield: some View {
        if let playfieldSnapshot {
            GeometryReader { proxy in
                if let image = playfieldSnapshot.image(proxy.size) {
                    Image(decorative: image, scale: 1)
                        .resizable()
                }
            }
        } else {
            Mania4KPlayfieldMetalView(model: model, reduceMotion: reduceMotion)
        }
    }

    @ViewBuilder
    private var overlayState: some View {
        switch model.phase {
        case .loading:
            Mania4KStatePanel(title: "Loading", detail: model.activeConfiguration?.chartSource.displayName ?? "Preparing chart") {
                ProgressView()
                    .controlSize(.small)
            } actions: {
                Button("Cancel") {
                    Task { await model.quitToSetup() }
                }
                .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.mutedText)))
            }
        case .paused:
            Mania4KStatePanel(title: "Paused", detail: "Resume when you're ready.") {
                EmptyView()
            } actions: {
                Button {
                    Task { await model.resume() }
                } label: {
                    Label("Resume", systemImage: "play.fill")
                }
                .buttonStyle(Mania4KActionButtonStyle())

                Button("Quit") {
                    Task { await model.quitToSetup() }
                }
                .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.mutedText)))
            }
        case .failed(let failure):
            Mania4KStatePanel(title: "Play stopped", detail: failure.localizedDescription) {
                EmptyView()
            } actions: {
                backToSetupButton
            }
        case .finished(let result):
            Mania4KStatePanel(
                title: "Results",
                detail: "\(Mania4KPlayHUDBar.formatAccuracy(result.score.accuracy))  ·  \(result.score.maxCombo) max combo  ·  \(result.score.tierCounts.missCount) miss"
            ) {
                EmptyView()
            } actions: {
                backToSetupButton
            }
        default:
            EmptyView()
        }
    }

    private var backToSetupButton: some View {
        Button {
            Task { await model.quitToSetup() }
        } label: {
            Label("Back to Setup", systemImage: "slider.horizontal.3")
        }
        .buttonStyle(Mania4KActionButtonStyle())
    }

    private func playFieldWidth(for availableWidth: CGFloat) -> CGFloat {
        min(max(0, availableWidth - 32), 440)
    }
}

/// Title, accuracy and the pause and quit buttons, on the stage without a panel of their own.
private struct Mania4KPlayHUDBar: View {
    let model: Mania4KPlaySessionModel
    let isCompact: Bool

    var body: some View {
        Group {
            if isCompact {
                VStack(alignment: .leading, spacing: 12) {
                    title

                    HStack(spacing: 12) {
                        accuracy(alignment: .leading)
                        Spacer(minLength: 0)
                        pauseButton
                        quitButton
                    }
                }
            } else {
                HStack(spacing: 14) {
                    title

                    Spacer()

                    accuracy(alignment: .trailing)
                        .padding(.trailing, 10)
                    pauseButton
                    quitButton
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
    }

    private var title: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.hud.title ?? model.activeConfiguration?.chartSource.displayName ?? "mania4k")
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .foregroundStyle(Color(Mania4KPlayPalette.text))
                .lineLimit(1)
                .truncationMode(.middle)

            Text(subtitleText)
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
        }
    }

    private func accuracy(alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 0) {
            Text(Self.formatAccuracy(model.hud.accuracy))
                .font(.system(.title2, design: .rounded, weight: .bold).monospacedDigit())
                .foregroundStyle(Color(Mania4KPlayPalette.text))

            Text("Accuracy")
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
        }
        .accessibilityElement(children: .combine)
    }

    private var pauseButton: some View {
        Button {
            Task {
                switch model.phase {
                case .paused:
                    await model.resume()
                case .playing:
                    await model.pause()
                default:
                    break
                }
            }
        } label: {
            Label(model.phase == .paused ? "Resume" : "Pause", systemImage: model.phase == .paused ? "play.fill" : "pause.fill")
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.hold)))
        .disabled(model.phase != .playing && model.phase != .paused)
    }

    private var quitButton: some View {
        Button(role: .cancel) {
            Task {
                await model.quitToSetup()
            }
        } label: {
            Label("Quit", systemImage: "xmark")
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.mutedText)))
    }

    private var subtitleText: String {
        guard let configuration = model.activeConfiguration else {
            return "--"
        }

        return "\(configuration.starDifficulty.formatted(.number.precision(.fractionLength(1)))) stars  ·  \(configuration.scrollSpeed.formatted(.number.precision(.fractionLength(1))))x speed"
    }

    static func formatAccuracy(_ accuracy: Double) -> String {
        (accuracy * 100).formatted(.number.precision(.fractionLength(2))) + "%"
    }
}

/// The key bound to each lane, over its key cap; a pressed key's label brightens with the cap.
private struct Mania4KKeyLabelsView: View {
    let model: Mania4KPlaySessionModel

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Mania4KLane.allCases) { lane in
                let isPressed = model.liveInputLaneStates.contains { $0.lane == lane.index && $0.isPressed }
                Text(model.keyBindings.displayLabel(for: lane))
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                    .foregroundStyle(Color(isPressed ? Mania4KPlayPalette.text : Mania4KPlayPalette.mutedText))
                    .frame(maxWidth: .infinity)
                    .frame(height: CGFloat(Mania4KPlayfieldLayout.keyAreaHeight))
            }
        }
        .allowsHitTesting(false)
    }
}

/// The latest judgement in its tier's colour and the combo, over the middle of the lanes. The core's presentation
/// sets the judgement's opacity, scale and drift; with Reduce Motion only the opacity is used. A Good shows whether
/// it was early or late. Until the first judgement of a play, a quiet "Ready" holds the place.
private struct Mania4KJudgementTextView: View {
    let model: Mania4KPlaySessionModel
    let reduceMotion: Bool

    var body: some View {
        GeometryReader { proxy in
            let lineY = max(0, proxy.size.height - CGFloat(Mania4KPlayfieldLayout.keyAreaHeight))

            VStack(spacing: 2) {
                ZStack {
                    if let presentation = model.judgementPresentation {
                        judgement(presentation)
                    } else if model.phase == .playing, model.hud.latestJudgement == nil {
                        Text("Ready")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(Color(Mania4KPlayPalette.mutedText, opacity: 0.75))
                    }
                }
                .frame(height: 40)

                if model.hud.combo > 1 {
                    VStack(spacing: -4) {
                        Text("\(model.hud.combo)")
                            .font(.system(size: 44, weight: .bold, design: .rounded).monospacedDigit())
                            .foregroundStyle(Color(Mania4KPlayPalette.text, opacity: 0.85))

                        Text("combo")
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(Color(Mania4KPlayPalette.mutedText, opacity: 0.8))
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .shadow(color: Color(Mania4KPlayPalette.ground, opacity: 0.9), radius: 8)
            .position(x: proxy.size.width / 2, y: lineY * 0.42)
        }
        .allowsHitTesting(false)
    }

    private func judgement(_ presentation: JudgementPresentation) -> some View {
        let event = presentation.event
        let hint = timingHint(for: event)
        // Equal slots either side keep the word centred whether or not the hint shows.
        return HStack(spacing: 6) {
            Color.clear
                .frame(width: 18, height: 1)

            Text(event.judgement.title)
                .font(.system(size: 30, weight: .heavy, design: .rounded))
                .foregroundStyle(Color(Mania4KPlayPalette.judgement(event.judgement)))
                .fixedSize()

            ZStack {
                if let hint {
                    Image(systemName: hint.systemName)
                        .font(.system(size: 17, weight: .heavy, design: .rounded))
                        .foregroundStyle(Color(hint.color))
                        .accessibilityLabel(hint.label)
                }
            }
            .frame(width: 18)
        }
        .opacity(presentation.opacity)
            .scaleEffect(reduceMotion ? 1 : presentation.scale)
            .offset(y: reduceMotion ? 0 : presentation.verticalOffset)
    }

    private func timingHint(for event: JudgementEvent) -> (systemName: String, color: SIMD3<Float>, label: String)? {
        guard event.judgement == .good, let hitErrorMs = event.offsetMs, hitErrorMs != 0 else {
            return nil
        }

        return hitErrorMs < 0
            ? ("arrow.up", Mania4KPlayPalette.early, "Early")
            : ("arrow.down", Mania4KPlayPalette.late, "Late")
    }
}

/// The stage ground with the brand's diagonal division behind the playfield.
private struct Mania4KStageBackground: View {
    var body: some View {
        LinearGradient(
            stops: [
                .init(color: Color(Mania4KPlayPalette.ground), location: 0.58),
                .init(color: Color(Mania4KPlayPalette.groundSecondHalf), location: 0.58)
            ],
            startPoint: UnitPoint(x: 0.27, y: 0.06),
            endPoint: UnitPoint(x: 0.73, y: 0.94)
        )
        .ignoresSafeArea()
    }
}

/// A panel in the brand's card shape: soft corners with a tighter bottom-left one.
private struct Mania4KStatePanel<Accessory: View, Actions: View>: View {
    let title: String
    let detail: String
    @ViewBuilder let accessory: Accessory
    @ViewBuilder let actions: Actions

    var body: some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: 26, bottomLeadingRadius: 10, bottomTrailingRadius: 26, topTrailingRadius: 26
        )

        VStack(spacing: 10) {
            accessory

            Text(title)
                .font(.system(.title2, design: .rounded, weight: .bold))
                .foregroundStyle(Color(Mania4KPlayPalette.text))

            Text(detail)
                .font(.callout)
                .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
                .multilineTextAlignment(.center)
                .lineLimit(4)

            HStack(spacing: 10) {
                actions
            }
            .padding(.top, 8)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 24)
        .frame(maxWidth: 380)
        .background(shape.fill(Color(Mania4KPlayPalette.surface)))
        .overlay(shape.stroke(Color(Mania4KPlayPalette.border), lineWidth: 1))
        .shadow(color: Color.black.opacity(0.35), radius: 24, x: 0, y: 12)
    }
}

/// The brand's primary action: a pill in the action gradient with dark ink.
private struct Mania4KActionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.body, design: .rounded, weight: .bold))
            .foregroundStyle(Color(Mania4KPlayPalette.actionInk))
            .padding(.horizontal, 20)
            .padding(.vertical, 9)
            .background(
                Capsule().fill(
                    LinearGradient(
                        colors: [
                            Color(Mania4KPlayPalette.actionStart),
                            Color(Mania4KPlayPalette.action),
                            Color(Mania4KPlayPalette.actionEnd)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            )
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}

/// An outlined pill for secondary play actions.
private struct Mania4KPillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        let color = isEnabled ? tint : Color(Mania4KPlayPalette.mutedText, opacity: 0.5)
        configuration.label
            .font(.system(.callout, design: .rounded, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(Capsule().fill(color.opacity(configuration.isPressed ? 0.22 : 0.10)))
            .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 1))
            .contentShape(Capsule())
    }
}

private struct Mania4KLiveLaneView: View {
    let lane: Mania4KLane
    let frame: Mania4KPlayFrame?
    let brightness: LaneBrightness
    let noteColor: Color
    let keyLabel: String

    var body: some View {
        GeometryReader { proxy in
            let receptorY = max(0, proxy.size.height - 78)

            ZStack(alignment: .bottom) {
                Rectangle()
                    .fill(Mania4KStyle.laneFill)

                Rectangle()
                    .fill(noteColor.opacity(brightness.lane))
                    .blendMode(.plusLighter)

                if let frame {
                    ForEach(frame.visibleObjects.filter { $0.lane == lane.index }, id: \.id) { object in
                        visibleObject(object, frame: frame, laneSize: proxy.size, receptorY: receptorY)
                    }
                }

                receptorLine

                RoundedRectangle(cornerRadius: 5)
                    .fill(Mania4KStyle.receptorFill)
                    .frame(height: 58)
                    .overlay(
                        RoundedRectangle(cornerRadius: 5)
                            .fill(noteColor.opacity(brightness.receptor))
                            .blendMode(.plusLighter)
                    )
                    .overlay(
                        Text(keyLabel)
                            .font(.headline.monospaced())
                            .foregroundStyle(Mania4KStyle.textPrimary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 5)
                            .stroke(noteColor.opacity(0.62), lineWidth: 1)
                    )
            }
        }
        .background(Mania4KStyle.laneFill, in: RoundedRectangle(cornerRadius: 8))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(noteColor.opacity(0.22), lineWidth: 1)
        )
    }

    private var receptorLine: some View {
        Rectangle()
            .fill(noteColor.opacity(0.82))
            .frame(height: 3)
            .padding(.bottom, 76)
    }

    @ViewBuilder
    private func visibleObject(
        _ object: VisibleObject,
        frame: Mania4KPlayFrame,
        laneSize: CGSize,
        receptorY: CGFloat
    ) -> some View {
        let opacity = object.state == .resolved ? 0.28 : (object.state == .missedButVisible ? 0.36 : 0.96)

        switch noteGeometry(
            object: object,
            renderTimeMs: frame.renderChartTimeMs,
            scrollTimeMs: frame.scrollTimeMs,
            receptorY: Double(receptorY)
        ) {
        case .hold(let bodyTopY, let bodyBottomY, let headY, let tailY):
            RoundedRectangle(cornerRadius: 4)
                .fill(noteColor.opacity(0.42 * opacity))
                .frame(width: max(laneSize.width * 0.52, 24), height: CGFloat(bodyBottomY - bodyTopY))
                .position(x: laneSize.width / 2, y: CGFloat((bodyTopY + bodyBottomY) / 2))

            if let tailY {
                note(width: laneSize.width, height: 18, opacity: opacity)
                    .position(x: laneSize.width / 2, y: CGFloat(tailY))
            }

            note(width: laneSize.width, height: 22, opacity: opacity)
                .position(x: laneSize.width / 2, y: CGFloat(headY))

        case .tap(let y):
            note(width: laneSize.width, height: 24, opacity: opacity)
                .position(x: laneSize.width / 2, y: CGFloat(y))
        }
    }

    private func note(width: CGFloat, height: CGFloat, opacity: Double) -> some View {
        RoundedRectangle(cornerRadius: 5)
            .fill(noteColor.opacity(opacity))
            .frame(width: max(0, width - 16), height: height)
            .shadow(color: noteColor.opacity(0.45), radius: 10, x: 0, y: 0)
    }

}

#if os(macOS)
private struct Mania4KKeyboardCaptureView: NSViewRepresentable {
    let model: Mania4KPlaySessionModel

    func makeNSView(context: Context) -> KeyboardView {
        let view = KeyboardView()
        view.model = model
        return view
    }

    func updateNSView(_ nsView: KeyboardView, context: Context) {
        nsView.model = model
        DispatchQueue.main.async {
            nsView.window?.makeFirstResponder(nsView)
        }
    }

    final class KeyboardView: NSView {
        var model: Mania4KPlaySessionModel?

        override var acceptsFirstResponder: Bool {
            true
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.makeFirstResponder(self)
        }

        override func keyDown(with event: NSEvent) {
            guard let key = event.charactersIgnoringModifiers else {
                return
            }

            model?.handleKeyboardInput(
                key: key,
                isPressed: true,
                isRepeat: event.isARepeat,
                hostTimeMs: event.timestamp * 1_000
            )
        }

        override func keyUp(with event: NSEvent) {
            guard let key = event.charactersIgnoringModifiers else {
                return
            }

            model?.handleKeyboardInput(
                key: key,
                isPressed: false,
                isRepeat: false,
                hostTimeMs: event.timestamp * 1_000
            )
        }
    }
}

private struct Mania4KKeyBindingCaptureView: NSViewRepresentable {
    @Binding var activeLane: Mania4KLane?
    let onCapture: (Mania4KLane, String) -> Void

    func makeNSView(context: Context) -> KeyCaptureView {
        KeyCaptureView()
    }

    func updateNSView(_ nsView: KeyCaptureView, context: Context) {
        nsView.activeLane = activeLane
        nsView.onCapture = onCapture
        nsView.setActiveLane = { activeLane = $0 }

        guard activeLane != nil else {
            return
        }

        DispatchQueue.main.async {
            nsView.window?.makeFirstResponder(nsView)
        }
    }

    final class KeyCaptureView: NSView {
        var activeLane: Mania4KLane?
        var onCapture: ((Mania4KLane, String) -> Void)?
        var setActiveLane: ((Mania4KLane?) -> Void)?

        override var acceptsFirstResponder: Bool {
            true
        }

        override func keyDown(with event: NSEvent) {
            guard let activeLane else {
                return
            }

            if event.keyCode == 53 {
                setActiveLane?(nil)
                return
            }

            guard !event.isARepeat,
                  let key = event.charactersIgnoringModifiers,
                  !Mania4KKeyBindingSet.normalizedKey(key).isEmpty
            else {
                return
            }

            setActiveLane?(nil)
            onCapture?(activeLane, key)
        }
    }
}
#endif

// Placeholder visual theme for the first playable mock. Replace with shared tokens once Ensomi has a settled design system.
private enum Mania4KStyle {
    static let backgroundTop = Color(red: 0.025, green: 0.035, blue: 0.055)
    static let backgroundBottom = Color(red: 0.085, green: 0.045, blue: 0.070)
    static let panelFill = Color(red: 0.075, green: 0.085, blue: 0.115)
    static let controlFill = Color(red: 0.105, green: 0.120, blue: 0.160)
    static let stageFill = Color(red: 0.030, green: 0.035, blue: 0.050)
    static let laneFill = Color(red: 0.070, green: 0.080, blue: 0.110).opacity(0.86)
    static let receptorFill = Color(red: 0.150, green: 0.165, blue: 0.205)
    static let border = Color.white.opacity(0.12)
    static let borderStrong = Color.white.opacity(0.20)
    static let textPrimary = Color(red: 0.965, green: 0.980, blue: 1.000)
    static let textSecondary = Color(red: 0.700, green: 0.760, blue: 0.840)
    static let textMuted = Color(red: 0.500, green: 0.565, blue: 0.660)
    static let accentBlue = Color(red: 0.290, green: 0.670, blue: 1.000)
    static let accentGreen = Color(red: 0.260, green: 0.880, blue: 0.620)
    static let accentAmber = Color(red: 1.000, green: 0.700, blue: 0.230)
    static let accentRed = Color(red: 1.000, green: 0.330, blue: 0.390)
}

private struct Mania4KBackdrop: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Mania4KStyle.backgroundTop,
                    Mania4KStyle.backgroundBottom,
                    Color(red: 0.025, green: 0.028, blue: 0.040)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            Mania4KGridOverlay(spacing: 44, opacity: 0.08)
        }
        .ignoresSafeArea()
    }
}

private struct Mania4KGridOverlay: View {
    let spacing: CGFloat
    let opacity: Double

    var body: some View {
        Canvas { context, size in
            var path = Path()

            for y in stride(from: CGFloat(0), through: size.height, by: spacing) {
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
            }

            for x in stride(from: CGFloat(0), through: size.width, by: spacing) {
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
            }

            context.stroke(path, with: .color(Color.white.opacity(opacity)), lineWidth: 1)
        }
        .allowsHitTesting(false)
    }
}

private struct Mania4KPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline.weight(.bold))
            .foregroundStyle(isEnabled ? Mania4KStyle.textPrimary : Mania4KStyle.textMuted)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(
                LinearGradient(
                    colors: isEnabled ? [Mania4KStyle.accentBlue, Mania4KStyle.accentGreen] : [Mania4KStyle.controlFill, Mania4KStyle.controlFill],
                    startPoint: .leading,
                    endPoint: .trailing
                ),
                in: RoundedRectangle(cornerRadius: 8)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.white.opacity(0.24), lineWidth: 1)
            )
            .shadow(color: Mania4KStyle.accentBlue.opacity(isEnabled ? (configuration.isPressed ? 0.18 : 0.34) : 0), radius: configuration.isPressed ? 5 : 14, x: 0, y: 0)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .scaleEffect(configuration.isPressed ? 0.99 : 1)
    }
}

private struct Mania4KSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    var tint = Mania4KStyle.accentBlue

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.callout.weight(.semibold))
            .foregroundStyle(isEnabled ? tint : Mania4KStyle.textMuted)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background((isEnabled ? tint : Mania4KStyle.textMuted).opacity(configuration.isPressed ? 0.20 : 0.12), in: RoundedRectangle(cornerRadius: 7))
            .overlay(
                RoundedRectangle(cornerRadius: 7)
                    .stroke((isEnabled ? tint : Mania4KStyle.textMuted).opacity(0.42), lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}

private extension Error {
    var isUserCancellation: Bool {
        let error = self as NSError
        return error.domain == NSCocoaErrorDomain && error.code == NSUserCancelledError
    }
}

private extension View {
    func panelStyle() -> some View {
        padding(18)
            .background(Mania4KStyle.panelFill.opacity(0.94), in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Mania4KStyle.borderStrong, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.28), radius: 18, x: 0, y: 12)
    }
}

#Preview("Setup") {
    Mania4KPlayExperienceView(model: Mania4KPlaySessionModel())
}
