import AVFoundation
import Foundation
import os

/// A buffer the player node rendered: the audio time it starts at, the host time the device takes it, and the
/// output latency after that. Audio time at any other host time follows at rate 1.
struct Mania4KRenderAnchor: Equatable, Sendable {
    let renderedAudioTimeMs: Double
    let renderHostTimeMs: Double
    let outputLatencyMs: Double

    func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        renderedAudioTimeMs + hostTimeMs - renderHostTimeMs - outputLatencyMs
    }
}

/// Plays a local file through `AVAudioEngine`. The time is what is heard at a host time: the player node's sample
/// time at its last render, carried to the asked host time and shifted by the output's presentation latency.
///
/// When the output device changes, the engine stops and is built again for the new device. Playback resumes from
/// the position that was being heard, so the audio time carries on from there and the gap only delays the song.
public final class AVFoundationMania4KAudioClock: Mania4KAudioClock, @unchecked Sendable {
    private enum Source {
        case file(AVAudioFile)
        case clickTrack(Mania4KCalibrationClickTrack)

        var format: AVAudioFormat {
            switch self {
            case .file(let file): file.processingFormat
            case .clickTrack(let track): track.loop.format
            }
        }
    }

    private struct State {
        var engine: AVAudioEngine?
        var player: AVAudioPlayerNode?
        var source: Source?
        var configurationObserver: NSObjectProtocol?
        /// The audio time of the player's first frame: 0, or the position playback resumed from.
        var startAudioTimeMs = 0.0
        /// The last render the player reported, to carry on from while the engine is stopped.
        var anchor: Mania4KRenderAnchor?
        /// Set by `play()` and cleared by `pause()` and `stop()`; `isRunning` is also false while the engine restarts.
        var wantsRunning = false
        var isRunning = false
        var pausedAudioTimeMs = 0.0
        var outputLatencyMs = 0.0
        var volume: Float = 1
        var musicVolume: Float = 1

        var mainMixerVolume: Float {
            volume * musicVolume
        }
    }

    private let state = OSAllocatedUnfairLock(uncheckedState: State())

    public init() {}

    deinit {
        if let observer = state.withLockUnchecked({ $0.configurationObserver }) {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// The engine's output volume, which silent runs set to 0. It is applied at the main mixer: a zero volume on the
    /// player itself changes how the mixer pulls the player, and its node time then drifts against the device clock.
    public var volume: Float {
        get { state.withLockUnchecked { $0.volume } }
        set {
            state.withLockUnchecked {
                $0.volume = newValue
                $0.engine?.mainMixerNode.outputVolume = $0.mainMixerVolume
            }
        }
    }

    /// The song's level, from 0 to 1 of its own. It scales the main mixer's output together with `volume`, for the
    /// same reason, so a silent run stays silent whatever the music volume.
    public func setMusicVolume(_ musicVolume: Double) async {
        state.withLockUnchecked {
            $0.musicVolume = Float(musicVolume)
            $0.engine?.mainMixerNode.outputVolume = $0.mainMixerVolume
        }
    }

    /// The output latency the clock subtracts, read when it last started rendering.
    var outputLatencyMs: Double {
        state.withLockUnchecked { $0.outputLatencyMs }
    }

    public func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        let file = try AVAudioFile(forReading: audioFileURL)
        try prepareEngine(source: .file(file), atMs: 0, resumesPlaying: false)

        return Mania4KAudioMetadata(
            durationMs: Double(file.length) / file.processingFormat.sampleRate * 1000,
            title: audioFileURL.deletingPathExtension().lastPathComponent
        )
    }

    /// Prepares a click track in place of a file. Audio time 0 is its first frame, as it is a file's first frame.
    public func prepare(clickTrack: Mania4KCalibrationClickTrack) throws {
        try prepareEngine(source: .clickTrack(clickTrack), atMs: 0, resumesPlaying: false)
    }

    /// Replaces the engine with one for the current output device, ready to play `source` from `positionMs`.
    private func prepareEngine(source: Source, atMs positionMs: Double, resumesPlaying: Bool) throws {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        // The mixer runs at the output's rate. Connected straight to the main mixer, a 44.1 kHz file's player
        // timeline runs about 1.3 ms per second fast against the device clock.
        let mixer = AVAudioMixerNode()
        let outputFormat = engine.outputNode.outputFormat(forBus: 0)
        guard outputFormat.sampleRate > 0 else {
            throw Mania4KPlayFailure.audioPrepareFailed("There is no audio output device.")
        }

        engine.attach(player)
        engine.attach(mixer)
        engine.connect(player, to: mixer, format: source.format)
        engine.connect(mixer, to: engine.mainMixerNode, format: outputFormat)
        switch source {
        case .file(let file):
            let frame = AVAudioFramePosition((positionMs * file.processingFormat.sampleRate / 1000).rounded())
            if frame < file.length {
                player.scheduleSegment(file, startingFrame: frame, frameCount: AVAudioFrameCount(file.length - frame), at: nil, completionHandler: nil)
            }
        case .clickTrack(let track):
            track.resumed(atMs: positionMs).schedule(on: player)
        }
        engine.prepare()

        let observer = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: nil
        ) { [weak self] _ in
            self?.restartAfterConfigurationChange()
        }
        state.withLockUnchecked {
            $0.engine?.stop()
            if let previousObserver = $0.configurationObserver {
                NotificationCenter.default.removeObserver(previousObserver)
            }
            $0.configurationObserver = observer
            $0.engine = engine
            $0.player = player
            $0.source = source
            $0.startAudioTimeMs = positionMs
            $0.anchor = nil
            $0.wantsRunning = $0.wantsRunning && resumesPlaying
            $0.isRunning = false
            $0.pausedAudioTimeMs = positionMs
            engine.mainMixerNode.outputVolume = $0.mainMixerVolume
        }
    }

