package io.ensomi.android.ui

import android.app.Activity
import android.view.WindowInsetsController
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
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
 * Says that an action of the prototype is not built yet. The app shows the message in a snackbar, so nothing in the
 * prototype pretends to work. Each call site names the step of docs/plan.md that builds it.
 */
fun interface NotWired {
    operator fun invoke(what: String)
}

val LocalNotWired = staticCompositionLocalOf { NotWired { } }

/**
 * Light status and navigation bar icons while a dark game screen is shown, whatever the system theme; the previous
 * appearance comes back when the screen leaves.
 */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = (view.context as? Activity)?.window?.insetsController
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val previous = controller?.systemBarsAppearance ?: 0
        controller?.setSystemBarsAppearance(0, mask)
        onDispose { controller?.setSystemBarsAppearance(previous and mask, mask) }
    }
}
