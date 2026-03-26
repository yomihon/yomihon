package mihon.data.dictionary

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryMigrationStage
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryKanjiExport
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryKanjiMetaExport
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermExport
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.DictionaryTermMetaExport
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
                styles = dictionary.styles,
                source_language = dictionary.sourceLanguage,
                target_language = dictionary.targetLanguage,
                // Store boolean as integer (1 = true, 0 = false)
                is_enabled = if (dictionary.isEnabled) 1L else 0L,
                priority = dictionary.priority.toLong(),
                date_added = dictionary.dateAdded,
                backend = dictionary.backend.toDbValue(),
                storage_path = dictionary.storagePath,
                storage_ready = if (dictionary.storageReady) 1L else 0L,
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
                styles = dictionary.styles,
                source_language = dictionary.sourceLanguage,
                target_language = dictionary.targetLanguage,
                // Store boolean as integer (1 = true, 0 = false)
                is_enabled = if (dictionary.isEnabled) 1L else 0L,
                priority = dictionary.priority.toLong(),
                backend = dictionary.backend.toDbValue(),
                storage_path = dictionary.storagePath,
                storage_ready = if (dictionary.storageReady) 1L else 0L,
            )
        }
    }

    override suspend fun bumpAllPrioritiesUp() {
        handler.await(inTransaction = true) {
            dictionaryQueries.bumpAllPrioritiesUp()
        }
    }

    override suspend fun bumpDownPrioritiesAbove(priority: Int) {
        handler.await(inTransaction = true) {
            dictionaryQueries.bumpDownPrioritiesAbove(priority.toLong())
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

    override suspend fun getLegacyDictionaries(): List<Dictionary> {
        return handler.awaitList {
            dictionaryQueries.getLegacyDictionaries(::mapDictionary)
        }
    }

    override fun subscribeToDictionaries(): Flow<List<Dictionary>> {
        return handler.subscribeToList {
            dictionaryQueries.getAllDictionaries(::mapDictionary)
        }
    }

    override suspend fun getFreqDictionaryIds(): List<Long> {
        return handler.awaitList {
            dictionaryQueries.getFreqDictionaryIds()
        }
    }

    override suspend fun updateDictionaryStorage(
        dictionaryId: Long,
        backend: String,
        storagePath: String?,
        storageReady: Boolean,
    ) {
        handler.await(inTransaction = true) {
            dictionaryQueries.updateDictionaryStorage(
                id = dictionaryId,
                backend = backend,
                storage_path = storagePath,
                storage_ready = if (storageReady) 1L else 0L,
            )
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

    override suspend fun getTagCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getTagCountForDictionary(dictionaryId)
        }
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

    override suspend fun getTermsForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryTerm> {
        return handler.awaitList {
            dictionaryQueries.getTermsForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            )
        }.map { it.toDomain() }
    }

    override suspend fun getTermsExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryTermExport> {
        return handler.awaitList {
            dictionaryQueries.getTermsExportForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            ) { expression, reading, definitionTags, rules, score, glossary, sequence, termTags ->
                DictionaryTermExport(
                    expression = expression,
                    reading = reading,
                    definitionTags = definitionTags,
                    rules = rules,
                    score = score.toInt(),
                    glossaryJson = glossary,
                    sequence = sequence,
                    termTags = termTags,
                )
            }
        }
    }

    override suspend fun getTermCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getTermCountForDictionary(dictionaryId)
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

    override suspend fun getKanjiForDictionary(dictionaryId: Long, limit: Long, offset: Long): List<DictionaryKanji> {
        return handler.awaitList {
            dictionaryQueries.getKanjiForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            )
        }.map { it.toDomain() }
    }

    override suspend fun getKanjiExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryKanjiExport> {
        return handler.awaitList {
            dictionaryQueries.getKanjiExportForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            ) { character, onyomi, kunyomi, tags, meanings, stats ->
                DictionaryKanjiExport(
                    character = character,
                    onyomi = onyomi,
                    kunyomi = kunyomi,
                    tags = tags,
                    meaningsJson = meanings,
                    statsJson = stats,
                )
            }
        }
    }

    override suspend fun getKanjiCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getKanjiCountForDictionary(dictionaryId)
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

    override suspend fun getTermMetaForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryTermMeta> {
        return handler.awaitList {
            dictionaryQueries.getTermMetaForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            )
        }.map { it.toDomain() }
    }

    override suspend fun getTermMetaExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryTermMetaExport> {
        return handler.awaitList {
            dictionaryQueries.getTermMetaExportForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            ) { expression, mode, data ->
                DictionaryTermMetaExport(
                    expression = expression,
                    mode = mode,
                    dataJson = data,
                )
            }
        }
    }

    override suspend fun getTermMetaCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getTermMetaCountForDictionary(dictionaryId)
        }
    }

    override suspend fun getTermMetaForExpression(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionaryTermMeta> {
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

    override suspend fun getKanjiMetaForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryKanjiMeta> {
        return handler.awaitList {
            dictionaryQueries.getKanjiMetaForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            )
        }.map { it.toDomain() }
    }

    override suspend fun getKanjiMetaExportForDictionary(
        dictionaryId: Long,
        limit: Long,
        offset: Long,
    ): List<DictionaryKanjiMetaExport> {
        return handler.awaitList {
            dictionaryQueries.getKanjiMetaExportForDictionary(
                dictionaryId = dictionaryId,
                limit = limit,
                offset = offset,
            ) { character, mode, data ->
                DictionaryKanjiMetaExport(
                    character = character,
                    mode = mode,
                    dataJson = data,
                )
            }
        }
    }

    override suspend fun getKanjiMetaCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getKanjiMetaCountForDictionary(dictionaryId)
        }
    }

    override suspend fun getKanjiMetaForCharacter(
        character: String,
        dictionaryIds: List<Long>,
    ): List<DictionaryKanjiMeta> {
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

    override suspend fun getLegacyRowCounts(dictionaryId: Long): DictionaryLegacyRowCounts {
        return DictionaryLegacyRowCounts(
            tagCount = getTagCountForDictionary(dictionaryId),
            termCount = getTermCountForDictionary(dictionaryId),
            termMetaCount = getTermMetaCountForDictionary(dictionaryId),
            kanjiCount = getKanjiCountForDictionary(dictionaryId),
            kanjiMetaCount = getKanjiMetaCountForDictionary(dictionaryId),
        )
    }

    override suspend fun upsertMigrationStatus(status: DictionaryMigrationStatus) {
        handler.await(inTransaction = true) {
            dictionaryQueries.upsertDictionaryMigrationStatus(
                dictionary_id = status.dictionaryId,
                state = status.state.toDbValue(),
                stage = status.stage.toDbValue(),
                progress_text = status.progressText,
                completed_dicts = status.completedDictionaries.toLong(),
                total_dicts = status.totalDictionaries.toLong(),
                last_error = status.lastError,
                updated_at = status.updatedAt,
            )
        }
    }

    override suspend fun getMigrationStatus(dictionaryId: Long): DictionaryMigrationStatus? {
        return handler.awaitOneOrNull {
            dictionaryQueries.getDictionaryMigrationStatus(
                dictionaryId = dictionaryId,
            ) { dictId, state, stage, progressText, completedDicts, totalDicts, lastError, updatedAt ->
                mapMigrationStatus(
                    dictionaryId = dictId,
                    state = state,
                    stage = stage,
                    progressText = progressText,
                    completedDictionaries = completedDicts,
                    totalDictionaries = totalDicts,
                    lastError = lastError,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    override suspend fun getAllMigrationStatuses(): List<DictionaryMigrationStatus> {
        return handler.awaitList {
            dictionaryQueries.getAllDictionaryMigrationStatuses(
            ) { dictId, state, stage, progressText, completedDicts, totalDicts, lastError, updatedAt ->
                mapMigrationStatus(
                    dictionaryId = dictId,
                    state = state,
                    stage = stage,
                    progressText = progressText,
                    completedDictionaries = completedDicts,
                    totalDictionaries = totalDicts,
                    lastError = lastError,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    override fun subscribeToMigrationStatuses(): Flow<List<DictionaryMigrationStatus>> {
        return handler.subscribeToList {
            dictionaryQueries.getAllDictionaryMigrationStatuses(
            ) { dictId, state, stage, progressText, completedDicts, totalDicts, lastError, updatedAt ->
                mapMigrationStatus(
                    dictionaryId = dictId,
                    state = state,
                    stage = stage,
                    progressText = progressText,
                    completedDictionaries = completedDicts,
                    totalDictionaries = totalDicts,
                    lastError = lastError,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    override suspend fun deleteMigrationStatus(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteDictionaryMigrationStatus(dictionaryId)
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
        styles: String?,
        sourceLanguage: String?,
        targetLanguage: String?,
        isEnabled: Long,
        priority: Long,
        dateAdded: Long,
        backend: String,
        storagePath: String?,
        storageReady: Long,
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
            styles = styles,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            isEnabled = isEnabled == 1L,
            priority = priority.toInt(),
            dateAdded = dateAdded,
            backend = DictionaryBackend.fromDbValue(backend),
            storagePath = storagePath,
            storageReady = storageReady == 1L,
        )
    }

    private fun mapMigrationStatus(
        dictionaryId: Long,
        state: String,
        stage: String,
        progressText: String?,
        completedDictionaries: Long,
        totalDictionaries: Long,
        lastError: String?,
        updatedAt: Long,
    ): DictionaryMigrationStatus {
        return DictionaryMigrationStatus(
            dictionaryId = dictionaryId,
            state = DictionaryMigrationState.fromDbValue(state),
            stage = DictionaryMigrationStage.fromDbValue(stage),
            progressText = progressText,
            completedDictionaries = completedDictionaries.toInt(),
            totalDictionaries = totalDictionaries.toInt(),
            lastError = lastError,
            updatedAt = updatedAt,
        )
    }
}
