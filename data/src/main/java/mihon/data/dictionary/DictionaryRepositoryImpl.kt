package mihon.data.dictionary

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.repository.DictionaryRepository
import tachiyomi.data.DatabaseHandler

class DictionaryRepositoryImpl(
    private val handler: DatabaseHandler,
) : DictionaryRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val glossarySerializer = ListSerializer(GlossaryEntry.serializer())

    // Dictionary operations

    override suspend fun insertDictionary(dictionary: Dictionary): Long {
        return handler.awaitOneExecutable(inTransaction = true) {
            dictionaryQueries.insertDictionary(
                title = dictionary.title,
                revision = dictionary.revision,
                version = dictionary.version.toLong(),
                author = dictionary.author,
                url = dictionary.url,
                description = dictionary.description,
                attribution = dictionary.attribution,
                // Store boolean as integer (1 = true, 0 = false)
                is_enabled = if (dictionary.isEnabled) 1L else 0L,
                priority = dictionary.priority.toLong(),
                date_added = dictionary.dateAdded,
            )
            dictionaryQueries.selectLastInsertedRowId()
        }
    }

    override suspend fun updateDictionary(dictionary: Dictionary) {
        handler.await(inTransaction = true) {
            dictionaryQueries.updateDictionary(
                id = dictionary.id,
                title = dictionary.title,
                revision = dictionary.revision,
                version = dictionary.version.toLong(),
                author = dictionary.author,
                url = dictionary.url,
                description = dictionary.description,
                attribution = dictionary.attribution,
                // Store boolean as integer (1 = true, 0 = false)
                is_enabled = if (dictionary.isEnabled) 1L else 0L,
                priority = dictionary.priority.toLong(),
            )
        }
    }

    override suspend fun bumpAllPrioritiesUp() {
        handler.await(inTransaction = true) {
            dictionaryQueries.bumpAllPrioritiesUp()
        }
    }

    override suspend fun deleteDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteDictionary(dictionaryId)
        }
    }

    override suspend fun getDictionary(dictionaryId: Long): Dictionary? {
        return handler.awaitOneOrNull {
            dictionaryQueries.getDictionary(dictionaryId, ::mapDictionary)
        }
    }

    override suspend fun getAllDictionaries(): List<Dictionary> {
        return handler.awaitList {
            dictionaryQueries.getAllDictionaries(::mapDictionary)
        }
    }

    override fun subscribeToDictionaries(): Flow<List<Dictionary>> {
        return handler.subscribeToList {
            dictionaryQueries.getAllDictionaries(::mapDictionary)
        }
    }

    // Tag operations

    override suspend fun insertTags(tags: List<DictionaryTag>) {
        tags.chunked(100).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { tag ->
                    dictionaryQueries.insertTag(
                        dictionary_id = tag.dictionaryId,
                        name = tag.name,
                        category = tag.category,
                        tag_order = tag.order.toLong(),
                        notes = tag.notes,
                        score = tag.score.toLong(),
                    )
                }
            }
        }
    }

    override suspend fun getTagsForDictionary(dictionaryId: Long): List<DictionaryTag> {
        return handler.awaitList {
            dictionaryQueries.getTagsForDictionary(dictionaryId)
        }.map { it.toDomain() }
    }

    override suspend fun deleteTagsForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteTagsForDictionary(dictionaryId)
        }
    }

    // Term operations

    override suspend fun insertTerms(terms: List<DictionaryTerm>) {
        terms.chunked(2000).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { term ->
                    dictionaryQueries.insertTerm(
                        dictionary_id = term.dictionaryId,
                        expression = term.expression,
                        reading = term.reading,
                        definition_tags = term.definitionTags,
                        rules = term.rules,
                        score = term.score.toLong(),
                        glossary = json.encodeToString(glossarySerializer, term.glossary),
                        sequence = term.sequence,
                        term_tags = term.termTags,
                    )
                }
            }
        }
    }

    override suspend fun searchTerms(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return handler.awaitList {
            dictionaryQueries.searchTerms(
                query = query,
                dictionaryIds = dictionaryIds,
                limit = 100,
            )
        }.map { it.toDomain() }
    }

    override suspend fun getTermsByExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return handler.awaitList {
            dictionaryQueries.getTermsByExpression(
                expression = expression,
                dictionaryIds = dictionaryIds,
            )
        }.map { it.toDomain() }
    }

    override suspend fun deleteTermsForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteTermsForDictionary(dictionaryId)
        }
    }

    // Kanji operations

    override suspend fun insertKanji(kanji: List<DictionaryKanji>) {
        kanji.chunked(2000).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { k ->
                    dictionaryQueries.insertKanji(
                        dictionary_id = k.dictionaryId,
                        character = k.character,
                        onyomi = k.onyomi,
                        kunyomi = k.kunyomi,
                        tags = k.tags,
                        meanings = json.encodeToString(k.meanings),
                        stats = k.stats?.let { json.encodeToString(it) },
                    )
                }
            }
        }
    }

    override suspend fun getKanjiByCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanji> {
        return handler.awaitList {
            dictionaryQueries.getKanjiByCharacter(
                character = character,
                dictionaryIds = dictionaryIds,
            )
        }.map { it.toDomain() }
    }

    override suspend fun deleteKanjiForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteKanjiForDictionary(dictionaryId)
        }
    }

    // Term meta operations

    override suspend fun insertTermMeta(termMeta: List<DictionaryTermMeta>) {
        termMeta.chunked(2000).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { meta ->
                    dictionaryQueries.insertTermMeta(
                        dictionary_id = meta.dictionaryId,
                        expression = meta.expression,
                        mode = meta.mode.toDbString(),
                        data = meta.data,
                    )
                }
            }
        }
    }

    override suspend fun getTermMetaForExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta> {
        return handler.awaitList {
            dictionaryQueries.getTermMetaForExpression(
                expression = expression,
                dictionaryIds = dictionaryIds,
            )
        }.map { it.toDomain() }
    }

    override suspend fun deleteTermMetaForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteTermMetaForDictionary(dictionaryId)
        }
    }

    // Kanji meta operations

    override suspend fun insertKanjiMeta(kanjiMeta: List<DictionaryKanjiMeta>) {
        kanjiMeta.chunked(2000).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { meta ->
                    dictionaryQueries.insertKanjiMeta(
                        dictionary_id = meta.dictionaryId,
                        character = meta.character,
                        mode = meta.mode.toDbString(),
                        data = meta.data,
                    )
                }
            }
        }
    }

    override suspend fun getKanjiMetaForCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanjiMeta> {
        return handler.awaitList {
            dictionaryQueries.getKanjiMetaForCharacter(
                character = character,
                dictionaryIds = dictionaryIds,
            )
        }.map { it.toDomain() }
    }

    override suspend fun deleteKanjiMetaForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteKanjiMetaForDictionary(dictionaryId)
        }
    }

    private fun mapDictionary(
        id: Long,
        title: String,
        revision: String,
        version: Long,
        author: String?,
        url: String?,
        description: String?,
        attribution: String?,
        isEnabled: Long,
        priority: Long,
        dateAdded: Long,
    ): Dictionary {
        return Dictionary(
            id = id,
            title = title,
            revision = revision,
            version = version.toInt(),
            author = author,
            url = url,
            description = description,
            attribution = attribution,
            isEnabled = isEnabled == 1L,
            priority = priority.toInt(),
            dateAdded = dateAdded,
        )
    }
}
