package eu.kanade.presentation.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.presentation.dictionary.components.SearchBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DictionarySearchScreen(
    state: DictionarySearchScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermClick: (DictionaryTerm) -> Unit,
    onOpenDictionarySettings: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_dictionary),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            SearchBar(
                query = state.query,
                onQueryChange = onQueryChange,
                onSearch = { onSearch(state.query) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            DictionaryResults(
                query = state.results?.query ?: "",
                highlightRange = state.results?.highlightRange,
                isLoading = state.isLoading,
                isSearching = state.isSearching,
                hasSearched = state.hasSearched,
                searchResults = state.results?.items ?: emptyList(),
                dictionaries = state.dictionaries,
                enabledDictionaryIds = state.enabledDictionaryIds.toSet(),
                termMetaMap = state.results?.termMetaMap ?: emptyMap(),
                existingTermExpressions = state.existingTermExpressions,
                onTermClick = onTermClick,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onOpenDictionarySettings = onOpenDictionarySettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
