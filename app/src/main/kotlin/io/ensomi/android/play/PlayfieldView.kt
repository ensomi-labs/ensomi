package io.ensomi.android.play

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.Trace
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.ensomi.android.R
import java.io.File
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playfield: a `SurfaceView` drawn by its own render thread, which `Choreographer.postVsyncCallback` paces once
 * per display refresh. Touches arrive here on the main thread, are stamped with their event time and lane, and go to
 * the render thread's queue; the render thread judges and draws.
 *
 * [controllerFactory] runs on the render thread when the surface is first created. The controller, and the game with
 * it, outlives the surface: when the app goes to the background the surface goes and the render thread with it, and
 * the next surface's thread goes on with the same paused game. The controller is closed when the view is detached.
 */
class PlayfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    var controllerFactory: ((MutableStateFlow<PlayHud>, () -> Long) -> PlayController)? = null

    /** Keeps the HUD clear of a display cutout and the key tray above the system's gesture area, in pixels. */
    var insets: Pair<Float, Float> = 0f to 0f
        set(value) {
            field = value
            updateGeometry(width, height)
        }

    /** The room left above the lanes for the HUD row; the calibration's playfield has none. */
    var hudHeightDp = PlayfieldGeometry.HUD_HEIGHT_DP
        set(value) {
            field = value
            updateGeometry(width, height)
        }

    /**
     * How far the lanes lean, as the far end's width over the line's (1 for none), when the surface is wider than tall
     * and when it is not; the surface's shape picks one, so a large screen that keeps its own orientation still gets
     * the matching lean. Either can change while the view runs.
     */
    var landscapeFarScale = 1f
        set(value) {
            field = value
            updateGeometry(width, height)
        }
    var portraitFarScale = 1f
        set(value) {
            field = value
            updateGeometry(width, height)
        }

    /**
     * A preview draws a whole screen of [previewSize] pixels (the phone's own, in the orientation play will use) into
     * a surface buffer of that size, which the system scales down to the view; so the preview is the game's layout,
     * only smaller. A preview takes no touches.
     */
    var previewSize: Pair<Int, Int>? = null
        set(value) {
            field = value
            if (value != null) holder.setFixedSize(value.first, value.second) else holder.setSizeFromLayout()
            updateGeometry(width, height)
        }

    /** Overrides the game's visual offset while the playfield runs: the calibration's slider. */
    @Volatile
    var visualOffsetMs: Double? = null
        set(value) {
            field = value
            if (value != null) controller?.visualOffsetMs = value
        }

    /** Overrides the game's scroll time while the playfield runs: the settings' preview follows the speed slider. */
    @Volatile
    var scrollTimeMs: Double? = null
        set(value) {
            field = value
            if (value != null) controller?.scrollTimeMs = value
        }

    var reduceMotion = false
        set(value) {
            field = value
            renderer.reduceMotion = value
        }

    /** "Vibrate on lane touch": a `VIRTUAL_KEY` tick as a finger lands on a lane, as the player's touch feedback allows. */
    var vibrateOnTouch = false

    /** Debug builds: where every frame's times go, as CSV, when the playfield closes ([FrameLog]); null for none. */
    var frameLogFile: File? = null
    private val frameLog = FrameLog()

    private val mutableHud = MutableStateFlow(PlayHud())
    val hud: StateFlow<PlayHud> = mutableHud.asStateFlow()

    private val sequence = AtomicLong()
    private val tracker = LaneTouchTracker()
    private val renderer = PlayfieldRenderer(playfieldWords(context))

    @Volatile
    private var geometry: PlayfieldGeometry? = null

    @Volatile
    private var controller: PlayController? = null
    private var renderThread: RenderThread? = null

    init {
        holder.addCallback(this)
        contentDescription = context.getString(R.string.play_playfield_description)
    }

    /** Set when [pause] comes before the render thread has made its controller. */
    @Volatile
    private var pausePending = false

    fun pause() {
        pausePending = true
        controller?.clock?.pause(System.nanoTime())
        // Lanes still down are released, so nothing stays pressed across the pause.
        tracker.cancel()
    }

    /** Whether the song has not started yet: the lead-in, or before the render thread has begun. */
    fun inLeadIn(): Boolean = (controller?.clock?.songTimeMs(System.nanoTime()) ?: -1.0) < 0

    fun resume() {
        pausePending = false
        controller?.clock?.resume(System.nanoTime())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(w, h)
    }

    private fun updateGeometry(viewWidth: Int, viewHeight: Int) {
        val (w, h) = previewSize ?: (viewWidth to viewHeight)
        if (w <= 0 || h <= 0) return
        val (top, bottom) = if (previewSize != null) 0f to 0f else insets
        val g = PlayfieldGeometry.of(
            w.toFloat(), h.toFloat(), resources.displayMetrics.density, top, bottom, hudHeightDp,
            if (w > h) landscapeFarScale else portraitFarScale,
        )
        geometry = g
        if (previewSize == null && excludeBackGesture) systemGestureExclusionRects = backGestureExclusion(g)
    }

    /** During play the outer lanes' lower part is kept from the back gesture (the system caps each edge's share). */
    var excludeBackGesture = false
        set(value) {
            field = value
            updateGeometry(width, height)
            if (!value) systemGestureExclusionRects = emptyList()
        }

    private fun backGestureExclusion(g: PlayfieldGeometry): List<Rect> {
        val bottom = g.height.toInt()
        val top = (bottom - g.dp(EXCLUSION_HEIGHT_DP)).toInt().coerceAtLeast(0)
        val laneEnd = (g.laneLeft(1)).toInt()
        val laneStart = (g.laneLeft(PlayfieldGeometry.LANES - 1)).toInt()
        return listOf(Rect(0, top, laneEnd, bottom), Rect(laneStart, top, g.width.toInt(), bottom))
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestHighestFrameRate(holder.surface)
        val factory = controllerFactory ?: return
        renderThread = RenderThread(holder) { factory(mutableHud) { sequence.incrementAndGet() } }.also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The surface must not be drawn after this returns: stop the thread and wait for it. The game stays.
        renderThread?.quitAndJoin()
        renderThread = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderThread?.quitAndJoin()
        renderThread = null
        // The session and the sound are let go off the main thread; no frame uses them any more.
        val closing = controller ?: return
        controller = null
        val logFile = frameLogFile
        Thread({
            closing.close()
            if (logFile != null) {
                Log.i(TAG, "Frames: ${frameLog.summary()}")
                runCatching { logFile.writeText(frameLog.csv()) }
            }
        }, "EnsomiPlayfieldClose").start()
    }

    /**
     * Asks for the display's fastest refresh rate while the playfield is shown (`Surface.setFrameRate`). The
     * system may refuse, for example in battery saver; the game draws for whatever rate it gets.
     */
    private fun requestHighestFrameRate(surface: Surface) {
        val shown = display ?: return
        val modes = shown.supportedModes.map { it.refreshRate }.distinct().sorted()
        val highest = modes.maxOrNull() ?: return
        surface.setFrameRate(highest, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "Frame rate: asked for %.1f Hz (modes %s, only if seamless); the display runs at %.1f Hz",
                highest, modes.joinToString { String.format(Locale.ROOT, "%.1f", it) }, shown.refreshRate,
            ),
        )
    }

    /**
     * Each pointer is a finger on a lane: its lane comes from where it went down. The time is the event's own,
     * `getEventTimeNanos()` (API 34; milliseconds before), not when it was handled. Lanes are touch targets, not
     * clickable views, so there is no click to perform.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (previewSize != null) return false
        val g = geometry ?: return true
        val target = controller ?: return true
        val nanos = eventNanos(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val lane = g.laneAt(event.getX(index))
                if (tracker.down(event.getPointerId(index), lane)) {
                    target.enqueue(LaneInput(lane, press = true, nanos, sequence.incrementAndGet()))
                    if (vibrateOnTouch) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // A pointer the system cancelled (FLAG_CANCELED, a palm for example) still lifts its lane.
                tracker.up(event.getPointerId(event.actionIndex))?.let { lane ->
                    target.enqueue(LaneInput(lane, press = false, nanos, sequence.incrementAndGet()))
                }
            }
            MotionEvent.ACTION_CANCEL -> tracker.cancel().forEach { lane ->
                target.enqueue(LaneInput(lane, press = false, nanos, sequence.incrementAndGet()))
            }
        }
        return true
    }

    private fun eventNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) event.eventTimeNanos else event.eventTime * 1_000_000

    /**
     * A thread with its own `Looper` and `Choreographer`, and the 8 MiB stack the runtime's worker uses, since the
     * runtime is called from it. Each vsync it runs one frame for the frame's expected presentation time and draws it
     * with `lockHardwareCanvas`.
     */
    private inner class RenderThread(
        private val surfaceHolder: SurfaceHolder,
        private val makeController: () -> PlayController,
    ) : Thread(null, null, "EnsomiPlayfield", 8L shl 20) {
        private val ready = CountDownLatch(1)

        @Volatile
        private var looper: Looper? = null

        @Volatile
        private var running = true

        override fun run() {
            Looper.prepare()
            looper = Looper.myLooper()
            // The game of an earlier surface goes on; otherwise this thread makes it and starts it.
            val playController = controller ?: try {
                makeController().also {
                    controller = it
                    it.start(System.nanoTime())
                    if (pausePending) it.clock.pause(System.nanoTime())
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "Playfield could not start", failure)
                ready.countDown()
                return
            }
            visualOffsetMs?.let { playController.visualOffsetMs = it }
            scrollTimeMs?.let { playController.scrollTimeMs = it }
            ready.countDown()
            val choreographer = Choreographer.getInstance()
            val stats = FrameStats { display?.refreshRate ?: 60f }
            val log = frameLog.takeIf { frameLogFile != null }
            val callback = object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    if (!running) return
                    val timeline = data.preferredFrameTimeline
                    drawFrame(playController, timeline.expectedPresentationTimeNanos, timeline.deadlineNanos, data.frameTimeNanos, stats, log)
                    choreographer.postVsyncCallback(this)
                }
            }
            choreographer.postVsyncCallback(callback)
            Looper.loop()
        }

        /**
         * One frame for [presentNanos], due at [deadlineNanos]. Its parts are trace sections (`EnsomiFrame`, with
         * `EnsomiRuntime` and `EnsomiDraw` inside), so a Perfetto trace shows them beside the frame timeline.
         */
        private fun drawFrame(
            playController: PlayController,
            presentNanos: Long,
            deadlineNanos: Long,
            vsyncNanos: Long,
            stats: FrameStats,
            log: FrameLog?,
        ) {
            val g = geometry ?: return
            Trace.beginSection("EnsomiFrame")
            try {
                val start = System.nanoTime()
                Trace.beginSection("EnsomiRuntime")
                val frame = try {
                    playController.frame(start, presentNanos)
                } finally {
                    Trace.endSection()
                }
                val runtimeDone = System.nanoTime()
                Trace.beginSection("EnsomiDraw")
                try {
                    val canvas = try {
                        surfaceHolder.lockHardwareCanvas()
                    } catch (failure: IllegalStateException) {
                        Log.w(TAG, "Playfield surface not ready", failure)
                        null
                    } ?: return
                    try {
                        renderer.draw(canvas, frame, g)
                    } finally {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    }
                } finally {
                    Trace.endSection()
                }
                val done = System.nanoTime()
                stats.record(vsyncNanos, runtimeDone - start, done - runtimeDone, late = done > deadlineNanos)
                log?.record(vsyncNanos, presentNanos, runtimeDone - start, done - runtimeDone, deadlineNanos, done)
            } finally {
                Trace.endSection()
            }
        }

        fun quitAndJoin() {
            running = false
            ready.await()
            looper?.quitSafely()
            join()
        }
    }

    private companion object {
        const val TAG = "EnsomiPlayfield"

        /** The height of each edge asked to be kept from the back gesture; Android grants up to 200 dp per edge. */
        const val EXCLUSION_HEIGHT_DP = 200f
    }
}

