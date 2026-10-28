package io.ensomi.android.play

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.ensomi.runtime.Geometry
import io.ensomi.runtime.Judgement
import io.ensomi.runtime.VisibleState
import io.ensomi.runtime.noteGeometry
import io.ensomi.runtime.tierJudgement

/** The play screen's colours, from the brand's dark plum theme and the web player, as ARGB for the canvas. */
object PlayPalette {
    const val GROUND = 0xFF130E1A.toInt()
    const val GROUND_SECOND_HALF = 0xFF1A1322.toInt()
    const val LANE_A = 0xFF1D1627.toInt()
    const val LANE_B = 0xFF191220.toInt()
    const val KEY_TRAY = 0xFF171020.toInt()
    const val SURFACE = 0xFF2A2036.toInt()
    const val TEXT = 0xFFF6F1FB.toInt()
    const val MUTED_TEXT = 0xFFC7BCD6.toInt()
    const val TAP = 0xFFEAA0B7.toInt()
    const val HOLD = 0xFFB5B3EF.toInt()
    const val HOLD_BODY = 0xFF9562D7.toInt()
    const val MISSED_NOTE = 0xFF8F7DAB.toInt()
    const val ACTION = 0xFFFF879F.toInt()
    const val PERFECT = 0xFFF6F1FB.toInt()
    const val GOOD = 0xFFF3C47E.toInt()
    const val MISS = 0xFFFF9AA5.toInt()
    const val EARLY = HOLD
    const val LATE = TAP

    fun judgement(judgement: Judgement): Int = when (judgement) {
        Judgement.PERFECT -> PERFECT
        Judgement.GOOD -> GOOD
        Judgement.MISS -> MISS
    }
}

/** The words the playfield draws, from the app's string resources. */
data class PlayfieldWords(
    val ready: String,
    val perfect: String,
    val good: String,
    val miss: String,
    val combo: String,
)

/**
 * Draws one play frame on a hardware canvas: lanes, lane lights, the key tray, notes, the judgement line, hit feedback,
 * song progress, and the judgement word with the combo. Everything that reacts to play follows what the runtime
 * reports: note states and positions, the judgement events of the last moments, lane brightness and the word's
 * presentation. Used on the render thread only; it allocates nothing per frame beyond what the runtime returns.
 */
