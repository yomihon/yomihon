package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.presentation.dictionary.components.SearchBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ResizableSheet
import tachiyomi.presentation.core.components.SheetValue
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private const val SHEET_EXPANSION_THRESHOLD = 0.80f

@Composable
fun OcrResultBottomSheet(
    onDismissRequest: () -> Unit,
    text: String,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermClick: (DictionaryTerm) -> Unit,
) {
    // Automatically search dictionary for the OCR text
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onQueryChange(text)
            onSearch(text)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Use BoxWithConstraints to measure the actual available space for the sheet content
        BoxWithConstraints(
            contentAlignment = Alignment.BottomCenter,
        ) {
            val useSideSheet = maxWidth >= 600.dp

            ResizableSheet(
                onDismissRequest = onDismissRequest,
                initialValue = SheetValue.PartiallyExpanded,
                contentAlignment = if (useSideSheet) Alignment.BottomEnd else Alignment.BottomCenter,
                sheetModifier = if (useSideSheet) Modifier.width(400.dp) else Modifier.fillMaxWidth(),
            ) { expansionFraction ->
                val isSheetExpanded = expansionFraction >= SHEET_EXPANSION_THRESHOLD
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    if (isSheetExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchBar(
                                query = searchState.query,
                                onQueryChange = onQueryChange,
                                onSearch = { onSearch(searchState.query) },
                                modifier = Modifier.weight(1f),
                            )

                            FilledTonalButton(
                                onClick = {
                                    onCopyText()
                                },
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(MR.strings.action_copy),
                                )
                            }
                        }

                        HorizontalDivider()
                    }

                    DictionaryResults(
                        modifier = Modifier.fillMaxWidth().weight(1f),
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
                        onTermClick = onTermClick,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                        contentPadding = PaddingValues(bottom = 8.dp),
                    )
                }
            }
        }
    }
}
