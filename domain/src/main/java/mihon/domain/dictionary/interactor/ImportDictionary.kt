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
 */
class ImportDictionary(
    private val dictionaryRepository: DictionaryRepository,
) {
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

    /**
     * Imports tags into the dictionary.
     */
    suspend fun importTags(tags: List<DictionaryTag>, dictionaryId: Long) {
        if (tags.isNotEmpty()) {
            val tagsWithDictId = tags.map { it.copy(dictionaryId = dictionaryId) }
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

    /**
     * Imports terms into the dictionary.
     */
    suspend fun importTerms(terms: List<DictionaryTerm>, dictionaryId: Long) {
        if (terms.isNotEmpty()) {
            val termsWithDictId = terms.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTerms(termsWithDictId)
        }
    }

    /**
     * Imports kanji into the dictionary.
     */
    suspend fun importKanji(kanji: List<DictionaryKanji>, dictionaryId: Long) {
        if (kanji.isNotEmpty()) {
            val kanjiWithDictId = kanji.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanji(kanjiWithDictId)
        }
    }

    /**
     * Imports term metadata into the dictionary.
     */
    suspend fun importTermMeta(termMeta: List<DictionaryTermMeta>, dictionaryId: Long) {
        if (termMeta.isNotEmpty()) {
            val termMetaWithDictId = termMeta.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTermMeta(termMetaWithDictId)
        }
    }

    /**
     * Imports kanji metadata into the dictionary.
     */
    suspend fun importKanjiMeta(kanjiMeta: List<DictionaryKanjiMeta>, dictionaryId: Long) {
        if (kanjiMeta.isNotEmpty()) {
            val kanjiMetaWithDictId = kanjiMeta.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanjiMeta(kanjiMetaWithDictId)
        }
    }
}