    /// The output device changed and the engine stopped. Builds it again for the new device, at the new output
    /// sample rate, and resumes from the position that was being heard; a paused clock stays paused there.
    func restartAfterConfigurationChange() {
        let positionMs = max(0, audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()))
        let (source, wasPlaying) = state.withLockUnchecked { ($0.source, $0.wantsRunning) }
        guard let source else {
            return
        }

        do {
            try prepareEngine(source: source, atMs: positionMs, resumesPlaying: true)
        } catch {
            return
        }

        if wasPlaying {
            Task { try? await play() }
        }
    }

    public func play() async throws {
        let (engine, player) = state.withLockUnchecked { ($0.engine, $0.player) }
        guard let engine, let player else {
            throw Mania4KPlayFailure.audioPrepareFailed("Audio player is not prepared.")
        }

        state.withLockUnchecked { $0.wantsRunning = true }
        let previousRenderSample = player.lastRenderTime?.sampleTime
        try engine.start()
        player.play()
        // Reads stay at the paused time until a buffer rendered after this call gives a node time to extrapolate from.
        for _ in 0..<500 {
            if let nodeTime = player.lastRenderTime,
               nodeTime.sampleTime != previousRenderSample,
               player.playerTime(forNodeTime: nodeTime) != nil {
                state.withLockUnchecked {
                    // A device change during the start replaced the engine; the restart plays the new one.
                    guard $0.player === player else {
                        return
                    }

                    $0.outputLatencyMs = engine.outputNode.presentationLatency * 1000
                    $0.isRunning = true
                }
                return
            }

            try await Task.sleep(nanoseconds: 2_000_000)
        }

        throw Mania4KPlayFailure.audioPrepareFailed("The audio engine did not start rendering.")
    }

    public func pause() async {
        let pausedAudioTimeMs = audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
        state.withLockUnchecked {
            $0.pausedAudioTimeMs = pausedAudioTimeMs
            $0.wantsRunning = false
            $0.isRunning = false
            $0.player?.pause()
        }
    }

    public func stop() async {
        state.withLockUnchecked {
            $0.player?.stop()
            $0.engine?.stop()
            $0.source = nil
            $0.anchor = nil
            $0.wantsRunning = false
            $0.isRunning = false
            $0.pausedAudioTimeMs = 0
        }
    }

    public func currentAudioTimeMs() async -> Double {
        audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
    }

    public func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        let (player, isRunning, pausedAudioTimeMs, startAudioTimeMs, outputLatencyMs, lastAnchor) = state.withLockUnchecked {
            ($0.player, $0.isRunning, $0.pausedAudioTimeMs, $0.startAudioTimeMs, $0.outputLatencyMs, $0.anchor)
        }
        guard isRunning else {
            return pausedAudioTimeMs
        }

        guard let player,
              let nodeTime = player.lastRenderTime,
              let playerTime = player.playerTime(forNodeTime: nodeTime)
        else {
            // The engine has stopped for a device change and not restarted yet: carry on from the last render.
            return lastAnchor?.audioTimeMs(atHostTimeMs: hostTimeMs) ?? pausedAudioTimeMs
        }

        let anchor = Mania4KRenderAnchor(
            renderedAudioTimeMs: startAudioTimeMs + Double(playerTime.sampleTime) / playerTime.sampleRate * 1000,
            renderHostTimeMs: AVAudioTime.seconds(forHostTime: nodeTime.hostTime) * 1000,
            outputLatencyMs: outputLatencyMs
        )
        state.withLockUnchecked { $0.anchor = anchor }
        return anchor.audioTimeMs(atHostTimeMs: hostTimeMs)
    }

    public func isRunning() async -> Bool {
        state.withLockUnchecked { $0.isRunning }
    }
}