class PlayfieldRenderer(private val words: PlayfieldWords) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val diagonal = Path()
    private var diagonalSize = 0f to 0f
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        textAlign = Paint.Align.CENTER
    }
    private val comboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.CENTER
    }

    /** A vertical ramp from transparent at the top to the colour at the bottom, one per colour, in unit space. */
    private val fadeUpShaders = HashMap<Int, LinearGradient>()

    var reduceMotion = false

    fun draw(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        drawGround(canvas, g)
        drawLanes(canvas, frame, g)
        drawTray(canvas, frame, g)
        if (frame != null) drawNotes(canvas, frame, g)
        fill.color = PlayPalette.ACTION
        fill.alpha = 255
        canvas.drawRect(g.fieldLeft, g.lineY - g.dp(1.5f), g.fieldLeft + g.fieldWidth, g.lineY + g.dp(1.5f), fill)
        if (frame != null) {
            drawHitFeedback(canvas, frame, g)
            drawProgress(canvas, frame, g)
            drawJudgement(canvas, frame, g)
        }
    }

    private fun drawGround(canvas: Canvas, g: PlayfieldGeometry) {
        canvas.drawColor(PlayPalette.GROUND)
        if (diagonalSize != g.width to g.height) {
            // The brand's diagonal division: the lighter half below a line from the bottom left to the top right.
            diagonal.reset()
            diagonal.moveTo(g.width * 0.12f, g.height)
            diagonal.lineTo(g.width, g.height * 0.08f)
            diagonal.lineTo(g.width, g.height)
            diagonal.close()
            diagonalSize = g.width to g.height
        }
        fill.color = PlayPalette.GROUND_SECOND_HALF
        canvas.drawPath(diagonal, fill)
    }

    private fun drawLanes(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        for (lane in 0 until PlayfieldGeometry.LANES) {
            fill.color = if (lane % 2 == 0) PlayPalette.LANE_A else PlayPalette.LANE_B
            val left = g.laneLeft(lane)
            canvas.drawRect(left, g.fieldTop, left + g.laneWidth, g.lineY, fill)
        }
        val brightness = frame?.brightness ?: return
        for (lane in 0 until PlayfieldGeometry.LANES) {
            val light = brightness[lane].lane.toFloat()
            if (light <= 0f) continue
            val height = g.fallHeight * 0.45f
            fadeUp(canvas, PlayPalette.HOLD, minOf(1f, light * 2f), g.laneLeft(lane), g.lineY - height, g.laneWidth, height, additive = true)
        }
    }

    private fun drawTray(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        val right = g.fieldLeft + g.fieldWidth
        val corner = g.dp(14f)
        canvas.save()
        canvas.clipRect(g.fieldLeft, g.lineY, right, g.trayBottom)
        fill.color = PlayPalette.KEY_TRAY
        rect.set(g.fieldLeft, g.lineY - corner, right, g.trayBottom)
        canvas.drawRoundRect(rect, corner, corner, fill)
        val inset = g.dp(PlayfieldGeometry.CAP_INSET_DP)
        val capCorner = g.dp(12f)
        for (lane in 0 until PlayfieldGeometry.LANES) {
            val left = g.laneLeft(lane) + g.dp(6f)
            rect.set(left, g.lineY + inset, left + g.laneWidth - g.dp(12f), g.trayBottom - inset)
            fill.color = PlayPalette.SURFACE
            canvas.drawRoundRect(rect, capCorner, capCorner, fill)
            val light = frame?.brightness?.get(lane)?.receptor?.toFloat() ?: 0f
            if (light > 0f) {
                additive.shader = null
                additive.color = PlayPalette.HOLD
                additive.alpha = (minOf(1f, light) * 255).toInt()
                canvas.drawRoundRect(rect, capCorner, capCorner, additive)
            }
        }
        canvas.restore()
    }

    /** Notes hit are gone; a miss still on screen is greyed. A hold being held keeps its head on the line and glows. */
    private fun drawNotes(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        val inset = g.dp(5f)
        val noteHeight = g.dp(20f)
        val corner = g.dp(7f)
        val clipBottom = g.lineY + g.dp(PlayfieldGeometry.CAP_INSET_DP)
        for (obj in frame.visible) {
            if (obj.state == VisibleState.RESOLVED) continue
            val lane = obj.lane.toInt()
            val laneX = g.laneLeft(lane)
            val noteLeft = laneX + inset
            val noteWidth = g.laneWidth - inset * 2
            val missed = obj.state == VisibleState.MISSED_BUT_VISIBLE
            val held = obj.state == VisibleState.HOLDING
            canvas.save()
            if (held) {
                canvas.clipRect(laneX - g.dp(24f), g.fieldTop, laneX + g.laneWidth + g.dp(24f), clipBottom)
            } else {
                canvas.clipRect(laneX, g.fieldTop, laneX + g.laneWidth, clipBottom)
            }
            when (val geometry = noteGeometry(obj, frame.renderMs, frame.scrollTimeMs, g.fallHeight.toDouble())) {
                is Geometry.Tap -> {
                    val y = g.fieldTop + geometry.y.toFloat()
                    fill.color = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.TAP
                    fill.alpha = if (missed) 128 else 255
                    rect.set(noteLeft, y - noteHeight / 2, noteLeft + noteWidth, y + noteHeight / 2)
                    canvas.drawRoundRect(rect, corner, corner, fill)
                }
                is Geometry.Hold -> {
                    val bodyWidth = noteWidth * 0.72f
                    val bodyLeft = noteLeft + (noteWidth - bodyWidth) / 2
                    val top = g.fieldTop + geometry.bodyTopY.toFloat()
                    val bottom = g.fieldTop + geometry.bodyBottomY.toFloat()
                    fill.color = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.HOLD_BODY
                    fill.alpha = when {
                        missed -> 64
                        held -> 217
                        else -> 153
                    }
                    rect.set(bodyLeft, top, bodyLeft + bodyWidth, bottom)
                    canvas.drawRoundRect(rect, g.dp(6f), g.dp(6f), fill)
                    val capColor = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.HOLD
                    val capAlpha = if (missed) 128 else 255
                    geometry.tailY?.let { tail ->
                        val y = g.fieldTop + tail.toFloat()
                        fill.color = capColor
                        fill.alpha = capAlpha
                        rect.set(bodyLeft, y - g.dp(4f), bodyLeft + bodyWidth, y + g.dp(4f))
                        canvas.drawRoundRect(rect, g.dp(4f), g.dp(4f), fill)
                    }
                    val headY = g.fieldTop + geometry.headY.toFloat()
                    if (held) {
                        // A soft halo stands in for the Swift renderer's glow.
                        additive.shader = null
                        additive.color = PlayPalette.HOLD
                        additive.alpha = 90
                        rect.set(noteLeft - g.dp(8f), headY - noteHeight, noteLeft + noteWidth + g.dp(8f), headY + noteHeight)
                        canvas.drawRoundRect(rect, noteHeight, noteHeight, additive)
                    }
                    fill.color = capColor
                    fill.alpha = capAlpha
                    rect.set(noteLeft, headY - noteHeight / 2, noteLeft + noteWidth, headY + noteHeight / 2)
                    canvas.drawRoundRect(rect, corner, corner, fill)
                }
            }
            canvas.restore()
        }
        fill.alpha = 255
    }

    /**
     * A hit lights the line in its lane in the tier's colour with a short flare up the lane; a miss tints the lane
     * above the line. Each is aged from the chart time the runtime recorded its batch at. With reduced motion the
     * flare is not drawn and the light only fades.
     */
    private fun drawHitFeedback(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        for (batch in frame.batches) {
            val age = frame.gameplayMs - batch.chartTimeMs
            if (age < 0) continue
            for (event in batch.events) {
                val laneX = g.laneLeft(event.lane.toInt())
                val judgement = tierJudgement(event.tier)
                val color = PlayPalette.judgement(judgement)
                if (judgement == Judgement.MISS) {
                    if (age >= MISS_FEEDBACK_MS) continue
                    val fadeOut = (1 - age / MISS_FEEDBACK_MS).toFloat()
                    val tint = g.dp(96f)
                    fadeUp(canvas, color, 0.34f * fadeOut, laneX, g.lineY - tint, g.laneWidth, tint, additive = false)
                    fill.color = color
                    fill.alpha = (255 * fadeOut).toInt()
                    canvas.drawRect(laneX, g.lineY - g.dp(2f), laneX + g.laneWidth, g.lineY + g.dp(2f), fill)
                    fill.alpha = 255
                    continue
                }
                if (age >= HIT_FEEDBACK_MS) continue
                val progress = (age / HIT_FEEDBACK_MS).toFloat()
                val fadeOut = (1 - progress) * (1 - progress)
                additive.shader = null
                additive.color = color
                additive.alpha = (255 * 0.3f * fadeOut).toInt()
                rect.set(laneX, g.lineY - g.dp(10f), laneX + g.laneWidth, g.lineY + g.dp(10f))
                canvas.drawRoundRect(rect, g.dp(10f), g.dp(10f), additive)
                additive.alpha = (255 * fadeOut).toInt()
                rect.set(laneX + g.dp(3f), g.lineY - g.dp(3f), laneX + g.laneWidth - g.dp(3f), g.lineY + g.dp(3f))
                canvas.drawRoundRect(rect, g.dp(3f), g.dp(3f), additive)
                if (!reduceMotion) {
                    val flare = g.dp(40f) + g.dp(70f) * (1 - fadeOut)
                    val side = g.dp(15f)
                    fadeUp(canvas, color, 0.24f * fadeOut, laneX + side, g.lineY - flare, g.laneWidth - 2 * side, flare, additive = true)
                }
            }
        }
    }

    private fun drawProgress(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        if (frame.durationMs <= 0) return
        val progress = (frame.gameplayMs / frame.durationMs).coerceIn(0.0, 1.0).toFloat()
        val height = g.dp(3f)
        fill.color = PlayPalette.SURFACE
        canvas.drawRect(g.fieldLeft, g.fieldTop, g.fieldLeft + g.fieldWidth, g.fieldTop + height, fill)
        fill.color = PlayPalette.ACTION
        canvas.drawRect(g.fieldLeft, g.fieldTop, g.fieldLeft + g.fieldWidth * progress, g.fieldTop + height, fill)
    }

    /** The judgement word and the combo at 42 % of the lane height; "Ready" until the first judgement of a play. */
    private fun drawJudgement(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        val centerX = g.fieldLeft + g.fieldWidth / 2
        val wordY = g.fieldTop + g.fallHeight * 0.42f
        val presentation = frame.presentation
        wordPaint.textSize = g.dp(34f)
        if (!frame.anyJudgement) {
            wordPaint.color = PlayPalette.MUTED_TEXT
            canvas.drawText(words.ready, centerX, wordY, wordPaint)
        } else if (presentation != null) {
            val judgement = tierJudgement(presentation.event.tier)
            val scale = if (reduceMotion) 1f else presentation.scale.toFloat()
            val drift = if (reduceMotion) 0f else g.dp(presentation.verticalOffset.toFloat())
            val word = when (judgement) {
                Judgement.PERFECT -> words.perfect
                Judgement.GOOD -> words.good
                Judgement.MISS -> words.miss
            }
            wordPaint.color = PlayPalette.judgement(judgement)
            wordPaint.alpha = (255 * presentation.opacity.coerceIn(0.0, 1.0)).toInt()
            canvas.save()
            canvas.translate(centerX, wordY + drift)
            canvas.scale(scale, scale)
            canvas.drawText(word, 0f, 0f, wordPaint)
            // A Good says whether it was early (up, lavender) or late (down, rose); a Perfect stays quiet.
            val offset = presentation.event.offsetMs
            if (judgement == Judgement.GOOD && offset != null && offset != 0.0) {
                val arrowX = wordPaint.measureText(word) / 2 + g.dp(16f)
                labelPaint.color = if (offset < 0) PlayPalette.EARLY else PlayPalette.LATE
                labelPaint.alpha = wordPaint.alpha
                labelPaint.textSize = g.dp(26f)
                canvas.drawText(if (offset < 0) "↑" else "↓", arrowX, 0f, labelPaint)
            }
            canvas.restore()
            wordPaint.alpha = 255
        }
        if (frame.combo >= 2) {
            comboPaint.color = PlayPalette.TEXT
            comboPaint.alpha = 230
            comboPaint.textSize = g.dp(46f)
            val comboY = wordY + g.dp(56f)
            canvas.drawText(frame.combo.toString(), centerX, comboY, comboPaint)
            labelPaint.color = PlayPalette.MUTED_TEXT
            labelPaint.alpha = 255
            labelPaint.textSize = g.dp(15f)
            canvas.drawText(words.combo, centerX, comboY + g.dp(22f), labelPaint)
        }
    }

    /** A rectangle in [color] that fades out towards its top. */
    private fun fadeUp(canvas: Canvas, color: Int, alpha: Float, left: Float, top: Float, width: Float, height: Float, additive: Boolean) {
        if (alpha <= 0f || height <= 0f) return
        val shader = fadeUpShaders.getOrPut(color) {
            LinearGradient(0f, 0f, 0f, 1f, color and 0x00FFFFFF, color, Shader.TileMode.CLAMP)
        }
        matrix.setScale(1f, height)
        matrix.postTranslate(0f, top)
        shader.setLocalMatrix(matrix)
        val paint = if (additive) this.additive else fade
        paint.shader = shader
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawRect(left, top, left + width, top + height, paint)
        paint.shader = null
        paint.alpha = 255
    }

    companion object {
        /** How long a hit's light stays at the line, and a miss's mark. */
        const val HIT_FEEDBACK_MS = 200.0
        const val MISS_FEEDBACK_MS = 360.0
    }
}
