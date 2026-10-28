package io.ensomi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The iOS client's palette and shapes, so the two clients look like one product. The values are the ones in
 * `EnsomiUI`'s `Mania4KStyle` and `Mania4KPlayPalette`: the brand's dark plum ground, plum panels with a
 * strong border, lavender accents and the coral action colour.
 */
object EnsomiStyle {
    val backgroundTop = Color(0xFF1C1525)
    val backgroundBottom = Color(0xFF231A2E)
    val ground = Color(0xFF130E1A)

    val panel = Color(0xFF2A2036)
    val control = Color(0xFF342944)
    val border = Color(0xFF4A3B5E)
    val borderStrong = Color(0xFF5C4A73)

    val textPrimary = Color(0xFFF6F1FB)
    val textSecondary = Color(0xFFC7BCD6)
    val textMuted = Color(0xFFA597BA)

    val accent = Color(0xFFB5B3EF)
    val tap = Color(0xFFEAA0B7)
    val action = Color(0xFFFF879F)
    val actionStart = Color(0xFFF39AAF)
    val actionEnd = Color(0xFFFF708F)
    val actionInk = Color(0xFF2B0F1E)

    val ok = Color(0xFF8FDCB2)
    val warn = Color(0xFFF3C47E)
    val error = Color(0xFFFF9AA5)

    /** The setup ground: the brand's two plum halves, as behind the stage. */
    val backdrop = Brush.verticalGradient(listOf(backgroundTop, backgroundBottom))
    val actionBrush = Brush.horizontalGradient(listOf(actionStart, actionEnd))
}

/** A plum panel with the iOS client's radius, border and shadow. */
fun Modifier.panel(): Modifier = this
    .background(EnsomiStyle.panel.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
    .border(1.dp, EnsomiStyle.borderStrong, RoundedCornerShape(8.dp))
    .padding(18.dp)

/** The fill a control sits in, matching the iOS `controlFill`. */
fun Modifier.controlFill(): Modifier = this
    .background(EnsomiStyle.control, RoundedCornerShape(8.dp))
    .border(1.dp, EnsomiStyle.border, RoundedCornerShape(8.dp))

/** A field's own border, as the iOS `fieldStyle()` draws it. */
fun Modifier.field(): Modifier = this
    .background(EnsomiStyle.control, RoundedCornerShape(8.dp))
    .border(1.dp, EnsomiStyle.border, RoundedCornerShape(8.dp))

/** A section title: the iOS client uses `.title2.bold()` for these. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = EnsomiStyle.textPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** A field label above the field itself. */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        color = EnsomiStyle.textPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** The caption the iOS client puts under a field to explain it. */
@Composable
fun HelpText(text: String, color: Color = EnsomiStyle.textSecondary) {
    Text(text, color = color, fontSize = 12.sp)
}

/** Rounded "chip" text, like the iOS `4K | Ready` badge. */
@Composable
fun Badge(text: String, borderColor: Color = EnsomiStyle.border) {
    Text(
        text,
        color = EnsomiStyle.textPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(EnsomiStyle.control, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

