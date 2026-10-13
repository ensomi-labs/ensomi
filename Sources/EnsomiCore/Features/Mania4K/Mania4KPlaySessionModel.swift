import EnsomiRuntime
import Foundation
import Observation

public struct Mania4KPlayHUD: Equatable, Sendable {
    public let title: String?
    public let accuracy: Double
    public let combo: Int
    public let latestJudgement: Judgement?

    public init(title: String? = nil, accuracy: Double = 1, combo: Int = 0, latestJudgement: Judgement? = nil) {
        self.title = title
        self.accuracy = accuracy
        self.combo = combo
        self.latestJudgement = latestJudgement
    }
}

/// The chart times of one frame. Judgement is at the audio time heard at the frame's host time plus the audio offset;
/// drawing is at the audio time heard when the display shows the frame, plus both offsets. Play and chart inspection
/// both time their frames with it, so the offsets apply once and the same way.
struct Mania4KFrameTiming {
    let gameplayChartTimeMs: Double
    let renderChartTimeMs: Double

    init(gameplayChartTimeMs: Double, renderChartTimeMs: Double) {
        self.gameplayChartTimeMs = gameplayChartTimeMs
        self.renderChartTimeMs = renderChartTimeMs
    }

    init(
        clock: any Mania4KAudioClock,
        audioOffsetMilliseconds: Double,
        visualOffsetMilliseconds: Double,
        atHostTimeMs hostTimeMs: Double,
        presentationHostTimeMs: Double
    ) {
        gameplayChartTimeMs = clock.audioTimeMs(atHostTimeMs: hostTimeMs) + audioOffsetMilliseconds
        renderChartTimeMs = clock.audioTimeMs(atHostTimeMs: presentationHostTimeMs) + audioOffsetMilliseconds
            + visualOffsetMilliseconds
    }

    /// What the frame draws: objects from 700 ms before the render time to 250 ms beyond the top of the playfield.
    func visibleObjects(of session: GameplaySession, scrollTimeMs: Double) -> [VisibleObject] {
        session.visibleObjects(lowerMs: renderChartTimeMs - 700, upperMs: renderChartTimeMs + scrollTimeMs + 250)
    }
}

@MainActor
@Observable
public final class Mania4KPlaySessionModel {
    /// How long after its timestamp a key event may reach the session and still be judged at that timestamp. A
    /// frame declares inputs delivered only up to its chart time minus this (the core never moves its clock back),
    /// because key events stamped before the frame can still wait in the main thread's queue. Hits are judged as
    /// soon as their key is handled; a miss shows up to this much later.
    static let inputDeliveryAllowanceMs = 100.0
    static let offsetStateKey = "mania4k.offsetCalibrationState"
    static let musicVolumeKey = "mania4k.musicVolume"
    private static let generatorDescription = "Ensomi R2 generator"

    public var audioFileURL: URL?
    public var starDifficulty: Double
    public var scrollSpeed: Double
    public var judgeDifficulty: Mania4KJudgeDifficulty
    public var keyBindings: Mania4KKeyBindingSet
    /// The offsets and presets, with each output device's audio offset. They change only while no game is in progress.
    public private(set) var offsetState: Mania4KOffsetCalibrationStoredState
    /// The song's level in play, from 0 to 1 of its own level. It changes only while no game is in progress.
    public private(set) var musicVolume: Double
    /// The output device whose audio offset is applied. A change of device during a game applies when the game ends.
    public private(set) var outputDevice: Mania4KAudioOutputDevice?

    public private(set) var audioSelectionErrorMessage: String?
    public private(set) var keyBindingErrorMessage: String?
    public private(set) var activeConfiguration: Mania4KPlayConfiguration?
    public private(set) var phase: Mania4KPlayPhase {
        didSet { applyReportedOutputDevice() }
    }
    public private(set) var playFrame: Mania4KPlayFrame?
    public private(set) var hud: Mania4KPlayHUD
    public private(set) var judgementPresentation: JudgementPresentation?
    /// A reference type: its state changes without notifying observers. The playfield pulls it every frame, and
    /// `judgementPresentation` carries what SwiftUI shows.
    public private(set) var gameplayFeedback: GameplayFeedback
    public private(set) var liveInputLaneStates: [LaneState]

