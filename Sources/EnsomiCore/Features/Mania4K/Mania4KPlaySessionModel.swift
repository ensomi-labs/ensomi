import Foundation
import Observation

public typealias Mania4KHitObjectStreamFactory = @Sendable (URL) -> any Mania4KHitObjectStreaming
public typealias InferenceEndpointClientFactory = @Sendable (InferenceEndpointConfiguration) -> any InferenceEndpointClient

private struct Mania4KFrameTiming {
    let gameplayChartTimeMs: Double
    let renderChartTimeMs: Double

    func streamReadThroughChartTimeMs(scrollTimeMs: Double) -> Double {
        max(gameplayChartTimeMs, renderChartTimeMs) + scrollTimeMs + 250
    }
}

@MainActor
@Observable
public final class Mania4KPlaySessionModel {
    public var beatmapFileURL: URL?
    public var audioFileURL: URL?
    public var starDifficulty: Double
    public var scrollSpeed: Double
    public var audioOffsetMilliseconds: Double
    public var visualOffsetMilliseconds: Double
    public var judgeDifficulty: Mania4KJudgeDifficulty
    public var keyBindings: Mania4KKeyBindingSet

    public private(set) var beatmapSelectionErrorMessage: String?
    public private(set) var audioSelectionErrorMessage: String?
    public private(set) var keyBindingErrorMessage: String?
    public private(set) var activeConfiguration: Mania4KPlayConfiguration?
    public private(set) var phase: Mania4KPlayPhase
    public private(set) var playFrame: Mania4KPlayFrame?
    public private(set) var gameplayFeedback: Mania4KGameplayFeedbackState
    public private(set) var liveInputLaneStates: [Mania4KLaneState]
    public private(set) var backendSessionID: String?
    public private(set) var backendSessionStatus: String
    public private(set) var backendReceivedTokenCount: Int
    public private(set) var backendReadyWindowMS: Double
    public private(set) var backendLastTokenDescription: String?
    public private(set) var backendIsMock: Bool
    public private(set) var backendReferenceTimeMS: Double?

    private let streamFactory: Mania4KHitObjectStreamFactory
    private let inferenceEndpointClientFactory: InferenceEndpointClientFactory
    private let defaultAudioClock: any Mania4KAudioClock
    @ObservationIgnored private var activeAudioClock: any Mania4KAudioClock
    @ObservationIgnored private var activeStream: (any Mania4KHitObjectStreaming)?
    @ObservationIgnored private var backendClient: (any InferenceEndpointClient)?
    @ObservationIgnored private var backendReceiveTask: Task<Void, Never>?
    @ObservationIgnored private var engine: Mania4KJudgementEngine?
    @ObservationIgnored private var metadata: Mania4KChartMetadata?
    @ObservationIgnored private var audioMetadata: Mania4KAudioMetadata?
    @ObservationIgnored private var streamCursor: Mania4KHitObjectStreamCursor?
    @ObservationIgnored private var streamCompleteThroughChartTimeMs: Double
    @ObservationIgnored private var streamEnded: Bool
    private let streamReadGate: AsyncGate
    @ObservationIgnored private var inputSequenceNumber: UInt64
    @ObservationIgnored private var keyboardRouter: Mania4KKeyboardInputRouter
    @ObservationIgnored private var frameLoopTask: Task<Void, Never>?
    @ObservationIgnored private var playStateGeneration: UInt64
    @ObservationIgnored private var pendingInputs: [Mania4KInputEvent]

