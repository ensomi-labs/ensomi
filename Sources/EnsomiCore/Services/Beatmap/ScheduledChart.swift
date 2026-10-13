import EnsomiRuntime
import Foundation

/// A chart the runtime's `ChartScheduler` makes for play along with music heard in the room. Generation starts when
/// the chart is made; play starts later, wherever the music is by then.
@MainActor
public final class ScheduledChart {
    /// The scheduler, once `make` has made it.
    public private(set) var scheduler: ChartScheduler?
    /// The chart time play joined at, once it started.
    public private(set) var joinMs: Double?
    /// The first `work()`: with no play started, it generates the whole chart and returns `.done`.
    private var generation: Task<SchedulerAction, Error>?

    /// Makes the scheduler with `make` and starts generating at once.
    public init(make: @escaping @Sendable () async throws -> ChartScheduler) {
        generation = Task {
            let scheduler = try await make()
            self.scheduler = scheduler
            return try await scheduler.work()
        }
    }

    /// The whole chart, once made. Throws the generation's error, or `GeneratorError.Cancelled` once cancelled.
    public func result() async throws -> ChartResult {
        _ = try await generation?.value
        guard let result = scheduler?.result() else { throw GeneratorError.Cancelled }
        return result
    }

    /// Starts play judged by `session`, a new streaming session, once the chart is whole, from the chart time `heard`
    /// gives then. Returns when `session` holds the chart from the scheduler's join point: the first time at least its
    /// lead past that time with no hold open. Earlier objects are not in `session`.
    func start(session: GameplaySession, heard: () async -> Double) async throws {
        // The scheduler joins from the time `start` gives it, and the music goes on while the chart is made, so play
        // starts from the time heard once the chart is whole.
        _ = try await result()
        guard let scheduler else { throw GeneratorError.Cancelled }
        scheduler.start(atMs: await heard(), session: session)
        guard case .ready(let joinMs) = try await scheduler.work() else { throw GeneratorError.Cancelled }
        self.joinMs = joinMs
    }

    /// Each frame of play: the chart time heard at `wallMs` on the host clock.
    func heard(chartMs: Double, wallMs: Double) {
        scheduler?.heard(chartMs: chartMs, wallMs: wallMs)
    }

    /// Stops generation; a chart not made by then never is.
    public func cancel() {
        scheduler?.stop()
        generation?.cancel()
    }
}