    @ObservationIgnored private let settings: UserDefaults?
    @ObservationIgnored private var reportedOutputDevice: Mania4KAudioOutputDevice?
    private let defaultAudioClock: any Mania4KAudioClock
    @ObservationIgnored private var activeAudioClock: any Mania4KAudioClock
    /// The scheduler's chart that ambient play hears each frame.
    @ObservationIgnored private var scheduledChart: ScheduledChart?
    @ObservationIgnored private var gameplay: Mania4KGameplay?
    @ObservationIgnored private var metadata: Mania4KChartMetadata?
    @ObservationIgnored private var audioMetadata: Mania4KAudioMetadata?
    @ObservationIgnored private var inputSequenceNumber: UInt64
    @ObservationIgnored private var keyboardRouter: Mania4KKeyboardInputRouter
    @ObservationIgnored private var isCheckingForFinish: Bool
    @ObservationIgnored private var gameplayIsResolved: Bool
    @ObservationIgnored private var playStateGeneration: UInt64
    @ObservationIgnored private var pendingInputs: [Mania4KInputEvent]

    public init(
        audioFileURL: URL? = nil,
        starDifficulty: Double = 4.0,
        scrollSpeed: Double = Mania4KDefaultPlaySettings.scrollSpeed,
        audioOffsetMilliseconds: Double = Double(Mania4KDefaultPlaySettings.audioOffsetMilliseconds),
        visualOffsetMilliseconds: Double = Double(Mania4KDefaultPlaySettings.visualOffsetMilliseconds),
        judgeDifficulty: Mania4KJudgeDifficulty = .c,
        keyBindings: Mania4KKeyBindingSet = .default,
        settings: UserDefaults? = nil,
        audioClock: any Mania4KAudioClock = AVFoundationMania4KAudioClock()
    ) {
        self.audioFileURL = audioFileURL
        self.starDifficulty = starDifficulty
        self.scrollSpeed = scrollSpeed
        self.judgeDifficulty = judgeDifficulty
        self.keyBindings = keyBindings
        // Offsets and volume stored in `settings` take the place of the given ones.
        let storedOffsetState = settings?.string(forKey: Self.offsetStateKey)
            .flatMap(Mania4KOffsetCalibrationStoredState.init(storageValue:))
        self.offsetState = storedOffsetState.map {
            Mania4KOffsetCalibrationModel.normalizedStoredState(
                $0,
                fallbackAppliedAudioOffsetMilliseconds: 0,
                fallbackAppliedVisualOffsetMilliseconds: 0
            )
        } ?? Mania4KOffsetCalibrationStoredState(
            appliedAudioOffsetMilliseconds: Int(audioOffsetMilliseconds.rounded()),
            appliedVisualOffsetMilliseconds: Int(visualOffsetMilliseconds.rounded()),
            presets: [Mania4KOffsetCalibrationStoredState.defaultPlayPreset]
        )
        self.musicVolume = settings?.object(forKey: Self.musicVolumeKey) as? Double ?? Mania4KDefaultPlaySettings.musicVolume
        self.settings = settings
        self.defaultAudioClock = audioClock
        self.activeAudioClock = audioClock
        self.phase = .setup
        self.hud = Mania4KPlayHUD()
        self.gameplayFeedback = GameplayFeedback()
        self.liveInputLaneStates = Self.makeLaneStates(pressedLanes: [])
        self.inputSequenceNumber = 0
        self.keyboardRouter = Mania4KKeyboardInputRouter(keyBindings: keyBindings)
        self.playStateGeneration = 0
        self.pendingInputs = []
        self.isCheckingForFinish = false
        self.gameplayIsResolved = false
    }

    /// A game has started and not ended. Volume, offsets and the output device's offset wait until it ends.
    public var isGameInProgress: Bool {
        switch phase {
        case .loading, .ready, .playing, .paused: true
        case .setup, .finished, .failed: false
        }
    }