    public init(
        beatmapFileURL: URL? = nil,
        audioFileURL: URL? = nil,
        starDifficulty: Double = 4.0,
        scrollSpeed: Double = Mania4KDefaultPlaySettings.scrollSpeed,
        audioOffsetMilliseconds: Double = Double(Mania4KDefaultPlaySettings.audioOffsetMilliseconds),
        visualOffsetMilliseconds: Double = Double(Mania4KDefaultPlaySettings.visualOffsetMilliseconds),
        judgeDifficulty: Mania4KJudgeDifficulty = .c,
        keyBindings: Mania4KKeyBindingSet = .default,
        audioClock: any Mania4KAudioClock = AVFoundationMania4KAudioClock(),
        streamFactory: @escaping Mania4KHitObjectStreamFactory = { OsuMania4KBeatmapStream(beatmapFileURL: $0) },
        inferenceEndpointClientFactory: @escaping InferenceEndpointClientFactory = {
            InferenceEndpointWebSocketClient(configuration: $0)
        }
    ) {
        self.beatmapFileURL = beatmapFileURL
        self.audioFileURL = audioFileURL
        self.starDifficulty = starDifficulty
        self.scrollSpeed = scrollSpeed
        self.audioOffsetMilliseconds = audioOffsetMilliseconds
        self.visualOffsetMilliseconds = visualOffsetMilliseconds
        self.judgeDifficulty = judgeDifficulty
        self.keyBindings = keyBindings
        self.defaultAudioClock = audioClock
        self.activeAudioClock = audioClock
        self.streamFactory = streamFactory
        self.inferenceEndpointClientFactory = inferenceEndpointClientFactory
        self.phase = .setup
        self.gameplayFeedback = Mania4KGameplayFeedbackState()
        self.liveInputLaneStates = Self.makeLaneStates(pressedLanes: [])
        self.backendSessionID = nil
        self.backendSessionStatus = "Idle"
        self.backendReceivedTokenCount = 0
        self.backendReadyWindowMS = 0
        self.backendLastTokenDescription = nil
        self.backendIsMock = false
        self.backendReferenceTimeMS = nil
        self.streamCompleteThroughChartTimeMs = 0
        self.streamEnded = false
        self.streamReadGate = AsyncGate()
        self.inputSequenceNumber = 0
        self.keyboardRouter = Mania4KKeyboardInputRouter(keyBindings: keyBindings)
        self.playStateGeneration = 0
        self.pendingInputs = []
    }

    public var isReadyToStart: Bool {
        beatmapFileURL != nil && audioFileURL != nil && phase != .loading
    }

    public var beatmapFileName: String {
        beatmapFileURL?.lastPathComponent ?? "No .osu file selected"
    }

    public var audioFileName: String {
        audioFileURL?.lastPathComponent ?? "No audio file selected"
    }

    public var scrollTimeMs: Double {
        11_485 / min(max(scrollSpeed, 1), 40)
    }

    public func selectBeatmapFile(_ url: URL) {
        guard url.hasOsuBeatmapExtension else {
            beatmapSelectionErrorMessage = "Choose a .osu beatmap file."
            return
        }

        beatmapFileURL = url
        beatmapSelectionErrorMessage = nil
        resetPreparedPlayState()
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
        beatmapFileURL = nil
        audioFileURL = nil
        beatmapSelectionErrorMessage = nil
        audioSelectionErrorMessage = nil
    }

    public func recordBeatmapImportFailure(_ error: Error) {
        beatmapSelectionErrorMessage = "Could not choose beatmap: \(error.localizedDescription)"
    }

    public func recordAudioImportFailure(_ error: Error) {
        audioSelectionErrorMessage = "Could not choose audio: \(error.localizedDescription)"
    }

