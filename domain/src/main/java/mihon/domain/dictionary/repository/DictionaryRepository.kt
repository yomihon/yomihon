package mihon.domain.dictionary.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryKanjiExport
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryKanjiMetaExport
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermExport
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.DictionaryTermMetaExport

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
        backend: String,
        storagePath: String?,
        storageReady: Boolean,
    )

    // Tag operations
    suspend fun insertTags(tags: List<DictionaryTag>)
    suspend fun getTagsForDictionary(dictionaryId: Long): List<DictionaryTag>
    suspend fun getTagCountForDictionary(dictionaryId: Long): Long
    suspend fun deleteTagsForDictionary(dictionaryId: Long)

    // Term operations
    suspend fun getTermsForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTerm>
    suspend fun getTermsExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTermExport>
    suspend fun getTermCountForDictionary(dictionaryId: Long): Long
    suspend fun insertTerms(terms: List<DictionaryTerm>)
    suspend fun searchTerms(query: String, dictionaryIds: List<Long>): List<DictionaryTerm>
    suspend fun getTermsByExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTerm>
    suspend fun deleteTermsForDictionary(dictionaryId: Long)

    // Kanji operations
    suspend fun getKanjiForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanji>
    suspend fun getKanjiExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanjiExport>
    suspend fun getKanjiCountForDictionary(dictionaryId: Long): Long
    suspend fun insertKanji(kanji: List<DictionaryKanji>)
    suspend fun getKanjiByCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanji>
    suspend fun deleteKanjiForDictionary(dictionaryId: Long)

    // Term meta operations
    suspend fun getTermMetaForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTermMeta>
    suspend fun getTermMetaExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTermMetaExport>
    suspend fun getTermMetaCountForDictionary(dictionaryId: Long): Long
    suspend fun insertTermMeta(termMeta: List<DictionaryTermMeta>)
    suspend fun getTermMetaForExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta>
    suspend fun deleteTermMetaForDictionary(dictionaryId: Long)

    // Kanji meta operations
    suspend fun getKanjiMetaForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanjiMeta>
    suspend fun getKanjiMetaExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanjiMetaExport>
    suspend fun getKanjiMetaCountForDictionary(dictionaryId: Long): Long
    suspend fun insertKanjiMeta(kanjiMeta: List<DictionaryKanjiMeta>)
    suspend fun getKanjiMetaForCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanjiMeta>
    suspend fun deleteKanjiMetaForDictionary(dictionaryId: Long)

    suspend fun getLegacyRowCounts(dictionaryId: Long): DictionaryLegacyRowCounts

    // Migration status operations
    suspend fun upsertMigrationStatus(status: DictionaryMigrationStatus)
    suspend fun getMigrationStatus(dictionaryId: Long): DictionaryMigrationStatus?
    suspend fun getAllMigrationStatuses(): List<DictionaryMigrationStatus>
    fun subscribeToMigrationStatuses(): Flow<List<DictionaryMigrationStatus>>
    suspend fun deleteMigrationStatus(dictionaryId: Long)
}