    /// The current output device's audio offset.
    public var audioOffsetMilliseconds: Double {
        Double(offsetState.appliedAudioOffsetMilliseconds)
    }

    /// The display's visual offset, the same for every output device.
    public var visualOffsetMilliseconds: Double {
        Double(offsetState.appliedVisualOffsetMilliseconds)
    }

    /// Sets the audio offset and saves it as the current output device's.
    @discardableResult
    public func setAudioOffset(_ milliseconds: Int) -> Bool {
        var state = offsetState
        state.setAudioOffset(milliseconds, for: outputDevice)
        return updateOffsetState(state)
    }

    @discardableResult
    public func setVisualOffset(_ milliseconds: Int) -> Bool {
        var state = offsetState
        state.appliedVisualOffsetMilliseconds = milliseconds
        return updateOffsetState(state)
    }

    /// Takes offsets and presets as the offset calibration leaves them. The current output device's audio offset
    /// stays the applied one.
    @discardableResult
    public func updateOffsetState(_ state: Mania4KOffsetCalibrationStoredState) -> Bool {
        guard !isGameInProgress else {
            return false
        }

        var state = state
        if let outputDevice {
            state.activate(outputDevice)
        }
        offsetState = state
        settings?.set(state.storageValue, forKey: Self.offsetStateKey)
        return true
    }

    /// Sets the song's level, which only scales it down: values outside 0 to 1 take the nearer end.
    @discardableResult
    public func setMusicVolume(_ volume: Double) -> Bool {
        guard !isGameInProgress else {
            return false
        }

        musicVolume = min(max(volume, 0), 1)
        settings?.set(musicVolume, forKey: Self.musicVolumeKey)
        return true
    }

    /// The output device is now `device`. Its audio offset, or the default for a device without one, applies at
    /// once, or when the game in progress ends.
    public func outputDeviceChanged(to device: Mania4KAudioOutputDevice?) {
        reportedOutputDevice = device
        applyReportedOutputDevice()
    }

    /// Follows the system's output device for the life of the app.
    public func followSystemOutputDevice() {
        Mania4KAudioOutputDevice.observeChanges { [weak self] in
            self?.outputDeviceChanged(to: Mania4KAudioOutputDevice.current())
        }
        outputDeviceChanged(to: Mania4KAudioOutputDevice.current())
    }

    private func applyReportedOutputDevice() {
        guard !isGameInProgress, reportedOutputDevice != outputDevice else {
            return
        }

        outputDevice = reportedOutputDevice
        updateOffsetState(offsetState)
    }

    public var audioFileName: String {
        audioFileURL?.lastPathComponent ?? "No audio file selected"
    }

    public var scrollTimeMs: Double {
        let range = Mania4KDefaultPlaySettings.scrollSpeedRange
        return 11_485 / min(max(scrollSpeed, range.lowerBound), range.upperBound)
    }

    public func selectAudioFile(_ url: URL) {
        audioFileURL = url
        audioSelectionErrorMessage = nil
        resetPreparedPlayState()
    }

    public func clearSetupSelections() {
        guard phase == .setup else {
            return
        }

        resetPreparedPlayState()
        audioFileURL = nil
        audioSelectionErrorMessage = nil
    }

    public func recordAudioImportFailure(_ error: Error) {
        audioSelectionErrorMessage = "Could not choose audio: \(error.localizedDescription)"
    }

    /// Plays a complete generated chart with its audio from time 0.
    @discardableResult
    public func startGeneratedChartPlay(
        audioFileURL: URL,
        chart: Mania4KGeneratedChart,
        title: String? = nil,
        artist: String? = nil
    ) async -> Bool {
        let title = title?.trimmedNilIfEmpty ?? audioFileURL.deletingPathExtension().lastPathComponent
        let metadata = Mania4KChartMetadata(
            title: title, artist: artist, sourceDescription: Self.generatorDescription,
            objectCount: chart.objectCount, durationMs: chart.durationMs)
        return await startPlay(audioFileURL: audioFileURL, chartSource: .generated(displayName: "Generated: \(title)")) { ruleset in
            (try GameplaySession(chart: chart.chart, ruleset: ruleset), metadata)
        }
    }

