package mihon.domain.dictionary.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.Dictionary

interface DictionaryRepository {
    // Dictionary operations
    suspend fun insertDictionary(dictionary: Dictionary): Long
    suspend fun updateDictionary(dictionary: Dictionary)
    suspend fun bumpAllPrioritiesUp()
    suspend fun bumpDownPrioritiesAbove(priority: Int)
    suspend fun deleteDictionary(dictionaryId: Long)
    suspend fun getDictionary(dictionaryId: Long): Dictionary?
    suspend fun getAllDictionaries(): List<Dictionary>
    fun subscribeToDictionaries(): Flow<List<Dictionary>>

    // Tag operations
    suspend fun insertTags(tags: List<DictionaryTag>)
    suspend fun getTagsForDictionary(dictionaryId: Long): List<DictionaryTag>
    suspend fun deleteTagsForDictionary(dictionaryId: Long)

    // Term operations
    suspend fun insertTerms(terms: List<DictionaryTerm>)
    suspend fun searchTerms(query: String, dictionaryIds: List<Long>): List<DictionaryTerm>
    suspend fun getTermsByExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTerm>
    suspend fun deleteTermsForDictionary(dictionaryId: Long)

    // Kanji operations
    suspend fun insertKanji(kanji: List<DictionaryKanji>)
    suspend fun getKanjiByCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanji>
    suspend fun deleteKanjiForDictionary(dictionaryId: Long)

    // Term meta operations
    suspend fun insertTermMeta(termMeta: List<DictionaryTermMeta>)
    suspend fun getTermMetaForExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta>
    suspend fun deleteTermMetaForDictionary(dictionaryId: Long)

    // Kanji meta operations
    suspend fun insertKanjiMeta(kanjiMeta: List<DictionaryKanjiMeta>)
    suspend fun getKanjiMetaForCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanjiMeta>
    suspend fun deleteKanjiMetaForDictionary(dictionaryId: Long)
}
