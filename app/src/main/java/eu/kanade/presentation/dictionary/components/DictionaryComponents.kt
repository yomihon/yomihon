package eu.kanade.presentation.dictionary.components

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.domain.dictionary.css.parseDictionaryCss
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.extractAttributionText
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Unified component for displaying dictionary search results.
 * Handles loading, empty, no dictionaries, and results states.
 */
@Composable
fun DictionaryResults(
    modifier: Modifier = Modifier,
    query: String = "",
    highlightRange: Pair<Int, Int>? = null,
    isLoading: Boolean,
    isSearching: Boolean,
    hasSearched: Boolean,
    searchResults: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    enabledDictionaryIds: Set<Long>,
    termMetaMap: Map<String, List<DictionaryTermMeta>>,
    onTermClick: (DictionaryTerm) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenDictionarySettings: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    when {
        isLoading -> {
            LoadingScreen(modifier.fillMaxSize())
        }
        dictionaries.isEmpty() || enabledDictionaryIds.isEmpty() -> {
            if (onOpenDictionarySettings != null) {
                NoDictionariesEnabledMessage(onOpenDictionarySettings = onOpenDictionarySettings)
            } else {
                Box(
                    modifier = modifier.fillMaxSize(),
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
        }
        isSearching -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        searchResults.isEmpty() && hasSearched -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (query.isNotBlank()) {
                    WordSelector(
                        text = query,
                        highlightRange = null,
                        onSearch = { onSearch(it) },
                    )
                }
                EmptyScreen(
                    stringRes = MR.strings.no_results_found,
                    modifier = modifier.fillMaxSize(),
                )
            }
        }
        searchResults.isNotEmpty() -> {
            SearchResultsList(
                results = searchResults,
                dictionaries = dictionaries,
                termMetaMap = termMetaMap,
                onTermClick = onTermClick,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                query = query,
                highlightRange = highlightRange,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        else -> {
            EmptyScreen(
                stringRes = MR.strings.information_empty_dictionary_search,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(MR.strings.action_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
    )
}

@Composable
private fun SearchResultsList(
    results: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    termMetaMap: Map<String, List<DictionaryTermMeta>>,
    onTermClick: (DictionaryTerm) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    query: String,
    highlightRange: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        if (query.isNotBlank()) {
            item {
                WordSelector(
                    text = query,
                    highlightRange = highlightRange,
                    onSearch = { onSearch(it) },
                )
            }
        }

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
private fun DictionaryTermCard(
    term: DictionaryTerm,
    dictionaryName: String,
    termMeta: List<DictionaryTermMeta>,
    dictionaries: List<Dictionary>,
    onClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groupedFrequencyData.take(16).forEach { freqInfo ->
                    // Find the dictionary name for the frequency entry
                    val sourceDictName = dictionaries.find { it.id == freqInfo.dictionaryId }?.title
                        ?: dictionaryName

                    val clipShape = remember { RoundedCornerShape(8.dp) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = clipShape,
                            )
                            .clip(clipShape),
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
                        onSearch(query)
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
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.clickable { onOpenDictionarySettings() },
            )
        }
    }
}

@Composable
private fun WordSelector(
    text: String,
    highlightRange: Pair<Int, Int>? = null,
    onSearch: (String) -> Unit,
) {
    // OCR text header - clickable to search from any character
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val highlightContainerColor = MaterialTheme.colorScheme.primaryContainer
    val highlightContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val annotatedString = remember(text, highlightRange, highlightContainerColor, highlightContentColor) {
        val start = highlightRange?.first
        val end = highlightRange?.second
        if (start != null && end != null && start < end && start in text.indices) {
            buildAnnotatedString {
                append(text.take(start))
                val endIndex = end.coerceAtMost(text.length)
                withStyle(
                    style = SpanStyle(
                        background = highlightContainerColor,
                        color = highlightContentColor,
                    )
                ) {
                    append(text.substring(start, endIndex))
                }
                append(text.substring(endIndex))
            }
        } else {
            AnnotatedString(text)
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Normal,
        ),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(text) {
                detectTapGestures { pos ->
                    layout?.let { layoutResult ->
                        val offset = layoutResult.getOffsetForPosition(pos)
                        if (offset < text.length) {
                            onSearch(text.substring(offset))
                        }
                    }
                }
            },
    )
}