    /// Plays `chart` along with music heard in the room, showing loading until the chart is whole: the clock runs on
    /// from `referenceTimeMS` at `anchorHostTimeMS`, and play joins the chart at the scheduler's join point past the
    /// position heard then. The local reference is never played aloud.
    @discardableResult
    public func startAmbientChartPlay(
        audioFileURL: URL, referenceTimeMS: Double, anchorHostTimeMS: Double,
        title: String? = nil, chart: ScheduledChart
    ) async -> Bool {
        let title = title ?? audioFileURL.deletingPathExtension().lastPathComponent
        // Unclamped: the chart's duration, measured from the decoded audio, ends play; a rounded library duration
        // must not freeze the clock early.
        let clock = HostTimeAnchoredMania4KAudioClock(referenceTimeAtAnchorMS: referenceTimeMS,
            title: title, anchorHostTimeMS: anchorHostTimeMS)
        return await startPlay(audioFileURL: audioFileURL, chartSource: .generated(displayName: title),
            playbackClock: clock, scheduledChart: chart) { ruleset in
            let session = GameplaySession.streaming(ruleset: ruleset)
            try await chart.start(session: session) { await self.currentFrameTiming().gameplayChartTimeMs }
            let made = try await chart.result().chart
            return (session, Mania4KChartMetadata(
                title: title, sourceDescription: Self.generatorDescription,
                objectCount: made.objects.count, durationMs: Double(made.durationMs)))
        }
    }

    /// Plays the session `chart` gives for the game's rules, with its audio on `playbackClock`, by default the
    /// local-file clock from time 0.
    private func startPlay(
        audioFileURL: URL,
        chartSource: Mania4KChartSource,
        playbackClock: (any Mania4KAudioClock)? = nil,
        scheduledChart: ScheduledChart? = nil,
        chart: (Ruleset) async throws -> (session: GameplaySession, metadata: Mania4KChartMetadata)
    ) async -> Bool {
        resetPreparedPlayState()
        self.audioFileURL = audioFileURL
        let startGeneration = playStateGeneration
        activeConfiguration = Mania4KPlayConfiguration(
            chartSource: chartSource,
            audioFileURL: audioFileURL, starDifficulty: starDifficulty,
            scrollSpeed: scrollSpeed, audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds, judgeDifficulty: judgeDifficulty,
            keyBindings: keyBindings)
        phase = .loading
        activeAudioClock = playbackClock ?? defaultAudioClock
        self.scheduledChart = scheduledChart

        let prepared: (session: GameplaySession, metadata: Mania4KChartMetadata)
        do {
            prepared = try await chart(Ruleset(judge: judgeDifficulty.judge))
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
        } catch {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(Self.playFailure(for: error))
            return false
        }

        let preparedAudioMetadata: Mania4KAudioMetadata
        do {
            // The game keeps this level: the volume does not change while it is in progress.
            await activeAudioClock.setMusicVolume(musicVolume)
            preparedAudioMetadata = try await activeAudioClock.prepare(audioFileURL: audioFileURL)
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
        } catch let failure as Mania4KPlayFailure {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(failure)
            return false
        } catch {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(.audioPrepareFailed(error.localizedDescription))
            return false
        }

        do {
            let gameplay = Mania4KGameplay(session: prepared.session, feedback: gameplayFeedback)
            self.gameplay = gameplay
            metadata = prepared.metadata
            audioMetadata = preparedAudioMetadata
            keyboardRouter.reset()
            resetLiveInputLaneStates()

            let initialTiming = await currentFrameTiming()
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
            gameplay.advance(
                to: initialTiming.gameplayChartTimeMs - Self.inputDeliveryAllowanceMs,
                frameTimeMs: initialTiming.gameplayChartTimeMs
            )
            publishFrame(timing: initialTiming)

            try await activeAudioClock.play()
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
            phase = .playing
            return true
        } catch {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(Self.playFailure(for: error))
            return false
        }
    }

