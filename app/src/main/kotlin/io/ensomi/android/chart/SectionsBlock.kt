package io.ensomi.android.chart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import io.ensomi.android.model.Problem
import io.ensomi.android.ui.sliderColorsWithoutTicks
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Optional sections: spans of 8 to 32 seconds with a pattern and its strength, or a star target of their own.
 * Collapsed by default, since most charts need none.
 */
@Composable
fun SectionsBlock(viewModel: MakeChartViewModel, capabilities: ChartCapabilities?, enabled: Boolean) {
    val sections = viewModel.sections
    val expandLabel = stringResource(if (viewModel.sectionsOpen) R.string.action_collapse else R.string.action_expand)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = expandLabel) { viewModel.sectionsOpen = !viewModel.sectionsOpen }
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.make_sections),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (sections.isEmpty()) {
                        stringResource(R.string.make_sections_none)
                    } else {
                        pluralStringResource(R.plurals.make_sections_count, sections.size, sections.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(if (viewModel.sectionsOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
            )
        }
        if (viewModel.sectionsOpen) {
            Text(stringResource(R.string.make_sections_help), style = MaterialTheme.typography.bodySmall)
            val issues = capabilities?.let(viewModel::issues).orEmpty()
            sections.forEachIndexed { index, section ->
                SectionCard(
                    number = index + 1,
                    section = section,
                    capabilities = capabilities,
                    problems = issues.filter { it.index == index }.map { it.problem },
                    enabled = enabled,
                    onChange = viewModel::updateSection,
                    onRemove = { viewModel.removeSection(section.id) },
                )
            }
            issues.filter { it.index == null }.forEach { Problem(stringResource(it.problem.message)) }
            TextButton(onClick = viewModel::addSection, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.make_add_section), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionCard(
    number: Int,
    section: SectionDraft,
    capabilities: ChartCapabilities?,
    problems: List<SectionProblem>,
    enabled: Boolean,
    onChange: (SectionDraft) -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.make_section_title, number),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.make_section_remove, number),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeField(stringResource(R.string.make_section_start), section.start, enabled, Modifier.weight(1f)) {
                    onChange(section.copy(start = it))
                }
                TimeField(stringResource(R.string.make_section_end), section.end, enabled, Modifier.weight(1f)) {
                    onChange(section.copy(end = it))
                }
            }
            val options = capabilities?.kinds.orEmpty()
            var expanded by remember { mutableStateOf(false) }
            val selected = options.firstOrNull { it.kind == section.kind }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
                OutlinedTextField(
                    value = selected?.let { kindTitle(it) } ?: stringResource(section.kind.title),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.make_section_kind)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(kindTitle(option)) },
                            onClick = {
                                onChange(section.copy(kind = option.kind))
                                expanded = false
                            },
                        )
                    }
                }
            }
            selected?.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (section.kind == SectionKind.STAR) {
                val range = capabilities?.selectableStars ?: (2.0..5.9)
                LabelledSlider(
                    label = stringResource(R.string.make_section_star),
                    valueText = String.format(Locale.ROOT, "%.1f", section.star),
                    value = section.star,
                    range = range.start.toFloat()..range.endInclusive.toFloat(),
                    steps = capabilities?.starSliderSteps ?: 0,
                    enabled = enabled,
                ) { onChange(section.copy(star = (it * 10).roundToInt() / 10f)) }
            } else {
                LabelledSlider(
                    label = stringResource(R.string.make_section_strength),
                    valueText = String.format(Locale.ROOT, "%.2f", section.strength),
                    value = section.strength,
                    range = 0f..1f,
                    steps = 19,
                    enabled = enabled,
                ) { onChange(section.copy(strength = (it * 20).roundToInt() / 20f)) }
            }
            problems.forEach { Problem(stringResource(it.message)) }
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == ':' }.take(5)) },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.make_time_placeholder)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = sliderColorsWithoutTicks(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
    }
}

@Composable
private fun kindTitle(option: KindOption): String {
    val title = stringResource(option.kind.title)
    return if (option.experimental) stringResource(R.string.make_kind_experimental, title) else title
}

private val SectionKind.title: Int
    get() = when (this) {
        SectionKind.JUMPSTREAM -> R.string.kind_jumpstream
        SectionKind.CHORDSTREAM -> R.string.kind_chordstream
        SectionKind.STREAM -> R.string.kind_stream
        SectionKind.JACK -> R.string.kind_jack
        SectionKind.CHORDJACK -> R.string.kind_chordjack
        SectionKind.STAR -> R.string.kind_star
    }

private val SectionProblem.message: Int
    get() = when (this) {
        SectionProblem.TIME_FORMAT -> R.string.section_problem_time
        SectionProblem.END_NOT_AFTER_START -> R.string.section_problem_order
        SectionProblem.OUTSIDE_SONG -> R.string.section_problem_outside
        SectionProblem.LENGTH -> R.string.section_problem_length
        SectionProblem.STAR_RANGE -> R.string.section_problem_star
        SectionProblem.OVERLAP -> R.string.section_problem_overlap
    }
