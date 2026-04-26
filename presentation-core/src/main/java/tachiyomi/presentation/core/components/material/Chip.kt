package tachiyomi.presentation.core.components.material

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.util.animateElevation
import androidx.compose.material3.SuggestionChipDefaults as SuggestionChipDefaultsM3

@ExperimentalMaterial3Api
@Composable
fun SuggestionChip(
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    elevation: ChipElevation? = SuggestionChipDefaults.suggestionChipElevation(),
    shape: Shape = MaterialTheme.shapes.small,
    border: ChipBorder? = SuggestionChipDefaults.suggestionChipBorder(),
    colors: ChipColors = SuggestionChipDefaults.suggestionChipColors(),
) = Chip(
    modifier = modifier,
    label = label,
    labelTextStyle = MaterialTheme.typography.labelLarge,
    labelColor = colors.labelColor(enabled).value,
    leadingIcon = icon,
    avatar = null,
    trailingIcon = null,
    leadingIconColor = colors.leadingIconContentColor(enabled).value,
    trailingIconColor = colors.trailingIconContentColor(enabled).value,
    containerColor = colors.containerColor(enabled).value,
    tonalElevation = elevation?.tonalElevation(enabled, interactionSource)?.value ?: 0.dp,
    shadowElevation = elevation?.shadowElevation(enabled, interactionSource)?.value ?: 0.dp,
    minHeight = SuggestionChipDefaultsM3.Height,
    paddingValues = SuggestionChipPadding,
    shape = shape,
    border = border?.borderStroke(enabled)?.value,
)

@ExperimentalMaterial3Api
@Composable
fun SuggestionChip(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    elevation: ChipElevation? = SuggestionChipDefaults.suggestionChipElevation(),
    shape: Shape = MaterialTheme.shapes.small,
    border: ChipBorder? = SuggestionChipDefaults.suggestionChipBorder(),
    colors: ChipColors = SuggestionChipDefaults.suggestionChipColors(),
) = Chip(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
    enabled = enabled,
    label = label,
    labelTextStyle = MaterialTheme.typography.labelLarge,
    labelColor = colors.labelColor(enabled).value,
    leadingIcon = icon,
    avatar = null,
    trailingIcon = null,
    leadingIconColor = colors.leadingIconContentColor(enabled).value,
    trailingIconColor = colors.trailingIconContentColor(enabled).value,
    containerColor = colors.containerColor(enabled).value,
    tonalElevation = elevation?.tonalElevation(enabled, interactionSource)?.value ?: 0.dp,
    shadowElevation = elevation?.shadowElevation(enabled, interactionSource)?.value ?: 0.dp,
    minHeight = SuggestionChipDefaultsM3.Height,
    paddingValues = SuggestionChipPadding,
    shape = shape,
    border = border?.borderStroke(enabled)?.value,
    interactionSource = interactionSource,
)

@Suppress("SameParameterValue")
@ExperimentalMaterial3Api
@Composable
private fun Chip(
    modifier: Modifier,
    label: @Composable () -> Unit,
    labelTextStyle: TextStyle,
    labelColor: Color,
    leadingIcon: @Composable (() -> Unit)?,
    avatar: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    leadingIconColor: Color,
    trailingIconColor: Color,
    containerColor: Color,
    tonalElevation: Dp,
    shadowElevation: Dp,
    minHeight: Dp,
    paddingValues: PaddingValues,
    shape: Shape,
    border: BorderStroke?,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        ChipContent(
            label = label,
            labelTextStyle = labelTextStyle,
            labelColor = labelColor,
            leadingIcon = leadingIcon,
            avatar = avatar,
            trailingIcon = trailingIcon,
            leadingIconColor = leadingIconColor,
            trailingIconColor = trailingIconColor,
            minHeight = minHeight,
            paddingValues = paddingValues,
        )
    }
}