    public func pause() async {
        guard phase == .playing else {
            return
        }

        phase = .paused
        pendingInputs.removeAll()
        await activeAudioClock.pause()
    }

    public func resume() async {
        guard phase == .paused else {
            return
        }

        do {
            try await activeAudioClock.play()
        } catch let failure as Mania4KPlayFailure {
            await fail(failure)
            return
        } catch {
            await fail(.audioPrepareFailed(error.localizedDescription))
            return
        }

        guard phase == .paused else {
            return
        }

        phase = .playing
    }

    public func quitToSetup() async {
        let previousClock = activeAudioClock
        resetPreparedPlayState()
        await previousClock.stop()
    }

    /// One game frame: delivers the inputs stamped up to this frame in timestamp order, advances judgement to the
    /// chart time of `hostTimeMs`, then ends the game if it is over. The frame is drawn for `presentationHostTimeMs`,
    /// when the display shows it.
    @discardableResult
    public func tick(
        atHostTimeMs hostTimeMs: Double = EnsomiHostClock.currentTimeMS(),
        presentationHostTimeMs: Double? = nil
    ) async -> Bool {
        guard phase == .playing else {
            return false
        }

        do {
            let timing = try judgeFrame(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs ?? hostTimeMs)
            await finishIfNeeded(chartTimeMs: timing.gameplayChartTimeMs)
            return true
        } catch {
            await fail(Self.playFailure(for: error))
            return false
        }
    }

    /// One game frame for the display link, without suspending between delivering inputs and advancing the session.
    @discardableResult
    public func advanceFrame(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) -> Bool {
        guard phase == .playing else {
            return false
        }

        do {
            let timing = try judgeFrame(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs)
            finishIfResolved(atChartTimeMs: timing.gameplayChartTimeMs)
            return true
        } catch {
            phase = .failed(Self.playFailure(for: error))
            Task { await releaseAfterFailure() }
            return false
        }
    }

    /// Queues a key event for the next frame, converting its timestamp (`NSEvent.timestamp`, in
    /// `EnsomiHostClock` milliseconds) to chart time through the audio clock. Returns whether the key was accepted.
    @discardableResult
    public func handleKeyboardInput(
        key: String,
        isPressed: Bool,
        isRepeat: Bool,
        hostTimeMs: Double = EnsomiHostClock.currentTimeMS()
    ) -> Bool {
        guard phase == .playing else {
            return false
        }

        inputSequenceNumber += 1
        guard let input = keyboardRouter.route(
            key: key,
            isPressed: isPressed,
            isRepeat: isRepeat,
            chartTimeMs: gameplayChartTimeMs(atHostTimeMs: hostTimeMs),
            sequenceNumber: inputSequenceNumber
        ) else {
            return false
        }

        accept(input, atUITimeMs: hostTimeMs)
        return true
    }

    @discardableResult
    public func handleInput(_ input: Mania4KInputEvent) -> Bool {
        guard phase == .playing else {
            return false
        }

        accept(input, atUITimeMs: EnsomiHostClock.currentTimeMS())
        return true
    }

    @discardableResult
    public func updateKeyBinding(lane: Mania4KLane, key: String) -> Bool {
        guard phase == .setup else {
            keyBindingErrorMessage = "Key bindings can only be changed from setup."
            return false
        }

        guard let nextKeyBindings = keyBindings.updating(lane: lane, key: key) else {
            keyBindingErrorMessage = "Choose four unique non-empty keys."
            return false
        }

        applyKeyBindings(nextKeyBindings)
        keyBindingErrorMessage = nil
        return true
    }

    public func applyKeyBindings(_ keyBindings: Mania4KKeyBindingSet) {
        guard phase == .setup else {
            keyBindingErrorMessage = "Key bindings can only be changed from setup."
            return
        }

        self.keyBindings = keyBindings
        keyboardRouter.updateKeyBindings(keyBindings)
        resetLiveInputLaneStates()
        keyBindingErrorMessage = nil
    }

