package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.domain.dictionary.OcrResultPresentation
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm

data class OcrResultPopupSettings(
    val widthDp: Int,
    val heightDp: Int,
    val contentScale: Float,
)

@Composable
fun OcrResultOverlay(
    onDismissRequest: () -> Unit,
    presentation: OcrResultPresentation,
    popupSettings: OcrResultPopupSettings,
    dimBackground: Boolean,
    text: String,
    initialSearchText: String = text,
    anchorRect: RectF?,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
) {
    BackHandler(onBack = onDismissRequest)

    Box(modifier = Modifier.fillMaxSize()) {
        if (dimBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
            )
        }

        when {
            presentation == OcrResultPresentation.POPUP && anchorRect != null -> {
                OcrResultPopup(
                    onDismissRequest = onDismissRequest,
                    text = text,
                    initialSearchText = initialSearchText,
                    anchorRect = anchorRect,
                    settings = popupSettings,
                    onCopyText = onCopyText,
                    searchState = searchState,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onTermGroupClick = onTermGroupClick,
                    onPlayAudioClick = onPlayAudioClick,
                )
            }
            else -> {
                OcrResultBottomSheet(
                    onDismissRequest = onDismissRequest,
                    text = text,
                    initialSearchText = initialSearchText,
                    onCopyText = onCopyText,
                    searchState = searchState,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onTermGroupClick = onTermGroupClick,
                    onPlayAudioClick = onPlayAudioClick,
                )
            }
        }
    }
}
