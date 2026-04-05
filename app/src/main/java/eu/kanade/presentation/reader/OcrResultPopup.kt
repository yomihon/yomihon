package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import eu.kanade.presentation.dictionary.components.DictResultContentScale
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
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
        val popupWidthPx = with(density) {
            settings.widthDp.dp.toPx().coerceAtMost((viewportWidthPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val popupHeightPx = with(density) {
            settings.heightDp.dp.toPx().coerceAtMost((viewportHeightPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val popupWidthDp = with(density) { popupWidthPx.toDp() }
        val popupHeightDp = with(density) { popupHeightPx.toDp() }
        val contentScale = settings.contentScale.coerceAtLeast(0.1f)
        val scaledDensity =
            remember(density, contentScale) {
                Density(
                    density = density.density * contentScale,
                    fontScale = density.fontScale,
                )
            }

        val placement =
            remember(anchorRect, popupWidthPx, popupHeightPx, viewportWidthPx, viewportHeightPx, gapPx, marginPx) {
                calculatePopupPlacement(
                    anchorRect = anchorRect,
                    popupWidthPx = popupWidthPx,
                    popupHeightPx = popupHeightPx,
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
                    .width(popupWidthDp)
                    .height(popupHeightDp)
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
                        DictResultContentScale provides contentScale,
                    ) {
                        DictionaryResults(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxSize(),
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
)

internal fun calculatePopupPlacement(
    anchorRect: RectF,
    popupWidthPx: Float,
    popupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement? {
    return calculatePopupPlacement(
        anchorLeft = anchorRect.left,
        anchorTop = anchorRect.top,
        anchorRight = anchorRect.right,
        anchorBottom = anchorRect.bottom,
        popupWidthPx = popupWidthPx,
        popupHeightPx = popupHeightPx,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        gapPx = gapPx,
        marginPx = marginPx,
    )
}

internal fun calculatePopupPlacement(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    popupWidthPx: Float,
    popupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement? {
    val placements = listOf(
        PopupPlacement(anchorRight + gapPx, anchorTop),
        PopupPlacement(anchorLeft - gapPx - popupWidthPx, anchorTop),
        PopupPlacement(anchorLeft, anchorBottom + gapPx),
        PopupPlacement(anchorLeft, anchorTop - gapPx - popupHeightPx),
    )

    return placements.firstOrNull { placement ->
        placement.x >= marginPx &&
            placement.y >= marginPx &&
            placement.x + popupWidthPx <= viewportWidthPx - marginPx &&
            placement.y + popupHeightPx <= viewportHeightPx - marginPx &&
            !rectsIntersect(
                firstLeft = anchorLeft,
                firstTop = anchorTop,
                firstRight = anchorRight,
                firstBottom = anchorBottom,
                secondLeft = placement.x,
                secondTop = placement.y,
                secondRight = placement.x + popupWidthPx,
                secondBottom = placement.y + popupHeightPx,
            )
    }
}

internal fun rectsIntersect(
    firstLeft: Float,
    firstTop: Float,
    firstRight: Float,
    firstBottom: Float,
    secondLeft: Float,
    secondTop: Float,
    secondRight: Float,
    secondBottom: Float,
): Boolean {
    return firstLeft < secondRight &&
        firstRight > secondLeft &&
        firstTop < secondBottom &&
        firstBottom > secondTop
}