    public func resetKeyBindingsToDefault() {
        applyKeyBindings(.default)
    }

    private func accept(_ input: Mania4KInputEvent, atUITimeMs uiTimeMs: Double) {
        gameplayFeedback.recordInput(
            lane: input.lane.index,
            phase: input.phase,
            sequenceNumber: input.sequenceNumber,
            uiTimeMs: uiTimeMs
        )
        commitLiveInputState(input)
        pendingInputs.append(input)
    }

    @discardableResult
    private func judgeFrame(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) throws -> Mania4KFrameTiming {
        let timing = frameTiming(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs)
        guard let gameplay else {
            return timing
        }
        scheduledChart?.heard(chartMs: timing.gameplayChartTimeMs, wallMs: hostTimeMs)

        let due = pendingInputs
            .filter { $0.chartTimeMs <= timing.gameplayChartTimeMs }
            .sorted { ($0.chartTimeMs, $0.sequenceNumber) < ($1.chartTimeMs, $1.sequenceNumber) }
        pendingInputs.removeAll { $0.chartTimeMs <= timing.gameplayChartTimeMs }
        for input in due {
            gameplay.input(InputEvent(lane: input.lane.index, phase: input.phase, timeMs: input.chartTimeMs))
        }

        let watermarkMs = timing.gameplayChartTimeMs - Self.inputDeliveryAllowanceMs
        gameplay.advance(to: watermarkMs, frameTimeMs: timing.gameplayChartTimeMs)
        guard gameplay.session.decidedThroughMs() >= watermarkMs else {
            throw Mania4KPlayFailure.streamFailed("The chart stream fell behind the judgement clock.")
        }

        publishFrame(timing: timing)
        return timing
    }

    private func finishIfResolved(atChartTimeMs chartTimeMs: Double) {
        guard gameplayIsResolved, !isCheckingForFinish else {
            return
        }

        isCheckingForFinish = true
        let generation = playStateGeneration
        Task {
            await finishIfNeeded(chartTimeMs: chartTimeMs)
            if playStateGeneration == generation {
                isCheckingForFinish = false
            }
        }
    }

    private static func playFailure(for error: Error) -> Mania4KPlayFailure {
        switch error {
        case let error as ChartError:
            return .chartPrepareFailed(.chart(error))
        case let failure as Mania4KPlayFailure:
            return failure
        default:
            return .streamFailed(error.localizedDescription)
        }
    }

    private func resetPreparedPlayState() {
        playStateGeneration &+= 1
        isCheckingForFinish = false
        gameplayIsResolved = false
        activeConfiguration = nil
        scheduledChart = nil
        gameplay = nil
        metadata = nil
        audioMetadata = nil
        playFrame = nil
        hud = Mania4KPlayHUD()
        judgementPresentation = nil
        gameplayFeedback = GameplayFeedback()
        phase = .setup
        inputSequenceNumber = 0
        keyboardRouter.reset()
        resetLiveInputLaneStates()
        pendingInputs.removeAll()
    }

    private func publishFrame(timing: Mania4KFrameTiming) {
        guard let metadata, let gameplay else {
            return
        }

        let session = gameplay.session
        let score = session.score()
        gameplayIsResolved = session.isResolved()
        playFrame = Mania4KPlayFrame(
            gameplayChartTimeMs: timing.gameplayChartTimeMs,
            renderChartTimeMs: timing.renderChartTimeMs,
            scrollTimeMs: scrollTimeMs,
            metadata: metadata,
            visibleObjects: timing.visibleObjects(of: session, scrollTimeMs: scrollTimeMs),
            score: score,
            laneStates: session.laneStates(),
            latestJudgement: gameplay.latestJudgement
        )

        // Views observe these two instead of `playFrame`, which changes every frame, so they redraw only when needed.
        let hud = Mania4KPlayHUD(
            title: metadata.title,
            accuracy: score.accuracy,
            combo: Int(score.combo),
            latestJudgement: gameplay.latestJudgement?.judgement
        )
        if hud != self.hud {
            self.hud = hud
        }
        let presentation = gameplayFeedback.judgementPresentation(chartTimeMs: timing.gameplayChartTimeMs)
        if presentation != judgementPresentation {
            judgementPresentation = presentation
        }
    }

