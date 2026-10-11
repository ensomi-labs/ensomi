import AVFoundation
import EnsomiRuntime
import Foundation
import os

public enum Mania4KDefaultPlaySettings {
    public static let scrollSpeed = 25.0
    /// What the engine clock leaves uncorrected: output latency the device does not report (the built-in speakers'
    /// stream latency and safety offset, about 13 ms) and keyboard latency.
    public static let audioOffsetMilliseconds = -20
    public static let visualOffsetMilliseconds = -15
    public static let offsetPresetID = UUID(uuidString: "D46D55AF-109E-48DC-A9F9-CE83F108EBF5")!
    public static let offsetPresetName = "default"
}

public enum Mania4KJudgeDifficulty: String, CaseIterable, Identifiable, Equatable, Sendable {
    case a = "A"
    case b = "B"
    case c = "C"
    case d = "D"
    case e = "E"

    public var id: String {
        rawValue
    }

    public var judge: Judge {
        switch self {
        case .a: return .a
        case .b: return .b
        case .c: return .c
        case .d: return .d
        case .e: return .e
        }
    }
}

public enum Mania4KChartSource: Equatable, Sendable {
    case localBeatmap(URL)
    case generated(displayName: String)

    public var beatmapFileURL: URL? {
        switch self {
        case .localBeatmap(let url):
            return url
        case .generated:
            return nil
        }
    }

    public var displayName: String {
        switch self {
        case .localBeatmap(let url):
            return url.deletingPathExtension().lastPathComponent
        case .generated(let displayName):
            return displayName
        }
    }
}

public struct Mania4KPlayConfiguration: Equatable, Sendable {
    public let chartSource: Mania4KChartSource
    public let audioFileURL: URL
    public let starDifficulty: Double
    public let scrollSpeed: Double
    public let audioOffsetMilliseconds: Double
    public let visualOffsetMilliseconds: Double
    public let judgeDifficulty: Mania4KJudgeDifficulty
    public let keyBindings: Mania4KKeyBindingSet

    public init(
        beatmapFileURL: URL,
        audioFileURL: URL,
        starDifficulty: Double,
        scrollSpeed: Double,
        audioOffsetMilliseconds: Double,
        visualOffsetMilliseconds: Double,
        judgeDifficulty: Mania4KJudgeDifficulty,
        keyBindings: Mania4KKeyBindingSet = .default
    ) {
        self.chartSource = .localBeatmap(beatmapFileURL)
        self.audioFileURL = audioFileURL
        self.starDifficulty = starDifficulty
        self.scrollSpeed = scrollSpeed
        self.audioOffsetMilliseconds = audioOffsetMilliseconds
        self.visualOffsetMilliseconds = visualOffsetMilliseconds
        self.judgeDifficulty = judgeDifficulty
        self.keyBindings = keyBindings
    }

    public init(
        chartSource: Mania4KChartSource,
        audioFileURL: URL,
        starDifficulty: Double,
        scrollSpeed: Double,
        audioOffsetMilliseconds: Double,
        visualOffsetMilliseconds: Double,
        judgeDifficulty: Mania4KJudgeDifficulty,
        keyBindings: Mania4KKeyBindingSet = .default
    ) {
        self.chartSource = chartSource
        self.audioFileURL = audioFileURL
        self.starDifficulty = starDifficulty
        self.scrollSpeed = scrollSpeed
        self.audioOffsetMilliseconds = audioOffsetMilliseconds
        self.visualOffsetMilliseconds = visualOffsetMilliseconds
        self.judgeDifficulty = judgeDifficulty
        self.keyBindings = keyBindings
    }

    public var beatmapFileURL: URL? {
        chartSource.beatmapFileURL
    }
}

public enum Mania4KLane: Int, CaseIterable, Identifiable, Comparable, Sendable {
    case left = 0
    case innerLeft = 1
    case innerRight = 2
    case right = 3

    public var id: Int {
        rawValue
    }

    public static func < (lhs: Mania4KLane, rhs: Mania4KLane) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    /// The lane as the gameplay core numbers it.
    public var index: UInt32 {
        UInt32(rawValue)
    }
}

