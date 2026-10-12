import AVFoundation
import XCTest
@testable import EnsomiCore

final class AVFoundationMania4KAudioClockTests: XCTestCase {
    func testRenderAnchorCarriesRenderedTimeToAnyHostTimeShiftedByOutputLatency() {
        let anchor = Mania4KRenderAnchor(renderedAudioTimeMs: 1_000, renderHostTimeMs: 5_000, outputLatencyMs: 20, rate: 1)

        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_000), 980)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_100), 1_080)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 4_900), 880)
    }

    func testRenderAnchorAtAnotherRateAdvancesAtThatRateAfterTheLatency() {
        let anchor = Mania4KRenderAnchor(renderedAudioTimeMs: 1_000, renderHostTimeMs: 5_000, outputLatencyMs: 20, rate: 0.5)

        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_020), 1_000)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_220), 1_100)
        XCTAssertEqual(anchor.audioTimeMs(atHostTimeMs: 5_000), 990)
    }

    /// A seek holds the clock at the new position until it plays; then the clock plays on from there at the rate
    /// asked: its first reading lies from the position as the first reading after the start lay from 0, and it
    /// follows the host clock at that rate.
    func testSeekHoldsThePositionThenPlaysOnFromItAtTheRate() async throws {
        let url = try writeToneFile(sampleRate: 44_100, seconds: 6)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        _ = try await clock.prepare(audioFileURL: url)
        try await play(clock)
        let startLeadMs = lead(of: clock, from: 0)
        try await Task.sleep(nanoseconds: 200_000_000)

        for (positionMs, rate) in [(3_000.0, 1.0), (1_000, 0.5), (2_000, 1.5)] {
            try clock.seek(toMs: positionMs, rate: rate)
            XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), positionMs)
            let isRunning = await clock.isRunning()
            XCTAssertFalse(isRunning)
            try await Task.sleep(nanoseconds: 100_000_000)
            XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), positionMs)

            try await clock.play()
            let readingMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
            let leadMs = (readingMs - positionMs) / rate + clock.outputLatencyMs
            print("TIMING seek to \(positionMs) ms at rate \(rate): lead \(leadMs) ms, at start \(startLeadMs) ms; latency \(clock.outputLatencyMs) ms")
            XCTAssertEqual(leadMs, startLeadMs, accuracy: 25)

            let samples = try await sample(clock, forMs: 500)
            let offsets = samples.map { $0.audio - rate * $0.host }
            XCTAssertLessThan(try XCTUnwrap(offsets.max()) - XCTUnwrap(offsets.min()), 0.5, "rate \(rate)")
            XCTAssertTrue(zip(samples, samples.dropFirst()).allSatisfy { $1.audio > $0.audio })
        }
        await clock.stop()
    }

    /// At a rate other than 1 the clock adds the varispeed unit's reported latency to the output's, so a sound at
    /// source time t must leave the unit at t / rate plus that latency. Rendered offline, with no device.
    func testVarispeedDelaysTheSourceByItsReportedLatency() throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let source = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 96_000))
        source.frameLength = 96_000
        let sourceSamples = try XCTUnwrap(source.floatChannelData)[0]
        for frame in 0..<96_000 {
            sourceSamples[frame] = (48_000..<48_048).contains(frame) ? 1 : 0
        }

        for rate in [0.5, 1.5] {
            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            let varispeed = AVAudioUnitVarispeed()
            let mixer = AVAudioMixerNode()
            varispeed.rate = Float(rate)
            try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4_096)
            [player, varispeed, mixer].forEach(engine.attach)
            engine.connect(player, to: varispeed, format: format)
            engine.connect(varispeed, to: mixer, format: format)
            engine.connect(mixer, to: engine.mainMixerNode, format: format)
            player.scheduleBuffer(source, at: nil, options: [], completionHandler: nil)
            try engine.start()
            player.play()

            let output = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4_096))
            var rendered: [Float] = []
            while rendered.count < 48_000 * 3 {
                XCTAssertEqual(try engine.renderOffline(4_096, to: output), .success)
                rendered += UnsafeBufferPointer(start: try XCTUnwrap(output.floatChannelData)[0], count: Int(output.frameLength))
            }
            let onset = try XCTUnwrap(rendered.firstIndex { abs($0) > 0.2 })
            XCTAssertEqual(Double(onset) / 48, 1_000 / rate + varispeed.latency * 1_000, accuracy: 0.1, "rate \(rate)")
            engine.stop()
        }
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

    /// The music volume scales the main mixer, not the player, so the clock keeps to the host clock at any level and
    /// when the level changes while playing. The file is silent, so nothing is heard whatever the level.
    func testMusicVolumeLeavesTheClockFollowingTheHostClock() async throws {
        let url = try writeToneFile(sampleRate: 44_100, seconds: 4, amplitude: 0)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        await clock.setMusicVolume(0.5)
        _ = try await clock.prepare(audioFileURL: url)
        try await play(clock)

        var samples = try await sample(clock, forMs: 1_000)
        await clock.setMusicVolume(0)
        samples += try await sample(clock, forMs: 1_000)

        let offsets = samples.map { $0.audio - $0.host }
        XCTAssertLessThan(try XCTUnwrap(offsets.max()) - XCTUnwrap(offsets.min()), 0.5)
        XCTAssertTrue(zip(samples, samples.dropFirst()).allSatisfy { $1.audio > $0.audio })
        await clock.stop()
    }

    /// A device change stops the engine. The clock builds it again and plays on from the position that was being
    /// heard. How long the new device takes to start depends on the device and is not checked.
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
        let startLeadMs = lead(of: clock, from: 0)
        try await Task.sleep(nanoseconds: 300_000_000)
        await clock.pause()
        let pausedAudioMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())

        clock.restartAfterConfigurationChange()
        try await Task.sleep(nanoseconds: 300_000_000)
        let stillRunning = await clock.isRunning()
        XCTAssertFalse(stillRunning)
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), pausedAudioMs, accuracy: 0.001)

        try await clock.play()
        try await checkResumed(clock, from: pausedAudioMs, startLeadMs: startLeadMs)
        await clock.stop()
    }

    private func checkRouteChange(of clock: AVFoundationMania4KAudioClock) async throws {
        try await play(clock)
        let startLeadMs = lead(of: clock, from: 0)
        try await Task.sleep(nanoseconds: 300_000_000)
        let heardAudioMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())

        clock.restartAfterConfigurationChange()
        // The new engine is not rendering yet: the clock holds the position it had reached.
        XCTAssertEqual(clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()), heardAudioMs, accuracy: 3)

        let deadlineHostMs = EnsomiHostClock.currentTimeMS() + 5_000
        var running = false
        while !running, EnsomiHostClock.currentTimeMS() < deadlineHostMs {
            try await Task.sleep(nanoseconds: 2_000_000)
            running = await clock.isRunning()
        }
        XCTAssertTrue(running)
        try await checkResumed(clock, from: heardAudioMs, startLeadMs: startLeadMs)
        await clock.stop()
    }

    /// The clock's reading plus the output latency it subtracts, less `positionMs`, the position it has just started
    /// playing from: minus the lead of the first buffer's render time, which depends on the device.
    private func lead(of clock: AVFoundationMania4KAudioClock, from positionMs: Double) -> Double {
        clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS()) + clock.outputLatencyMs - positionMs
    }

    /// Once the restarted engine renders, the clock plays on from `heardAudioMs` as a clock started there would: its
    /// first reading lies from that position as the first reading after the clock's start lay from 0 (`startLeadMs`),
    /// within the render cycle the reading falls in, neither ahead nor further behind. Then it follows the host clock.
    private func checkResumed(_ clock: AVFoundationMania4KAudioClock, from heardAudioMs: Double, startLeadMs: Double) async throws {
        let resumeLeadMs = lead(of: clock, from: heardAudioMs)
        print("TIMING route change: reading + latency - position after start \(startLeadMs) ms, after restart \(resumeLeadMs) ms; latency \(clock.outputLatencyMs) ms")
        XCTAssertEqual(resumeLeadMs, startLeadMs, accuracy: 25)

        let samples = try await sample(clock, forMs: 500)

        let offsets = samples.map { $0.audio - $0.host }
        XCTAssertLessThan(try XCTUnwrap(offsets.max()) - XCTUnwrap(offsets.min()), 0.5)
        XCTAssertTrue(zip(samples, samples.dropFirst()).allSatisfy { $1.audio > $0.audio })
    }

    private func sample(_ clock: AVFoundationMania4KAudioClock, forMs durationMs: Double) async throws -> [(host: Double, audio: Double)] {
        var samples: [(host: Double, audio: Double)] = []
        let endHostMs = EnsomiHostClock.currentTimeMS() + durationMs
        while EnsomiHostClock.currentTimeMS() < endHostMs {
            let hostMs = EnsomiHostClock.currentTimeMS()
            samples.append((hostMs, clock.audioTimeMs(atHostTimeMs: hostMs)))
            try await Task.sleep(nanoseconds: 3_000_000)
        }
        return samples
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

    private func writeToneFile(sampleRate: Double, seconds: Double, amplitude: Float = 0.1) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("wav")
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1))
        let frameCount = AVAudioFrameCount(sampleRate * seconds)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount))
        buffer.frameLength = frameCount
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        for frame in 0..<Int(frameCount) {
            samples[frame] = amplitude * sinf(Float(frame) * 2 * .pi * 440 / Float(sampleRate))
        }
        try AVAudioFile(forWriting: url, settings: format.settings).write(from: buffer)
        return url
    }
}