    private func finishIfNeeded(chartTimeMs: Double) async {
        guard let metadata, let gameplay, gameplay.session.isResolved() else {
            return
        }

        let score = gameplay.session.score()

        let audioTimeMs = await activeAudioClock.currentAudioTimeMs()
        let running = await activeAudioClock.isRunning()
        let durationMs = audioMetadata?.durationMs ?? metadata.durationMs
        let audioHasEnded = durationMs.map { audioTimeMs >= $0 - 1 } ?? !running

        guard audioHasEnded else {
            return
        }

        await activeAudioClock.stop()
        resetLiveInputLaneStates()
        phase = .finished(
            Mania4KPlayResult(
                metadata: metadata,
                score: score,
                finishedChartTimeMs: chartTimeMs
            )
        )
    }

    private func fail(_ failure: Mania4KPlayFailure) async {
        phase = .failed(failure)
        await releaseAfterFailure()
    }

    private func releaseAfterFailure() async {
        let failureGeneration = playStateGeneration
        await activeAudioClock.stop()
        guard playStateGeneration == failureGeneration else {
            return
        }
        resetLiveInputLaneStates()
    }

    private func currentFrameTiming() async -> Mania4KFrameTiming {
        let audioTimeMs = await activeAudioClock.currentAudioTimeMs()
        return frameTiming(audioTimeMs: audioTimeMs)
    }

    private func frameTiming(audioTimeMs: Double) -> Mania4KFrameTiming {
        let gameplayChartTimeMs = audioTimeMs + audioOffsetMilliseconds
        return frameTiming(gameplayChartTimeMs: gameplayChartTimeMs)
    }

    private func frameTiming(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) -> Mania4KFrameTiming {
        Mania4KFrameTiming(
            clock: activeAudioClock,
            audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds,
            atHostTimeMs: hostTimeMs,
            presentationHostTimeMs: presentationHostTimeMs
        )
    }

    private func gameplayChartTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        activeAudioClock.audioTimeMs(atHostTimeMs: hostTimeMs) + audioOffsetMilliseconds
    }

    private func frameTiming(gameplayChartTimeMs: Double) -> Mania4KFrameTiming {
        Mania4KFrameTiming(
            gameplayChartTimeMs: gameplayChartTimeMs,
            renderChartTimeMs: gameplayChartTimeMs + visualOffsetMilliseconds
        )
    }

    private func isCurrentStart(_ generation: UInt64) -> Bool {
        playStateGeneration == generation && phase == .loading
    }

    private func abandonStaleStartIfNeeded(_ generation: UInt64) async -> Bool {
        guard !isCurrentStart(generation) else {
            return false
        }

        if phase == .setup {
            await activeAudioClock.stop()
        }
        return true
    }

    private func commitLiveInputState(_ input: Mania4KInputEvent) {
        var pressedLanes = Set(liveInputLaneStates.filter(\.isPressed).map(\.lane))
        let lane = input.lane.index

        switch input.phase {
        case .press:
            pressedLanes.insert(lane)
        case .release:
            pressedLanes.remove(lane)
        }

        liveInputLaneStates = Self.makeLaneStates(pressedLanes: pressedLanes)
    }

    private func resetLiveInputLaneStates() {
        liveInputLaneStates = Self.makeLaneStates(pressedLanes: [])
    }

    private static func makeLaneStates(pressedLanes: Set<UInt32>) -> [LaneState] {
        Mania4KLane.allCases.map { lane in
            LaneState(lane: lane.index, isPressed: pressedLanes.contains(lane.index), holdingObject: nil)
        }
    }
}

private extension String {
    var trimmedNilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
