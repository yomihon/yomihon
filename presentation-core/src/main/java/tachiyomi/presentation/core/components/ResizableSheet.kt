package tachiyomi.presentation.core.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class SheetValue(internal val heightFraction: Float) {
	Expanded(0.80f),
	PartiallyExpanded(0.35f),
}

@Composable
fun ResizableSheet(
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	initialValue: SheetValue = SheetValue.PartiallyExpanded,
	contentAlignment: Alignment = Alignment.BottomCenter,
	sheetModifier: Modifier = Modifier.fillMaxWidth(),
	content: @Composable (Float) -> Unit,
) {
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()

	BoxWithConstraints(
		modifier = modifier.fillMaxSize(),
	) {
		val maxHeightPx = with(density) { maxHeight.toPx() }
		if (maxHeightPx <= 0f) {
			return@BoxWithConstraints
		}

		val expandedAnchor = maxHeightPx * SheetValue.Expanded.heightFraction
		val partialAnchor = maxHeightPx * SheetValue.PartiallyExpanded.heightFraction
		val anchors = remember(maxHeightPx) {
			mapOf(
				SheetValue.Expanded to expandedAnchor,
				SheetValue.PartiallyExpanded to partialAnchor,
			)
		}

		val minHeightPx = anchors[SheetValue.PartiallyExpanded] ?: partialAnchor
		val maxHeightPxSheet = anchors[SheetValue.Expanded] ?: expandedAnchor
		val dismissDistancePx = with(density) { DISMISS_DISTANCE.dp.toPx() }
		val initialHeightPx = (anchors[initialValue] ?: partialAnchor)
			.coerceIn(minHeightPx, maxHeightPxSheet)

		val sheetHeight = remember(maxHeightPx) {
			Animatable(initialHeightPx)
		}

		LaunchedEffect(minHeightPx, maxHeightPxSheet) {
			sheetHeight.updateBounds(minHeightPx, maxHeightPxSheet)
		}

		LaunchedEffect(initialHeightPx) {
			sheetHeight.snapTo(initialHeightPx)
		}

		val draggableState = rememberDraggableState { delta ->
			scope.launch {
				sheetHeight.stop()
				sheetHeight.snapTo((sheetHeight.value - delta).coerceIn(minHeightPx, maxHeightPxSheet))
			}
		}

		val sheetHeightValue = sheetHeight.value
		val sheetHeightDp = with(density) { sheetHeightValue.toDp() }
		val expansionFraction = if (maxHeightPxSheet > 0f) {
			(sheetHeightValue / maxHeightPxSheet).coerceIn(0f, 1f)
		} else {
			0f
		}

		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = contentAlignment,
		) {
			// backdrop that dismisses the sheet when clicked
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clickable(
						indication = null,
						interactionSource = remember { MutableInteractionSource() },
						onClick = onDismissRequest,
					),
			)

			Surface(
				modifier = sheetModifier
					.height(sheetHeightDp)
					.draggable(
						state = draggableState,
						orientation = Orientation.Vertical,
						onDragStopped = { velocity ->
							scope.launch {
								val current = sheetHeight.value
								if (velocity > DISMISS_VELOCITY && current <= minHeightPx + dismissDistancePx) {
									onDismissRequest()
									return@launch
								}

								val target = when {
									velocity < -VELOCITY_THRESHOLD -> maxHeightPxSheet
									velocity > VELOCITY_THRESHOLD -> minHeightPx
									abs(maxHeightPxSheet - current) < abs(current - minHeightPx) -> maxHeightPxSheet
									else -> minHeightPx
								}

								sheetHeight.stop()
								sheetHeight.animateTo(
									targetValue = target,
									initialVelocity = -velocity,
								)
							}
						},
					),
				tonalElevation = 6.dp,
				shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
				color = MaterialTheme.colorScheme.surface,
			) {
				Column(
					modifier = Modifier.fillMaxSize(),
				) {
					HandleBar()
					Box(
						modifier = Modifier
							.weight(1f, fill = true)
							.fillMaxWidth(),
					) {
						content(expansionFraction)
					}
				}
			}
		}
	}
}

@Composable
private fun HandleBar() {
	val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 12.dp, bottom = 8.dp),
	) {
		Box(
			modifier = Modifier
				.align(Alignment.Center)
				.width(64.dp)
				.height(4.dp)
				.clip(CircleShape)
				.background(indicatorColor),
		)
	}
}

private const val VELOCITY_THRESHOLD = 600f
private const val DISMISS_VELOCITY = 2000f
private const val DISMISS_DISTANCE = 48
