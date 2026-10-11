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
public final class AVFoundationMania4KAudioClock: Mania4KAudioClock, @unchecked Sendable {
    private struct State {
        var engine: AVAudioEngine?
        var player: AVAudioPlayerNode?
        var isRunning = false
        var pausedAudioTimeMs = 0.0
        var outputLatencyMs = 0.0
        var volume: Float = 1
    }

    private let state = OSAllocatedUnfairLock(uncheckedState: State())

    public init() {}

    /// The engine's output volume. It is applied at the main mixer: a zero volume on the player itself changes how
    /// the mixer pulls the player, and its node time then drifts against the device clock.
    public var volume: Float {
        get { state.withLockUnchecked { $0.volume } }
        set {
            state.withLockUnchecked {
                $0.volume = newValue
                $0.engine?.mainMixerNode.outputVolume = newValue
            }
        }
    }

    public func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        let file = try AVAudioFile(forReading: audioFileURL)
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        // The mixer runs at the output's rate. Connected straight to the main mixer, a 44.1 kHz file's player
        // timeline runs about 1.3 ms per second fast against the device clock.
        let mixer = AVAudioMixerNode()
        engine.attach(player)
        engine.attach(mixer)
        engine.connect(player, to: mixer, format: file.processingFormat)
        engine.connect(mixer, to: engine.mainMixerNode, format: engine.outputNode.outputFormat(forBus: 0))
        player.scheduleFile(file, at: nil, completionHandler: nil)
        engine.prepare()

        state.withLockUnchecked {
            $0.engine?.stop()
            $0.engine = engine
            $0.player = player
            $0.isRunning = false
            $0.pausedAudioTimeMs = 0
            engine.mainMixerNode.outputVolume = $0.volume
        }

        return Mania4KAudioMetadata(
            durationMs: Double(file.length) / file.processingFormat.sampleRate * 1000,
            title: audioFileURL.deletingPathExtension().lastPathComponent
        )
    }

    public func play() async throws {
        let (engine, player) = state.withLockUnchecked { ($0.engine, $0.player) }
        guard let engine, let player else {
            throw Mania4KPlayFailure.audioPrepareFailed("Audio player is not prepared.")
        }

        let previousRenderSample = player.lastRenderTime?.sampleTime
        try engine.start()
        player.play()
        // Reads stay at the paused time until a buffer rendered after this call gives a node time to extrapolate from.
        for _ in 0..<500 {
            if let nodeTime = player.lastRenderTime,
               nodeTime.sampleTime != previousRenderSample,
               player.playerTime(forNodeTime: nodeTime) != nil {
                state.withLockUnchecked {
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
            $0.isRunning = false
            $0.player?.pause()
        }
    }

    public func stop() async {
        state.withLockUnchecked {
            $0.player?.stop()
            $0.engine?.stop()
            $0.isRunning = false
            $0.pausedAudioTimeMs = 0
        }
    }

    public func currentAudioTimeMs() async -> Double {
        audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
    }

    public func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        let (player, isRunning, pausedAudioTimeMs, outputLatencyMs) = state.withLockUnchecked {
            ($0.player, $0.isRunning, $0.pausedAudioTimeMs, $0.outputLatencyMs)
        }
        guard isRunning,
              let player,
              let nodeTime = player.lastRenderTime,
              let playerTime = player.playerTime(forNodeTime: nodeTime)
        else {
            return pausedAudioTimeMs
        }

        return Mania4KRenderAnchor(
            renderedAudioTimeMs: Double(playerTime.sampleTime) / playerTime.sampleRate * 1000,
            renderHostTimeMs: AVAudioTime.seconds(forHostTime: nodeTime.hostTime) * 1000,
            outputLatencyMs: outputLatencyMs
        ).audioTimeMs(atHostTimeMs: hostTimeMs)
    }

    public func isRunning() async -> Bool {
        state.withLockUnchecked { $0.isRunning }
    }
}