/**
 * The render thread's own account of pacing, logged under `EnsomiPlayfield` every two seconds: frames drawn, the
 * display's refresh rate, vsyncs that came more than 1.5 refresh intervals after the previous one (frames the thread
 * missed), frames finished after their deadline, the time each frame spent in the runtime (inputs, advance, reads) and
 * in drawing (lock, draw, post) on average and at most, and the whole frame's work at the median and the 99th
 * percentile (gameplay.md's target is 3 ms at p99). It allocates nothing per frame.
 */
private class FrameStats(private val refreshRateHz: () -> Float) {
    private var intervalNanos = (1e9 / refreshRateHz()).toLong()
    private var windowStartNanos = 0L
    private var frames = 0
    private var missed = 0
    private var late = 0
    private var lastVsyncNanos = 0L
    private var runtimeSumNanos = 0L
    private var runtimeMaxNanos = 0L
    private var drawSumNanos = 0L
    private var drawMaxNanos = 0L
    private val work = LongArray(MAX_FRAMES)

    fun record(vsyncNanos: Long, runtimeNanos: Long, drawNanos: Long, late: Boolean) {
        if (windowStartNanos == 0L) windowStartNanos = vsyncNanos
        if (lastVsyncNanos != 0L && vsyncNanos - lastVsyncNanos > intervalNanos * 3 / 2) missed++
        lastVsyncNanos = vsyncNanos
        if (late) this.late++
        if (frames < MAX_FRAMES) work[frames] = runtimeNanos + drawNanos
        frames++
        runtimeSumNanos += runtimeNanos
        runtimeMaxNanos = maxOf(runtimeMaxNanos, runtimeNanos)
        drawSumNanos += drawNanos
        drawMaxNanos = maxOf(drawMaxNanos, drawNanos)
        val elapsed = vsyncNanos - windowStartNanos
        if (elapsed >= WINDOW_NANOS) {
            val counted = minOf(frames, MAX_FRAMES)
            Arrays.sort(work, 0, counted)
            val refresh = refreshRateHz()
            Log.i(
                "EnsomiPlayfield",
                String.format(
                    Locale.ROOT,
                    "fps=%.1f frames=%d refresh=%.1fHz missed=%d late=%d runtime_avg=%.2fms runtime_max=%.2fms " +
                        "draw_avg=%.2fms draw_max=%.2fms work_p50=%.2fms work_p99=%.2fms",
                    frames * 1e9 / elapsed, frames, refresh, missed, this.late,
                    runtimeSumNanos / 1e6 / frames, runtimeMaxNanos / 1e6, drawSumNanos / 1e6 / frames, drawMaxNanos / 1e6,
                    work[(counted - 1) / 2] / 1e6, work[((counted - 1) * 99) / 100] / 1e6,
                ),
            )
            intervalNanos = (1e9 / refresh).toLong()
            windowStartNanos = vsyncNanos
            frames = 0
            missed = 0
            this.late = 0
            runtimeSumNanos = 0
            runtimeMaxNanos = 0
            drawSumNanos = 0
            drawMaxNanos = 0
        }
    }

