package mihon.domain.dictionary.repository

import mihon.domain.dictionary.model.DictionaryKanjiExport
import mihon.domain.dictionary.model.DictionaryKanjiMetaExport
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryTermExport
import mihon.domain.dictionary.model.DictionaryTermMetaExport

interface DictionaryLegacyRepository {
    suspend fun getTermsExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTermExport>
    suspend fun getKanjiExportForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanjiExport>
    suspend fun getTermMetaExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryTermMetaExport>
    suspend fun getKanjiMetaExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryKanjiMetaExport>
    suspend fun getLegacyRowCounts(dictionaryId: Long): DictionaryLegacyRowCounts
    suspend fun deleteTagsForDictionary(dictionaryId: Long)
    suspend fun deleteTermsForDictionary(dictionaryId: Long)
    suspend fun deleteKanjiForDictionary(dictionaryId: Long)
    suspend fun deleteTermMetaForDictionary(dictionaryId: Long)
    suspend fun deleteKanjiMetaForDictionary(dictionaryId: Long)
}
