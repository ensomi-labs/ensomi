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
