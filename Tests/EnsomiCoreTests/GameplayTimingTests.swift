import AVFoundation
import AppKit
import Metal
import QuartzCore
import SwiftUI
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

/// Timing measurements for `docs/gameplay-timing.md`. Everything plays silently: the engine's output volume is 0.
/// The 60 s run needs a window on a lit display and only runs with `TEST_RUNNER_ENSOMI_TIMING_RUN=1`.
@MainActor
final class GameplayTimingTests: XCTestCase {
    func testHostClocksAgreeAndNSEventConvertsNanosecondsToSeconds() throws {
        XCTAssertEqual(CACurrentMediaTime() * 1_000, EnsomiHostClock.currentTimeMS(), accuracy: 1)
        XCTAssertEqual(AVAudioTime.seconds(forHostTime: mach_absolute_time()) * 1_000, EnsomiHostClock.currentTimeMS(), accuracy: 1)

        let cgEvent = try XCTUnwrap(CGEvent(keyboardEventSource: nil, virtualKey: 2, keyDown: true))
        cgEvent.timestamp = CGEventTimestamp(ProcessInfo.processInfo.systemUptime * 1e9)
        let event = try XCTUnwrap(NSEvent(cgEvent: cgEvent))
        XCTAssertEqual(event.timestamp * 1_000, EnsomiHostClock.currentTimeMS(), accuracy: 20)
    }

    /// Key events with known timestamps, handled up to 60 ms after they happened, against the real engine clock. Frames
    /// declare inputs delivered only up to `inputDeliveryAllowanceMs` (100 ms) before their chart time, so each is
    /// judged at its timestamp.
    func testKeyEventsHandledLateAreJudgedAtTheirTimestamps() async throws {
        let url = try writeToneFile(seconds: 14)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        let audioOffset = -20.0
        let noteTimes = Array(stride(from: 1_500.0, through: 9_500.0, by: 400))
        let stream = InMemoryMania4KHitObjectStream(
            objects: noteTimes.map { Mania4KHitObject(lane: .left, timeMs: $0, kind: .tap) }
        )
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: audioOffset,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/timing.osu"))
        model.selectAudioFile(url)
        guard await model.startPlay() else {
            throw XCTSkip("The audio engine did not start: \(model.phase)")
        }

        // Each press is stamped with the host time at which the chart time is the note's, read from the clock once.
        let delays = noteTimes.indices.map { [0.0, 10, 30, 60][$0 % 4] }
        let planHostMs = EnsomiHostClock.currentTimeMS()
        let planAudioMs = clock.audioTimeMs(atHostTimeMs: planHostMs)
        let stamps = noteTimes.map { planHostMs + ($0 - audioOffset) - planAudioMs }
        var next = 0
        var seenEventIDs = Set<UInt64>()
        var errorsByDelay: [Double: [Double]] = [:]
        let deadlineMs = planHostMs + 12_000

        while errorsByDelay.values.map(\.count).reduce(0, +) < noteTimes.count, EnsomiHostClock.currentTimeMS() < deadlineMs {
            let nowMs = EnsomiHostClock.currentTimeMS()
            while next < noteTimes.count, stamps[next] + delays[next] <= nowMs {
                model.handleKeyboardInput(key: "d", isPressed: true, isRepeat: false, hostTimeMs: stamps[next])
                model.handleKeyboardInput(key: "d", isPressed: false, isRepeat: false, hostTimeMs: stamps[next] + 20)
                next += 1
            }

            await model.tick()
            for event in model.gameplayFeedback.judgementEventBatches().flatMap(\.events) where seenEventIDs.insert(event.id).inserted {
                if let hitErrorMs = event.offsetMs, let index = noteTimes.firstIndex(of: event.objectTimeMs) {
                    errorsByDelay[delays[index], default: []].append(hitErrorMs)
                }
            }
            try await Task.sleep(nanoseconds: 4_000_000)
        }
        await model.quitToSetup()

        XCTAssertEqual(errorsByDelay.values.map(\.count).reduce(0, +), noteTimes.count)
        print("TIMING key-event timestamp error, \(noteTimes.count) presses against the engine clock")
        print("TIMING delay_ms n mean_abs_error_us max_abs_error_us stamp_at_handling_error_ms")
        for delay in errorsByDelay.keys.sorted() {
            let errors = errorsByDelay[delay]!.map(abs)
            print(String(format: "TIMING %.0f %d %.1f %.1f %.1f", delay, errors.count, errors.mean * 1_000, (errors.max() ?? 0) * 1_000, delay))
            XCTAssertLessThan(errors.max() ?? 0, 0.5, "delay \(delay) ms")
        }
    }

