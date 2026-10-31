package io.ensomi.android.recognition

import kotlinx.serialization.Serializable

/** How Ensomi starts the host music app. */
@Serializable
enum class LaunchPath {
    /** The launcher intent; the recognition page is still to open. */
    A,

    /** The deep link `orpheus://identify`; what is left of opening the page is the driver's. */
    B,

    /** Path B, and Path A if the deep link does not start. */
    AUTO,
}

/** What has to happen inside the host app, in order. */
@Serializable
enum class HostStep {
    /** The recognition page is open and listening. */
    OPEN_RECOGNITION,

    /** The page shows a result: report it with [HostReports.resultSeen]. */
    READ_RESULT,

    /** The song's download action was used; a song already downloaded is not downloaded twice. */
    TAP_DOWNLOAD,

    /** The song was shared to Ensomi, which is also how the person lands back here. */
    SHARE_TO_ENSOMI,
}

/**
 * What a driver, or anything else that watches the host app, reports to the session. Every call is an observation, not
 * a command: the session goes on from what it sees, in whatever order it sees it, and a share or a file arriving moves
 * it past any step that was never reported. Safe from any thread; a call after the session ended is dropped.
 */
interface HostReports {
    /** [step] has begun, with what the driver sees if it says anything useful for the trace. */
    fun stepStarted(step: HostStep, detail: String? = null)

    fun stepDone(step: HostStep, detail: String? = null)

    /** A driver cannot do [step]: the person is asked to, from this step on, and the session goes on. */
    fun handToPerson(step: HostStep, reason: String)

    /** The host shows this song as the recognition's result. */
    fun resultSeen(title: String, artist: String?, durationMs: Long? = null)

    /** The host says it did not recognize the music. */
    fun recognitionFailed(reason: String)

    /** The host refuses to download the song, for example because the account has no right to it. */
    fun downloadRefused(reason: String)
}

/**
 * What the pipeline needs from anything that acts inside the host app. Nothing in the session depends on a driver
 * doing anything: it asks for a step and goes on watching for what follows. [perform] returns at once and may be asked
 * for a later step while an earlier one has been handed to the person; [cancel] says the session no longer wants the
 * last step asked for.
 */
interface HostDriver {
    /** For the trace. */
    val name: String

    fun perform(step: HostStep, path: LaunchPath, reports: HostReports)

    fun cancel()
}

/** No automation: every step is the person's, and the screen says what to do. */
object ManualHostDriver : HostDriver {
    override val name = "manual"

    override fun perform(step: HostStep, path: LaunchPath, reports: HostReports) {
        reports.handToPerson(step, reason = "manual")
    }

    override fun cancel() = Unit
}

/**
 * The driver in use, and the session's [reports] for code that sees something without having been asked. A driver in
 * the same process installs itself when it is ready and removes itself when it goes; until then, and after, the person
 * does the steps. The reports are always there: with no session they drop what they get.
 */
object HostDrivers {
    @Volatile
    private var installed: HostDriver? = null

    /** Set once by the session's controller. */
    @Volatile
    internal var sink: HostReports = DroppedReports

    val current: HostDriver get() = installed ?: ManualHostDriver

    val reports: HostReports get() = ForwardingReports

    fun install(driver: HostDriver) {
        installed = driver
    }

    /** Removes [driver], unless another has replaced it. */
    fun remove(driver: HostDriver) {
        if (installed === driver) installed = null
    }

    private object ForwardingReports : HostReports {
        override fun stepStarted(step: HostStep, detail: String?) = sink.stepStarted(step, detail)

        override fun stepDone(step: HostStep, detail: String?) = sink.stepDone(step, detail)

        override fun handToPerson(step: HostStep, reason: String) = sink.handToPerson(step, reason)

        override fun resultSeen(title: String, artist: String?, durationMs: Long?) = sink.resultSeen(title, artist, durationMs)

        override fun recognitionFailed(reason: String) = sink.recognitionFailed(reason)

        override fun downloadRefused(reason: String) = sink.downloadRefused(reason)
    }

    private object DroppedReports : HostReports {
        override fun stepStarted(step: HostStep, detail: String?) = Unit

        override fun stepDone(step: HostStep, detail: String?) = Unit

        override fun handToPerson(step: HostStep, reason: String) = Unit

        override fun resultSeen(title: String, artist: String?, durationMs: Long?) = Unit

        override fun recognitionFailed(reason: String) = Unit

        override fun downloadRefused(reason: String) = Unit
    }
}
