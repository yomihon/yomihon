package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.repository.DictionaryRepository

/**
 * Interactor for importing compatible dictionaries.
 * Uses streaming with batched database inserts for memory efficiency.
 */
class ImportDictionary(
    private val dictionaryRepository: DictionaryRepository,
) {
    companion object {
        private const val BATCH_SIZE = 500
    }

    /**
     * Creates a dictionary record in the database.
     * The new dictionary is assigned priority 1 (highest), and all existing
     * dictionaries have their priorities bumped up by 1.
     *
     * @param index The parsed index.json metadata
     * @param styles Optional CSS styles from the dictionary's styles.css file
     * @return The ID of the created dictionary
     */
    suspend fun createDictionary(index: DictionaryIndex, styles: String? = null): Long {
        dictionaryRepository.bumpAllPrioritiesUp()

        val dictionary = Dictionary(
            title = index.title,
            revision = index.revision,
            version = index.effectiveVersion,
            author = index.author,
            url = index.url,
            description = index.description,
            attribution = index.attribution,
            styles = styles,
            priority = 1,
        )
        return dictionaryRepository.insertDictionary(dictionary)
    }

    suspend fun importTags(tags: Sequence<DictionaryTag>, dictionaryId: Long) {
        tags.chunked(BATCH_SIZE).forEach { chunk ->
            val tagsWithDictId = chunk.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTags(tagsWithDictId)
        }
    }

    /**
     * Imports tags from the index.json tagMeta section.
     * Newer dictionaries should use individual tag files instead of this.
     */
    suspend fun importIndexTags(index: DictionaryIndex, dictionaryId: Long) {
        index.tagMeta?.let { tagMeta ->
            val indexTags = tagMeta.map { (name, meta) ->
                DictionaryTag(
                    dictionaryId = dictionaryId,
                    name = name,
                    category = meta.category,
                    order = meta.order,
                    notes = meta.notes,
                    score = meta.score,
                )
            }
            dictionaryRepository.insertTags(indexTags)
        }
    }

    suspend fun importTerms(terms: Sequence<DictionaryTerm>, dictionaryId: Long) {
        terms.chunked(BATCH_SIZE).forEach { chunk ->
            val termsWithDictId = chunk.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTerms(termsWithDictId)
        }
    }

    suspend fun importKanji(kanji: Sequence<DictionaryKanji>, dictionaryId: Long) {
        kanji.chunked(BATCH_SIZE).forEach { chunk ->
            val kanjiWithDictId = chunk.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanji(kanjiWithDictId)
        }
    }

    suspend fun importTermMeta(termMeta: Sequence<DictionaryTermMeta>, dictionaryId: Long) {
        termMeta.chunked(BATCH_SIZE).forEach { chunk ->
            val termMetaWithDictId = chunk.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTermMeta(termMetaWithDictId)
        }
    }

    suspend fun importKanjiMeta(kanjiMeta: Sequence<DictionaryKanjiMeta>, dictionaryId: Long) {
        kanjiMeta.chunked(BATCH_SIZE).forEach { chunk ->
            val kanjiMetaWithDictId = chunk.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanjiMeta(kanjiMetaWithDictId)
        }
    }
}