public struct Mania4KKeyBindingSet: Equatable, Sendable {
    public static let `default` = Mania4KKeyBindingSet(keysByLane: [
        .left: "d",
        .innerLeft: "f",
        .innerRight: "j",
        .right: "k"
    ])!

    private let keysByLane: [Mania4KLane: String]

    public init?(keysByLane: [Mania4KLane: String]) {
        var normalizedKeysByLane: [Mania4KLane: String] = [:]
        var usedKeys: Set<String> = []

        for lane in Mania4KLane.allCases {
            guard let rawKey = keysByLane[lane] else {
                return nil
            }

            let normalizedKey = Self.normalizedKey(rawKey)
            guard !normalizedKey.isEmpty, !usedKeys.contains(normalizedKey) else {
                return nil
            }

            normalizedKeysByLane[lane] = normalizedKey
            usedKeys.insert(normalizedKey)
        }

        self.keysByLane = normalizedKeysByLane
    }

    public init?(storageValue: String) {
        let keys = storageValue.components(separatedBy: "\t")
        guard keys.count == Mania4KLane.allCases.count else {
            return nil
        }

        self.init(
            keysByLane: Dictionary(uniqueKeysWithValues: zip(Mania4KLane.allCases, keys))
        )
    }

    public var storageValue: String {
        Mania4KLane.allCases.map { key(for: $0) }.joined(separator: "\t")
    }

    public func key(for lane: Mania4KLane) -> String {
        keysByLane[lane] ?? Self.default.key(for: lane)
    }

    public func displayLabel(for lane: Mania4KLane) -> String {
        Self.displayLabel(for: key(for: lane))
    }

    public func lane(for key: String) -> Mania4KLane? {
        let normalizedKey = Self.normalizedKey(key)
        return Mania4KLane.allCases.first { self.key(for: $0) == normalizedKey }
    }

    public func updating(lane: Mania4KLane, key: String) -> Mania4KKeyBindingSet? {
        var nextKeysByLane = keysByLane
        nextKeysByLane[lane] = key
        return Mania4KKeyBindingSet(keysByLane: nextKeysByLane)
    }

    public static func normalizedKey(_ rawKey: String) -> String {
        rawKey.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func displayLabel(for key: String) -> String {
        guard key.count == 1 else {
            return key.uppercased()
        }

        return key.uppercased()
    }
}

public typealias Mania4KHitObjectKind = TokenKind

public struct Mania4KHitObject: Equatable, Sendable {
    public let lane: Mania4KLane
    public let timeMs: Double
    public let kind: Mania4KHitObjectKind

    public init(lane: Mania4KLane, timeMs: Double, kind: Mania4KHitObjectKind) {
        self.lane = lane
        self.timeMs = timeMs
        self.kind = kind
    }

    public var token: ChartToken {
        ChartToken(lane: lane.index, timeMs: timeMs, kind: kind)
    }

    /// The stream form of a chart: a tap, or a hold's start and end, ordered by time, then lane, then chart order.
    /// The chart must be one the gameplay core accepted, so its lanes are valid.
    public static func objects(of chart: Chart) -> [Mania4KHitObject] {
        var tokens: [(object: Mania4KHitObject, order: Int)] = []
        for source in chart.objects {
            let lane = Mania4KLane(rawValue: Int(source.lane))!
            let start = Double(source.startMs)
            if let end = source.endMs {
                tokens.append((Mania4KHitObject(lane: lane, timeMs: start, kind: .holdStart), tokens.count))
                tokens.append((Mania4KHitObject(lane: lane, timeMs: Double(end), kind: .holdEnd), tokens.count))
            } else {
                tokens.append((Mania4KHitObject(lane: lane, timeMs: start, kind: .tap), tokens.count))
            }
        }
        return tokens
            .sorted { ($0.object.timeMs, $0.object.lane, $0.order) < ($1.object.timeMs, $1.object.lane, $1.order) }
            .map(\.object)
    }
}

public struct Mania4KChartMetadata: Equatable, Sendable {
    public let title: String
    public let artist: String?
    public let sourceDescription: String
    public let objectCount: Int?
    public let durationMs: Double?