/// The offset calibration's click track: silence until the first tick at `leadInMs`, then `tick` every `intervalMs`.
/// `loop` starts with the tick and is as long as the interval, so after `leadIn` it puts tick k at audio time
/// `leadInMs + k * intervalMs`.
public struct Mania4KCalibrationClickTrack {
    let leadIn: AVAudioPCMBuffer
    let loop: AVAudioPCMBuffer

    public init(tick: AVAudioPCMBuffer, leadInMs: Int, intervalMs: Int) {
        let format = tick.format
        let sampleRate = format.sampleRate
        leadIn = Self.silence(format: format, frames: Int((Double(leadInMs) * sampleRate / 1000).rounded()))
        loop = Self.silence(format: format, frames: Int((Double(intervalMs) * sampleRate / 1000).rounded()))
        for channel in 0..<Int(format.channelCount) {
            loop.floatChannelData![channel].update(from: tick.floatChannelData![channel], count: Int(tick.frameLength))
        }
    }

    private init(leadIn: AVAudioPCMBuffer, loop: AVAudioPCMBuffer) {
        self.leadIn = leadIn
        self.loop = loop
    }

    /// The track from `positionMs` of audio time on: the rest of the lead-in, or of the interval the position falls
    /// in, plays once, then the loop repeats. Call it on the track as built, not on a resumed one.
    func resumed(atMs positionMs: Double) -> Mania4KCalibrationClickTrack {
        let frame = Int((positionMs * loop.format.sampleRate / 1000).rounded())
        let leadInFrames = Int(leadIn.frameLength)
        guard frame >= leadInFrames else {
            return Mania4KCalibrationClickTrack(leadIn: Self.silence(format: loop.format, frames: leadInFrames - frame), loop: loop)
        }

        let loopFrames = Int(loop.frameLength)
        let phase = (frame - leadInFrames) % loopFrames
        let rest = Self.silence(format: loop.format, frames: loopFrames - phase)
        for channel in 0..<Int(loop.format.channelCount) {
            rest.floatChannelData![channel].update(from: loop.floatChannelData![channel] + phase, count: loopFrames - phase)
        }
        return Mania4KCalibrationClickTrack(leadIn: rest, loop: loop)
    }

    /// `leadIn` plays once, then `loop` repeats until the player stops.
    func schedule(on player: AVAudioPlayerNode) {
        player.scheduleBuffer(leadIn, at: nil, options: [], completionHandler: nil)
        player.scheduleBuffer(loop, at: nil, options: .loops, completionHandler: nil)
    }

    private static func silence(format: AVAudioFormat, frames: Int) -> AVAudioPCMBuffer {
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))!
        buffer.frameLength = AVAudioFrameCount(frames)
        for channel in 0..<Int(format.channelCount) {
            buffer.floatChannelData![channel].update(repeating: 0, count: frames)
        }
        return buffer
    }
}
