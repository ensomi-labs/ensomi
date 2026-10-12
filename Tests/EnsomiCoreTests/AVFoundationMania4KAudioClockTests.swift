import AVFoundation
import XCTest
@testable import EnsomiCore

final class AVFoundationMania4KAudioClockTests: XCTestCase {
    func testRenderAnchorCarriesRenderedTimeToAnyHostTimeShiftedByOutputLatency() {
        let anchor = Mania4KRenderAnchor(renderedAudioTimeMs: 1_000, renderHostTimeMs: 5_000, outputLatencyMs: 20)

        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_000), 980)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_100), 1_080)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 4_900), 880)
    }

    func testEngineClockFollowsTheHostClockSmoothlyAndHoldsWhilePaused() async throws {
        let url = try writeToneFile(sampleRate: 44_100, seconds: 6)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        let metadata = try await clock.prepare(audioFileURL: url)
        XCTAssertEqual(try XCTUnwrap(metadata.durationMs), 6_000, accuracy: 1)
        do {
            try await clock.play()
        } catch {
            throw XCTSkip("No audio output to render to: \(error.localizedDescription)")
        }

        var samples: [(host: Double, audio: Double)] = []
        let endHostMs = EnsomiHostClock.currentTimeMS() + 1_500
        while EnsomiHostClock.currentTimeMS() < endHostMs {
            let hostMs = EnsomiHostClock.currentTimeMS()
            samples.append((hostMs, clock.audioTimeMs(atHostTimeMs: hostMs)))
            try await Task.sleep(nanoseconds: 3_000_000)
        }

        let offsets = samples.map { $0.audio - $0.host }
        XCTAssertLessThan(try XCTUnwrap(offsets.max()) - XCTUnwrap(offsets.min()), 0.5)
        XCTAssertTrue(zip(samples, samples.dropFirst()).allSatisfy { $1.audio > $0.audio })

        await clock.pause()
        let pausedAudioMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), pausedAudioMs)

        try await clock.play()
        let resumedHostMs = EnsomiHostClock.currentTimeMS()
        let resumedAudioMs = clock.audioTimeMs(atHostTimeMs: resumedHostMs)
        XCTAssertEqual(resumedAudioMs, pausedAudioMs, accuracy: 40)
        try await Task.sleep(nanoseconds: 400_000_000)
        let laterHostMs = EnsomiHostClock.currentTimeMS()
        let laterAudioMs = clock.audioTimeMs(atHostTimeMs: laterHostMs)
        XCTAssertEqual(laterAudioMs - resumedAudioMs, laterHostMs - resumedHostMs, accuracy: 1)

        await clock.stop()
        let stoppedRunning = await clock.isRunning()
        XCTAssertFalse(stoppedRunning)
    }

    /// Tick k must start at 1_000 + 500 * k ms of audio time (the offset calibration's notes), so the lead-in and the
    /// looped interval are rendered through a player as the clock schedules them, in an offline engine with no device.
    func testCalibrationClickTrackRendersTickKAtLeadInPlusKIntervals() throws {
        let track = try makeClickTrack(sampleRate: 44_100, leadInMs: 1_000, intervalMs: 500)
        let rendered = try render(track, seconds: 3)

        let onsets = rendered.indices.filter { abs(rendered[$0]) > 1e-6 && ($0 == 0 || abs(rendered[$0 - 1]) <= 1e-6) }
        XCTAssertEqual(Array(onsets.prefix(4)), [44_100, 66_150, 88_200, 110_250])
        // The main mixer pans a mono source (-3 dB), so compare the tick's shape against its first sample.
        for onset in onsets.prefix(4) {
            for frame in 0..<100 {
                XCTAssertEqual(rendered[onset + frame] / rendered[onset], Float(frame + 1), accuracy: 1e-3)
            }
        }
    }

    /// Resuming the click track after a device change must play what the original plays from that position on, whether
    /// the position is in the lead-in, on a tick's first frame, inside a tick or between ticks.
    func testResumedClickTrackPlaysWhatTheOriginalPlaysFromThatPosition() throws {
        let track = try makeClickTrack(sampleRate: 48_000, leadInMs: 1_000, intervalMs: 500)
        let original = try render(track, seconds: 4)

        for positionMs in [400.0, 1_000, 1_001, 1_250, 1_500, 2_300] {
            let frame = Int(positionMs * 48)
            let resumed = try render(track.resumed(atMs: positionMs), seconds: 1)
            XCTAssertTrue(resumed == Array(original[frame..<frame + resumed.count]), "resumed at \(positionMs) ms")
        }
    }

    /// A device change stops the engine. The clock builds it again and plays on from the position that was being
    /// heard, so the audio time continues from there instead of jumping, and only the gap delays the song.
    func testRouteChangeResumesFromTheHeardPositionAndKeepsAdvancing() async throws {
        let url = try writeToneFile(sampleRate: 44_100, seconds: 6)
        defer { try? FileManager.default.removeItem(at: url) }
        let fileClock = AVFoundationMania4KAudioClock()
        fileClock.volume = 0
        _ = try await fileClock.prepare(audioFileURL: url)
        try await checkRouteChange(of: fileClock)

        // The click track is in its looped part at the change: 100 ms of lead-in, then ticks every 200 ms.
        let clickClock = AVFoundationMania4KAudioClock()
        clickClock.volume = 0
        try clickClock.prepare(clickTrack: makeClickTrack(sampleRate: 44_100, leadInMs: 100, intervalMs: 200))
        try await checkRouteChange(of: clickClock)
    }

    func testRouteChangeKeepsAPausedClockPausedAtItsPosition() async throws {
        let url = try writeToneFile(sampleRate: 44_100, seconds: 6)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        _ = try await clock.prepare(audioFileURL: url)
        try await play(clock)
        try await Task.sleep(nanoseconds: 300_000_000)
        await clock.pause()
        let pausedAudioMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())

        clock.restartAfterConfigurationChange()
        try await Task.sleep(nanoseconds: 300_000_000)
        let stillRunning = await clock.isRunning()
        XCTAssertFalse(stillRunning)
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), pausedAudioMs, accuracy: 0.001)

        let resumeHostMs = EnsomiHostClock.currentTimeMS()
        try await clock.play()
        try await Task.sleep(nanoseconds: 300_000_000)
        let laterHostMs = EnsomiHostClock.currentTimeMS()
        // The song starts again from the paused position after the new engine has started, a little after play() is called.
        let lostMs = (laterHostMs - resumeHostMs) - (clock.audioTimeMs(atHostTimeMs: laterHostMs) - pausedAudioMs)
        XCTAssertGreaterThan(lostMs, 0)
        XCTAssertLessThan(lostMs, 150)
        await clock.stop()
    }

    private func checkRouteChange(of clock: AVFoundationMania4KAudioClock) async throws {
        try await play(clock)
        try await Task.sleep(nanoseconds: 300_000_000)
        let beforeHostMs = EnsomiHostClock.currentTimeMS()
        let beforeAudioMs = clock.audioTimeMs(atHostTimeMs: beforeHostMs)

        clock.restartAfterConfigurationChange()
        // The new engine is not rendering yet: the clock holds the position it had reached.
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), beforeAudioMs, accuracy: 3)

        var running = false
        for _ in 0..<500 where !running {
            running = await clock.isRunning()
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertTrue(running)
        let runningHostMs = EnsomiHostClock.currentTimeMS()

        // From there the audio time follows the host time again.
        var samples: [(host: Double, audio: Double)] = []
        let endHostMs = EnsomiHostClock.currentTimeMS() + 500
        while EnsomiHostClock.currentTimeMS() < endHostMs {
            let hostMs = EnsomiHostClock.currentTimeMS()
            samples.append((hostMs, clock.audioTimeMs(atHostTimeMs: hostMs)))
            try await Task.sleep(nanoseconds: 3_000_000)
        }
        let offsets = samples.map { $0.audio - $0.host }
        XCTAssertLessThan(try XCTUnwrap(offsets.max()) - XCTUnwrap(offsets.min()), 0.5)
        XCTAssertTrue(zip(samples, samples.dropFirst()).allSatisfy { $1.audio > $0.audio })
        // The song waited for the new engine and for its first render, which leads the call by 13 to 22 ms.
        let last = try XCTUnwrap(samples.last)
        let lostMs = (beforeAudioMs - beforeHostMs) - (last.audio - last.host)
        XCTAssertGreaterThan(lostMs, 0)
        XCTAssertLessThan(lostMs, runningHostMs - beforeHostMs + 40)
        await clock.stop()
    }

    private func play(_ clock: AVFoundationMania4KAudioClock) async throws {
        do {
            try await clock.play()
        } catch {
            throw XCTSkip("No audio output to render to: \(error.localizedDescription)")
        }
    }

    private func makeClickTrack(sampleRate: Double, leadInMs: Int, intervalMs: Int) throws -> Mania4KCalibrationClickTrack {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1))
        let tick = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 100))
        tick.frameLength = 100
        let tickSamples = try XCTUnwrap(tick.floatChannelData)[0]
        for frame in 0..<100 {
            tickSamples[frame] = Float(frame + 1)
        }
        return Mania4KCalibrationClickTrack(tick: tick, leadInMs: leadInMs, intervalMs: intervalMs)
    }

    /// The track as a player in an offline engine renders it, so no audio device is involved.
    private func render(_ track: Mania4KCalibrationClickTrack, seconds: Double) throws -> [Float] {
        let format = track.loop.format
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4_096)
        track.schedule(on: player)
        try engine.start()
        player.play()

        let output = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4_096))
        var rendered: [Float] = []
        let frames = Int(seconds * format.sampleRate)
        while rendered.count < frames {
            XCTAssertEqual(try engine.renderOffline(AVAudioFrameCount(min(4_096, frames - rendered.count)), to: output), .success)
            rendered += UnsafeBufferPointer(start: try XCTUnwrap(output.floatChannelData)[0], count: Int(output.frameLength))
        }
        return rendered
    }

    private func writeToneFile(sampleRate: Double, seconds: Double) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("wav")
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1))
        let frameCount = AVAudioFrameCount(sampleRate * seconds)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount))
        buffer.frameLength = frameCount
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        for frame in 0..<Int(frameCount) {
            samples[frame] = 0.1 * sinf(Float(frame) * 2 * .pi * 440 / Float(sampleRate))
        }
        try AVAudioFile(forWriting: url, settings: format.settings).write(from: buffer)
        return url
    }
}