    public init(
        title: String,
        artist: String? = nil,
        sourceDescription: String,
        objectCount: Int? = nil,
        durationMs: Double? = nil
    ) {
        self.title = title
        self.artist = artist
        self.sourceDescription = sourceDescription
        self.objectCount = objectCount
        self.durationMs = durationMs
    }
}

public protocol Mania4KHitObjectStreaming: Sendable {
    func cancel() async
    func prepare() async throws -> Mania4KChartMetadata
    func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch
}

public extension Mania4KHitObjectStreaming {
    func cancel() async {}
}

public struct Mania4KHitObjectStreamCursor: Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct Mania4KHitObjectBatch: Equatable, Sendable {
    public let objects: [Mania4KHitObject]
    public let nextCursor: Mania4KHitObjectStreamCursor?
    public let completeThroughChartTimeMs: Double
    public let isEndOfStream: Bool

    public init(
        objects: [Mania4KHitObject],
        nextCursor: Mania4KHitObjectStreamCursor?,
        completeThroughChartTimeMs: Double,
        isEndOfStream: Bool
    ) {
        self.objects = objects
        self.nextCursor = nextCursor
        self.completeThroughChartTimeMs = completeThroughChartTimeMs
        self.isEndOfStream = isEndOfStream
    }
}

public enum Mania4KInputSource: Equatable, Sendable {
    case keyboard
    case touch
    case replay
    case test
}

public struct Mania4KInputEvent: Equatable, Sendable {
    public let lane: Mania4KLane
    public let phase: Phase
    public let chartTimeMs: Double
    public let sequenceNumber: UInt64
    public let source: Mania4KInputSource

    public init(
        lane: Mania4KLane,
        phase: Phase,
        chartTimeMs: Double,
        sequenceNumber: UInt64,
        source: Mania4KInputSource
    ) {
        self.lane = lane
        self.phase = phase
        self.chartTimeMs = chartTimeMs
        self.sequenceNumber = sequenceNumber
        self.source = source
    }
}

public enum Mania4KPlayPhase: Equatable, Sendable {
    case setup
    case loading
    case ready
    case playing
    case paused
    case finished(Mania4KPlayResult)
    case failed(Mania4KPlayFailure)
}

public struct Mania4KPlayResult: Equatable, Sendable {
    public let metadata: Mania4KChartMetadata
    public let score: Score
    public let finishedChartTimeMs: Double

    public init(metadata: Mania4KChartMetadata, score: Score, finishedChartTimeMs: Double) {
        self.metadata = metadata
        self.score = score
        self.finishedChartTimeMs = finishedChartTimeMs
    }
}

public struct Mania4KPlayFrame: Equatable, Sendable {
    public let gameplayChartTimeMs: Double
    public let renderChartTimeMs: Double
    public let scrollTimeMs: Double
    public let metadata: Mania4KChartMetadata
    public let visibleObjects: [VisibleObject]
    public let score: Score
    public let laneStates: [LaneState]
    public let latestJudgement: JudgementEvent?

    public init(
        gameplayChartTimeMs: Double,
        renderChartTimeMs: Double,
        scrollTimeMs: Double,
        metadata: Mania4KChartMetadata,
        visibleObjects: [VisibleObject],
        score: Score,
        laneStates: [LaneState],
        latestJudgement: JudgementEvent?
    ) {
        self.gameplayChartTimeMs = gameplayChartTimeMs
        self.renderChartTimeMs = renderChartTimeMs
        self.scrollTimeMs = scrollTimeMs
        self.metadata = metadata
        self.visibleObjects = visibleObjects
        self.score = score
        self.laneStates = laneStates
        self.latestJudgement = latestJudgement
    }
}

public enum Mania4KPlayFailure: Equatable, Error, Sendable {
    case audioPrepareFailed(String)
    case chartPrepareFailed(Mania4KChartValidationError)
    case streamFailed(String)
    case engineRejectedObjects(Mania4KChartValidationError)
}

/// A chart or beatmap the gameplay core rejected, with the core's error and the text the player sees.
public enum Mania4KChartValidationError: Equatable, Error, Sendable {
    case chart(ChartError)
    case osu(OsuError)
}

extension Mania4KPlayFailure: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .audioPrepareFailed(let message):
            return "Audio could not be prepared: \(message)"
        case .chartPrepareFailed(let error):
            return "Chart could not be prepared: \(error.localizedDescription)"
        case .streamFailed(let message):
            return "Chart stream failed: \(message)"
        case .engineRejectedObjects(let error):
            return "Chart was rejected by the engine: \(error.localizedDescription)"
        }
    }
}

