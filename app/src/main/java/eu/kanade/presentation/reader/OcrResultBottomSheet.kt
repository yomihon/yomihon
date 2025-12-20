package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.dictionary.DictionaryTermCard
import eu.kanade.presentation.dictionary.SearchBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ResizableSheet
import tachiyomi.presentation.core.components.SheetValue
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

private const val SHEET_EXPANSION_THRESHOLD = 0.80f

@Composable
fun OcrResultBottomSheet(
    onDismissRequest: () -> Unit,
    text: String,
    onCopyText: () -> Unit,
    searchScreenModel: DictionarySearchScreenModel,
) {
    val searchState by searchScreenModel.state.collectAsState()

    // Automatically search dictionary for the OCR text
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            searchScreenModel.updateQuery(text)
            searchScreenModel.search()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ResizableSheet(
            onDismissRequest = onDismissRequest,
            initialValue = SheetValue.PartiallyExpanded,
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
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Dictionary search bar (editable, initialized with OCR text)
                        SearchBar(
                            query = searchState.query,
                            onQueryChange = { query ->
                                searchScreenModel.updateQuery(query)
                            },
                            onSearch = {
                                searchScreenModel.search()
                            },
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

                // Dictionary results
                val resultModifier = Modifier.fillMaxWidth().weight(1f)
                when {
                    searchState.isLoading -> {
                        // Initial state - loading dictionaries
                        Box(
                            modifier = resultModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    searchState.dictionaries.isEmpty() || searchState.enabledDictionaryIds.isEmpty() -> {
                        // No dictionaries enabled
                        Box(
                            modifier = resultModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(MR.strings.no_dictionaries_enabled),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    searchState.isSearching -> {
                        Box(
                            modifier = resultModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    searchState.searchResults.isEmpty() && searchState.hasSearched -> {
                        Box(
                            modifier = resultModifier,
                        ) {
                            EmptyScreen(
                                stringRes = MR.strings.no_results_found,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    searchState.searchResults.isNotEmpty() -> {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = resultModifier,
                        ) {
                            items(
                                items = searchState.searchResults,
                                key = { it.id },
                            ) { term ->
                                DictionaryTermCard(
                                    term = term,
                                    dictionaryName = searchState.dictionaries.find { it.id == term.dictionaryId }?.title ?: "",
                                    termMeta = searchState.termMetaMap[term.expression] ?: emptyList(),
                                    dictionaries = searchState.dictionaries,
                                    onClick = { /* TODO: Anki */ },
                                    onQueryChange = { query ->
                                        searchScreenModel.updateQuery(query)
                                    },
                                    onSearch = {
                                        searchScreenModel.search()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}