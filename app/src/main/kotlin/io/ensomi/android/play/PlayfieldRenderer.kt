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
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import io.ensomi.runtime.Geometry
import io.ensomi.runtime.Judgement
import io.ensomi.runtime.Tier
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
 *
 * What lies on the lanes is drawn in lane space and tilted by the lean's perspective ([PlayfieldGeometry]); the tray,
 * the line, the progress bar and the words are drawn on the screen as they are.
 */
class PlayfieldRenderer(private val words: PlayfieldWords) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val perspective = Matrix()
    private val farFade = LinearGradient(0f, 0f, 0f, 1f, PlayPalette.GROUND, PlayPalette.GROUND and 0x00FFFFFF, Shader.TileMode.CLAMP)
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

    /** The runtime's word for each tier (`tierJudgement`), asked once rather than per event and frame. */
    private val judgementOf: Map<Tier, Judgement> by lazy { Tier.entries.associateWith { tierJudgement(it) } }

    fun draw(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        drawGround(canvas, g)
        drawTray(canvas, frame, g)
        onLanes(canvas, g) {
            drawLanes(canvas, frame, g)
            if (frame != null) drawNotes(canvas, frame, g)
            drawFarFade(canvas, g)
        }
        fill.color = PlayPalette.ACTION
        fill.alpha = 255
        canvas.drawRect(g.lineLeft, g.lineY - g.dp(1.5f), g.lineLeft + g.lineWidth, g.lineY + g.dp(1.5f), fill)
        if (frame != null) {
            onLanes(canvas, g) { drawHitFeedback(canvas, frame, g) }
            if (frame.judging) drawProgress(canvas, frame, g)
            if (frame.judging && !frame.paused) drawJudgement(canvas, frame, g)
        }
    }

    /**
     * Runs [block] in lane space: through the lean's perspective matrix when the lanes lean, so lanes, notes and
     * their light are drawn as on a flat strip and the GPU tilts them. Nothing in lane space clips, since a clip under
     * a perspective matrix is no longer a rectangle; notes are cut to the lanes by hand instead.
     */
    private inline fun onLanes(canvas: Canvas, g: PlayfieldGeometry, block: () -> Unit) {
        if (!g.leans) return block()
        canvas.withSave {
            perspective.setValues(g.perspective)
            concat(perspective)
            block()
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

    /** Lane space. Each lane's light rises 45 % of the lanes' height on the screen, whatever the lean. */
    private fun drawLanes(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        for (lane in 0 until PlayfieldGeometry.LANES) {
            fill.color = if (lane % 2 == 0) PlayPalette.LANE_A else PlayPalette.LANE_B
            val left = g.laneLeft(lane)
            canvas.drawRect(left, g.laneTop, left + g.laneWidth, g.lineY, fill)
        }
        val brightness = frame?.brightness ?: return
        val lightTop = g.laneY(g.lineY - g.screenFall * 0.45f)
        for (lane in 0 until PlayfieldGeometry.LANES) {
            val light = brightness[lane].lane.toFloat()
            if (light <= 0f) continue
            fadeUp(canvas, PlayPalette.HOLD, minOf(1f, light * 2f), g.laneLeft(lane), lightTop, g.laneWidth, g.lineY - lightTop, additive = true)
        }
    }

    /** Screen space: the tray and its caps are the touch zones at the line, so they never lean. */
    private fun drawTray(canvas: Canvas, frame: PlayFrame?, g: PlayfieldGeometry) {
        val right = g.lineLeft + g.lineWidth
        val corner = g.dp(14f)
        canvas.withClip(g.lineLeft, g.lineY, right, g.trayBottom) {
        fill.color = PlayPalette.KEY_TRAY
        rect.set(g.lineLeft, g.lineY - corner, right, g.trayBottom)
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
        }
    }

    /**
     * Lane space. Notes hit are gone; a miss still on screen is greyed. A hold being held keeps its head on the line
     * and glows. Each note is cut to the lanes, from the far end to just under the top edge of its key cap, so a note
     * centred on the line is drawn whole and then slips under the cap.
     */
    private fun drawNotes(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        val inset = g.dp(5f)
        val noteHeight = g.dp(20f)
        val corner = g.dp(7f)
        val top = g.laneTop
        val bottom = g.lineY + g.dp(PlayfieldGeometry.CAP_INSET_DP)
        val fallLength = g.fallLength.toDouble()
        for (obj in frame.visible) {
            if (obj.state == VisibleState.RESOLVED) continue
            val lane = obj.lane.toInt()
            val noteLeft = g.laneLeft(lane) + inset
            val noteWidth = g.laneWidth - inset * 2
            val missed = obj.state == VisibleState.MISSED_BUT_VISIBLE
            val held = obj.state == VisibleState.HOLDING
            when (val geometry = noteGeometry(obj, frame.renderMs, frame.scrollTimeMs, fallLength)) {
                is Geometry.Tap -> {
                    val y = top + geometry.y.toFloat()
                    val half = noteHeight / 2 * g.keepShape(y)
                    fill.color = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.TAP
                    fill.alpha = if (missed) 128 else 255
                    drawCut(canvas, noteLeft, y - half, noteLeft + noteWidth, y + half, corner, top, bottom, fill)
                }
                is Geometry.Hold -> {
                    val bodyWidth = noteWidth * 0.72f
                    val bodyLeft = noteLeft + (noteWidth - bodyWidth) / 2
                    fill.color = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.HOLD_BODY
                    fill.alpha = when {
                        missed -> 64
                        held -> 217
                        else -> 153
                    }
                    val bodyTop = top + geometry.bodyTopY.toFloat()
                    val bodyBottom = top + geometry.bodyBottomY.toFloat()
                    drawCut(canvas, bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyBottom, g.dp(6f), top, bottom, fill)
                    val capColor = if (missed) PlayPalette.MISSED_NOTE else PlayPalette.HOLD
                    val capAlpha = if (missed) 128 else 255
                    geometry.tailY?.let { tail ->
                        val y = top + tail.toFloat()
                        val half = g.dp(4f) * g.keepShape(y)
                        fill.color = capColor
                        fill.alpha = capAlpha
                        drawCut(canvas, bodyLeft, y - half, bodyLeft + bodyWidth, y + half, g.dp(4f), top, bottom, fill)
                    }
                    val headY = top + geometry.headY.toFloat()
                    val headHalf = noteHeight / 2 * g.keepShape(headY)
                    if (held) {
                        // A soft halo stands in for the Swift renderer's glow.
                        additive.shader = null
                        additive.color = PlayPalette.HOLD
                        additive.alpha = 90
                        drawCut(
                            canvas, noteLeft - g.dp(8f), headY - noteHeight, noteLeft + noteWidth + g.dp(8f), headY + noteHeight,
                            noteHeight, top, bottom, additive,
                        )
                    }
                    fill.color = capColor
                    fill.alpha = capAlpha
                    drawCut(canvas, noteLeft, headY - headHalf, noteLeft + noteWidth, headY + headHalf, corner, top, bottom, fill)
                }
            }
        }
        fill.alpha = 255
    }

    /** A rounded rectangle cut to [minY]..[maxY]; nothing when it lies outside. */
    private fun drawCut(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        corner: Float,
        minY: Float,
        maxY: Float,
        paint: Paint,
    ) {
        val cutTop = maxOf(top, minY)
        val cutBottom = minOf(bottom, maxY)
        if (cutBottom <= cutTop) return
        rect.set(left, cutTop, right, cutBottom)
        val radius = minOf(corner, (cutBottom - cutTop) / 2)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    /**
     * Lane space. The far end of the lanes fades into the ground over [FAR_FADE] of their height on the screen, so
     * notes come out of the distance instead of appearing at an edge.
     */
    private fun drawFarFade(canvas: Canvas, g: PlayfieldGeometry) {
        val end = g.laneY(g.fieldTop + g.screenFall * FAR_FADE)
        val height = end - g.laneTop
        if (height <= 0f) return
        matrix.setScale(1f, height)
        matrix.postTranslate(0f, g.laneTop)
        farFade.setLocalMatrix(matrix)
        fade.shader = farFade
        fade.alpha = 255
        canvas.drawRect(g.lineLeft, g.laneTop, g.lineLeft + g.lineWidth, end, fade)
        fade.shader = null
    }

    /**
     * Lane space. A hit lights the line in its lane in the tier's colour with a short flare up the lane; a miss tints
     * the lane above the line. Each is aged from the chart time the runtime recorded its batch at. With reduced motion
     * the flare is not drawn and the light only fades.
     */
    private fun drawHitFeedback(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        for (batch in frame.batches) {
            val age = frame.gameplayMs - batch.chartTimeMs
            if (age < 0) continue
            for (event in batch.events) {
                val laneX = g.laneLeft(event.lane.toInt())
                val judgement = judgementOf.getValue(event.tier)
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

    /** Screen space, along the far end of the lanes. */
    private fun drawProgress(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        if (frame.durationMs <= 0) return
        val progress = (frame.gameplayMs / frame.durationMs).coerceIn(0.0, 1.0).toFloat()
        val height = g.dp(3f)
        fill.color = PlayPalette.SURFACE
        canvas.drawRect(g.farLeft, g.fieldTop, g.farLeft + g.farWidth, g.fieldTop + height, fill)
        fill.color = PlayPalette.ACTION
        canvas.drawRect(g.farLeft, g.fieldTop, g.farLeft + g.farWidth * progress, g.fieldTop + height, fill)
    }

    /**
     * Screen space, so the words stay upright and sharp at any lean: the judgement word and the combo at 42 % of the
     * lanes' height on the screen; "Ready" until the first judgement of a play.
     */
    private fun drawJudgement(canvas: Canvas, frame: PlayFrame, g: PlayfieldGeometry) {
        val centerX = g.centerX
        val wordY = g.fieldTop + g.screenFall * 0.42f
        val presentation = frame.presentation
        wordPaint.textSize = g.dp(34f)
        if (!frame.anyJudgement) {
            wordPaint.color = PlayPalette.MUTED_TEXT
            canvas.drawText(words.ready, centerX, wordY, wordPaint)
        } else if (presentation != null) {
            val judgement = judgementOf.getValue(presentation.event.tier)
            val scale = if (reduceMotion) 1f else presentation.scale.toFloat()
            val drift = if (reduceMotion) 0f else g.dp(presentation.verticalOffset.toFloat())
            val word = when (judgement) {
                Judgement.PERFECT -> words.perfect
                Judgement.GOOD -> words.good
                Judgement.MISS -> words.miss
            }
            wordPaint.color = PlayPalette.judgement(judgement)
            wordPaint.alpha = (255 * presentation.opacity.coerceIn(0.0, 1.0)).toInt()
            canvas.withTranslation(centerX, wordY + drift) {
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
            }
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

        /** The part of the lanes' screen height, at the far end, over which they fade into the ground. */
        const val FAR_FADE = 0.08f
    }
}