extension Mania4KChartValidationError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .chart(let error):
            return Self.message(for: error)
        case .osu(.UnsupportedMode):
            return "Only osu!mania mode is supported."
        case .osu(.UnsupportedKeyCount(let keyCount)):
            return "Only 4-key osu!mania charts are supported; this chart has \(keyCount) keys."
        case .osu(.UnsupportedHitObject(let line)):
            return "Unsupported hit object: \(line)."
        case .osu(.InvalidChart(let error)):
            return Self.message(for: error)
        }
    }

    private static func message(for error: ChartError) -> String {
        switch error {
        case .InvalidLane(let object):
            return "Object \(object) is not in one of the four lanes."
        case .InvalidHold(let object):
            return "Hold \(object) ends before it starts."
        case .OutsideDuration(let object):
            return "Object \(object) lies beyond the end of the chart."
        case .NotSorted(let object):
            return "Object \(object) is out of order."
        case .SameLaneOverlap(let object):
            return "Object \(object) overlaps the previous object in its lane."
        case .InvalidTime(let object):
            return "Object \(object) has an invalid time."
        case .BeforeCoverage(let object):
            return "Object \(object) arrived after the stream had declared its time complete."
        case .HoldEndWithoutStart(let lane):
            return "A hold end in lane \(lane) has no start."
        case .UnclosedHold(let object):
            return "Hold \(object) was not closed."
        }
    }
}

public protocol Mania4KAudioClock: Sendable {
    func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata
    func play() async throws
    func pause() async
    func stop() async
    func currentAudioTimeMs() async -> Double
    /// The audio time heard at `hostTimeMs` (`EnsomiHostClock` milliseconds), past or future.
    /// Never suspends, so a key event's timestamp converts where the event is handled.
    func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double
    func isRunning() async -> Bool
}

public struct Mania4KAudioMetadata: Equatable, Sendable {
    public let durationMs: Double?
    public let title: String?

    public init(durationMs: Double? = nil, title: String? = nil) {
        self.durationMs = durationMs
        self.title = title
    }
}

public actor FakeMania4KAudioClock: Mania4KAudioClock {
    private let metadata: Mania4KAudioMetadata
    private let storedAudioTimeMs: OSAllocatedUnfairLock<Double>
    private var running: Bool
    private let prepareError: Error?

    public init(
        metadata: Mania4KAudioMetadata = Mania4KAudioMetadata(durationMs: 10_000, title: "Fake Audio"),
        audioTimeMs: Double = 0,
        isRunning: Bool = false,
        prepareError: Error? = nil
    ) {
        self.metadata = metadata
        self.storedAudioTimeMs = OSAllocatedUnfairLock(initialState: audioTimeMs)
        self.running = isRunning
        self.prepareError = prepareError
    }

    public func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        if let prepareError {
            throw prepareError
        }

        storedAudioTimeMs.withLock { $0 = 0 }
        return metadata
    }

    public func play() async throws {
        running = true
    }

    public func pause() async {
        running = false
    }

    public func stop() async {
        running = false
        storedAudioTimeMs.withLock { $0 = 0 }
    }

    public func currentAudioTimeMs() async -> Double {
        storedAudioTimeMs.withLock { $0 }
    }

    /// The fake stands still: every host time reads the time last set.
    public nonisolated func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        storedAudioTimeMs.withLock { $0 }
    }

    public func isRunning() async -> Bool {
        running
    }

    public func setAudioTimeMs(_ audioTimeMs: Double) {
        storedAudioTimeMs.withLock { $0 = audioTimeMs }
    }

    public func advance(by milliseconds: Double) {
        storedAudioTimeMs.withLock { $0 += milliseconds }
    }

    public func setRunning(_ running: Bool) {
        self.running = running
    }
}

