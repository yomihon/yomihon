package eu.kanade.tachiyomi.ui.dictionary

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import eu.kanade.presentation.dictionary.components.FrequencyFormatter
import eu.kanade.presentation.dictionary.components.PitchAccentFormatter
import mihon.domain.ankidroid.interactor.AddDictionaryCard
import mihon.domain.ankidroid.repository.AnkiDroidRepository
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.SearchDictionaryTerms
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.toDictionaryTermCard
import mihon.domain.dictionary.service.toHtml
import tachiyomi.core.common.util.system.logcat
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DictionarySearchScreenModel(
    private val searchDictionaryTerms: SearchDictionaryTerms = Injekt.get(),
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
    private val addDictionaryCard: AddDictionaryCard = Injekt.get(),
) : StateScreenModel<DictionarySearchScreenModel.State>(State()) {

    val snackbarHostState = SnackbarHostState()

    private val _events = Channel<Event>()
    val events: Flow<Event> = _events.receiveAsFlow()

    // Simple LRU cache for search results (max 10 entries)
    private val searchCache = object : LinkedHashMap<String, SearchCacheEntry>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, SearchCacheEntry>): Boolean {
            return size > 10
        }
    }

    private data class SearchCacheEntry(
        val results: List<DictionaryTerm>,
        val termMetaMap: Map<String, List<DictionaryTermMeta>>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    init {
        loadDictionaries()
    }

    fun refreshDictionaries() {
        screenModelScope.launch {
            try {
                val dictionaries = dictionaryInteractor.getAllDictionaries()
                mutableState.update { state ->
                    state.copy(
                        dictionaries = dictionaries,
                        enabledDictionaryIds = dictionaries.filter { it.isEnabled }.map { it.id },
                        isLoading = false,
                    )
                }
                // Dictionary changes may invalidate cache
                searchCache.clear()
                if (mutableState.value.query.isNotBlank()) {
                    search(mutableState.value.query)
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false) }
                _events.send(Event.ShowError(UiMessage.Text(e.message ?: "Failed to load dictionaries")))
            }
        }
    }

    private fun loadDictionaries() {
        screenModelScope.launch {
            try {
                val dictionaries = dictionaryInteractor.getAllDictionaries()
                mutableState.update { state ->
                    state.copy(
                        dictionaries = dictionaries,
                        enabledDictionaryIds = dictionaries.filter { it.isEnabled }.map { it.id },
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false) }
                _events.send(Event.ShowError(UiMessage.Text(e.message ?: "Failed to load dictionaries")))
            }
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    /**
     * Updates dictionary results without needing changes to the query.
     */
    fun search(sentence: String) {
        if (sentence.isBlank()) {
            mutableState.update { it.copy(results = null) }
            return
        }

        // Calculate highlight offset: sentence is always a suffix of query
        val query = state.value.query
        val highlightStart = if (query.endsWith(sentence, ignoreCase = true)) {
            query.length - sentence.length
        } else {
            0
        }

        mutableState.update {
            it.copy(
                isSearching = true,
                results = (it.results ?: SearchResults(query = query)).copy(
                    highlightRange = highlightStart to highlightStart,
                ),
            )
        }

        screenModelScope.launch {
            try {
                val enabledDictionaryIds = state.value.enabledDictionaryIds
                if (enabledDictionaryIds.isEmpty()) {
                    _events.send(Event.ShowError(UiMessage.Text("No dictionaries enabled")))
                    mutableState.update { it.copy(isSearching = false, results = null) }
                    return@launch
                }

                // Get the longest dictionary match starting from the first character
                val word = searchDictionaryTerms.findFirstWord(sentence, enabledDictionaryIds)

                val cacheKey = "$word|${enabledDictionaryIds.joinToString(",")}"

                // Check cache before searching
                val cachedEntry = searchCache[cacheKey]
                if (cachedEntry != null) {
                    logcat(LogPriority.DEBUG) { "Using cached results for: $word" }
                    mutableState.update {
                        it.copy(
                            results = SearchResults(
                                query = query,
                                highlightRange = highlightStart to (highlightStart + word.length),
                                items = cachedEntry.results,
                                termMetaMap = cachedEntry.termMetaMap,
                            ),
                            isSearching = false,
                            hasSearched = true,
                        )
                    }
                    return@launch
                }

                // Fetch term results and meta (frequency data) for all results
                val items = searchDictionaryTerms.search(word, enabledDictionaryIds)
                val expressions = items.map { it.expression }.distinct()
                val termMetaMap = searchDictionaryTerms.getTermMeta(expressions, enabledDictionaryIds)

                searchCache[cacheKey] = SearchCacheEntry(items, termMetaMap)

                mutableState.update {
                    it.copy(
                        results = SearchResults(
                            query = query,
                            highlightRange = highlightStart to (highlightStart + word.length),
                            items = items,
                            termMetaMap = termMetaMap,
                        ),
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isSearching = false) }
                _events.send(Event.ShowError(UiMessage.Text(e.message ?: "Search failed")))
            }
        }
    }

    fun selectTerm(term: DictionaryTerm) {
        mutableState.update { it.copy(selectedTerm = term) }
    }

    fun addToAnki(term: DictionaryTerm, pictureUri: Uri? = null) {
        screenModelScope.launch {
            val dictionary = state.value.dictionaries
                .firstOrNull { it.id == term.dictionaryId }
            val dictionaryName = dictionary?.title.orEmpty()
            val styles = dictionary?.styles
            val glossaryHtml = term.glossary.toHtml(styles)

            // Determine sentence: use query unless it matches the exported word, in which case it's just a duplicate
            val query = state.value.query
            val sentence = if (query.isNotBlank() && query != term.expression) query else ""

            val termMeta = state.value.results?.termMetaMap?.get(term.expression) ?: emptyList()
            val pitchAccentSvg = PitchAccentFormatter.formatPitchAccentSvg(termMeta)
            val frequencyText = formatFrequencyText(termMeta)
            val pictureUrl = pictureUri?.toString() ?: ""

            val card = term.toDictionaryTermCard(
                dictionaryName = dictionaryName,
                glossaryHtml = glossaryHtml,
                sentence = sentence,
                pitchAccent = pitchAccentSvg,
                frequency = frequencyText,
                pictureUrl = pictureUrl,
            )

            when (val result = addDictionaryCard(card)) {
                AnkiDroidRepository.Result.Added -> {
                    _events.send(Event.ShowMessage(UiMessage.Resource(MR.strings.anki_add_success)))
                }
                AnkiDroidRepository.Result.Duplicate -> {
                    _events.send(Event.ShowMessage(UiMessage.Resource(MR.strings.anki_add_duplicate)))
                }
                AnkiDroidRepository.Result.NotAvailable -> {
                    _events.send(Event.ShowError(UiMessage.Resource(MR.strings.anki_add_not_available)))
                }
                is AnkiDroidRepository.Result.Error -> {
                    logcat(LogPriority.ERROR, result.throwable)
                    _events.send(Event.ShowError(UiMessage.Resource(MR.strings.anki_add_failed)))
                }
            }
        }
    }

    private fun formatFrequencyText(termMeta: List<DictionaryTermMeta>): String {
        val grouped = FrequencyFormatter.parseGroupedFrequencies(termMeta)
        if (grouped.isEmpty()) return ""

        val dictionaries = state.value.dictionaries
        val listItems = grouped.joinToString("") { freqData ->
            val dictName = dictionaries.find { it.id == freqData.dictionaryId }?.title ?: ""
            val entry = if (dictName.isNotBlank()) "$dictName: ${freqData.frequencies}" else freqData.frequencies
            "<li>$entry</li>"
        }
        return "<ul>$listItems</ul>"
    }

    @Immutable
    data class SearchResults(
        val query: String,
        val highlightRange: Pair<Int, Int>? = null,
        val items: List<DictionaryTerm> = emptyList(),
        val termMetaMap: Map<String, List<DictionaryTermMeta>> = emptyMap(),
    )

    @Immutable
    data class State(
        val query: String = "",
        val results: SearchResults? = null,
        val dictionaries: List<Dictionary> = emptyList(),
        val enabledDictionaryIds: List<Long> = emptyList(),
        val selectedTerm: DictionaryTerm? = null,
        val isLoading: Boolean = true,
        val isSearching: Boolean = false,
        val hasSearched: Boolean = false,
    )

    sealed interface UiMessage {
        data class Text(val value: String) : UiMessage
        data class Resource(val value: StringResource) : UiMessage
    }

    sealed interface Event {
        data class ShowError(val message: UiMessage) : Event
        data class ShowMessage(val message: UiMessage) : Event
    }
}