    @discardableResult
    public func startPlay() async -> Bool {
        guard let beatmapFileURL, let audioFileURL else {
            return false
        }

        resetPreparedPlayState()
        let startGeneration = playStateGeneration
        let configuration = Mania4KPlayConfiguration(
            beatmapFileURL: beatmapFileURL,
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
            stream: streamFactory(beatmapFileURL),
            startGeneration: startGeneration
        )
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

    /// Play a complete generated chart with its audio from time 0, like an imported beatmap.
    @discardableResult
    public func startGeneratedChartPlay(
        audioFileURL: URL,
        chart: Mania4KGeneratedChart,
        title: String? = nil,
        artist: String? = nil
    ) async -> Bool {
        resetPreparedPlayState()
        self.audioFileURL = audioFileURL
        let generation = playStateGeneration
        let title = title?.trimmedNilIfEmpty ?? audioFileURL.deletingPathExtension().lastPathComponent
        let configuration = Mania4KPlayConfiguration(
            chartSource: .generated(displayName: "Generated: \(title)"),
            audioFileURL: audioFileURL, starDifficulty: starDifficulty,
            scrollSpeed: scrollSpeed, audioOffsetMilliseconds: audioOffsetMilliseconds,
            visualOffsetMilliseconds: visualOffsetMilliseconds, judgeDifficulty: judgeDifficulty,
            keyBindings: keyBindings)
        let metadata = Mania4KChartMetadata(
            title: title, artist: artist, sourceDescription: "Ensomi chart service",
            objectCount: chart.objectCount, durationMs: chart.durationMs)
        do {
            let stream = try InMemoryMania4KHitObjectStream(objects: chart.objects, metadata: metadata)
            return await startPreparedPlay(configuration: configuration, stream: stream, startGeneration: generation)
        } catch let error as Mania4KChartValidationError {
            await fail(.chartPrepareFailed(error))
            return false
        } catch {
            await fail(.streamFailed(error.localizedDescription))
            return false
        }
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
        var preparedEngine = Mania4KJudgementEngine(judgeDifficulty: judgeDifficulty)
        engine = preparedEngine

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
            streamCompleteThroughChartTimeMs = -.infinity
            streamCursor = nil
            streamEnded = false
            keyboardRouter.reset()
            resetLiveInputLaneStates()

            let initialTiming = try await prepareInitialStreamCoverage(expectedGeneration: startGeneration)
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }

            if let currentEngine = engine {
                preparedEngine = currentEngine
            }
            let initialUpdate = preparedEngine.advance(to: initialTiming.gameplayChartTimeMs)
            recordJudgementEvents(
                initialUpdate.judgementEvents,
                atChartTimeMs: initialTiming.gameplayChartTimeMs
            )
            engine = preparedEngine
            publishFrame(timing: initialTiming)

            try await activeAudioClock.play()
            if await abandonStaleStartIfNeeded(startGeneration) {
                return false
            }
            phase = .playing
            startFrameLoop()
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

        guard streamEnded || timing.gameplayChartTimeMs <= streamCompleteThroughChartTimeMs else {
            throw Mania4KPlayFailure.streamFailed("The chart stream is not safe through the initial chart time.")
        }

        return timing
    }

    public func pause() async {
        guard phase == .playing else {
            return
        }

        phase = .paused
        frameLoopTask?.cancel()
        frameLoopTask = nil
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
        startFrameLoop()
    }

    public func quitToSetup() async {
        frameLoopTask?.cancel()
        frameLoopTask = nil
        let previousClock = activeAudioClock
        resetPreparedPlayState()
        await previousClock.stop()
    }

