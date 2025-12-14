package eu.kanade.tachiyomi.ui.dictionary

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
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.SearchDictionaryTerms
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DictionarySearchScreenModel(
    private val searchDictionaryTerms: SearchDictionaryTerms = Injekt.get(),
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
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
                    search()
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false) }
                _events.send(Event.ShowError(e.message ?: "Failed to load dictionaries"))
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
                _events.send(Event.ShowError(e.message ?: "Failed to load dictionaries"))
            }
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun search() {
        val sentence = state.value.query
        if (sentence.isBlank()) {
            mutableState.update { it.copy(searchResults = emptyList(), termMetaMap = emptyMap()) }
            return
        }

        mutableState.update { it.copy(isSearching = true) }

        screenModelScope.launch {
            try {
                val enabledDictionaryIds = state.value.enabledDictionaryIds
                if (enabledDictionaryIds.isEmpty()) {
                    _events.send(Event.ShowError("No dictionaries enabled"))
                    mutableState.update { it.copy(isSearching = false, searchResults = emptyList(), termMetaMap = emptyMap()) }
                    return@launch
                }

                // Get the longest dictionary match starting from the first character
                val word = searchDictionaryTerms.getWord(sentence, enabledDictionaryIds)

                val cacheKey = "$word|${enabledDictionaryIds.joinToString(",")}"

                // Check cache before searching
                val cachedEntry = searchCache[cacheKey]
                if (cachedEntry != null) {
                    logcat(LogPriority.DEBUG) { "Using cached results for: $word" }
                    mutableState.update {
                        it.copy(
                            searchResults = cachedEntry.results,
                            termMetaMap = cachedEntry.termMetaMap,
                            isSearching = false,
                            hasSearched = true,
                        )
                    }
                }

                // Fetch term results and meta (frequency data) for all results
                val results = searchDictionaryTerms.search(word, enabledDictionaryIds)
                val expressions = results.map { it.expression }.distinct()
                val termMetaMap = searchDictionaryTerms.getTermMeta(expressions, enabledDictionaryIds)

                searchCache[cacheKey] = SearchCacheEntry(results, termMetaMap)

                mutableState.update {
                    it.copy(
                        searchResults = results,
                        termMetaMap = termMetaMap,
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isSearching = false) }
                _events.send(Event.ShowError(e.message ?: "Search failed"))
            }
        }
    }

    fun selectTerm(term: DictionaryTerm) {
        mutableState.update { it.copy(selectedTerm = term) }
    }

    @Immutable
    data class State(
        val query: String = "",
        val searchResults: List<DictionaryTerm> = emptyList(),
        val dictionaries: List<Dictionary> = emptyList(),
        val enabledDictionaryIds: List<Long> = emptyList(),
        val selectedTerm: DictionaryTerm? = null,
        val termMetaMap: Map<String, List<DictionaryTermMeta>> = emptyMap(),
        val isLoading: Boolean = true,
        val isSearching: Boolean = false,
        val hasSearched: Boolean = false,
    )

    sealed interface Event {
        data class ShowError(val message: String) : Event
    }
}
