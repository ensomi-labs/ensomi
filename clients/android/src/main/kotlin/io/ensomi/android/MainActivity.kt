package io.ensomi.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.ensomi.android.probe.CaptureProbe
import io.ensomi.android.probe.CaptureResult
import io.ensomi.android.probe.CorrelationRules
import io.ensomi.android.probe.CandidateVerdict
import io.ensomi.android.probe.DeviceProbe
import io.ensomi.android.probe.DeviceRecord
import io.ensomi.android.probe.DownloadWatcher
import io.ensomi.android.probe.FileSnapshot
import io.ensomi.android.probe.NetEaseProbe
import io.ensomi.android.probe.WatchEvent
import io.ensomi.android.probe.WatchKind
import io.ensomi.android.ui.EnsomiTheme
import io.ensomi.android.ui.ProbeActions
import io.ensomi.android.ui.ProbeScreen
import io.ensomi.android.ui.ProbeState
import io.ensomi.trace.TraceWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The probe harness. It runs the experiments the report asks for on the target device and writes one
 * JSONL trace, so a result can be tied to the build, the NetEase version and a monotonic timestamp.
 */
class MainActivity : ComponentActivity(), ProbeActions {
    private lateinit var state: ProbeState
    private lateinit var main: Handler
    private var trace: TraceWriter? = null
    private var watcher: DownloadWatcher? = null
    private var captureProbe: CaptureProbe? = null
    private var watchSessionStartWallMs = 0L
    private var eventCount = 0

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state.microphoneGranted = granted
            state.message = if (granted) "microphone granted" else "microphone denied; capture cannot start"
            emit("permission.microphone", detail = buildJsonObject { put("granted", granted) })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        main = Handler(Looper.getMainLooper())

        val traceDirectory = getExternalFilesDir("traces") ?: File(filesDir, "traces")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val traceFile = File(traceDirectory, "android-$stamp.jsonl")
        state = ProbeState(traceFile)

        state.microphoneGranted = hasMicrophone()
        state.allFilesGranted = hasAllFilesAccess()

        val device = DeviceProbe.read(this)
        state.device = device
        trace = TraceWriter(
            file = traceFile,
            process = "ensomi-android",
            neteaseVersionCode = device.netEase?.versionCode?.toString(),
            hyperosBuild = device.buildIncremental,
        ).open()

        captureProbe = CaptureProbe(getExternalFilesDir("captures") ?: File(filesDir, "captures"))

