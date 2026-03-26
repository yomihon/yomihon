package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta

interface DictionarySearchBackend {
    suspend fun exactSearch(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionarySearchEntry>

    suspend fun lookup(
        text: String,
        dictionaryIds: List<Long>,
        maxResults: Int,
    ): List<DictionaryLookupMatch>
}

data class DictionarySearchEntry(
    val term: DictionaryTerm,
    val termMeta: List<DictionaryTermMeta>,
)

data class DictionaryLookupMatch(
    val matched: String,
    val deinflected: String,
    val process: List<String>,
    val term: DictionaryTerm,
    val termMeta: List<DictionaryTermMeta>,
)