@Suppress("SameParameterValue")
@ExperimentalMaterial3Api
@Composable
private fun Chip(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
    label: @Composable () -> Unit,
    labelTextStyle: TextStyle,
    labelColor: Color,
    leadingIcon: @Composable (() -> Unit)?,
    avatar: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    leadingIconColor: Color,
    trailingIconColor: Color,
    containerColor: Color,
    tonalElevation: Dp,
    shadowElevation: Dp,
    minHeight: Dp,
    paddingValues: PaddingValues,
    shape: Shape,
    border: BorderStroke?,
    interactionSource: MutableInteractionSource,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        interactionSource = interactionSource,
    ) {
        ChipContent(
            label = label,
            labelTextStyle = labelTextStyle,
            labelColor = labelColor,
            leadingIcon = leadingIcon,
            avatar = avatar,
            trailingIcon = trailingIcon,
            leadingIconColor = leadingIconColor,
            trailingIconColor = trailingIconColor,
            minHeight = minHeight,
            paddingValues = paddingValues,
        )
    }
}

@Composable
private fun ChipContent(
    label: @Composable () -> Unit,
    labelTextStyle: TextStyle,
    labelColor: Color,
    leadingIcon: @Composable (() -> Unit)?,
    avatar: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    leadingIconColor: Color,
    trailingIconColor: Color,
    minHeight: Dp,
    paddingValues: PaddingValues,
) {
    CompositionLocalProvider(
        LocalContentColor provides labelColor,
        LocalTextStyle provides labelTextStyle,
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = minHeight).padding(paddingValues),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatar != null) {
                avatar()
            } else if (leadingIcon != null) {
                CompositionLocalProvider(
                    LocalContentColor provides leadingIconColor,
                    content = leadingIcon,
                )
            }
            Spacer(Modifier.width(HorizontalElementsPadding))
            label()
            Spacer(Modifier.width(HorizontalElementsPadding))
            if (trailingIcon != null) {
                CompositionLocalProvider(
                    LocalContentColor provides trailingIconColor,
                    content = trailingIcon,
                )
            }
        }
    }
}

@ExperimentalMaterial3Api
object SuggestionChipDefaults {
    @Composable
    fun suggestionChipColors(
        containerColor: Color = Color.Transparent,
        labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        iconContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor: Color = Color.Transparent,
        disabledLabelColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledIconContentColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    ): ChipColors = ChipColors(
        containerColor = containerColor,
        labelColor = labelColor,
        leadingIconContentColor = iconContentColor,
        trailingIconContentColor = Color.Unspecified,
        disabledContainerColor = disabledContainerColor,
        disabledLabelColor = disabledLabelColor,
        disabledLeadingIconContentColor = disabledIconContentColor,
        disabledTrailingIconContentColor = Color.Unspecified,
    )

    @Composable
    fun suggestionChipElevation(
        defaultElevation: Dp = 0.0.dp,
        pressedElevation: Dp = defaultElevation,
        focusedElevation: Dp = defaultElevation,
        hoveredElevation: Dp = defaultElevation,
        draggedElevation: Dp = 8.0.dp,
        disabledElevation: Dp = defaultElevation,
    ): ChipElevation = ChipElevation(
        defaultElevation = defaultElevation,
        pressedElevation = pressedElevation,
        focusedElevation = focusedElevation,
        hoveredElevation = hoveredElevation,
        draggedElevation = draggedElevation,
        disabledElevation = disabledElevation,
    )

    @Composable
    fun suggestionChipBorder(
        borderColor: Color = MaterialTheme.colorScheme.outline,
        disabledBorderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        borderWidth: Dp = 1.0.dp,
    ): ChipBorder = ChipBorder(
        borderColor = borderColor,
        disabledBorderColor = disabledBorderColor,
        borderWidth = borderWidth,
    )
}