public actor HostTimeAnchoredMania4KAudioClock: Mania4KAudioClock {
    public typealias HostTimeProvider = @Sendable () -> Double

    private let referenceTimeAtAnchorMS: Double
    private let anchorHostTimeMS: Double
    private let hostTimeProvider: HostTimeProvider
    private let metadata: Mania4KAudioMetadata
    private var running: Bool

    public init(
        referenceTimeAtAnchorMS: Double,
        durationMS: Double? = nil,
        title: String? = nil,
        anchorHostTimeMS: Double = EnsomiHostClock.currentTimeMS(),
        hostTimeProvider: @escaping HostTimeProvider = EnsomiHostClock.currentTimeMS
    ) {
        self.referenceTimeAtAnchorMS = referenceTimeAtAnchorMS
        self.anchorHostTimeMS = anchorHostTimeMS
        self.hostTimeProvider = hostTimeProvider
        self.metadata = Mania4KAudioMetadata(durationMs: durationMS, title: title)
        self.running = false
    }

    public func prepare(audioFileURL: URL) async throws -> Mania4KAudioMetadata {
        metadata
    }

    public func play() async throws {
        running = true
    }

    public func pause() async {
        running = false
    }

    public func stop() async {
        running = false
    }

    public func currentAudioTimeMs() async -> Double {
        audioTimeMs(atHostTimeMs: hostTimeProvider())
    }

    public nonisolated func audioTimeMs(atHostTimeMs hostTimeMs: Double) -> Double {
        let projectedTimeMS = referenceTimeAtAnchorMS + hostTimeMs - anchorHostTimeMS
        guard let durationMs = metadata.durationMs else {
            return max(0, projectedTimeMS)
        }

        return min(max(0, projectedTimeMS), durationMs)
    }

    public func isRunning() async -> Bool {
        running
    }
}

public struct Mania4KKeyboardInputRouter: Sendable {
    private var keyBindings: Mania4KKeyBindingSet
    private var pressedLanes: Set<Mania4KLane>

    public init(keyBindings: Mania4KKeyBindingSet = .default) {
        self.keyBindings = keyBindings
        self.pressedLanes = []
    }

    public mutating func route(
        key: String,
        isPressed: Bool,
        isRepeat: Bool,
        chartTimeMs: Double,
        sequenceNumber: UInt64,
        source: Mania4KInputSource = .keyboard
    ) -> Mania4KInputEvent? {
        guard let lane = keyBindings.lane(for: key) else {
            return nil
        }

        if isPressed {
            guard !isRepeat, !pressedLanes.contains(lane) else {
                return nil
            }

            pressedLanes.insert(lane)
            return Mania4KInputEvent(
                lane: lane,
                phase: .press,
                chartTimeMs: chartTimeMs,
                sequenceNumber: sequenceNumber,
                source: source
            )
        } else {
            guard pressedLanes.contains(lane) else {
                return nil
            }

            pressedLanes.remove(lane)
            return Mania4KInputEvent(
                lane: lane,
                phase: .release,
                chartTimeMs: chartTimeMs,
                sequenceNumber: sequenceNumber,
                source: source
            )
        }
    }

    public mutating func reset() {
        pressedLanes.removeAll()
    }

    public mutating func updateKeyBindings(_ keyBindings: Mania4KKeyBindingSet) {
        self.keyBindings = keyBindings
        reset()
    }

    public static func lane(for key: String) -> Mania4KLane? {
        Mania4KKeyBindingSet.default.lane(for: key)
    }
}

