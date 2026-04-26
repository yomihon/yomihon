package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun OcrResultPopup(
    onDismissRequest: () -> Unit,
    anchorRect: RectF,
    settings: OcrResultPopupSettings,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val marginPx = with(density) { 8.dp.toPx() }
        val gapPx = marginPx
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val preferredPopupWidthPx = with(density) {
            settings.widthDp.dp.toPx().coerceAtMost((viewportWidthPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val preferredPopupHeightPx = with(density) {
            settings.heightDp.dp.toPx().coerceAtMost((viewportHeightPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val contentScale = settings.contentScale.coerceAtLeast(0.1f)
        val scaledDensity =
            remember(density, contentScale) {
                Density(
                    density = density.density * contentScale,
                    fontScale = density.fontScale,
                )
            }

        val placement =
            remember(
                anchorRect,
                preferredPopupWidthPx,
                preferredPopupHeightPx,
                viewportWidthPx,
                viewportHeightPx,
                gapPx,
                marginPx,
            ) {
                calculatePopupPlacement(
                    anchorRect = anchorRect,
                    preferredPopupWidthPx = preferredPopupWidthPx,
                    preferredPopupHeightPx = preferredPopupHeightPx,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    gapPx = gapPx,
                    marginPx = marginPx,
                )
            }

        if (placement == null) {
            OcrResultBottomSheet(
                onDismissRequest = onDismissRequest,
                onCopyText = onCopyText,
                searchState = searchState,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onTermGroupClick = onTermGroupClick,
                onPlayAudioClick = onPlayAudioClick,
            )
        } else {
            Surface(
                modifier = Modifier
                    .width(with(density) { placement.width.toDp() })
                    .height(with(density) { placement.height.toDp() })
                    .offset {
                        IntOffset(
                            placement.x.roundToInt(),
                            placement.y.roundToInt(),
                        )
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides scaledDensity,
                    ) {
                        DictionaryResults(
                            modifier = Modifier.fillMaxSize(),
                            query = searchState.results?.query ?: "",
                            highlightRange = searchState.results?.highlightRange,
                            showQueryHeader = false,
                            isLoading = searchState.isLoading,
                            isSearching = searchState.isSearching,
                            hasSearched = searchState.hasSearched,
                            searchResults = searchState.results?.items ?: emptyList(),
                            dictionaries = searchState.dictionaries,
                            enabledDictionaryIds = searchState.enabledDictionaryIds.toSet(),
                            termMetaMap = searchState.results?.termMetaMap ?: emptyMap(),
                            existingTermExpressions = searchState.existingTermExpressions,
                            audioStates = searchState.audioStates,
                            onTermGroupClick = onTermGroupClick,
                            onPlayAudioClick = onPlayAudioClick,
                            onQueryChange = onQueryChange,
                            onSearch = onSearch,
                            onCopyText = null,
                            contentPadding = PaddingValues(8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal data class PopupPlacement(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

private data class PlacementBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

private enum class PopupSide {
    Right,
    Left,
    Below,
    Above,
}

internal fun calculatePopupPlacement(
    anchorRect: RectF,
    preferredPopupWidthPx: Float,
    preferredPopupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement? {
    val viewport = PlacementBounds(
        left = marginPx,
        top = marginPx,
        right = viewportWidthPx - marginPx,
        bottom = viewportHeightPx - marginPx,
    )
    if (viewport.width <= 0f || viewport.height <= 0f) {
        return null
    }

    val normalizedAnchorLeft = anchorRect.left.coerceIn(0f, viewportWidthPx)
    val normalizedAnchorTop = anchorRect.top.coerceIn(0f, viewportHeightPx)
    val normalizedAnchorRight = anchorRect.right.coerceIn(0f, viewportWidthPx)
    val normalizedAnchorBottom = anchorRect.bottom.coerceIn(0f, viewportHeightPx)

    val safeAnchorLeft = min(normalizedAnchorLeft, normalizedAnchorRight)
    val safeAnchorTop = min(normalizedAnchorTop, normalizedAnchorBottom)
    val safeAnchorRight = max(normalizedAnchorLeft, normalizedAnchorRight)
    val safeAnchorBottom = max(normalizedAnchorTop, normalizedAnchorBottom)

    val anchorCenterX = (safeAnchorLeft + safeAnchorRight) / 2f
    val anchorCenterY = (safeAnchorTop + safeAnchorBottom) / 2f
    val preferredWidth = preferredPopupWidthPx.coerceAtLeast(1f)
    val preferredHeight = preferredPopupHeightPx.coerceAtLeast(1f)

    data class CandidatePlacement(
        val placement: PopupPlacement,
        val widthRatio: Float,
        val heightRatio: Float,
        val areaRatio: Float,
        val priority: Int,
    )

    fun Float.clampToRange(minValue: Float, maxValue: Float): Float {
        return if (maxValue < minValue) minValue else coerceIn(minValue, maxValue)
    }

    fun buildCandidate(
        side: PopupSide,
        availableBounds: PlacementBounds,
        priority: Int,
    ): CandidatePlacement? {
        val width = min(preferredWidth, availableBounds.width)
        val height = min(preferredHeight, availableBounds.height)
        if (width <= 0f || height <= 0f) {
            return null
        }

        val x = when (side) {
            PopupSide.Right -> availableBounds.left
            PopupSide.Left -> availableBounds.right - width
            PopupSide.Above,
            PopupSide.Below,
            -> (anchorCenterX - (width / 2f)).clampToRange(availableBounds.left, availableBounds.right - width)
        }
        val y = when (side) {
            PopupSide.Below -> availableBounds.top
            PopupSide.Above -> availableBounds.bottom - height
            PopupSide.Right,
            PopupSide.Left,
            -> (anchorCenterY - (height / 2f)).clampToRange(availableBounds.top, availableBounds.bottom - height)
        }

        val placement = PopupPlacement(
            x = x,
            y = y,
            width = width,
            height = height,
        )

        return CandidatePlacement(
            placement = placement,
            widthRatio = width / preferredWidth,
            heightRatio = height / preferredHeight,
            areaRatio = (width * height) / (preferredWidth * preferredHeight),
            priority = priority,
        )
    }

    val candidateSpecs = listOf(
        Triple(
            PopupSide.Right,
            PlacementBounds(
                left = max(safeAnchorRight + gapPx, viewport.left),
                top = viewport.top,
                right = viewport.right,
                bottom = viewport.bottom,
            ),
            0,
        ),
        Triple(
            PopupSide.Left,
            PlacementBounds(
                left = viewport.left,
                top = viewport.top,
                right = min(safeAnchorLeft - gapPx, viewport.right),
                bottom = viewport.bottom,
            ),
            1,
        ),
        Triple(
            PopupSide.Below,
            PlacementBounds(
                left = viewport.left,
                top = max(safeAnchorBottom + gapPx, viewport.top),
                right = viewport.right,
                bottom = viewport.bottom,
            ),
            2,
        ),
        Triple(
            PopupSide.Above,
            PlacementBounds(
                left = viewport.left,
                top = viewport.top,
                right = viewport.right,
                bottom = min(safeAnchorTop - gapPx, viewport.bottom),
            ),
            3,
        ),
    )

    val bestCandidate =
        candidateSpecs
            .mapNotNull { (side, bounds, priority) -> buildCandidate(side, bounds, priority) }
            .sortedWith(
                compareByDescending<CandidatePlacement> { it.areaRatio }
                    .thenByDescending { min(it.widthRatio, it.heightRatio) }
                    .thenByDescending { it.widthRatio >= 1f && it.heightRatio >= 1f }
                    .thenBy { it.priority },
            )
            .firstOrNull()
            ?: return null

    val minimumWidthRatio = 0.55f
    val minimumHeightRatio = 0.40f
    val minimumAreaRatio = 0.35f
    val isUsableFloatingPopup =
        bestCandidate.widthRatio >= minimumWidthRatio &&
            bestCandidate.heightRatio >= minimumHeightRatio &&
            bestCandidate.areaRatio >= minimumAreaRatio

    return if (isUsableFloatingPopup) {
        bestCandidate.placement
    } else {
        null
    }
}
