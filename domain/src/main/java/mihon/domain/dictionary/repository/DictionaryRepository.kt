package mihon.domain.dictionary.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta

interface DictionaryRepository {
    // Dictionary operations
    suspend fun insertDictionary(dictionary: Dictionary): Long
    suspend fun updateDictionary(dictionary: Dictionary)
    suspend fun bumpAllPrioritiesUp()
    suspend fun bumpDownPrioritiesAbove(priority: Int)
    suspend fun deleteDictionary(dictionaryId: Long)
    suspend fun getDictionary(dictionaryId: Long): Dictionary?
    suspend fun getAllDictionaries(): List<Dictionary>
    suspend fun getLegacyDictionaries(): List<Dictionary>
    fun subscribeToDictionaries(): Flow<List<Dictionary>>
    suspend fun getFreqDictionaryIds(): List<Long>
    suspend fun updateDictionaryStorage(
        dictionaryId: Long,
        backend: DictionaryBackend,
        storagePath: String?,
        storageReady: Boolean,
    )

    // Tag operations
    suspend fun getTagsForDictionary(dictionaryId: Long): List<DictionaryTag>

    // Term operations
    suspend fun searchTerms(query: String, dictionaryIds: List<Long>): List<DictionaryTerm>

    // Term meta operations
    suspend fun getTermMetaForExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta>
}