public actor InMemoryMania4KHitObjectStream: Mania4KHitObjectStreaming {
    private let objects: [Mania4KHitObject]
    private let metadata: Mania4KChartMetadata

    public init(objects: [Mania4KHitObject], metadata: Mania4KChartMetadata? = nil) {
        self.objects = objects
        let duration = objects.map(\.timeMs).max()
        self.metadata = metadata ?? Mania4KChartMetadata(
            title: "In-memory mania4k chart",
            sourceDescription: "In-memory",
            objectCount: objects.filter { $0.kind != .holdEnd }.count,
            durationMs: duration
        )
    }

    public func prepare() async throws -> Mania4KChartMetadata {
        metadata
    }

    public func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        readFromArray(objects, cursor: cursor, throughChartTimeMs: throughChartTimeMs, limit: limit, finalDurationMs: metadata.durationMs)
    }
}

public actor OsuMania4KBeatmapStream: Mania4KHitObjectStreaming {
    private let beatmapFileURL: URL
    private var parsedObjects: [Mania4KHitObject]?
    private var parsedMetadata: Mania4KChartMetadata?

    public init(beatmapFileURL: URL) {
        self.beatmapFileURL = beatmapFileURL
    }

    public func prepare() async throws -> Mania4KChartMetadata {
        let text = try String(contentsOf: beatmapFileURL, encoding: .utf8)
        let beatmap: OsuBeatmap
        do {
            beatmap = try parseOsu(text: text)
        } catch let error as OsuError {
            throw Mania4KChartValidationError.osu(error)
        }

        let metadata = Mania4KChartMetadata(
            title: beatmap.title ?? beatmapFileURL.deletingPathExtension().lastPathComponent,
            artist: beatmap.artist,
            sourceDescription: beatmapFileURL.lastPathComponent,
            objectCount: beatmap.chart.objects.count,
            durationMs: Double(beatmap.chart.durationMs)
        )
        parsedObjects = Mania4KHitObject.objects(of: beatmap.chart)
        parsedMetadata = metadata
        return metadata
    }

    public func read(
        after cursor: Mania4KHitObjectStreamCursor?,
        throughChartTimeMs: Double,
        limit: Int
    ) async throws -> Mania4KHitObjectBatch {
        guard let parsedObjects, let parsedMetadata else {
            throw Mania4KPlayFailure.streamFailed("Beatmap stream has not been prepared.")
        }

        return readFromArray(
            parsedObjects,
            cursor: cursor,
            throughChartTimeMs: throughChartTimeMs,
            limit: limit,
            finalDurationMs: parsedMetadata.durationMs
        )
    }
}

private func readFromArray(
    _ objects: [Mania4KHitObject],
    cursor: Mania4KHitObjectStreamCursor?,
    throughChartTimeMs: Double,
    limit: Int,
    finalDurationMs: Double?
) -> Mania4KHitObjectBatch {
    let startIndex = max(Int(cursor?.rawValue ?? "0") ?? 0, 0)
    let safeLimit = max(limit, 1)
    var nextIndex = startIndex
    var emitted: [Mania4KHitObject] = []

    while nextIndex < objects.count,
          objects[nextIndex].timeMs <= throughChartTimeMs,
          emitted.count < safeLimit {
        emitted.append(objects[nextIndex])
        nextIndex += 1
    }

    let isEnd = nextIndex >= objects.count
    let nextCursor = isEnd ? nil : Mania4KHitObjectStreamCursor(rawValue: String(nextIndex))
    let completeThrough: Double
    if isEnd {
        completeThrough = max(throughChartTimeMs, finalDurationMs ?? throughChartTimeMs)
    } else if objects[nextIndex].timeMs <= throughChartTimeMs {
        completeThrough = objects[nextIndex].timeMs.nextDown
    } else {
        completeThrough = throughChartTimeMs
    }

    return Mania4KHitObjectBatch(
        objects: emitted,
        nextCursor: nextCursor,
        completeThroughChartTimeMs: completeThrough,
        isEndOfStream: isEnd
    )
}