        emit("session.start", detail = deviceDetail(device))
        setContent { EnsomiTheme { ProbeScreen(state, this) } }
        handleProbeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProbeIntent(intent)
    }

    /**
     * Lets `adb shell am start` drive one probe step, so an experiment does not depend on tapping a
     * Compose button at a coordinate that moves between runs.
     */
    private fun handleProbeIntent(intent: Intent?) {
        when (intent?.getStringExtra("probe")) {
            "capture" -> capture(intent.getIntExtra("seconds", 5))
            "watch" -> {
                intent.getStringExtra("directory")?.let { state.watchDirectory = it }
                if (watcher?.isWatching != true) toggleWatching()
            }
            "links" -> probeDeepLinks()
            "recognition" -> openRecognition()
        }
    }

    override fun onDestroy() {
        watcher?.stop()
        emit("session.end")
        trace?.close()
        super.onDestroy()
    }

    // ---- actions -------------------------------------------------------------------------

    override fun freezeDevice() {
        val device = DeviceProbe.read(this)
        state.device = device
        state.microphoneGranted = hasMicrophone()
        state.allFilesGranted = hasAllFilesAccess()
        emit("device.frozen", detail = deviceDetail(device))
        state.message = "frozen ${device.buildIncremental} · NetEase ${device.netEase?.versionCode ?: "absent"}"
    }

    override fun requestMicrophone() {
        if (hasMicrophone()) {
            state.microphoneGranted = true
            state.message = "microphone already granted"
            return
        }
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            state.message = "all-files access is not needed below Android 11"
            return
        }
        if (hasAllFilesAccess()) {
            state.allFilesGranted = true
            state.message = "all-files access already granted"
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        state.message = "grant all-files access to watch the download directory by path"
    }

    override fun probeDeepLinks() {
        val lines = mutableListOf<String>()
        NetEaseProbe.links.forEach { link ->
            val uri = link.uri.replace("{songId}", SAMPLE_SONG_ID.toString())
            val resolution = NetEaseProbe.resolve(this, uri)
            val outcome = resolution.component ?: resolution.error ?: "unresolved"
            lines += "${link.label}: $outcome"
            emit(
                "link.resolve",
                songId = if (uri.contains(SAMPLE_SONG_ID.toString())) SAMPLE_SONG_ID else null,
                detail = buildJsonObject {
                    put("uri", uri)
                    put("component", resolution.component)
                    put("evidence", link.evidence.name)
                },
            )
        }
        state.netEaseLines = lines
        state.message = "resolved ${NetEaseProbe.links.size} URIs"
    }

    override fun openSong() {
        launch(NetEaseProbe.song(SAMPLE_SONG_ID), "link.launch", SAMPLE_SONG_ID)
    }

    override fun openRecognition() {
        launch(NetEaseProbe.IDENTIFY_URI, "recognition.launch", null)
    }

    override fun toggleWatching() {
        val active = watcher?.isWatching == true
        if (active) {
            watcher?.stop()
            state.watching = false
            emit("watch.stop")
            return
        }

        val directory = File(state.watchDirectory)
        if (!directory.isDirectory) {
            state.message = "not a directory: ${directory.absolutePath}"
            emit("watch.error", detail = buildJsonObject { put("path", directory.absolutePath) })
            return
        }
        if (!directory.canRead()) {
            state.message = "cannot read ${directory.absolutePath}; grant all-files access"
            emit("watch.error", detail = buildJsonObject {
                put("path", directory.absolutePath)
                put("reason", "unreadable")
            })
            return
        }

        watchSessionStartWallMs = System.currentTimeMillis()
        val created = DownloadWatcher(directory) { event -> main.post { onWatchEvent(event) } }
        runCatching { created.start() }
            .onFailure { error ->
                state.message = "watch failed: ${error.message}"
                emit("watch.error", detail = buildJsonObject { put("error", error.message) })
                return@onFailure
            }
        watcher = created
        state.watching = true
        state.message = "watching ${directory.absolutePath}"
        emit(
            "watch.start",
            detail = buildJsonObject {
                put("path", directory.absolutePath)
                put("events", "CREATE|MODIFY|CLOSE_WRITE|MOVED_TO|MOVED_FROM|DELETE")
            },
        )
    }

    override fun capture(seconds: Int) {
        if (state.capturing) return
        if (!hasMicrophone()) {
            state.message = "grant the microphone first"
            return
        }
        state.capturing = true
        state.message = null
        emit("capture.start", detail = buildJsonObject { put("seconds", seconds) })
        captureProbe?.capture(seconds) { result -> main.post { onCaptureFinished(result) } }
    }

    override fun note(text: String) {
        state.message = text
    }

    // ---- events --------------------------------------------------------------------------

    private fun onWatchEvent(event: WatchEvent) {
        val name = File(event.path).name
        state.watchLines = (state.watchLines + "${event.kind} · $name").takeLast(40)
        emit(
            "file.${event.kind.name.lowercase(Locale.US)}",
            detail = buildJsonObject {
                put("path", event.path)
                put("wall_ms", event.wallMs)
            },
        )
        if (event.kind == WatchKind.CLOSE_WRITE || event.kind == WatchKind.MOVED_TO) {
            judge(event, attempt = 0)
        }
    }

    /**
     * Applies the report's acceptance condition. A file that is still growing waits; the wait is a
     * delayed re-check rather than a sleep, so the trace keeps its ordering.
     */
    private fun judge(event: WatchEvent, attempt: Int) {
        val file = File(event.path)
        val snapshot = FileSnapshot(file.name, file.length(), file.lastModified())
        val (decodable, durationMs) = readAudio(file)
        val verdict = CorrelationRules.judge(
            event = event,
            snapshot = snapshot,
            sessionStartWallMs = watchSessionStartWallMs,
            decodeOk = decodable,
            durationMs = durationMs,
            expectedDurationMs = null,
            nowWallMs = System.currentTimeMillis(),
        )

        if (verdict == CandidateVerdict.WAIT_SIZE_STABLE && attempt < MAX_STABLE_RETRIES) {
            state.watchLines = (state.watchLines + "waiting for ${file.name} to settle").takeLast(40)
            main.postDelayed({ judge(event, attempt + 1) }, CorrelationRules.SIZE_STABLE_MS + 200)
            return
        }

        state.watchLines = (state.watchLines + "${verdict.name.lowercase(Locale.US)} · ${file.name}").takeLast(40)
        emit(
            if (verdict == CandidateVerdict.READY) "audio.ready" else "audio.candidate",
            detail = buildJsonObject {
                put("path", file.absolutePath)
                put("verdict", verdict.name)
                put("size_bytes", snapshot.sizeBytes)
                put("duration_ms", durationMs)
            },
        )
        if (verdict == CandidateVerdict.READY) {
            state.message = "audio ready: ${file.name}"
        }
    }

    private fun onCaptureFinished(result: Result<CaptureResult>) {
        state.capturing = false
        result
            .onSuccess { capture ->
                state.captures = listOf(capture) + state.captures
                state.message = "recorded ${capture.durationMs} ms, rms ${"%.1f".format(capture.rms)}"
                emit(
                    "capture.end",
                    captureDurationMs = capture.durationMs,
                    detail = buildJsonObject {
                        put("wav", capture.wavPath)
                        put("rms", capture.rms)
                        put("peak", capture.peak)
                        put("first_mono_ns", capture.firstReadNanos)
                        put("last_mono_ns", capture.lastReadNanos)
                        put("audio_stamp_frame", capture.timestampFramePosition)
                        put("audio_stamp_ns", capture.timestampNanos)
                        put("sha256", capture.sha256)
                    },
                )
            }
            .onFailure { error ->
                state.message = "capture failed: ${error.message}"
                emit("capture.failure", detail = buildJsonObject { put("error", error.message) })
            }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun launch(uri: String, event: String, songId: Long?) {
        val launch = NetEaseProbe.launch(this, uri)
        emit(
            event,
            songId = songId,
            detail = buildJsonObject {
                put("uri", uri)
                put("component", launch.component)
                put("started", launch.started)
                put("error", launch.error)
            },
        )
        state.message = when {
            launch.started -> "started $uri"
            else -> "could not start $uri: ${launch.error}"
        }
    }

    private fun readAudio(file: File): Pair<Boolean, Long?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            true to duration
        } catch (error: Exception) {
            false to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun emit(name: String, songId: Long? = null, captureDurationMs: Long? = null, detail: JsonObject? = null) {
        val writer = trace ?: return
        writer.event(
            name = name,
            tMonoNs = SystemClock.elapsedRealtimeNanos(),
            wallMs = System.currentTimeMillis(),
            songId = songId,
            captureDurationMs = captureDurationMs,
            detail = detail,
        )
        eventCount += 1
        state.traceLineCount = eventCount
    }

    private fun deviceDetail(device: DeviceRecord): JsonObject = buildJsonObject {
        put("manufacturer", device.manufacturer)
        put("model", device.model)
        put("device", device.device)
        put("android_release", device.release)
        put("sdk", device.sdk)
        put("fingerprint", device.fingerprint)
        put("build_incremental", device.buildIncremental)
        put("netease_version_name", device.netEase?.versionName)
        put("netease_version_code", device.netEase?.versionCode)
        put("netease_target_sdk", device.netEase?.targetSdk)
        put("netease_exported_activities", device.netEase?.exportedActivities?.size)
        put("netease_error", device.netEaseError)
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private companion object {
        const val SAMPLE_SONG_ID = 31_861_269L
        const val MAX_STABLE_RETRIES = 3
    }
}
