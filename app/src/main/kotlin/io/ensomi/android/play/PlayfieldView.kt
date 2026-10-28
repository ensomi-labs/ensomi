package io.ensomi.android.play

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.ensomi.android.R
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
 * [controllerFactory] runs on the render thread when the surface is created, so the runtime's session lives there.
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

    /** Overrides the game's visual offset while the playfield runs: the calibration's slider. */
    @Volatile
    var visualOffsetMs: Double? = null
        set(value) {
            field = value
            if (value != null) controller?.visualOffsetMs = value
        }

    var reduceMotion = false
        set(value) {
            field = value
            renderer.reduceMotion = value
        }

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

    fun resume() {
        pausePending = false
        controller?.clock?.resume(System.nanoTime())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(w, h)
    }

    private fun updateGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        geometry = PlayfieldGeometry.of(
            w.toFloat(), h.toFloat(), resources.displayMetrics.density, insets.first, insets.second, hudHeightDp,
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestHighestFrameRate(holder.surface)
        val factory = controllerFactory ?: return
        renderThread = RenderThread(holder) { factory(mutableHud) { sequence.incrementAndGet() } }.also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The surface must not be drawn after this returns: stop the thread and wait for it.
        renderThread?.quitAndJoin()
        renderThread = null
        controller = null
    }

    /**
     * Asks for the display's fastest refresh rate while the playfield is shown (`Surface.setFrameRate`). The
     * system may refuse, for example in battery saver; the game draws for whatever rate it gets.
     */
    private fun requestHighestFrameRate(surface: Surface) {
        val highest = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
        surface.setFrameRate(highest, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
    }

    /**
     * Each pointer is a finger on a lane: its lane comes from where it went down. The time is the event's own,
     * `getEventTimeNanos()` (API 34; milliseconds before), not when it was handled. Lanes are touch targets, not
     * clickable views, so there is no click to perform.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return true
        val target = controller ?: return true
        val nanos = eventNanos(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val lane = g.laneAt(event.getX(index))
                if (tracker.down(event.getPointerId(index), lane)) {
                    target.enqueue(LaneInput(lane, press = true, nanos, sequence.incrementAndGet()))
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
            val playController = try {
                makeController()
            } catch (failure: Throwable) {
                Log.e(TAG, "Playfield could not start", failure)
                ready.countDown()
                return
            }
            controller = playController
            playController.start(System.nanoTime())
            if (pausePending) playController.clock.pause(System.nanoTime())
            visualOffsetMs?.let { playController.visualOffsetMs = it }
            ready.countDown()
            val choreographer = Choreographer.getInstance()
            val stats = FrameStats(refreshRateHz = display?.refreshRate ?: 60f)
            val callback = object : Choreographer.VsyncCallback {
                override fun onVsync(data: Choreographer.FrameData) {
                    if (!running) return
                    val start = System.nanoTime()
                    drawFrame(playController, data.preferredFrameTimeline.expectedPresentationTimeNanos)
                    stats.record(data.frameTimeNanos, System.nanoTime() - start)
                    choreographer.postVsyncCallback(this)
                }
            }
            choreographer.postVsyncCallback(callback)
            Looper.loop()
            playController.close()
        }

        private fun drawFrame(playController: PlayController, presentNanos: Long) {
            val g = geometry ?: return
            val frame = playController.frame(System.nanoTime(), presentNanos)
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
    }
}

/**
 * The render thread's own account of pacing, logged under `EnsomiPlayfield` every 240 frames: vsyncs that came more
 * than 1.5 refresh intervals after the previous one (frames the thread missed), and the time each frame's work took
 * (inputs, advance, runtime reads and drawing, up to posting the canvas). The start of plan step 9's frame logs.
 */
private class FrameStats(refreshRateHz: Float) {
    private val intervalNanos = (1e9 / refreshRateHz).toLong()
    private var frames = 0
    private var missed = 0
    private var lastVsyncNanos = 0L
    private var workSumNanos = 0L
    private var workMaxNanos = 0L

    fun record(vsyncNanos: Long, workNanos: Long) {
        if (lastVsyncNanos != 0L && vsyncNanos - lastVsyncNanos > intervalNanos * 3 / 2) missed++
        lastVsyncNanos = vsyncNanos
        frames++
        workSumNanos += workNanos
        workMaxNanos = maxOf(workMaxNanos, workNanos)
        if (frames == WINDOW) {
            Log.i(
                "EnsomiPlayfield",
                String.format(
                    Locale.ROOT,
                    "frames=%d refresh=%.1fHz missed=%d work_avg=%.2fms work_max=%.2fms",
                    frames, 1e9 / intervalNanos, missed, workSumNanos / 1e6 / frames, workMaxNanos / 1e6,
                ),
            )
            frames = 0
            missed = 0
            workSumNanos = 0
            workMaxNanos = 0
        }
    }

    private companion object {
        const val WINDOW = 240
    }
}

private fun playfieldWords(context: Context) = PlayfieldWords(
    ready = context.getString(R.string.play_ready),
    perfect = context.getString(R.string.judgement_perfect),
    good = context.getString(R.string.judgement_good),
    miss = context.getString(R.string.judgement_miss),
    combo = context.getString(R.string.play_combo),
)
