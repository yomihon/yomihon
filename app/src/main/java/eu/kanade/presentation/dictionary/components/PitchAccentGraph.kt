package eu.kanade.presentation.dictionary.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTermMeta

/**
 * Display pitch accent graphs for a term.
 */
@Composable
internal fun PitchAccentSection(
    termMeta: List<DictionaryTermMeta>,
    dictionaries: List<Dictionary>,
    modifier: Modifier = Modifier,
) {
    val pitchData = remember(termMeta) {
        PitchAccentFormatter.parsePitchAccents(termMeta)
    }

    if (pitchData.isEmpty()) return

    // Display pitch patterns without dictionary names (names shown at bottom of card)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pitchData.take(8).flatMap { data -> data.patterns.take(4) }.forEach { pattern ->
            PitchAccentChip(
                pattern = pattern,
            )
        }
    }
}

/**
 * Get the list of pitch accent dictionary names for a term.
 */
@Composable
internal fun getPitchAccentDictionaryNames(
    termMeta: List<DictionaryTermMeta>,
    dictionaries: List<Dictionary>,
): List<String> {
    val pitchData = remember(termMeta) {
        PitchAccentFormatter.parsePitchAccents(termMeta)
    }

    return remember(pitchData, dictionaries) {
        pitchData.mapNotNull { data ->
            dictionaries.find { it.id == data.dictionaryId }?.title
        }.distinct()
    }
}

/**
 * A single pitch accent chip showing the pattern.
 */
@Composable
private fun PitchAccentChip(
    pattern: PitchPattern,
    modifier: Modifier = Modifier,
) {
    val clipShape = remember { RoundedCornerShape(8.dp) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = clipShape,
            )
            .clip(clipShape),
    ) {
        PitchAccentGraph(
            pattern = pattern,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        if (pattern.tags.isNotEmpty()) {
            Text(
                text = pattern.tags.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * Renders a visual pitch accent graph.
 *
 * The graph shows:
 * - High/low pitch positions with connected lines
 * - Mora characters below the graph
 * - Nasal indicators (small circle) for bidakuon
 * - Devoiced indicators (strikethrough) for museika
 */
@Composable
private fun PitchAccentGraph(
    pattern: PitchPattern,
    modifier: Modifier = Modifier,
    moraWidth: Dp = 18.dp,
    graphHeight: Dp = 20.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val morae = pattern.morae
    if (morae.isEmpty()) return

    val totalWidth = moraWidth * morae.size
    val highY = with(density) { 4.dp.toPx() }
    val lowY = with(density) { (graphHeight - 4.dp).toPx() }
    val moraWidthPx = with(density) { moraWidth.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }
    val dotRadius = with(density) { 3.dp.toPx() }
    val nasalRadius = with(density) { 2.dp.toPx() }

    // Colors for special morae
    val nasalColor = MaterialTheme.colorScheme.tertiary
    val devoicedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pitch line graph
        Canvas(
            modifier = Modifier
                .width(totalWidth)
                .height(graphHeight),
        ) {
            morae.forEachIndexed { index, moraPitch ->
                val x = moraWidthPx * index + moraWidthPx / 2
                val y = if (moraPitch.pitch == PitchLevel.HIGH) highY else lowY

                // Draw connecting line to next mora
                if (index < morae.size - 1) {
                    val nextMora = morae[index + 1]
                    val nextX = moraWidthPx * (index + 1) + moraWidthPx / 2
                    val nextY = if (nextMora.pitch == PitchLevel.HIGH) highY else lowY

                    drawLine(
                        color = lineColor,
                        start = Offset(x, y),
                        end = Offset(nextX, nextY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }

                // Draw dot at each mora position
                drawCircle(
                    color = when {
                        moraPitch.isNasal -> nasalColor
                        moraPitch.isDevoiced -> devoicedColor
                        else -> lineColor
                    },
                    radius = dotRadius,
                    center = Offset(x, y),
                )

                // Draw nasal indicator (small ring around dot)
                if (moraPitch.isNasal) {
                    drawCircle(
                        color = nasalColor,
                        radius = dotRadius + nasalRadius,
                        center = Offset(x, y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = with(density) { 1.dp.toPx() },
                        ),
                    )
                }

                // Draw devoiced indicator (small x or strikethrough)
                if (moraPitch.isDevoiced) {
                    val strikeSize = dotRadius * 1.5f
                    drawLine(
                        color = devoicedColor,
                        start = Offset(x - strikeSize, y - strikeSize),
                        end = Offset(x + strikeSize, y + strikeSize),
                        strokeWidth = with(density) { 1.dp.toPx() },
                    )
                    drawLine(
                        color = devoicedColor,
                        start = Offset(x + strikeSize, y - strikeSize),
                        end = Offset(x - strikeSize, y + strikeSize),
                        strokeWidth = with(density) { 1.dp.toPx() },
                    )
                }
            }

            // Draw particle indicator (dashed line after last mora for potential particle)
            val lastMora = morae.last()
            val lastX = moraWidthPx * (morae.size - 1) + moraWidthPx / 2
            val lastY = if (lastMora.pitch == PitchLevel.HIGH) highY else lowY

            // Use the calculated particle pitch from the pattern
            val particleY = if (pattern.particlePitch == PitchLevel.HIGH) highY else lowY

            // Heiban (0): Last Mora HIGH -> Particle HIGH. Graph should show a dashed line going straight.
            // Odaka (N): Last Mora HIGH -> Particle LOW. Graph should show a dashed line going down.
            // Atamadaka (1): Last Mora LOW -> Particle LOW. No dashed line needed.

            if (lastMora.pitch == PitchLevel.HIGH) {
                drawLine(
                    color = lineColor.copy(alpha = 0.5f),
                    start = Offset(lastX, lastY),
                    end = Offset(lastX + moraWidthPx / 2, particleY),
                    strokeWidth = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    cap = StrokeCap.Round,
                )
            }
        }

        // Mora text row
        Row {
            morae.forEach { moraPitch ->
                Text(
                    text = moraPitch.mora,
                    style = textStyle,
                    color = when {
                        moraPitch.isDevoiced -> devoicedColor
                        moraPitch.isNasal -> nasalColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(moraWidth),
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}
