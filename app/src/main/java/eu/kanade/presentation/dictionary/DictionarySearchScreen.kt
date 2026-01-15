package eu.kanade.presentation.dictionary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.css.parseDictionaryCss
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.extractAttributionText
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun DictionarySearchScreen(
    state: DictionarySearchScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
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
            // Search bar
            SearchBar(
                query = state.query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            // Results
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.fillMaxSize())
                }
                state.enabledDictionaryIds.isEmpty() -> {
                    NoDictionariesEnabledMessage(onOpenDictionarySettings = onOpenDictionarySettings)
                }
                state.isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.searchResults.isEmpty() && state.hasSearched -> {
                    EmptyScreen(
                        stringRes = MR.strings.no_results_found,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.searchResults.isNotEmpty() -> {
                    SearchResultsList(
                        results = state.searchResults,
                        dictionaries = state.dictionaries,
                        termMetaMap = state.termMetaMap,
                        onTermClick = onTermClick,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                    )
                }
                else -> {
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_dictionary_search,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(MR.strings.action_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun SearchResultsList(
    results: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    termMetaMap: Map<String, List<DictionaryTermMeta>>,
    onTermClick: (DictionaryTerm) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = results,
            key = { it.id },
        ) { term ->
            DictionaryTermCard(
                term = term,
                dictionaryName = dictionaries.find { it.id == term.dictionaryId }?.title ?: "",
                termMeta = termMetaMap[term.expression] ?: emptyList(),
                dictionaries = dictionaries,
                onClick = { onTermClick(term) },
                onQueryChange = onQueryChange,
                onSearch = onSearch,
            )
        }
    }
}

@Composable
internal fun DictionaryTermCard(
    term: DictionaryTerm,
    dictionaryName: String,
    termMeta: List<DictionaryTermMeta>,
    dictionaries: List<Dictionary>,
    onClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    var showAttribution by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = term.expression,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (term.reading.isNotBlank() && term.reading != term.expression) {
                Text(
                    text = term.reading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(6.dp))

            // Display pitch accent graphs if available
            PitchAccentSection(
                termMeta = termMeta,
                dictionaries = dictionaries,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Display frequency indicator if available (grouped by dictionary)
            val groupedFrequencyData = remember(termMeta) {
                FrequencyFormatter.parseGroupedFrequencies(termMeta)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groupedFrequencyData.take(16).forEach { freqInfo ->
                    // Find the dictionary name for the frequency entry
                    val sourceDictName = dictionaries.find { it.id == freqInfo.dictionaryId }?.title
                        ?: dictionaryName

                    val clipShape = remember { RoundedCornerShape(8.dp) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = clipShape,
                        )
                            .clip(clipShape)
                    ) {
                        Text(
                            text = sourceDictName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )

                        Text(
                            text = freqInfo.frequencies,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Check if this is a "forms" entry - if so, show it differently
            val isFormsEntry = term.definitionTags?.contains("forms") == true

            // Display definition tags if present and not forms
            val definitionTags = term.definitionTags
            if (!isFormsEntry && !definitionTags.isNullOrBlank()) {
                Text(
                    text = definitionTags.replace(",", " · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Get dictionary CSS styles for box style detection
            val dictionary = dictionaries.find { it.id == term.dictionaryId }
            val parsedCss = remember(dictionary?.styles) {
                parseDictionaryCss(dictionary?.styles)
            }

            GlossarySection(
                entries = term.glossary,
                isFormsEntry = isFormsEntry,
                modifier = Modifier.padding(vertical = 2.dp),
                parsedCss = parsedCss,
                onLinkClick = { linkText ->
                    val query = linkText.trim()
                    if (query.isNotEmpty()) {
                        onQueryChange(query)
                        onSearch()
                    }
                },
            )


            // Dictionary sources
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = dictionaryName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showAttribution = !showAttribution },
            )

            val pitchDictNames = getPitchAccentDictionaryNames(termMeta, dictionaries)

            // Attribution section (hidden by default, expands on dictionary name click)
            val attributionText = remember(term.glossary) {
                term.glossary.extractAttributionText()
            }
            if (attributionText != null) {
                AnimatedVisibility(visible = showAttribution) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAttribution = false },
                    ) {
                        if (pitchDictNames.isNotEmpty()) {
                            Text(
                                text = pitchDictNames.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = attributionText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoDictionariesEnabledMessage(
    onOpenDictionarySettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.strings.dictionary_no_enabled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(MR.strings.dictionary_manage_settings),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.clickable { onOpenDictionarySettings() }
            )
        }
    }
}
