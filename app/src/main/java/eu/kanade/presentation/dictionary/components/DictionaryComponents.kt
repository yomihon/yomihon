package eu.kanade.presentation.dictionary.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
 * Data class representing a group of dictionary terms with the same expression+reading.
 */
data class TermGroup(
    val expression: String,
    val reading: String,
    val terms: List<DictionaryTerm>,
)

sealed interface DictionaryCardAudioState {
    data object Idle : DictionaryCardAudioState

    data object Loading : DictionaryCardAudioState

    data object Ready : DictionaryCardAudioState

    data object Error : DictionaryCardAudioState
}

/**
 * Unified component for displaying dictionary search results.
 * Handles loading, empty, no dictionaries, and results states.
 */
@Composable
fun DictionaryResults(
    modifier: Modifier = Modifier,
    query: String = "",
    highlightRange: Pair<Int, Int>? = null,
    showQueryHeader: Boolean = true,
    isLoading: Boolean,
    isSearching: Boolean,
    hasSearched: Boolean,
    searchResults: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    enabledDictionaryIds: Set<Long>,
    termMetaMap: Map<String, List<DictionaryTermMeta>>,
    existingTermExpressions: Set<String> = emptySet(),
    audioStates: Map<String, DictionaryCardAudioState> = emptyMap(),
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCopyText: (() -> Unit)? = null,
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
            ) {
                if (showQueryHeader && query.isNotBlank()) {
                    WordSelector(
                        text = query,
                        highlightRange = null,
                        onSearch = { onSearch(it) },
                        modifier = Modifier.padding(contentPadding),
                        onTrailingAction = onCopyText,
                    )
                }
                EmptyScreen(
                    stringRes = MR.strings.no_results_found,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        searchResults.isNotEmpty() -> {
            SearchResultsList(
                results = searchResults,
                dictionaries = dictionaries,
                termMetaMap = termMetaMap,
                existingTermExpressions = existingTermExpressions,
                audioStates = audioStates,
                onTermGroupClick = onTermGroupClick,
                onPlayAudioClick = onPlayAudioClick,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onCopyText = onCopyText,
                query = query,
                highlightRange = highlightRange,
                showQueryHeader = showQueryHeader,
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
    existingTermExpressions: Set<String>,
    audioStates: Map<String, DictionaryCardAudioState>,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCopyText: (() -> Unit)? = null,
    query: String,
    highlightRange: Pair<Int, Int>?,
    showQueryHeader: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    // Group terms by (expression, reading) to show them in a single card
    val termGroups = remember(results) {
        results
            .groupBy { it.expression to it.reading }
            .map { (key, terms) ->
                TermGroup(
                    expression = key.first,
                    reading = key.second,
                    terms = terms,
                )
            }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        if (showQueryHeader && query.isNotBlank()) {
            item {
                WordSelector(
                    text = query,
                    highlightRange = highlightRange,
                    onSearch = { onSearch(it) },
                    onTrailingAction = onCopyText,
                )
            }
        }

        items(
            items = termGroups,
            key = { "${it.expression}|${it.reading}" },
        ) { group ->
            GroupedTermCard(
                group = group,
                dictionaries = dictionaries,
                termMeta = termMetaMap[group.expression] ?: emptyList(),
                isDuplicatePending = group.expression in existingTermExpressions,
                audioState = audioStates["${group.expression}|${group.reading}"] ?: DictionaryCardAudioState.Idle,
                onClick = { onTermGroupClick(group.terms) },
                onPlayAudioClick = { onPlayAudioClick(group.terms) },
                onQueryChange = onQueryChange,
                onSearch = onSearch,
            )
        }
    }
}

@Composable
private fun GroupedTermCard(
    group: TermGroup,
    dictionaries: List<Dictionary>,
    termMeta: List<DictionaryTermMeta>,
    isDuplicatePending: Boolean,
    audioState: DictionaryCardAudioState,
    onClick: () -> Unit,
    onPlayAudioClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    var showAttribution by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Expression + reading header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.expression,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (group.reading.isNotBlank() && group.reading != group.expression) {
                        Text(
                            text = group.reading,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onPlayAudioClick,
                        enabled = audioState != DictionaryCardAudioState.Loading,
                    ) {
                        if (audioState == DictionaryCardAudioState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = stringResource(MR.strings.action_play_audio),
                                tint = if (audioState == DictionaryCardAudioState.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = onClick,
                    ) {
                        val (icon, tint) = if (isDuplicatePending) {
                            Icons.Default.LibraryAddCheck to MaterialTheme.colorScheme.primary
                        } else {
                            Icons.Default.Add to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(MR.strings.action_add),
                            tint = tint,
                        )
                    }
                }
            }

            Spacer(Modifier.size(6.dp))

            // Display pitch accent graphs if available (use first term's reading)
            PitchAccentSection(
                termMeta = termMeta,
                dictionaries = dictionaries,
                termReading = group.reading,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Display frequency indicators (grouped by dictionary)
            val groupedFrequencyData = remember(termMeta, group.reading) {
                FrequencyFormatter.parseGroupedFrequencies(termMeta, group.reading)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groupedFrequencyData.take(16).forEach { freqInfo ->
                    val sourceDictName = dictionaries.find { it.id == freqInfo.dictionaryId }?.title ?: ""
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

            // Group terms by dictionary for numbered display
            val termsByDictionary = remember(group.terms) {
                group.terms.groupBy { it.dictionaryId }
            }

            // Global definition counter (1-based across all dictionaries)
            var globalIndex = 1

            termsByDictionary.entries.forEachIndexed { dictGroupIndex, (dictionaryId, terms) ->
                val dictionary = dictionaries.find { it.id == dictionaryId }
                val dictionaryName = dictionary?.title ?: ""
                val parsedCss = remember(dictionary?.styles) {
                    parseDictionaryCss(dictionary?.styles)
                }

                // Dictionary separator (between dictionary groups)
                if (dictGroupIndex > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                // Render each definition entry within this dictionary
                terms.forEachIndexed { termIndex, term ->
                    val isFormsEntry = term.definitionTags?.contains("forms") == true

                    // Definition number + tags header row
                    Row(
                        modifier = Modifier.padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Numbered definition index badge
                        if (!isFormsEntry) {
                            val indexBadgeShape = remember { RoundedCornerShape(4.dp) }
                            Text(
                                text = "$globalIndex",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.secondary,
                                        shape = indexBadgeShape,
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }

                        // Definition tags (e.g. "n", "v5r", etc.)
                        val definitionTags = term.definitionTags
                        if (!isFormsEntry && !definitionTags.isNullOrBlank()) {
                            definitionTags.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                                val tagShape = remember { RoundedCornerShape(4.dp) }
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = tagShape,
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }

                        // Dictionary name badge
                        if (dictionaryName.isNotBlank()) {
                            val dictBadgeShape = remember { RoundedCornerShape(4.dp) }
                            Text(
                                text = dictionaryName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = dictBadgeShape,
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }

                    // Glossary content
                    GlossarySection(
                        entries = term.glossary,
                        isFormsEntry = isFormsEntry,
                        modifier = Modifier.padding(vertical = 2.dp),
                        parsedCss = parsedCss,
                        onLinkClick = { linkText ->
                            val q = linkText.trim()
                            if (q.isNotEmpty()) {
                                onQueryChange(q)
                                onSearch(q)
                            }
                        },
                    )

                    if (!isFormsEntry) {
                        globalIndex++
                    }
                }
            }

            // Attribution section
            Spacer(modifier = Modifier.height(4.dp))

            // Collect all dictionary names for the group
            val dictionaryNames = remember(group.terms) {
                group.terms.mapNotNull { term ->
                    dictionaries.find { it.id == term.dictionaryId }?.title
                }.distinct()
            }

            // Clickable dictionary names that expand to show attribution
            Text(
                text = dictionaryNames.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showAttribution = !showAttribution },
            )

            // Attribution text from first available entry
            val attributionText = remember(group.terms) {
                group.terms.firstNotNullOfOrNull { it.glossary.extractAttributionText() }
            }
            if (attributionText != null) {
                AnimatedVisibility(visible = showAttribution) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAttribution = false },
                    ) {
                        val pitchDictNames = getPitchAccentDictionaryNames(
                            termMeta = termMeta,
                            dictionaries = dictionaries,
                            termReading = group.reading,
                        )
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
    modifier: Modifier = Modifier,
    onTrailingAction: (() -> Unit)? = null,
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
                    ),
                ) {
                    append(text.substring(start, endIndex))
                }
                append(text.substring(endIndex))
            }
        } else {
            AnnotatedString(text)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                .weight(1f)
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

        if (onTrailingAction != null) {
            IconButton(onClick = onTrailingAction) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.action_copy),
                )
            }
        }
    }
}
