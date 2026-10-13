@_exported import EnsomiRuntime
import Foundation

/// One play judged by the gameplay core. The play session model feeds it key input and the frame clock; each call
/// that decides events also records them in the feedback the views read.
final class Mania4KGameplay {
    let session: GameplaySession
    let feedback: GameplayFeedback
    /// The last event decided, for the HUD.
    private(set) var latestJudgement: JudgementEvent?

    init(session: GameplaySession, feedback: GameplayFeedback = GameplayFeedback()) {
        self.session = session
        self.feedback = feedback
    }

    @discardableResult
    func input(_ input: InputEvent) -> [JudgementEvent] {
        let events = session.input(input: input)
        record(events, atChartTimeMs: input.timeMs)
        return events
    }

    /// Every input stamped at or before `watermarkMs` has been delivered. The events are recorded for the feedback
    /// at `frameTimeMs`, the chart time of the frame that shows them.
    @discardableResult
    func advance(to watermarkMs: Double, frameTimeMs: Double) -> [JudgementEvent] {
        let events = session.advance(watermarkMs: watermarkMs)
        record(events, atChartTimeMs: frameTimeMs)
        return events
    }

    private func record(_ events: [JudgementEvent], atChartTimeMs chartTimeMs: Double) {
        feedback.recordJudgementEvents(events: events, chartTimeMs: chartTimeMs)
        if let last = events.last {
            latestJudgement = last
        }
    }
}

extension Judgement {
    /// The word the player sees.
    public var title: String {
        switch self {
        case .perfect: return "Perfect"
        case .good: return "Good"
        case .miss: return "Miss"
        }
    }
}

extension Score {
    /// The score before any object is judged.
    public static let zero = Score(
        tierCounts: TierCounts(bigP: 0, p1: 0, p2: 0, p3: 0, g: 0, m: 0),
        accuracy: 1,
        combo: 0,
        maxCombo: 0,
        suggestedAudioOffsetMs: nil
    )
}
