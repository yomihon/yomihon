package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm

@Composable
fun OcrResultOverlay(
    onDismissRequest: () -> Unit,
    dimBackground: Boolean,
    text: String,
    anchorRect: RectF?,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
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

        OcrResultBottomSheet(
            onDismissRequest = onDismissRequest,
            text = text,
            onCopyText = onCopyText,
            searchState = searchState,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onTermGroupClick = onTermGroupClick,
        )
    }
}
