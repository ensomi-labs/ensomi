package io.ensomi.android.play

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The caps' key labels: their colour as the cap lights (keyboard.md, Visual language). */
class CapLabelTest {
    @Test
    fun theLabelBrightensToLightTextAndTakesTheInkPastFortyFivePerCent() {
        assertEquals(PlayPalette.MUTED_TEXT, CapLabel.color(0f))
        assertEquals(PlayPalette.MUTED_TEXT, CapLabel.color(-1f))
        assertEquals(PlayPalette.TEXT, CapLabel.color(CapLabel.INK_FROM))
        assertEquals(PlayPalette.GROUND, CapLabel.color(0.46f))
        assertEquals(PlayPalette.GROUND, CapLabel.color(1f))
        assertEquals("The light is the cap's, at most 1", PlayPalette.GROUND, CapLabel.color(1.6f))
        var last = luminance(CapLabel.color(0f))
        for (step in 1..45) {
            val now = luminance(CapLabel.color(step / 100f))
            assertTrue("Brighter at $step %", now >= last)
            last = now
        }
    }

    /** Large text needs 3:1; the label keeps 4.0:1 on the cap at every step of its light, as the renderer adds it. */
    @Test
    fun everyStepOfTheLightKeepsFourToOne() {
        var worst = Double.MAX_VALUE
        for (step in 0..1_000) {
            val light = step / 1_000f
            worst = minOf(worst, contrast(CapLabel.color(light), litCap(light)))
        }
        assertTrue("Worst contrast $worst", worst >= 4.0)
        assertTrue("Unlit, 8.5:1", contrast(CapLabel.color(0f), PlayPalette.SURFACE) > 8.4)
    }

    @Test
    fun mixingMovesEachChannel() {
        assertEquals(0xFF000000.toInt(), CapLabel.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFF808080.toInt(), CapLabel.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF102030.toInt(), CapLabel.mix(0xFF102030.toInt(), 0xFF102030.toInt(), 0.7f))
    }

    /** The cap lit as the renderer lights it: the lavender, at the light's alpha, added channel by channel. */
    private fun litCap(light: Float): Int {
        val alpha = (minOf(1f, light) * 255).toInt() / 255.0
        fun channel(shift: Int): Int {
            val cap = PlayPalette.SURFACE shr shift and 0xFF
            val add = PlayPalette.HOLD shr shift and 0xFF
            return minOf(255, (cap + add * alpha).toInt()) shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }

    /** WCAG 2's relative luminance. */
    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = (color shr shift and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
