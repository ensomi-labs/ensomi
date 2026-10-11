import ImageIO
import Metal
import SwiftUI
import UniformTypeIdentifiers
import XCTest
@testable import EnsomiCore
@testable import EnsomiUI

/// Renders the play screen offscreen into PNGs at chosen chart times of a fixed chart and input log, for judging its
/// look; `6-narrow` is the miss moment in a narrow window. The session runs through the play model with a clock that
/// stands still at each frame's time; the playfield is drawn by the Metal renderer and the HUD by `ImageRenderer`,
/// without a window. Runs only with `TEST_RUNNER_ENSOMI_SCREENSHOT_DIR` set to the directory to write into.
@MainActor
final class PlayScreenScreenshotTests: XCTestCase {
    private static let screenSize = CGSize(width: 1_120, height: 780)
    private static let scale: CGFloat = 2
    private static let frameMs = 1_000.0 / 60

    /// Taps and holds, in ms and lanes 0 to 3: a lead-in, a passage of 16th-note chords at 150 BPM, a hold under
    /// taps, then a chord whose right note is never pressed.
    private static let chart: [(lanes: [Int], timeMs: Double, endMs: Double?)] = [
        ([0, 3], 2_000, nil), ([1], 2_100, nil), ([2], 2_200, nil), ([0, 2], 2_300, nil), ([1, 3], 2_400, nil),
        ([0, 1, 2], 2_500, nil), ([3], 2_600, nil), ([1, 2], 2_700, nil), ([0], 2_800, nil), ([2, 3], 2_900, nil),
        ([0, 1, 3], 3_000, nil), ([2], 3_100, nil), ([1, 3], 3_200, nil), ([0, 2], 3_300, nil), ([1], 3_400, nil),
        ([0, 3], 3_500, nil), ([2], 3_600, nil), ([1, 3], 3_700, nil),
        ([1], 4_000, 4_700), ([3], 4_000, nil), ([2], 4_200, nil), ([3], 4_400, nil), ([2], 4_550, nil),
        ([0, 2], 4_800, nil), ([1, 3], 4_900, nil), ([0], 5_000, nil), ([2], 5_100, nil),
        ([0, 3], 5_400, nil), ([1], 5_500, nil), ([2], 5_600, nil), ([1, 3], 5_700, nil), ([0], 5_800, nil),
        ([2, 3], 5_900, nil), ([1], 6_000, nil), ([0, 2], 6_100, nil), ([3], 6_200, nil), ([1, 2], 6_300, nil)
    ]

    /// Every note is pressed at its time plus an error from this cycle (one in five is Good at judge C), except
    /// the right note at 5400 ms.
    private static let hitErrorsMs: [Double] = [4, -9, 13, -3, 47, -16, 7, 0, -22, 10]

    private static let moments: [(name: String, timeMs: Double)] = [
        ("1-before-first-note", 1_700),
        ("2-chord-passage", 3_060),
        ("3-hold", 4_460),
        ("4-miss", 5_780)
    ]

    func testPlayScreenScreenshots() async throws {
        guard let directory = ProcessInfo.processInfo.environment["ENSOMI_SCREENSHOT_DIR"] else {
            throw XCTSkip("Set TEST_RUNNER_ENSOMI_SCREENSHOT_DIR to the directory for the PNGs.")
        }
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw XCTSkip("No Metal device")
        }

        let directoryURL = URL(fileURLWithPath: directory, isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        let snapshotter = try PlayfieldSnapshotter(device: device, scale: Self.scale)
        let clock = FakeMania4KAudioClock(metadata: Mania4KAudioMetadata(durationMs: 12_000, title: "Screenshot"))
        let stream = InMemoryMania4KHitObjectStream(
            objects: Self.hitObjects(),
            metadata: Mania4KChartMetadata(title: "Screenshot chart", sourceDescription: "Fixed chart", durationMs: 12_000)
        )
        let model = Mania4KPlaySessionModel(
            audioOffsetMilliseconds: 0,
            visualOffsetMilliseconds: 0,
            audioClock: clock,
            streamFactory: { _ in stream }
        )
        model.selectBeatmapFile(URL(fileURLWithPath: "/tmp/screenshot.osu"))
        model.selectAudioFile(URL(fileURLWithPath: "/tmp/screenshot.wav"))
        let started = await model.startPlay()
        XCTAssertTrue(started)

        let inputs = Self.inputLog()
        var nextInput = 0
        var timeMs = 0.0
        // The fake clock reads the time last set at every host time, and host time stands in for chart time, so a
        // key's timestamp and the lane lights use the same milliseconds as the chart.
        func play(to targetMs: Double) async {
            while timeMs < targetMs {
                timeMs = min(timeMs + Self.frameMs, targetMs)
                while nextInput < inputs.count, inputs[nextInput].timeMs <= timeMs {
                    let input = inputs[nextInput]
                    await clock.setAudioTimeMs(input.timeMs)
                    model.handleKeyboardInput(
                        key: input.key, isPressed: input.isPressed, isRepeat: false, hostTimeMs: input.timeMs
                    )
                    nextInput += 1
                }
                await clock.setAudioTimeMs(timeMs)
                await model.tick(atHostTimeMs: timeMs, presentationHostTimeMs: timeMs)
            }
        }

        for moment in Self.moments {
            await play(to: moment.timeMs)
            XCTAssertEqual(model.phase, .playing)
            try write(model: model, snapshotter: snapshotter, uiTimeMs: timeMs, to: directoryURL, name: moment.name)
        }
        try write(
            model: model, snapshotter: snapshotter, uiTimeMs: timeMs, to: directoryURL, name: "6-narrow",
            size: CGSize(width: 520, height: 760)
        )
        await model.pause()
        try write(model: model, snapshotter: snapshotter, uiTimeMs: timeMs, to: directoryURL, name: "5-paused")

        let counts = try XCTUnwrap(model.playFrame).score.tierCounts
        print("SCREENSHOT \(directoryURL.path): perfect \(counts.perfectCount), good \(counts.goodCount), miss \(counts.missCount)")
        XCTAssertEqual(counts.missCount, 1)
        await model.quitToSetup()
    }