    /// CPU cost of one frame's session work on a long chart: advance, visible objects and lane states.
    func testSessionFrameCostOnALongChart() throws {
        let objects = (0..<4_000).map { ChartObject(lane: UInt32($0 % 4), startMs: 500 + UInt32($0) * 50, endMs: nil) }
        let session = try GameplaySession(
            chart: Chart(durationMs: 201_000, objects: objects), ruleset: Ruleset(judge: .c)
        )
        var costsMs: [Double] = []
        for frame in 0..<1_000 {
            let chartTimeMs = 90_000 + Double(frame) * 16.667
            let startMs = EnsomiHostClock.currentTimeMS()
            _ = session.advance(watermarkMs: chartTimeMs)
            _ = session.visibleObjects(lowerMs: chartTimeMs - 700, upperMs: chartTimeMs + 1_000)
            _ = session.laneStates()
            costsMs.append(EnsomiHostClock.currentTimeMS() - startMs)
        }

        print(String(format: "TIMING session advance+visibleObjects+laneStates, 4000 objects: p50 %.3f ms, p99 %.3f ms, max %.3f ms", costsMs.percentile(0.5), costsMs.percentile(0.99), costsMs.max() ?? 0))
    }

    /// CPU cost of laying a busy frame out (about 70 visible notes) and encoding it for the GPU.
    func testPlayfieldLayoutAndEncodeCost() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw XCTSkip("No Metal device")
        }

        let renderer = try Mania4KMetalRenderer(device: device, pixelFormat: .bgra8Unorm)
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 720, height: 1400, mipmapped: false)
        descriptor.usage = .renderTarget
        descriptor.storageMode = .private
        let texture = try XCTUnwrap(device.makeTexture(descriptor: descriptor))
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        let objects = (0..<70).map { index -> VisibleObject in
            let startMs = 1_000 + Double(index) * 25
            return VisibleObject(
                id: UInt32(index), lane: Mania4KLane.allCases[index % 4].index,
                startTimeMs: startMs, endTimeMs: index % 5 == 0 ? startMs + 200 : nil, state: .waiting
            )
        }
        let frame = Mania4KPlayFrame(
            gameplayChartTimeMs: 1_000, renderChartTimeMs: 1_000, scrollTimeMs: 459,
            metadata: Mania4KChartMetadata(title: "T", sourceDescription: "T"),
            visibleObjects: objects, score: .zero, laneStates: [], latestJudgement: nil
        )
        let size = CGSize(width: 360, height: 700)
        let dark = { (_: Mania4KLane) in LaneBrightness(receptor: 0.2, lane: 0.05) }
        var layoutCostsMs: [Double] = []
        var encodeCostsMs: [Double] = []
        var quadCount = 0
        for _ in 0..<300 {
            let layoutStartMs = EnsomiHostClock.currentTimeMS()
            let quads = Mania4KPlayfieldLayout.quads(frame: frame, brightness: dark, size: size)
            let encodeStartMs = EnsomiHostClock.currentTimeMS()
            let commandBuffer = renderer.draw(quads, viewportSize: size, pass: pass, drawable: nil)
            let endMs = EnsomiHostClock.currentTimeMS()
            commandBuffer?.waitUntilCompleted()
            layoutCostsMs.append(encodeStartMs - layoutStartMs)
            encodeCostsMs.append(endMs - encodeStartMs)
            quadCount = quads.count
        }

        print(String(format: "TIMING layout (%d quads): p50 %.3f ms, p99 %.3f ms, max %.3f ms", quadCount, layoutCostsMs.percentile(0.5), layoutCostsMs.percentile(0.99), layoutCostsMs.max() ?? 0))
        print(String(format: "TIMING encode and commit: p50 %.3f ms, p99 %.3f ms, max %.3f ms", encodeCostsMs.percentile(0.5), encodeCostsMs.percentile(0.99), encodeCostsMs.max() ?? 0))
    }

    func testFramePacingAndClockJitterOverASilentAutoplay() async throws {
        guard ProcessInfo.processInfo.environment["ENSOMI_TIMING_RUN"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_TIMING_RUN=1 with a lit display to run the 60 s measurement.")
        }

        _ = NSApplication.shared
        NSApp.setActivationPolicy(.accessory)
        let seconds = Double(ProcessInfo.processInfo.environment["ENSOMI_TIMING_SECONDS"] ?? "") ?? 60
        let url = try writeToneFile(seconds: seconds + 12)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        let oldClockPlayer = try AVAudioPlayer(contentsOf: url)
        oldClockPlayer.volume = 0
        let audioOffset = -20.0

        // A note every 140 ms cycling through the lanes, every sixth a 400 ms hold.
        struct Note { let lane: Mania4KLane; let startMs: Double; let endMs: Double? }
        let notes = (0..<420).map { index -> Note in
            let start = 1_500 + Double(index) * 140
            return Note(lane: Mania4KLane.allCases[(index * 3) % 4], startMs: start, endMs: index % 6 == 5 ? start + 400 : nil)
        }
        let objects = notes.flatMap { note -> [Mania4KHitObject] in
            note.endMs.map { [Mania4KHitObject(lane: note.lane, timeMs: note.startMs, kind: .holdStart), Mania4KHitObject(lane: note.lane, timeMs: $0, kind: .holdEnd)] }
                ?? [Mania4KHitObject(lane: note.lane, timeMs: note.startMs, kind: .tap)]
        }.sorted { ($0.timeMs, $0.lane.rawValue) < ($1.timeMs, $1.lane.rawValue) }
        let stream = InMemoryMania4KHitObjectStream(objects: objects)
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: audioOffset,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/timing.osu"))
        model.selectAudioFile(url)

        let window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 360, height: 700), styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        let playfield = Mania4KPlayfieldView(model: model)
        window.contentView = playfield
        window.orderFrontRegardless()
        print("TIMING window: screens \(NSScreen.screens.count), visible \(window.isVisible), occluded \(!window.occlusionState.contains(.visible)), display asleep \(CGDisplayIsAsleep(CGMainDisplayID())), session \(String(describing: CGSessionCopyCurrentDictionary()))")
        guard await model.startPlay() else {
            throw XCTSkip("The audio engine did not start: \(model.phase)")
        }
        oldClockPlayer.play()

        struct Frame { let callbackMs: Double; let timestampMs: Double; let targetMs: Double; let chartMs: Double; let oldClockMs: Double }
        var frames: [Frame] = []
        var scheduledNotes = 0
        let keys = ["d", "f", "j", "k"]
        let link = Mania4KDisplayLink(view: playfield) { link in
            let callbackMs = EnsomiHostClock.currentTimeMS()
            let targetMs = link.targetTimestamp * 1_000
            let chartMs = clock.audioTimeMs(atHostTimeMs: targetMs) + audioOffset
            frames.append(Frame(callbackMs: callbackMs, timestampMs: link.timestamp * 1_000, targetMs: targetMs, chartMs: chartMs, oldClockMs: oldClockPlayer.currentTime * 1_000))

            // Autoplay: press each note at its time, stamped with the host time of that chart time.
            while scheduledNotes < notes.count, notes[scheduledNotes].startMs <= chartMs + 40 {
                let note = notes[scheduledNotes]
                let key = keys[note.lane.rawValue]
                let pressHostMs = targetMs + (note.startMs - chartMs)
                let releaseHostMs = pressHostMs + ((note.endMs.map { $0 - note.startMs }) ?? 40)
                model.handleKeyboardInput(key: key, isPressed: true, isRepeat: false, hostTimeMs: pressHostMs)
                model.handleKeyboardInput(key: key, isPressed: false, isRepeat: false, hostTimeMs: releaseHostMs)
                scheduledNotes += 1
            }
        }

        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        link.invalidate()
        let finalFrame = model.playFrame
        oldClockPlayer.stop()
        await model.quitToSetup()
        window.close()

        let targetIntervals = zip(frames.dropFirst(), frames).map { $0.targetMs - $1.targetMs }
        let callbackIntervals = zip(frames.dropFirst(), frames).map { $0.callbackMs - $1.callbackMs }
        let lateness = frames.map { $0.callbackMs - $0.timestampMs }
        let nominal = targetIntervals.percentile(0.5)
        let chartJitter = zip(frames.dropFirst(), frames).map { ($0.chartMs - $1.chartMs) - ($0.targetMs - $1.targetMs) }
        let oldJitter = zip(frames.dropFirst(), frames).map { ($0.oldClockMs - $1.oldClockMs) - ($0.callbackMs - $1.callbackMs) }

        print(String(format: "TIMING frames %d over %.1f s, nominal refresh interval %.3f ms", frames.count, seconds, nominal))
        print("TIMING display refresh interval (target to target): \(targetIntervals.summary)")
        print("TIMING main-thread callback interval: \(callbackIntervals.summary)")
        print("TIMING callback lateness after the vsync timestamp: \(lateness.summary)")
        print(String(format: "TIMING refresh intervals over 1.5x nominal: %d, over 2.5x: %d", targetIntervals.filter { $0 > nominal * 1.5 }.count, targetIntervals.filter { $0 > nominal * 2.5 }.count))
        print(String(format: "TIMING callback intervals over 1.5x nominal: %d, over 2.5x: %d", callbackIntervals.filter { $0 > nominal * 1.5 }.count, callbackIntervals.filter { $0 > nominal * 2.5 }.count))
        print("TIMING engine-clock chart-time step minus refresh step (ms): \(chartJitter.summary)")
        print("TIMING AVAudioPlayer.currentTime step minus callback step (ms): \(oldJitter.summary)")
        let finalCounts = finalFrame?.score.tierCounts
        print("TIMING autoplay result: perfect \(finalCounts.map { Int($0.perfectCount) } ?? -1), good \(finalCounts.map { Int($0.goodCount) } ?? -1), miss \(finalCounts.map { Int($0.missCount) } ?? -1), average hit error \(finalFrame?.score.suggestedAudioOffsetMs.map { String(format: "%.3f ms", -$0) } ?? "none"), notes \(notes.count), scheduled \(scheduledNotes)")
        XCTAssertGreaterThan(frames.count, Int(seconds * 30))
        XCTAssertEqual(finalCounts?.missCount, 0)
    }

    /// The whole play screen in a window: the Metal view under SwiftUI gets its display link, and key events sent
    /// to the window reach the session with their timestamps.
    func testHostedPlayScreenRunsFramesAndJudgesKeyEvents() async throws {
        guard ProcessInfo.processInfo.environment["ENSOMI_TIMING_RUN"] != nil else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_TIMING_RUN=1 with a lit display.")
        }

        _ = NSApplication.shared
        NSApp.setActivationPolicy(.accessory)
        let url = try writeToneFile(seconds: 20)
        defer { try? FileManager.default.removeItem(at: url) }
        let clock = AVFoundationMania4KAudioClock()
        clock.volume = 0
        let noteTimes = [2_000.0, 2_600, 3_200, 3_800, 4_400, 5_000]
        let stream = InMemoryMania4KHitObjectStream(objects: noteTimes.map { Mania4KHitObject(lane: .left, timeMs: $0, kind: .tap) })
        let model = Mania4KPlaySessionModel(audioOffsetMilliseconds: 0, visualOffsetMilliseconds: 0, audioClock: clock, streamFactory: { _ in stream })
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/timing.osu"))
        model.selectAudioFile(url)

        let window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 900, height: 800), styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: Mania4KPlayExperienceView(model: model).frame(width: 900, height: 800))
        window.makeKeyAndOrderFront(nil)
        guard await model.startPlay() else {
            throw XCTSkip("The audio engine did not start: \(model.phase)")
        }

        func descendants(of view: NSView) -> [NSView] {
            view.subviews + view.subviews.flatMap(descendants)
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        let playfield = try XCTUnwrap(descendants(of: try XCTUnwrap(window.contentView)).compactMap { $0 as? Mania4KPlayfieldView }.first)
        XCTAssertNotNil(playfield.window)
        XCTAssertGreaterThan(playfield.bounds.width, 280)
        XCTAssertLessThan(playfield.bounds.width, 621)
        XCTAssertGreaterThan(playfield.bounds.height, 400)
        print("TIMING hosted playfield size \(playfield.bounds.size), first responder \(String(describing: window.firstResponder.map { type(of: $0) }))")

        var next = 0
        while next < noteTimes.count {
            let chartMs = clock.audioTimeMs(atHostTimeMs: EnsomiHostClock.currentTimeMS())
            if chartMs >= noteTimes[next] {
                for (isDown, delayMs) in [(true, 0.0), (false, 30.0)] {
                    let event = NSEvent.keyEvent(
                        with: isDown ? .keyDown : .keyUp, location: .zero, modifierFlags: [],
                        timestamp: ProcessInfo.processInfo.systemUptime + delayMs / 1_000, windowNumber: window.windowNumber, context: nil,
                        characters: "d", charactersIgnoringModifiers: "d", isARepeat: false, keyCode: 2
                    )
                    window.sendEvent(try XCTUnwrap(event))
                }
                next += 1
            }
            try await Task.sleep(nanoseconds: 3_000_000)
        }
        try await Task.sleep(nanoseconds: 400_000_000)

        let frame = try XCTUnwrap(model.playFrame)
        let counts = frame.score.tierCounts
        print("TIMING hosted play screen: chart time \(frame.gameplayChartTimeMs), perfect \(counts.perfectCount), good \(counts.goodCount), miss \(counts.missCount), HUD \(model.hud)")
        XCTAssertEqual(Int(counts.perfectCount), noteTimes.count)
        XCTAssertEqual(counts.missCount, 0)
        await model.quitToSetup()
        window.close()
    }

    private func writeToneFile(seconds: Double) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("wav")
        let sampleRate = 44_100.0
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

private extension Array where Element == Double {
    var mean: Double {
        isEmpty ? 0 : reduce(0, +) / Double(count)
    }

    func percentile(_ fraction: Double) -> Double {
        guard !isEmpty else {
            return 0
        }

        let sorted = sorted()
        return sorted[Swift.min(sorted.count - 1, Int(Double(sorted.count - 1) * fraction + 0.5))]
    }

    var summary: String {
        let spread = (reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(Swift.max(count, 1))).squareRoot()
        return String(
            format: "n %d, mean %.3f, sd %.3f, p1 %.3f, p50 %.3f, p99 %.3f, min %.3f, max %.3f",
            count, mean, spread, percentile(0.01), percentile(0.5), percentile(0.99), self.min() ?? 0, self.max() ?? 0
        )
    }
}
