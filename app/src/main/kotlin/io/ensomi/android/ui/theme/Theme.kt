package io.ensomi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 for the app's chrome (library, making a chart, settings), in the brand's colours by default and following
 * the system's light or dark theme. [dynamicColor] switches to the wallpaper's colours instead; docs/design.md says why
 * the brand scheme is the default. The game screens use [GameTheme] whatever the system theme.
 */
@Composable
fun EnsomiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** The play, results and calibration screens: the brand's dark plum, the same in light and dark system themes. */
@Composable
fun GameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandDark, content = content)
}

/** Colours of the brand mark and the web player; see docs/design.md, "Visual language". */
object Brand {
    val Plum = Color(0xFF130E1A)
    val PlumLight = Color(0xFF1A1322)
    val Panel = Color(0xFF2A2036)
    val PanelBorder = Color(0xFF4A3B5E)
    val Text = Color(0xFFF6F1FB)
    val MutedText = Color(0xFFC7BCD6)
    val Lavender = Color(0xFFB5B3EF)
    val Rose = Color(0xFFEAA0B7)
    val Purple = Color(0xFF9562D7)

    /** The action colour: the primary button, the judgement line and song progress, nothing else. */
    val Action = Color(0xFFFF879F)
    val ActionStart = Color(0xFFF39AAF)
    val ActionEnd = Color(0xFFFF708F)
    val ActionInk = Color(0xFF2B0F1E)

    val Perfect = Color(0xFFF6F1FB)
    val Good = Color(0xFFF3C47E)
    val Miss = Color(0xFFFF9AA5)
}

private val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF5D58A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF1A1458),
    inversePrimary = Color(0xFFC6C2FF),
    secondary = Color(0xFF5F5670),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6DCF4),
    onSecondaryContainer = Color(0xFF1C1529),
    tertiary = Color(0xFF95405C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E0019),
    background = Color(0xFFFCF8FE),
    onBackground = Color(0xFF1C1A21),
    surface = Color(0xFFFCF8FE),
    onSurface = Color(0xFF1C1A21),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF4A4553),
    surfaceTint = Color(0xFF5D58A8),
    inverseSurface = Color(0xFF312F36),
    inverseOnSurface = Color(0xFFF4EFF7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7B7584),
    outlineVariant = Color(0xFFCCC4D4),
    surfaceBright = Color(0xFFFCF8FE),
    surfaceDim = Color(0xFFDDD8E2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F0),
    surfaceContainerHighest = Color(0xFFE5E0EA),
)

private val BrandDark: ColorScheme = darkColorScheme(
    primary = Brand.Lavender,
    onPrimary = Color(0xFF24205A),
    primaryContainer = Color(0xFF3C3878),
    onPrimaryContainer = Color(0xFFE3DFFF),
    inversePrimary = Color(0xFF5D58A8),
    secondary = Brand.MutedText,
    onSecondary = Color(0xFF2F2840),
    secondaryContainer = Color(0xFF463D57),
    onSecondaryContainer = Color(0xFFE7DDF5),
    tertiary = Brand.Rose,
    onTertiary = Color(0xFF4F1C2E),
    tertiaryContainer = Color(0xFF6B3045),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Brand.Plum,
    onBackground = Brand.Text,
    surface = Brand.Plum,
    onSurface = Brand.Text,
    surfaceVariant = Brand.Panel,
    onSurfaceVariant = Brand.MutedText,
    surfaceTint = Brand.Lavender,
    inverseSurface = Color(0xFFE8E0EE),
    inverseOnSurface = Brand.Panel,
    error = Brand.Miss,
    onError = Color(0xFF561522),
    errorContainer = Color(0xFF73283A),
    onErrorContainer = Color(0xFFFFD9DD),
    outline = Color(0xFF8F7DAB),
    outlineVariant = Brand.PanelBorder,
    surfaceBright = Color(0xFF3A3046),
    surfaceDim = Brand.Plum,
    surfaceContainerLowest = Color(0xFF0E0A14),
    surfaceContainerLow = Brand.PlumLight,
    surfaceContainer = Color(0xFF1E1727),
    surfaceContainerHigh = Brand.Panel,
    surfaceContainerHighest = Color(0xFF342942),
)