    private func write(
        model: Mania4KPlaySessionModel,
        snapshotter: PlayfieldSnapshotter,
        uiTimeMs: Double,
        to directoryURL: URL,
        name: String,
        size: CGSize = PlayScreenScreenshotTests.screenSize
    ) throws {
        let snapshot = Mania4KPlayfieldSnapshot { size in
            snapshotter.image(model: model, uiTimeMs: uiTimeMs, size: size)
        }
        let content = Mania4KPlayExperienceView(model: model)
            .environment(\.colorScheme, .dark)
            .environment(\.mania4kPlayfieldSnapshot, snapshot)
            .frame(width: size.width, height: size.height)
        let renderer = ImageRenderer(content: content)
        renderer.scale = Self.scale
        let image = try XCTUnwrap(renderer.cgImage, "ImageRenderer produced no image for \(name)")

        let url = directoryURL.appendingPathComponent(name).appendingPathExtension("png")
        let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination), "writing \(url.path)")
    }

    private static func hitObjects() -> [Mania4KHitObject] {
        chart.flatMap { row in
            row.lanes.flatMap { lane -> [Mania4KHitObject] in
                let lane = Mania4KLane(rawValue: lane)!
                guard let endMs = row.endMs else {
                    return [Mania4KHitObject(lane: lane, timeMs: row.timeMs, kind: .tap)]
                }
                return [
                    Mania4KHitObject(lane: lane, timeMs: row.timeMs, kind: .holdStart),
                    Mania4KHitObject(lane: lane, timeMs: endMs, kind: .holdEnd)
                ]
            }
        }
        .sorted { ($0.timeMs, $0.lane.rawValue) < ($1.timeMs, $1.lane.rawValue) }
    }

    private static func inputLog() -> [(timeMs: Double, key: String, isPressed: Bool)] {
        let keys = ["d", "f", "j", "k"]
        var log: [(timeMs: Double, key: String, isPressed: Bool)] = []
        var noteIndex = 0
        for row in chart {
            for lane in row.lanes {
                defer { noteIndex += 1 }
                if row.timeMs == 5_400, lane == 3 {
                    continue
                }
                let pressMs = row.timeMs + hitErrorsMs[noteIndex % hitErrorsMs.count]
                let releaseMs = row.endMs.map { $0 + 5 } ?? pressMs + 60
                log.append((pressMs, keys[lane], true))
                log.append((releaseMs, keys[lane], false))
            }
        }
        return log.sorted { $0.timeMs < $1.timeMs }
    }
}

/// Draws the playfield's quads with the app's Metal renderer into a texture and reads it back as an image.
@MainActor
private final class PlayfieldSnapshotter {
    private let device: any MTLDevice
    private let renderer: Mania4KMetalRenderer
    private let scale: CGFloat

    init(device: any MTLDevice, scale: CGFloat) throws {
        self.device = device
        self.renderer = try Mania4KMetalRenderer(device: device, pixelFormat: .bgra8Unorm)
        self.scale = scale
    }

    func image(model: Mania4KPlaySessionModel, uiTimeMs: Double, size: CGSize) -> CGImage? {
        let width = Int((size.width * scale).rounded()), height = Int((size.height * scale).rounded())
        guard width > 0, height > 0 else {
            return nil
        }

        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false)
        descriptor.usage = .renderTarget
        descriptor.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        pass.colorAttachments[0].storeAction = .store

        let quads = Mania4KPlayfieldView.quads(model: model, uiTimeMs: uiTimeMs, size: size)
        renderer.draw(quads, viewportSize: size, pass: pass, drawable: nil)?.waitUntilCompleted()

        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        texture.getBytes(&bytes, bytesPerRow: width * 4, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else {
            return nil
        }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }
}
