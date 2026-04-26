package eu.kanade.tachiyomi.ui.dictionary

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.dictionary.DictionaryPreferences
import eu.kanade.presentation.dictionary.components.DictionaryCardAudioState
import eu.kanade.presentation.dictionary.components.FrequencyFormatter
import eu.kanade.presentation.dictionary.components.PitchAccentFormatter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.ankidroid.interactor.AddDictionaryCard
import mihon.domain.ankidroid.interactor.FindExistingAnkiNotes
import mihon.domain.ankidroid.repository.AnkiDroidRepository
import mihon.domain.dictionary.audio.DictionaryAudio
import mihon.domain.dictionary.audio.DictionaryAudioPlayer
import mihon.domain.dictionary.audio.DictionaryAudioRepository
import mihon.domain.dictionary.audio.DictionaryAudioResult
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.SearchDictionaryTerms
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.buildGlossaryHtmlBundle
import mihon.domain.dictionary.model.createGroupedTermCard
import mihon.domain.dictionary.service.toHtml
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ankidroid.service.AnkiDroidPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DictionarySearchScreenModel(
    private val searchDictionaryTerms: SearchDictionaryTerms = Injekt.get(),
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
    private val addDictionaryCard: AddDictionaryCard = Injekt.get(),
    private val findExistingAnkiNotes: FindExistingAnkiNotes = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val ankiDroidPreferences: AnkiDroidPreferences = Injekt.get(),
    private val dictionaryAudioRepository: DictionaryAudioRepository = Injekt.get(),
    private val dictionaryAudioPlayer: DictionaryAudioPlayer = Injekt.get(),
) : StateScreenModel<DictionarySearchScreenModel.State>(State()) {

    val snackbarHostState = SnackbarHostState()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // Simple LRU cache for search results (max 10 entries)
    private val searchCache = object : LinkedHashMap<String, SearchCacheEntry>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, SearchCacheEntry>): Boolean {
            return size > 10
        }
    }
    private val audioCache = linkedMapOf<String, DictionaryAudio>()

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
                _events.send(Event.ShowError(UiMessage.Resource(MR.strings.dictionary_load_fail)))
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
                _events.send(Event.ShowError(UiMessage.Resource(MR.strings.dictionary_load_fail)))
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
                    _events.send(Event.ShowError(UiMessage.Resource(MR.strings.dictionary_no_enabled)))
                    mutableState.update { it.copy(isSearching = false, results = null) }
                    return@launch
                }

                // Get the longest dictionary match starting from the first character (or words).
                val parserLanguage = dictionaryPreferences.parserLanguageOverride().get()
                val firstWordMatch = searchDictionaryTerms.findFirstWordMatch(
                    sentence,
                    enabledDictionaryIds,
                    parserLanguage,
                )
                val word = firstWordMatch.word
                val rangeStart = highlightStart + firstWordMatch.sourceOffset
                val rangeEnd = rangeStart + firstWordMatch.sourceLength

                val cacheKey = "$word|${enabledDictionaryIds.joinToString(",")}|${parserLanguage.name}"

                // Check cache before searching
                val cachedEntry = searchCache[cacheKey]
                if (cachedEntry != null) {
                    logcat(LogPriority.DEBUG) { "Using cached results for: $word" }
                    mutableState.update {
                        it.copy(
                            results = SearchResults(
                                query = query,
                                highlightRange = rangeStart to rangeEnd,
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
                val items = searchDictionaryTerms.search(word, enabledDictionaryIds, parserLanguage)
                val expressions = items.map { it.expression }.distinct()
                val termMetaMap = searchDictionaryTerms.getTermMeta(expressions, enabledDictionaryIds)

                searchCache[cacheKey] = SearchCacheEntry(items, termMetaMap)

                mutableState.update {
                    it.copy(
                        results = SearchResults(
                            query = query,
                            highlightRange = rangeStart to rangeEnd,
                            items = items,
                            termMetaMap = termMetaMap,
                        ),
                        isSearching = false,
                        hasSearched = true,
                        existingTermExpressions = emptySet(),
                    )
                }

                // Proactively check which result expressions already exist in AnkiDroid
                checkExistingNotesInBackground(items.map { it.expression }.distinct())
            } catch (e: Exception) {
                mutableState.update { it.copy(isSearching = false) }
                _events.send(Event.ShowError(UiMessage.Resource(MR.strings.dictionary_search_failed)))
            }
        }
    }

    private fun checkExistingNotesInBackground(expressions: List<String>) {
        screenModelScope.launch {
            val existing = findExistingAnkiNotes(expressions)
            if (existing.isNotEmpty()) {
                mutableState.update { it.copy(existingTermExpressions = existing) }
            }
        }
    }

    fun selectTerm(term: DictionaryTerm) {
        mutableState.update { it.copy(selectedTerm = term) }
    }

    fun addGroupToAnki(terms: List<DictionaryTerm>, pictureUri: Uri? = null) {
        if (terms.isEmpty()) return
        screenModelScope.launch {
            try {
                val expression = terms.first().expression
                val reading = terms.first().reading
                val dicts = state.value.dictionaries

                // Build all glossary HTML variants in one pass
                val glossaryHtml = buildGlossaryHtmlBundle(terms, dicts) { term, styles ->
                    term.glossary.toHtml(styles)
                }

                // Shared metadata
                val query = state.value.query
                val sentence = if (query.isNotBlank() && query != expression) query else ""
                val termMeta = state.value.results?.termMetaMap?.get(expression) ?: emptyList()
                val pitchAccentSvg = PitchAccentFormatter.formatPitchAccentSvg(termMeta, reading)
                val frequencyText = formatFrequencyText(termMeta, reading)
                val pictureUrl = pictureUri?.toString() ?: ""
                val audio = if (ankiDroidPreferences.dictionaryAudioPrefill().get()) {
                    ensureAudioForExport(expression, reading)
                } else {
                    null
                }

                val frequencies = FrequencyFormatter.parseFrequencies(termMeta, reading)
                val numericValues = frequencies.mapNotNull { it.numericFrequency }
                val minValuesPerDict = frequencies
                    .filter { it.numericFrequency != null }
                    .groupBy { it.dictionaryId }
                    .mapValues { (_, dictFrequencies) -> dictFrequencies.minOf { it.numericFrequency!! } }

                val avgFreq = minValuesPerDict.values
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toInt()
                    ?.toString()
                    ?: ""
                val minFreq = numericValues.minOrNull()?.toString() ?: ""
                val singleFreqValues = minValuesPerDict.mapValues { it.value.toString() }

                val card = createGroupedTermCard(
                    expression = expression,
                    reading = reading,
                    terms = terms,
                    dictionaries = dicts,
                    glossaryHtml = glossaryHtml,
                    sentence = sentence,
                    audio = audio?.file?.absolutePath.orEmpty(),
                    pitchAccent = pitchAccentSvg,
                    frequency = frequencyText,
                    pictureUrl = pictureUrl,
                    freqAvgValue = avgFreq,
                    freqLowestValue = minFreq,
                    singleFreqValues = singleFreqValues,
                )

                handleAnkiResult(addDictionaryCard(card), expression)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to add term group to Anki" }
                _events.send(Event.ShowError(UiMessage.Resource(MR.strings.anki_add_failed)))
            }
        }
    }

    private suspend fun handleAnkiResult(result: AnkiDroidRepository.Result, expression: String) {
        when (result) {
            AnkiDroidRepository.Result.Added -> {
                mutableState.update {
                    it.copy(existingTermExpressions = it.existingTermExpressions + expression)
                }
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

    private fun formatFrequencyText(termMeta: List<DictionaryTermMeta>, reading: String): String {
        val frequencies = FrequencyFormatter.parseFrequencies(termMeta, reading)
        if (frequencies.isEmpty()) return ""

        val dictionaries = state.value.dictionaries
        val listItems = frequencies.joinToString("") { freqData ->
            val dictName = dictionaries.find { it.id == freqData.dictionaryId }?.title ?: ""
            val entry = if (dictName.isNotBlank()) "$dictName: ${freqData.frequency}" else freqData.frequency
            "<li>$entry</li>"
        }
        return "<ul>$listItems</ul>"
    }

    fun fetchAndPlayAudio(terms: List<DictionaryTerm>) {
        if (terms.isEmpty()) return
        val expression = terms.first().expression
        val reading = terms.first().reading
        screenModelScope.launch {
            val audio = ensureAudio(expression, reading, showFailure = true) ?: return@launch
            try {
                dictionaryAudioPlayer.play(audio)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to play dictionary audio" }
                markAudioState(expression, reading, DictionaryCardAudioState.Error)
            }
        }
    }

    private suspend fun ensureAudioForExport(
        expression: String,
        reading: String,
    ): DictionaryAudio? {
        val audio = ensureAudio(expression, reading, showFailure = false)
        return audio
    }

    private suspend fun ensureAudio(
        expression: String,
        reading: String,
        showFailure: Boolean,
    ): DictionaryAudio? {
        val key = audioKey(expression, reading)
        val cached = audioCache[key]
        if (cached != null && cached.file.isFile) {
            markAudioState(expression, reading, DictionaryCardAudioState.Ready)
            return cached
        }
        if (cached != null && !cached.file.isFile) {
            audioCache.remove(key)
        }

        markAudioState(expression, reading, DictionaryCardAudioState.Loading)
        return when (val result = dictionaryAudioRepository.fetchAudio(expression, reading)) {
            is DictionaryAudioResult.Success -> {
                audioCache[key] = result.audio
                markAudioState(expression, reading, DictionaryCardAudioState.Ready)
                result.audio
            }
            DictionaryAudioResult.NotFound -> {
                markAudioState(expression, reading, DictionaryCardAudioState.Error)
                null
            }
            is DictionaryAudioResult.Error -> {
                logcat(LogPriority.ERROR, result.throwable) { "Failed to fetch dictionary audio" }
                markAudioState(expression, reading, DictionaryCardAudioState.Error)
                null
            }
        }
    }

    private fun markAudioState(
        expression: String,
        reading: String,
        audioState: DictionaryCardAudioState,
    ) {
        val key = audioKey(expression, reading)
        mutableState.update {
            it.copy(audioStates = it.audioStates + (key to audioState))
        }
    }

    private fun audioKey(expression: String, reading: String): String {
        return "$expression|$reading"
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
        val existingTermExpressions: Set<String> = emptySet(),
        val audioStates: Map<String, DictionaryCardAudioState> = emptyMap(),
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