@ExperimentalMaterial3Api
@Immutable
class ChipColors internal constructor(
    private val containerColor: Color,
    private val labelColor: Color,
    private val leadingIconContentColor: Color,
    private val trailingIconContentColor: Color,
    private val disabledContainerColor: Color,
    private val disabledLabelColor: Color,
    private val disabledLeadingIconContentColor: Color,
    private val disabledTrailingIconContentColor: Color,
) {
    @Composable
    internal fun containerColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) containerColor else disabledContainerColor)
    }

    @Composable
    internal fun labelColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) labelColor else disabledLabelColor)
    }

    @Composable
    internal fun leadingIconContentColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(
            if (enabled) leadingIconContentColor else disabledLeadingIconContentColor,
        )
    }

    @Composable
    internal fun trailingIconContentColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(
            if (enabled) trailingIconContentColor else disabledTrailingIconContentColor,
        )
    }
}

@ExperimentalMaterial3Api
@Immutable
class ChipBorder internal constructor(
    private val borderColor: Color,
    private val disabledBorderColor: Color,
    private val borderWidth: Dp,
) {
    @Composable
    internal fun borderStroke(enabled: Boolean): State<BorderStroke?> {
        return rememberUpdatedState(
            BorderStroke(borderWidth, if (enabled) borderColor else disabledBorderColor),
        )
    }
}

@ExperimentalMaterial3Api
@Immutable
class ChipElevation internal constructor(
    private val defaultElevation: Dp,
    private val pressedElevation: Dp,
    private val focusedElevation: Dp,
    private val hoveredElevation: Dp,
    private val draggedElevation: Dp,
    private val disabledElevation: Dp,
) {
    @Composable
    internal fun tonalElevation(
        enabled: Boolean,
        interactionSource: InteractionSource,
    ): State<Dp> {
        return animateElevation(enabled = enabled, interactionSource = interactionSource)
    }

    @Composable
    internal fun shadowElevation(
        enabled: Boolean,
        interactionSource: InteractionSource,
    ): State<Dp> {
        return animateElevation(enabled = enabled, interactionSource = interactionSource)
    }

    @Composable
    private fun animateElevation(
        enabled: Boolean,
        interactionSource: InteractionSource,
    ): State<Dp> {
        val interactions = remember { mutableStateListOf<Interaction>() }
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> interactions.add(interaction)
                    is HoverInteraction.Exit -> interactions.remove(interaction.enter)
                    is FocusInteraction.Focus -> interactions.add(interaction)
                    is FocusInteraction.Unfocus -> interactions.remove(interaction.focus)
                    is PressInteraction.Press -> interactions.add(interaction)
                    is PressInteraction.Release -> interactions.remove(interaction.press)
                    is PressInteraction.Cancel -> interactions.remove(interaction.press)
                    is DragInteraction.Start -> interactions.add(interaction)
                    is DragInteraction.Stop -> interactions.remove(interaction.start)
                    is DragInteraction.Cancel -> interactions.remove(interaction.start)
                }
            }
        }

        val interaction = interactions.lastOrNull()
        val target = if (!enabled) {
            disabledElevation
        } else {
            when (interaction) {
                is PressInteraction.Press -> pressedElevation
                is HoverInteraction.Enter -> hoveredElevation
                is FocusInteraction.Focus -> focusedElevation
                is DragInteraction.Start -> draggedElevation
                else -> defaultElevation
            }
        }

        val animatable = remember { Animatable(target, Dp.VectorConverter) }

        if (!enabled) {
            LaunchedEffect(target) { animatable.snapTo(target) }
        } else {
            LaunchedEffect(target) {
                val lastInteraction = when (animatable.targetValue) {
                    pressedElevation -> PressInteraction.Press(Offset.Zero)
                    hoveredElevation -> HoverInteraction.Enter()
                    focusedElevation -> FocusInteraction.Focus()
                    draggedElevation -> DragInteraction.Start()
                    else -> null
                }
                animatable.animateElevation(
                    from = lastInteraction,
                    to = interaction,
                    target = target,
                )
            }
        }

        return animatable.asState()
    }
}

private val HorizontalElementsPadding = 8.dp
private val SuggestionChipPadding = PaddingValues(horizontal = HorizontalElementsPadding)
