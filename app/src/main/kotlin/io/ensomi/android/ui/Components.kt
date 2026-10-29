package io.ensomi.android.ui

import android.app.Activity
import android.view.WindowInsetsController
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ensomi.android.ui.theme.Brand

/**
 * The one primary action of a screen (Make chart, Play, Resume): a pill in the brand's coral gradient with dark ink.
 * Everything else uses Material's own buttons.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(percent = 50)
    val background = if (enabled) {
        Modifier.background(Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionEnd)), shape)
    } else {
        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
    }
    val ink = if (enabled) Brand.ActionInk else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .then(background)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            }
            Text(text, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Slider colours without step ticks: a slider with tens of steps (stars, scroll speed) would otherwise draw a row of
 * dots along its track.
 */
@Composable
fun sliderColorsWithoutTicks(): SliderColors = SliderDefaults.colors(
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
    disabledActiveTickColor = Color.Transparent,
    disabledInactiveTickColor = Color.Transparent,
)

/**
 * Light status and navigation bar icons while a dark game screen is shown, whatever the system theme; the previous
 * appearance comes back when the screen leaves. Applied again after each configuration change (the game's
 * orientation, the font size), after which the window may set its bars back to the theme's.
 */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val controller = remember(view) { (view.context as? Activity)?.window?.insetsController }
    val previous = remember(controller) { controller?.systemBarsAppearance ?: 0 }
    DisposableEffect(controller, configuration) {
        controller?.setSystemBarsAppearance(0, DARK_BARS_MASK)
        // Once more after the next layout, which a rotation may end by restoring the theme's bars.
        val again = Runnable { controller?.setSystemBarsAppearance(0, DARK_BARS_MASK) }
        view.post(again)
        onDispose { view.removeCallbacks(again) }
    }
    DisposableEffect(controller) {
        onDispose { controller?.setSystemBarsAppearance(previous and DARK_BARS_MASK, DARK_BARS_MASK) }
    }
}

private const val DARK_BARS_MASK = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