    private companion object {
        const val WINDOW_NANOS = 2_000_000_000L

        /** Two seconds at 240 Hz, with room. */
        const val MAX_FRAMES = 1_024
    }
}

/**
 * Debug builds: every frame of a playfield, kept across surfaces, and written as CSV when it closes
 * (`vsyncNanos,presentNanos,runtimeNanos,drawNanos,deadlineNanos,doneNanos`: the frame's deadline from `FrameData` and
 * when its drawing was posted; README). [summary] goes to the log then.
 */
class FrameLog {
    private var data = LongArray(COLUMNS * 8_192)
    private var size = 0

    @Synchronized
    fun record(vsyncNanos: Long, presentNanos: Long, runtimeNanos: Long, drawNanos: Long, deadlineNanos: Long, doneNanos: Long) {
        if (size + COLUMNS > data.size) data = data.copyOf(data.size * 2)
        data[size] = vsyncNanos
        data[size + 1] = presentNanos
        data[size + 2] = runtimeNanos
        data[size + 3] = drawNanos
        data[size + 4] = deadlineNanos
        data[size + 5] = doneNanos
        size += COLUMNS
    }

    /** Frames, and the whole frame's work at the median, the 99th percentile and the most. */
    @Synchronized
    fun summary(): String {
        val frames = size / COLUMNS
        if (frames == 0) return "frames=0"
        val work = LongArray(frames) { data[it * COLUMNS + 2] + data[it * COLUMNS + 3] }.also { it.sort() }
        return String.format(
            Locale.ROOT,
            "frames=%d work_p50=%.2fms work_p99=%.2fms work_max=%.2fms",
            frames, work[(frames - 1) / 2] / 1e6, work[((frames - 1) * 99) / 100] / 1e6, work[frames - 1] / 1e6,
        )
    }

    @Synchronized
    fun csv(): String = buildString {
        append("vsyncNanos,presentNanos,runtimeNanos,drawNanos,deadlineNanos,doneNanos\n")
        var i = 0
        while (i < size) {
            for (column in 0 until COLUMNS) {
                if (column > 0) append(',')
                append(data[i + column])
            }
            append('\n')
            i += COLUMNS
        }
    }

    private companion object {
        const val COLUMNS = 6
    }
}

private fun playfieldWords(context: Context) = PlayfieldWords(
    ready = context.getString(R.string.play_ready),
    perfect = context.getString(R.string.judgement_perfect),
    good = context.getString(R.string.judgement_good),
    miss = context.getString(R.string.judgement_miss),
    combo = context.getString(R.string.play_combo),
)