    /// One game frame: reads the stream ahead, delivers the inputs stamped up to this frame in timestamp order,
    /// then advances judgement to the chart time of `hostTimeMs`.
    @discardableResult
    public func tick(atHostTimeMs hostTimeMs: Double = EnsomiHostClock.currentTimeMS()) async -> Bool {
        guard phase == .playing else {
            return false
        }

        let timing = frameTiming(atHostTimeMs: hostTimeMs)
        let streamReadThrough = max(
            timing.streamReadThroughChartTimeMs(scrollTimeMs: scrollTimeMs),
            pendingInputs.map(\.chartTimeMs).max() ?? -.infinity
        )

        do {
            try await readStream(throughChartTimeMs: streamReadThrough)
            guard phase == .playing else {
                return false
            }

            let advancedTiming = try advanceFrame(atHostTimeMs: hostTimeMs)
            await finishIfNeeded(chartTimeMs: advancedTiming.gameplayChartTimeMs)
            return true
        } catch let validationError as Mania4KChartValidationError {
            await fail(.engineRejectedObjects(validationError))
            return false
        } catch let failure as Mania4KPlayFailure {
            await fail(failure)
            return false
        } catch {
            await fail(.streamFailed(error.localizedDescription))
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
        gameplayFeedback.recordInput(input, atUITimeMs: uiTimeMs)
        commitLiveInputState(input)
        pendingInputs.append(input)
    }

    @discardableResult
    private func advanceFrame(atHostTimeMs hostTimeMs: Double) throws -> Mania4KFrameTiming {
        let timing = frameTiming(atHostTimeMs: hostTimeMs)
        guard var engine else {
            return timing
        }

        guard streamEnded || timing.gameplayChartTimeMs <= streamCompleteThroughChartTimeMs else {
            throw Mania4KPlayFailure.streamFailed("The chart stream fell behind the judgement clock.")
        }

        let due = pendingInputs
            .filter { $0.chartTimeMs <= timing.gameplayChartTimeMs }
            .sorted { ($0.chartTimeMs, $0.sequenceNumber) < ($1.chartTimeMs, $1.sequenceNumber) }
        pendingInputs.removeAll { $0.chartTimeMs <= timing.gameplayChartTimeMs }
        for input in due {
            let update = engine.handle(input)
            recordJudgementEvents(update.judgementEvents, atChartTimeMs: input.chartTimeMs)
        }

        let update = engine.advance(to: timing.gameplayChartTimeMs)
        recordJudgementEvents(update.judgementEvents, atChartTimeMs: timing.gameplayChartTimeMs)
        self.engine = engine
        publishFrame(timing: timing)
        return timing
    }

    private func resetPreparedPlayState() {
        playStateGeneration &+= 1
        frameLoopTask?.cancel()
        frameLoopTask = nil
        stopBackendSession(markStopped: false)
        activeConfiguration = nil
        if let previousStream = activeStream { Task { await previousStream.cancel() } }
        activeStream = nil
        engine = nil
        metadata = nil
        audioMetadata = nil
        playFrame = nil
        gameplayFeedback = Mania4KGameplayFeedbackState()
        phase = .setup
        streamCursor = nil
        streamCompleteThroughChartTimeMs = -.infinity
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

    private func startFrameLoop() {
        frameLoopTask?.cancel()
        frameLoopTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 16_666_667)
                } catch {
                    break
                }

                guard !Task.isCancelled else {
                    break
                }

                await self?.tick()
            }
        }
    }

    private func readStream(throughChartTimeMs: Double, expectedGeneration: UInt64? = nil) async throws {
        try validatePlayStateGeneration(expectedGeneration)
        guard !streamEnded, throughChartTimeMs > streamCompleteThroughChartTimeMs else {
            return
        }

        while !streamReadGate.tryEnter() {
            try await streamReadGate.wait()
            try validatePlayStateGeneration(expectedGeneration)
            guard !streamEnded, throughChartTimeMs > streamCompleteThroughChartTimeMs else {
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
            let previousWatermark = streamCompleteThroughChartTimeMs

            if previousWatermark.isFinite,
               let invalidObject = batch.objects.first(where: { $0.timeMs <= previousWatermark }) {
                throw Mania4KPlayFailure.streamFailed(
                    "The chart stream violated its watermark by emitting an object at \(invalidObject.timeMs) ms after declaring completion through \(previousWatermark) ms."
                )
            }

            if !batch.objects.isEmpty {
                guard var engine else {
                    throw Mania4KPlayFailure.streamFailed("Judgement engine is not prepared.")
                }
                try engine.ingest(batch.objects)
                self.engine = engine
            }

            streamCursor = batch.nextCursor
            streamCompleteThroughChartTimeMs = max(streamCompleteThroughChartTimeMs, batch.completeThroughChartTimeMs)
            streamEnded = batch.isEndOfStream
            if streamEnded, let engine {
                try engine.validateEndOfStream()
            }

            didReachLimit = batch.objects.count >= 512
        }
    }

    private func recordJudgementEvents(_ events: [Mania4KJudgementEvent], atChartTimeMs chartTimeMs: Double) {
        var updatedFeedback = gameplayFeedback
        updatedFeedback.recordJudgementEvents(events, atChartTimeMs: chartTimeMs)
        if updatedFeedback != gameplayFeedback {
            gameplayFeedback = updatedFeedback
        }
    }

    private func publishFrame(timing: Mania4KFrameTiming) {
        guard let metadata, let engine else {
            return
        }

        let snapshot = engine.snapshot(
            visibleRange: (timing.renderChartTimeMs - 700)...(timing.renderChartTimeMs + scrollTimeMs + 250)
        )
        playFrame = Mania4KPlayFrame(
            gameplayChartTimeMs: timing.gameplayChartTimeMs,
            renderChartTimeMs: timing.renderChartTimeMs,
            scrollTimeMs: scrollTimeMs,
            metadata: metadata,
            visibleObjects: snapshot.visibleObjects,
            score: snapshot.score,
            laneStates: snapshot.laneStates,
            latestJudgement: snapshot.latestJudgement
        )
    }

    private func finishIfNeeded(chartTimeMs: Double) async {
        guard streamEnded, let metadata, let engine else {
            return
        }

        let snapshot = engine.snapshot(visibleRange: chartTimeMs...chartTimeMs)
        guard snapshot.isResolved else {
            return
        }

        let audioTimeMs = await activeAudioClock.currentAudioTimeMs()
        let running = await activeAudioClock.isRunning()
        let durationMs = audioMetadata?.durationMs ?? metadata.durationMs
        let audioHasEnded = durationMs.map { audioTimeMs >= $0 - 1 } ?? !running

        guard audioHasEnded else {
            return
        }

        frameLoopTask?.cancel()
        frameLoopTask = nil
        await activeAudioClock.stop()
        resetLiveInputLaneStates()
        if let activeStream { Task { await activeStream.cancel() } }
        phase = .finished(
            Mania4KPlayResult(
                metadata: metadata,
                score: snapshot.score,
                finishedChartTimeMs: chartTimeMs
            )
        )
    }

    private func fail(_ failure: Mania4KPlayFailure) async {
        let failureGeneration = playStateGeneration
        frameLoopTask?.cancel()
        frameLoopTask = nil
        await activeAudioClock.stop()
        guard playStateGeneration == failureGeneration else {
            return
        }
        resetLiveInputLaneStates()
        if let activeStream { Task { await activeStream.cancel() } }
        phase = .failed(failure)
    }

    private func currentFrameTiming() async -> Mania4KFrameTiming {
        let audioTimeMs = await activeAudioClock.currentAudioTimeMs()
        return frameTiming(audioTimeMs: audioTimeMs)
    }

    private func frameTiming(audioTimeMs: Double) -> Mania4KFrameTiming {
        let gameplayChartTimeMs = audioTimeMs + audioOffsetMilliseconds
        return frameTiming(gameplayChartTimeMs: gameplayChartTimeMs)
    }

    private func frameTiming(atHostTimeMs hostTimeMs: Double) -> Mania4KFrameTiming {
        frameTiming(gameplayChartTimeMs: gameplayChartTimeMs(atHostTimeMs: hostTimeMs))
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

        switch input.phase {
        case .press:
            pressedLanes.insert(input.lane)
        case .release:
            pressedLanes.remove(input.lane)
        }

        liveInputLaneStates = Self.makeLaneStates(pressedLanes: pressedLanes)
    }

    private func resetLiveInputLaneStates() {
        liveInputLaneStates = Self.makeLaneStates(pressedLanes: [])
    }

    private static func makeLaneStates(pressedLanes: Set<Mania4KLane>) -> [Mania4KLaneState] {
        Mania4KLane.allCases.map { lane in
            Mania4KLaneState(lane: lane, isPressed: pressedLanes.contains(lane))
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

private extension URL {
    var hasOsuBeatmapExtension: Bool {
        pathExtension.localizedCaseInsensitiveCompare("osu") == .orderedSame
    }
}

private extension String {
    var trimmedNilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
