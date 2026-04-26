package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.DictionaryTermMeta

interface DictionarySearchGateway {
    suspend fun exactSearch(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionarySearchEntry>

    suspend fun lookup(
        text: String,
        dictionaryIds: List<Long>,
        maxResults: Int,
    ): List<DictionaryLookupMatch>

    suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>>
}
