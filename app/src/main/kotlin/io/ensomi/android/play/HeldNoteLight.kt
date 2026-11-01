package io.ensomi.android.play

import kotlin.math.PI
import kotlin.math.sin

/** A small, smooth pulse on the game's clock: two crests a second, frozen with that clock during pause. */
internal object HeldNoteLight {
    const val PERIOD_MS = 500.0

    fun pulse(gameplayMs: Double, reduceMotion: Boolean): Float =
        if (reduceMotion) 0f else sin((gameplayMs % PERIOD_MS) * (2 * PI / PERIOD_MS)).toFloat()

    // Keep the tail and head solid; only the existing body and halo change alpha. No new drawing or objects.
    fun bodyAlpha(pulse: Float): Int = (217 + 10 * pulse).toInt()
    fun haloAlpha(pulse: Float): Int = (90 + 12 * pulse).toInt()
}
