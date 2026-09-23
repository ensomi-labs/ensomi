import Foundation

public enum RecognitionProvider: String, CaseIterable, Sendable {
    case acrCloud

    public var displayName: String {
        switch self {
        case .acrCloud:
            return "ACRCloud"
        }
    }
}

public struct ACRCloudConfiguration: Equatable, Sendable {
    public let host: String
    public let accessKey: String
    public let accessSecret: String

    public init(host: String, accessKey: String, accessSecret: String) {
        self.host = host
        self.accessKey = accessKey
        self.accessSecret = accessSecret
    }

    public var isComplete: Bool {
        !host.isEmpty && !accessKey.isEmpty && !accessSecret.isEmpty
    }
}

public struct RecognitionAudioClip: Equatable, Sendable {
    public let fileURL: URL
    public let mimeType: String
    public let duration: TimeInterval
    public let recordedAt: Date

    public init(
        fileURL: URL,
        mimeType: String,
        duration: TimeInterval,
        recordedAt: Date
    ) {
        self.fileURL = fileURL
        self.mimeType = mimeType
        self.duration = duration
        self.recordedAt = recordedAt
    }
}

public protocol RecognitionAudioCapturing: Sendable {
    func prepare() async
    func captureClip(duration: TimeInterval) async -> Result<RecognitionAudioClip, RecognitionFailure>
    func cancelCapture() async
}

public protocol RecognitionProviderClient: Sendable {
    var provider: RecognitionProvider { get }
    var backendLabel: String { get }

    func prepare() async
    func recognize(clip: RecognitionAudioClip) async -> RecognitionOutcome
    func cancelRecognition() async
}
