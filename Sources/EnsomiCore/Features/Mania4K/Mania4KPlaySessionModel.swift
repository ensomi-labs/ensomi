import EnsomiRuntime
import Foundation
import Observation

public typealias InferenceEndpointClientFactory = @Sendable (InferenceEndpointConfiguration) -> any InferenceEndpointClient

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

    func streamReadThroughChartTimeMs(scrollTimeMs: Double) -> Double {
        max(gameplayChartTimeMs, renderChartTimeMs) + scrollTimeMs + 250
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
    public private(set) var backendSessionID: String?
    public private(set) var backendSessionStatus: String
    public private(set) var backendReceivedTokenCount: Int
    public private(set) var backendReadyWindowMS: Double
    public private(set) var backendLastTokenDescription: String?
    public private(set) var backendIsMock: Bool
    public private(set) var backendReferenceTimeMS: Double?

    @ObservationIgnored private let settings: UserDefaults?
    @ObservationIgnored private var reportedOutputDevice: Mania4KAudioOutputDevice?
    private let inferenceEndpointClientFactory: InferenceEndpointClientFactory
    private let defaultAudioClock: any Mania4KAudioClock
    @ObservationIgnored private var activeAudioClock: any Mania4KAudioClock
    @ObservationIgnored private var activeStream: (any Mania4KHitObjectStreaming)?
    @ObservationIgnored private var backendClient: (any InferenceEndpointClient)?
    @ObservationIgnored private var backendReceiveTask: Task<Void, Never>?
    @ObservationIgnored private var gameplay: Mania4KGameplay?
    @ObservationIgnored private var metadata: Mania4KChartMetadata?
    @ObservationIgnored private var audioMetadata: Mania4KAudioMetadata?
    @ObservationIgnored private var streamCursor: Mania4KHitObjectStreamCursor?
    @ObservationIgnored private var streamEnded: Bool
    private let streamReadGate: AsyncGate
    @ObservationIgnored private var inputSequenceNumber: UInt64
    @ObservationIgnored private var keyboardRouter: Mania4KKeyboardInputRouter
    @ObservationIgnored private var streamRefillTask: Task<Void, Never>?
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
        audioClock: any Mania4KAudioClock = AVFoundationMania4KAudioClock(),
        inferenceEndpointClientFactory: @escaping InferenceEndpointClientFactory = {
            InferenceEndpointWebSocketClient(configuration: $0)
        }
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
        self.inferenceEndpointClientFactory = inferenceEndpointClientFactory
        self.phase = .setup
        self.hud = Mania4KPlayHUD()
        self.gameplayFeedback = GameplayFeedback()
        self.liveInputLaneStates = Self.makeLaneStates(pressedLanes: [])
        self.backendSessionID = nil
        self.backendSessionStatus = "Idle"
        self.backendReceivedTokenCount = 0
        self.backendReadyWindowMS = 0
        self.backendLastTokenDescription = nil
        self.backendIsMock = false
        self.backendReferenceTimeMS = nil
        self.streamEnded = false
        self.streamReadGate = AsyncGate()
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

    @discardableResult
    public func startGeneratedBackendPlay(
        audioFileURL: URL,
        isMock: Bool,
        referenceTimeMS: Double = 0,
        durationMS: Double? = nil,
        title: String? = nil
    ) async -> Bool {
        let referenceTimeProvider = FixedInferenceReferenceTimeProvider(timeMS: referenceTimeMS)
        return await startGeneratedBackendPlay(
            audioFileURL: audioFileURL,
            isMock: isMock,
            referenceTimeMS: referenceTimeMS,
            durationMS: durationMS,
            title: title,
            musicSource: .systemAudio,
            playbackClock: defaultAudioClock,
            sourceDescription: "Inference endpoint",
            chartSourcePrefix: "Generated",
            referenceTimeProvider: {
                await referenceTimeProvider.value()
            }
        )
    }

    @discardableResult
    public func startAmbientGeneratedBackendPlay(
        audioFileURL: URL,
        isMock: Bool,
        referenceTimeMS: Double,
        anchorHostTimeMS: Double,
        durationMS: Double? = nil,
        title: String? = nil,
        musicSource: MusicSource = .background
    ) async -> Bool {
        let sourceTitle = title?.trimmedNilIfEmpty ?? audioFileURL.deletingPathExtension().lastPathComponent
        let ambientClock = HostTimeAnchoredMania4KAudioClock(
            referenceTimeAtAnchorMS: referenceTimeMS,
            durationMS: durationMS,
            title: sourceTitle,
            anchorHostTimeMS: anchorHostTimeMS
        )

        return await startGeneratedBackendPlay(
            audioFileURL: audioFileURL,
            isMock: isMock,
            referenceTimeMS: referenceTimeMS,
            durationMS: durationMS,
            title: sourceTitle,
            musicSource: musicSource,
            playbackClock: ambientClock,
            sourceDescription: "Ambient inference endpoint",
            chartSourcePrefix: "Ambient",
            referenceTimeProvider: {
                await ambientClock.currentAudioTimeMs()
            }
        )
    }

    /// Play a complete generated chart with its audio from time 0.
    @discardableResult
    public func startGeneratedChartPlay(
        audioFileURL: URL,
        chart: Mania4KGeneratedChart,
        title: String? = nil,
        artist: String? = nil
    ) async -> Bool {
        let title = title?.trimmedNilIfEmpty ?? audioFileURL.deletingPathExtension().lastPathComponent
        let metadata = Mania4KChartMetadata(
            title: title, artist: artist, sourceDescription: "Ensomi R2 generator",
            objectCount: chart.objectCount, durationMs: chart.durationMs)
        return await startPlay(
            stream: InMemoryMania4KHitObjectStream(objects: chart.objects, metadata: metadata),
            audioFileURL: audioFileURL,
            chartSource: .generated(displayName: "Generated: \(title)")
        )
    }

    /// Plays `stream` with its audio from time 0 on the local-file clock.
    @discardableResult
    func startPlay(
        stream: any Mania4KHitObjectStreaming,
        audioFileURL: URL,
        chartSource: Mania4KChartSource
    ) async -> Bool {
        resetPreparedPlayState()
        self.audioFileURL = audioFileURL
        let generation = playStateGeneration
        let configuration = Mania4KPlayConfiguration(
            chartSource: chartSource,
            audioFileURL: audioFileURL, starDifficulty: starDifficulty,
            scrollSpeed: scrollSpeed, audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds, judgeDifficulty: judgeDifficulty,
            keyBindings: keyBindings)
        return await startPreparedPlay(configuration: configuration, stream: stream, startGeneration: generation)
    }

    /// Start at the recognized reference clock; the local reference is never played aloud.
    @discardableResult
    public func startAmbientControlledPlay(
        audioFileURL: URL, referenceTimeMS: Double, anchorHostTimeMS: Double,
        title: String? = nil, controls: GenerationControls = .init(),
        endpointURL: URL = ControlledGenerationClient.defaultURL
    ) async -> Bool {
        resetPreparedPlayState()
        self.audioFileURL = audioFileURL
        let generation = playStateGeneration
        let title = title ?? audioFileURL.deletingPathExtension().lastPathComponent
        // The producer's decoded duration owns EOS; a rounded library duration must not freeze the clock early.
        let clock = HostTimeAnchoredMania4KAudioClock(referenceTimeAtAnchorMS: referenceTimeMS,
            title: title, anchorHostTimeMS: anchorHostTimeMS)
        let stream = ControlledGenerationStream(client: ControlledGenerationClient(baseURL: endpointURL),
            audioURL: audioFileURL, title: title, controls: controls,
            referenceTime: { await clock.currentAudioTimeMs() })
        let configuration = Mania4KPlayConfiguration(chartSource: .generated(displayName: title),
            audioFileURL: audioFileURL, starDifficulty: controls.difficulty ?? starDifficulty,
            scrollSpeed: scrollSpeed, audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds, judgeDifficulty: judgeDifficulty,
            keyBindings: keyBindings)
        return await startPreparedPlay(configuration: configuration, stream: stream,
            startGeneration: generation, playbackClock: clock)
    }

    private func startGeneratedBackendPlay(
        audioFileURL: URL,
        isMock: Bool,
        referenceTimeMS: Double,
        durationMS: Double?,
        title: String?,
        musicSource: MusicSource,
        playbackClock: any Mania4KAudioClock,
        sourceDescription: String,
        chartSourcePrefix: String,
        referenceTimeProvider: @escaping BufferedInferenceMania4KHitObjectStream.ReferenceTimeProvider
    ) async -> Bool {
        self.audioFileURL = audioFileURL
        resetPreparedPlayState()
        self.audioFileURL = audioFileURL

        let startGeneration = playStateGeneration
        let sessionID = UUID().uuidString
        let sourceTitle = title?.trimmedNilIfEmpty ?? audioFileURL.deletingPathExtension().lastPathComponent
        let endpointConfiguration = InferenceEndpointConfiguration(
            difficulty: starDifficulty,
            isMock: isMock
        )
        let endpointClient = inferenceEndpointClientFactory(endpointConfiguration)
        let generatedStream = BufferedInferenceMania4KHitObjectStream(
            metadata: Mania4KChartMetadata(
                title: sourceTitle,
                sourceDescription: sourceDescription,
                durationMs: durationMS
            ),
            maximumAcceptedTimeMS: durationMS,
            referenceTimeProvider: referenceTimeProvider
        )

        backendClient = endpointClient
        backendSessionID = sessionID
        backendSessionStatus = "Connecting to inference backend"
        backendReceivedTokenCount = 0
        backendReadyWindowMS = 0
        backendLastTokenDescription = nil
        backendIsMock = isMock
        backendReferenceTimeMS = referenceTimeMS
        startBackendReceiveTask(
            client: endpointClient,
            stream: generatedStream,
            sessionID: sessionID,
            generation: startGeneration
        )

        do {
            try await endpointClient.prepare()
            guard playStateGeneration == startGeneration, backendSessionID == sessionID else {
                return false
            }

            backendSessionStatus = "Publishing audio path"
            try await endpointClient.sendAudioPath(
                audioFileURL.path,
                sessionID: sessionID,
                musicSource: musicSource
            )
            guard playStateGeneration == startGeneration, backendSessionID == sessionID else {
                return false
            }

            backendSessionStatus = "Publishing reference time"
            try await endpointClient.sendReferenceTime(
                sessionID: sessionID,
                refTimeMS: referenceTimeMS,
                localHostTimeSendMS: EnsomiHostClock.currentTimeMS()
            )
            guard playStateGeneration == startGeneration, backendSessionID == sessionID else {
                return false
            }

            backendSessionStatus = "Waiting for generated beatmap"
        } catch {
            guard playStateGeneration == startGeneration, backendSessionID == sessionID else {
                return false
            }
            await fail(.streamFailed("Inference backend setup failed: \(error.localizedDescription)"))
            return false
        }

        let configuration = Mania4KPlayConfiguration(
            chartSource: .generated(displayName: "\(chartSourcePrefix): \(sourceTitle)"),
            audioFileURL: audioFileURL,
            starDifficulty: starDifficulty,
            scrollSpeed: scrollSpeed,
            audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds,
            judgeDifficulty: judgeDifficulty,
            keyBindings: keyBindings
        )
        return await startPreparedPlay(
            configuration: configuration,
            stream: generatedStream,
            startGeneration: startGeneration,
            playbackClock: playbackClock
        )
    }

    private func startPreparedPlay(
        configuration: Mania4KPlayConfiguration,
        stream: any Mania4KHitObjectStreaming,
        startGeneration: UInt64,
        playbackClock: (any Mania4KAudioClock)? = nil
    ) async -> Bool {
        activeConfiguration = configuration
        phase = .loading
        activeAudioClock = playbackClock ?? defaultAudioClock

        activeStream = stream
        let gameplay = Mania4KGameplay.streaming(judge: judgeDifficulty.judge, feedback: gameplayFeedback)
        self.gameplay = gameplay

        let preparedMetadata: Mania4KChartMetadata
        do {
            preparedMetadata = try await stream.prepare()
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
        } catch let validationError as Mania4KChartValidationError {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(.chartPrepareFailed(validationError))
            return false
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
            await fail(.streamFailed(error.localizedDescription))
            return false
        }

        let preparedAudioMetadata: Mania4KAudioMetadata
        do {
            // The game keeps this level: the volume does not change while it is in progress.
            await activeAudioClock.setMusicVolume(musicVolume)
            preparedAudioMetadata = try await activeAudioClock.prepare(audioFileURL: configuration.audioFileURL)
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
            metadata = preparedMetadata
            audioMetadata = preparedAudioMetadata
            streamCursor = nil
            streamEnded = false
            keyboardRouter.reset()
            resetLiveInputLaneStates()

            let initialTiming = try await prepareInitialStreamCoverage(expectedGeneration: startGeneration)
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
        } catch let validationError as Mania4KChartValidationError {
            guard isCurrentStart(startGeneration) else {
                return false
            }
            await fail(.engineRejectedObjects(validationError))
            return false
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
            await fail(.streamFailed(error.localizedDescription))
            return false
        }
    }

    private func prepareInitialStreamCoverage(expectedGeneration: UInt64) async throws -> Mania4KFrameTiming {
        var timing = await currentFrameTiming()
        try validatePlayStateGeneration(expectedGeneration)
        try await readStream(
            throughChartTimeMs: timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs),
            expectedGeneration: expectedGeneration
        )
        try validatePlayStateGeneration(expectedGeneration)

        let checkedTiming = await currentFrameTiming()
        if checkedTiming.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs) > timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs) {
            timing = checkedTiming
            try await readStream(
                throughChartTimeMs: timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs),
                expectedGeneration: expectedGeneration
            )
            try validatePlayStateGeneration(expectedGeneration)
        }

        guard timing.gameplayChartTimeMs <= streamCoverageMs else {
            throw Mania4KPlayFailure.streamFailed("The chart stream is not safe through the initial chart time.")
        }

        return timing
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

    /// One game frame that waits for the stream to cover it: reads ahead, delivers the inputs stamped up to this
    /// frame in timestamp order, then advances judgement to the chart time of `hostTimeMs`. The frame is drawn for
    /// `presentationHostTimeMs`, when the display shows it.
    @discardableResult
    public func tick(
        atHostTimeMs hostTimeMs: Double = EnsomiHostClock.currentTimeMS(),
        presentationHostTimeMs: Double? = nil
    ) async -> Bool {
        guard phase == .playing else {
            return false
        }

        let presentationHostTimeMs = presentationHostTimeMs ?? hostTimeMs
        let timing = frameTiming(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs)
        let streamReadThrough = max(
            timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs),
            pendingInputs.map(\.chartTimeMs).max() ?? -.infinity
        )

        do {
            try await readStream(throughChartTimeMs: streamReadThrough)
            guard phase == .playing else {
                return false
            }

            let judgedTiming = try judgeFrame(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs)
            await finishIfNeeded(chartTimeMs: judgedTiming.gameplayChartTimeMs)
            return true
        } catch {
            await fail(Self.playFailure(for: error))
            return false
        }
    }

    /// One game frame for the display link, without suspending between delivering inputs and advancing the session.
    /// The stream is read ahead in the background, so a stream that has not covered the frame fails the session.
    @discardableResult
    public func advanceFrame(atHostTimeMs hostTimeMs: Double, presentationHostTimeMs: Double) -> Bool {
        guard phase == .playing else {
            return false
        }

        do {
            let timing = try judgeFrame(atHostTimeMs: hostTimeMs, presentationHostTimeMs: presentationHostTimeMs)
            refillStreamIfNeeded(for: timing)
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

    private func refillStreamIfNeeded(for timing: Mania4KFrameTiming) {
        let through = timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs)
        guard streamRefillTask == nil, !streamEnded, through > streamCoverageMs + 250 else {
            return
        }

        let generation = playStateGeneration
        streamRefillTask = Task { [weak self] in
            do {
                try await self?.readStream(throughChartTimeMs: through, expectedGeneration: generation)
            } catch is StaleMania4KPlayStateError {
            } catch {
                await self?.failIfCurrent(generation, Self.playFailure(for: error))
            }
            if self?.playStateGeneration == generation {
                self?.streamRefillTask = nil
            }
        }
    }

    private func finishIfResolved(atChartTimeMs chartTimeMs: Double) {
        guard streamEnded, gameplayIsResolved, !isCheckingForFinish else {
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

    private func failIfCurrent(_ generation: UInt64, _ failure: Mania4KPlayFailure) async {
        guard playStateGeneration == generation, phase == .playing else {
            return
        }

        await fail(failure)
    }

    private static func playFailure(for error: Error) -> Mania4KPlayFailure {
        switch error {
        case let validationError as Mania4KChartValidationError:
            return .engineRejectedObjects(validationError)
        case let failure as Mania4KPlayFailure:
            return failure
        default:
            return .streamFailed(error.localizedDescription)
        }
    }

    private func resetPreparedPlayState() {
        playStateGeneration &+= 1
        streamRefillTask?.cancel()
        streamRefillTask = nil
        isCheckingForFinish = false
        gameplayIsResolved = false
        stopBackendSession(markStopped: false)
        activeConfiguration = nil
        if let previousStream = activeStream { Task { await previousStream.cancel() } }
        activeStream = nil
        gameplay = nil
        metadata = nil
        audioMetadata = nil
        playFrame = nil
        hud = Mania4KPlayHUD()
        judgementPresentation = nil
        gameplayFeedback = GameplayFeedback()
        phase = .setup
        streamCursor = nil
        streamEnded = false
        inputSequenceNumber = 0
        keyboardRouter.reset()
        resetLiveInputLaneStates()
        pendingInputs.removeAll()
    }

    private func startBackendReceiveTask(
        client: any InferenceEndpointClient,
        stream: BufferedInferenceMania4KHitObjectStream,
        sessionID: String,
        generation: UInt64
    ) {
        backendReceiveTask?.cancel()
        backendReceiveTask = Task { @MainActor [weak self] in
            guard let self else {
                return
            }

            do {
                while !Task.isCancelled {
                    let event = try await client.nextEvent()
                    await self.applyBackendEvent(
                        event,
                        stream: stream,
                        expectedSessionID: sessionID,
                        generation: generation
                    )
                }
            } catch is CancellationError {
                return
            } catch {
                guard self.playStateGeneration == generation,
                      self.backendSessionID == sessionID
                else {
                    return
                }
                self.backendSessionStatus = "Backend receive failed: \(error.localizedDescription)"
                if self.phase == .loading || self.phase == .playing || self.phase == .paused {
                    await self.fail(.streamFailed(error.localizedDescription))
                }
            }
        }
    }

    private func applyBackendEvent(
        _ event: InferenceEndpointEvent,
        stream: BufferedInferenceMania4KHitObjectStream,
        expectedSessionID: String,
        generation: UInt64
    ) async {
        guard playStateGeneration == generation,
              backendSessionID == expectedSessionID
        else {
            return
        }

        switch event {
        case .hitObjectToken(let token):
            guard token.sessionID == expectedSessionID else {
                return
            }

            guard await stream.append(contentsOf: token.objects) else {
                return
            }

            backendReceivedTokenCount += 1
            let readiness = await stream.currentRenderReadiness()
            backendReadyWindowMS = readiness?.bufferedDurationAfterFirstObjectMS ?? 0
            backendLastTokenDescription = "token_id \(token.tokenID) -> \(token.objects.count) objects @ \(Int(token.timeMS.rounded())) ms"

            if readiness?.isReady == true {
                backendSessionStatus = "Generated beatmap ready"
            } else {
                backendSessionStatus = "Buffering generated beatmap"
            }
        case .endOfStream(let end):
            guard end.sessionID == expectedSessionID else {
                return
            }

            await stream.finish(completeThroughTimeMS: end.completeThroughMS)
            backendReadyWindowMS = end.completeThroughMS
            backendLastTokenDescription = "end_of_stream @ \(Int(end.completeThroughMS.rounded())) ms"
            backendSessionStatus = "Generated beatmap complete"
        }
    }

    private func stopBackendSession(markStopped: Bool) {
        let sessionID = backendSessionID
        let client = backendClient

        backendSessionID = nil
        backendClient = nil
        backendReceiveTask?.cancel()
        backendReceiveTask = nil
        backendReceivedTokenCount = 0
        backendReadyWindowMS = 0
        backendLastTokenDescription = nil
        backendReferenceTimeMS = nil

        if let sessionID, let client {
            Task {
                try? await client.stop(sessionID: sessionID)
            }
        }

        backendSessionStatus = markStopped ? "Stopped" : "Idle"
    }

    private func readStream(throughChartTimeMs: Double, expectedGeneration: UInt64? = nil) async throws {
        try validatePlayStateGeneration(expectedGeneration)
        guard !streamEnded, throughChartTimeMs > streamCoverageMs else {
            return
        }

        while !streamReadGate.tryEnter() {
            try await streamReadGate.wait()
            try validatePlayStateGeneration(expectedGeneration)
            guard !streamEnded, throughChartTimeMs > streamCoverageMs else {
                return
            }
        }

        do {
            try await readStreamUnlocked(throughChartTimeMs: throughChartTimeMs, expectedGeneration: expectedGeneration)
            streamReadGate.leave()
        } catch {
            streamReadGate.leave(throwing: error)
            throw error
        }
    }

    private func readStreamUnlocked(throughChartTimeMs: Double, expectedGeneration: UInt64?) async throws {
        try validatePlayStateGeneration(expectedGeneration)
        guard let activeStream else {
            return
        }

        var didReachLimit = true
        while didReachLimit && !streamEnded {
            let batch = try await activeStream.read(
                after: streamCursor,
                throughChartTimeMs: throughChartTimeMs,
                limit: 512
            )
            try validatePlayStateGeneration(expectedGeneration)
            guard let gameplay else {
                return
            }

            try gameplay.append(
                tokens: batch.objects.map(\.token),
                coverageMs: batch.completeThroughChartTimeMs,
                endOfStream: batch.isEndOfStream
            )
            streamCursor = batch.nextCursor
            streamEnded = batch.isEndOfStream

            didReachLimit = batch.objects.count >= 512
        }
    }

    /// How far the chart stream is complete; nothing before the first page, infinite after the last.
    private var streamCoverageMs: Double {
        gameplay?.session.coverageMs() ?? -.infinity
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
        guard streamEnded, let metadata, let gameplay, gameplay.session.isResolved() else {
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
        if let activeStream { Task { await activeStream.cancel() } }
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
        if let activeStream { Task { await activeStream.cancel() } }
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

    private func validatePlayStateGeneration(_ expectedGeneration: UInt64?) throws {
        guard let expectedGeneration else {
            return
        }

        guard playStateGeneration == expectedGeneration else {
            throw StaleMania4KPlayStateError()
        }
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

private actor FixedInferenceReferenceTimeProvider {
    private let timeMS: Double

    init(timeMS: Double) {
        self.timeMS = timeMS
    }

    func value() -> Double {
        timeMS
    }
}

@MainActor
private final class AsyncGate {
    private var isEntered = false
    private var waiters: [CheckedContinuation<Void, any Error>] = []

    func tryEnter() -> Bool {
        guard !isEntered else {
            return false
        }

        isEntered = true
        return true
    }

    func wait() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            waiters.append(continuation)
        }
    }

    func leave(throwing error: (any Error)? = nil) {
        isEntered = false

        let waiters = waiters
        self.waiters.removeAll()

        if let error {
            waiters.forEach { $0.resume(throwing: error) }
        } else {
            waiters.forEach { $0.resume() }
        }
    }
}

private struct StaleMania4KPlayStateError: Error {}

private extension String {
    var trimmedNilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
