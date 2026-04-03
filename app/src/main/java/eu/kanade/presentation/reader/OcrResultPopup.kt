package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.presentation.dictionary.components.SearchBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import kotlin.math.roundToInt

@Composable
fun OcrResultPopup(
    onDismissRequest: () -> Unit,
    text: String,
    anchorRect: RectF,
    settings: OcrResultPopupSettings,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
) {
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onQueryChange(text)
            onSearch(text)
        }
    }

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
        val contentWidthDp = with(density) { (popupWidthPx / contentScale).toDp() }
        val contentHeightDp = with(density) { (popupHeightPx / contentScale).toDp() }

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismissRequest,
                ),
        ) {
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
                    Column(
                        modifier = Modifier
                            .width(contentWidthDp)
                            .height(contentHeightDp)
                            .graphicsLayer(
                                scaleX = contentScale,
                                scaleY = contentScale,
                                transformOrigin = TransformOrigin(0f, 0f),
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SearchBar(
                            query = searchState.query,
                            onQueryChange = onQueryChange,
                            onSearch = { onSearch(searchState.query) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )

                        HorizontalDivider()

                        DictionaryResults(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            query = searchState.results?.query ?: "",
                            highlightRange = searchState.results?.highlightRange,
                            isLoading = searchState.isLoading,
                            isSearching = searchState.isSearching,
                            hasSearched = searchState.hasSearched,
                            searchResults = searchState.results?.items ?: emptyList(),
                            dictionaries = searchState.dictionaries,
                            enabledDictionaryIds = searchState.enabledDictionaryIds.toSet(),
                            termMetaMap = searchState.results?.termMetaMap ?: emptyMap(),
                            existingTermExpressions = searchState.existingTermExpressions,
                            onTermGroupClick = onTermGroupClick,
                            onQueryChange = onQueryChange,
                            onSearch = onSearch,
                            onCopyText = onCopyText,
                            contentPadding = PaddingValues(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class PopupPlacement(
    val x: Float,
    val y: Float,
)

private fun calculatePopupPlacement(
    anchorRect: RectF,
    popupWidthPx: Float,
    popupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement {
    val placements = listOf(
        PopupPlacement(anchorRect.right + gapPx, anchorRect.top),
        PopupPlacement(anchorRect.left - gapPx - popupWidthPx, anchorRect.top),
        PopupPlacement(anchorRect.left, anchorRect.bottom + gapPx),
        PopupPlacement(anchorRect.left, anchorRect.top - gapPx - popupHeightPx),
    )

    val fitsInViewport: (PopupPlacement) -> Boolean = { placement ->
        placement.x >= marginPx &&
            placement.y >= marginPx &&
            placement.x + popupWidthPx <= viewportWidthPx - marginPx &&
            placement.y + popupHeightPx <= viewportHeightPx - marginPx
    }

    val preferredPlacement = placements.firstOrNull(fitsInViewport) ?: placements.first()

    return PopupPlacement(
        x = preferredPlacement.x.coerceIn(
            minimumValue = marginPx,
            maximumValue = (viewportWidthPx - popupWidthPx - marginPx).coerceAtLeast(marginPx),
        ),
        y = preferredPlacement.y.coerceIn(
            minimumValue = marginPx,
            maximumValue = (viewportHeightPx - popupHeightPx - marginPx).coerceAtLeast(marginPx),
        ),
    )
}
