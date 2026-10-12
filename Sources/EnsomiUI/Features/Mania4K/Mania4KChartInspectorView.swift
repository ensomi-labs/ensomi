#if os(macOS)
import EnsomiCore
import SwiftUI

public enum Mania4KChartInspectorWindow {
    public static let windowID = "mania4k-chart-inspector"
    public static let windowTitle = "Chart Inspector"
}

/// The chart inspector, opened from Settings' Developer section: a chart drawn against its audio by the play screen's
/// playfield, with play and pause, a scrubber, steps by time and by note, a playback rate and the scroll speed. The
/// offsets, scroll speed and volume are the ones Settings sets for play.
public struct Mania4KChartInspectorView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.mania4kPlayfieldSnapshot) private var playfieldSnapshot
    @Bindable public var model: Mania4KChartInspectionModel
    @State private var isChoosingFiles = false
    @State private var resumesAfterScrubbing = false

    public init(model: Mania4KChartInspectionModel) {
        self.model = model
    }

    public var body: some View {
        VStack(spacing: 0) {
            header

            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            if let chart = model.chart {
                transport(durationMs: chart.durationMs)
            }
        }
        .background(Mania4KStageBackground())
        .preferredColorScheme(.dark)
        .fileImporter(
            isPresented: $isChoosingFiles,
            // Any file: the model sorts the .osz, .osu and audio apart and refuses what it cannot open.
            allowedContentTypes: [.data],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls):
                Task { await model.open(urls) }
            case .failure(let error):
                let error = error as NSError
                if !(error.domain == NSCocoaErrorDomain && error.code == NSUserCancelledError) {
                    model.recordOpenFailure(error)
                }
            }
        }
        .onChange(of: model.settings.musicVolume) {
            Task { await model.applyMusicVolume() }
        }
        .onDisappear {
            Task { await model.pause() }
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.chart?.title ?? Mania4KChartInspectorWindow.windowTitle)
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .foregroundStyle(Color(Mania4KPlayPalette.text))
                    .lineLimit(1)
                    .truncationMode(.middle)

                Text(subtitle)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer()

            if model.chart != nil, readableDifficulties.count > 1 {
                Picker("Difficulty", selection: difficultyBinding) {
                    ForEach(readableDifficulties) { difficulty in
                        Text(difficulty.name).tag(Optional(difficulty.id))
                    }
                }
                .fixedSize()
                .disabled(model.isLoading)
            }

            Button {
                isChoosingFiles = true
            } label: {
                Label("Open…", systemImage: "folder")
            }
            .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.hold)))
            .keyboardShortcut("o")
            .help("Open an .osz, or an .osu file together with its audio file (⌘O)")
            .disabled(model.isLoading)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
    }

    private var subtitle: String {
        guard let chart = model.chart else {
            return "No chart open"
        }

        return ([chart.artist, chart.difficulty].compactMap { $0 } + ["\(chart.objectCount.formatted()) notes"])
            .joined(separator: "  ·  ")
    }

    @ViewBuilder
    private var content: some View {
        if model.chart != nil {
            GeometryReader { proxy in
                playfield
                    .frame(width: min(max(0, proxy.size.width - 32), 440))
                    .padding(.bottom, 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .overlay(alignment: .top) {
                if let error = model.error {
                    errorLabel(error)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(Color(Mania4KPlayPalette.surface)))
                }
            }
        } else if !model.difficulties.isEmpty {
            difficultyList
        } else if let error = model.error {
            Mania4KStatePanel(title: "The chart could not be opened", detail: error.localizedDescription) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.title2)
                    .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
            } actions: {
                openButton
            }
        } else {
            Mania4KStatePanel(
                title: "Open a Chart",
                detail: "An .osz, or an .osu file together with its audio file. The chart is drawn against the song with the offsets, scroll speed and volume from Settings."
            ) {
                if model.isLoading {
                    ProgressView()
                        .controlSize(.small)
                }
            } actions: {
                openButton
            }
        }
    }

    private var openButton: some View {
        Button {
            isChoosingFiles = true
        } label: {
            Label("Open…", systemImage: "folder")
        }
        .buttonStyle(Mania4KActionButtonStyle())
        .disabled(model.isLoading)
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

    /// The `.osz`'s difficulties, those the gameplay core refused shown with its reason and not selectable.
    private var difficultyList: some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: 26, bottomLeadingRadius: 10, bottomTrailingRadius: 26, topTrailingRadius: 26
        )
        return VStack(alignment: .leading, spacing: 12) {
            Text("Choose a Difficulty")
                .font(.system(.title2, design: .rounded, weight: .bold))
                .foregroundStyle(Color(Mania4KPlayPalette.text))

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(model.difficulties) { difficulty in
                        difficultyRow(difficulty)
                    }
                }
            }
            .frame(maxHeight: 420)
            .fixedSize(horizontal: false, vertical: true)

            if let error = model.error {
                errorLabel(error)
            }
        }
        .padding(24)
        .frame(maxWidth: 440)
        .background(shape.fill(Color(Mania4KPlayPalette.surface)))
        .overlay(shape.stroke(Color(Mania4KPlayPalette.border), lineWidth: 1))
        .shadow(color: Color.black.opacity(0.35), radius: 24, x: 0, y: 12)
    }

    private func difficultyRow(_ difficulty: Mania4KInspectionDifficulty) -> some View {
        let detail: String
        let isReadable: Bool
        switch difficulty.beatmap {
        case .success(let beatmap):
            detail = "\(beatmap.chart.objects.count.formatted()) notes"
            isReadable = true
        case .failure(let refusal):
            detail = refusal.localizedDescription
            isReadable = false
        }

        return Button {
            Task { await model.choose(difficulty) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: isReadable ? "music.note.list" : "exclamationmark.triangle")
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(difficulty.name)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(Color(Mania4KPlayPalette.text))
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(Color(Mania4KPlayPalette.hold))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .contentShape(RoundedRectangle(cornerRadius: 12))
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(Mania4KPlayPalette.ground, opacity: 0.6)))
        }
        .buttonStyle(.plain)
        .disabled(!isReadable || model.isLoading)
    }

    private func errorLabel(_ error: Mania4KChartInspectionError) -> some View {
        Label(error.localizedDescription, systemImage: "exclamationmark.triangle")
            .font(.callout)
            .foregroundStyle(Color(Mania4KPlayPalette.text))
            .textSelection(.enabled)
    }

    private func transport(durationMs: Double) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Button {
                    Task { await model.stepToNote(forward: false) }
                } label: {
                    Label("Previous Note", systemImage: "backward.end.fill")
                }
                .keyboardShortcut(.leftArrow, modifiers: .option)
                .help("Put the previous note on the judgement line (⌥←)")

                Button {
                    Task { await model.step(by: -Mania4KChartInspectionModel.stepMs) }
                } label: {
                    Label("Step Back", systemImage: "backward.frame.fill")
                }
                .keyboardShortcut(.leftArrow, modifiers: [])
                .help("Back \(Int(Mania4KChartInspectionModel.stepMs)) ms (←)")

                Button {
                    Task { await model.togglePlayback() }
                } label: {
                    Label(model.isPlaying ? "Pause" : "Play", systemImage: model.isPlaying ? "pause.fill" : "play.fill")
                        .frame(width: 18)
                }
                .buttonStyle(Mania4KActionButtonStyle())
                .keyboardShortcut(.space, modifiers: [])
                .help(model.isPlaying ? "Pause (Space)" : "Play (Space)")

                Button {
                    Task { await model.step(by: Mania4KChartInspectionModel.stepMs) }
                } label: {
                    Label("Step Forward", systemImage: "forward.frame.fill")
                }
                .keyboardShortcut(.rightArrow, modifiers: [])
                .help("Forward \(Int(Mania4KChartInspectionModel.stepMs)) ms (→)")

                Button {
                    Task { await model.stepToNote(forward: true) }
                } label: {
                    Label("Next Note", systemImage: "forward.end.fill")
                }
                .keyboardShortcut(.rightArrow, modifiers: .option)
                .help("Put the next note on the judgement line (⌥→)")

                Text(Self.timeLabel(model.positionMs))
                    .monospacedDigit()
                    .foregroundStyle(Color(Mania4KPlayPalette.text))
                    .padding(.leading, 6)

                Slider(value: positionBinding, in: 0...max(durationMs, 1)) { isEditing in
                    if isEditing {
                        resumesAfterScrubbing = model.isPlaying
                        Task { await model.pause() }
                    } else if resumesAfterScrubbing {
                        Task { await model.play() }
                    }
                }
                .accessibilityLabel("Position")
                .accessibilityValue(Self.timeLabel(model.positionMs))

                Text(Self.timeLabel(durationMs))
                    .monospacedDigit()
                    .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
            }
            .labelStyle(.iconOnly)
            .buttonStyle(Mania4KPillButtonStyle(tint: Color(Mania4KPlayPalette.hold)))

            HStack(spacing: 18) {
                Picker("Rate", selection: rateBinding) {
                    ForEach(Mania4KChartInspectionModel.rates, id: \.self) { rate in
                        Text("\(rate.formatted())×").tag(rate)
                    }
                }
                .fixedSize()
                .help("Playback rate; the pitch follows it")

                Stepper(value: scrollSpeedBinding, in: Mania4KDefaultPlaySettings.scrollSpeedRange, step: 1) {
                    Text("Scroll speed \(model.settings.scrollSpeed.formatted(.number.precision(.fractionLength(1))))×")
                        .monospacedDigit()
                }
                .help("The scroll speed of play, as in Settings")

                Spacer(minLength: 0)

                Text(settingsSummary)
                    .font(.caption)
                    .foregroundStyle(Color(Mania4KPlayPalette.mutedText))
                    .lineLimit(2)
                    .multilineTextAlignment(.trailing)
            }
            .foregroundStyle(Color(Mania4KPlayPalette.text))
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 14)
    }

    private var settingsSummary: String {
        let settings = model.settings
        let device = settings.outputDevice.map { " for \($0.name)" } ?? ""
        return "Audio offset \(Int(settings.audioOffsetMilliseconds)) ms\(device)  ·  Visual offset \(Int(settings.visualOffsetMilliseconds)) ms  ·  Volume \(settings.musicVolume.formatted(.percent.precision(.fractionLength(0)))), from Settings"
    }

    private var readableDifficulties: [Mania4KInspectionDifficulty] {
        model.difficulties.filter { (try? $0.beatmap.get()) != nil }
    }

    private var difficultyBinding: Binding<String?> {
        Binding(
            get: { model.chosenDifficultyID },
            set: { id in
                if let difficulty = model.difficulties.first(where: { $0.id == id }) {
                    Task { await model.choose(difficulty) }
                }
            }
        )
    }

    private var positionBinding: Binding<Double> {
        Binding(
            get: { model.positionMs },
            set: { positionMs in Task { await model.seek(toMs: positionMs) } }
        )
    }

    private var rateBinding: Binding<Double> {
        Binding(
            get: { model.rate },
            set: { rate in Task { await model.setRate(rate) } }
        )
    }

    private var scrollSpeedBinding: Binding<Double> {
        Binding(
            get: { model.settings.scrollSpeed },
            set: { model.settings.scrollSpeed = $0 }
        )
    }

    /// `m:ss.mmm`.
    static func timeLabel(_ milliseconds: Double) -> String {
        let seconds = max(0, milliseconds) / 1000
        return String(format: "%d:%06.3f", Int(seconds) / 60, seconds.truncatingRemainder(dividingBy: 60))
    }
}
#endif
