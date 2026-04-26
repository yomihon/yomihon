package mihon.data.dictionary

import de.manhhao.hoshi.HoshiDicts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryKanjiExport
import mihon.domain.dictionary.model.DictionaryKanjiMetaExport
import mihon.domain.dictionary.model.DictionaryLegacyRowCounts
import mihon.domain.dictionary.model.DictionaryMigrationStage
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermExport
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.DictionaryTermMetaExport
import mihon.domain.dictionary.repository.DictionaryLegacyRepository
import mihon.domain.dictionary.repository.DictionaryMigrationStatusRepository
import mihon.domain.dictionary.repository.DictionaryRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler

class DictionaryRepositoryImpl(
    private val handler: DatabaseHandler,
) : DictionaryRepository, DictionaryLegacyRepository, DictionaryMigrationStatusRepository {

    private val hoshi by lazy { HoshiDicts() }

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
        val freqDictionaryIds = handler.awaitList {
            dictionaryQueries.getFreqDictionaryIds()
        }.toMutableSet()

        val dictionaries = getAllDictionaries()
        val hoshiFrequencyIds = withContext(Dispatchers.IO) {
            dictionaries
                .asSequence()
                .filter {
                    it.backend == DictionaryBackend.HOSHI &&
                        it.isEnabled &&
                        it.storageReady &&
                        !it.storagePath.isNullOrBlank()
                }
                .mapNotNull { dictionary ->
                    val storagePath = dictionary.storagePath ?: return@mapNotNull null
                    dictionary.id.takeIf {
                        hasAtLeastFreqEntries(storagePath)
                    }
                }
                .toSet()
        }

        freqDictionaryIds += hoshiFrequencyIds
        return freqDictionaryIds.toList()
    }

    private fun hasAtLeastFreqEntries(storagePath: String, minFreqEntryCount: Int = MIN_FREQ_ENTRY_COUNT): Boolean {
        if (storagePath.isBlank()) return false
        if (minFreqEntryCount <= 0) return true

        return runCatching {
            hoshi.hasMetaModeEntries(storagePath, FREQ_MODE, minFreqEntryCount)
        }.getOrDefault(false)
    }

    override suspend fun updateDictionaryStorage(
        dictionaryId: Long,
        backend: DictionaryBackend,
        storagePath: String?,
        storageReady: Boolean,
    ) {
        handler.await(inTransaction = true) {
            dictionaryQueries.updateDictionaryStorage(
                id = dictionaryId,
                backend = backend.toDbValue(),
                storage_path = storagePath,
                storage_ready = if (storageReady) 1L else 0L,
            )
        }
    }

    // Tag operations

    override suspend fun getTagsForDictionary(dictionaryId: Long): List<DictionaryTag> {
        return handler.awaitList {
            dictionaryQueries.getTagsForDictionary(dictionaryId)
        }.map { it.toDomain() }
    }

    private suspend fun getTagCountForDictionary(dictionaryId: Long): Long {
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
                    glossaryJson = glossary.also {
                        logcat { "Legacy Term Meta Export: $expression | $glossary" }
                    },
                    sequence = sequence,
                    termTags = termTags,
                )
            }
        }
    }

    private suspend fun getTermCountForDictionary(dictionaryId: Long): Long {
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
        }.map { term ->
            logcat { "Legacy Term Search: ${term.expression} | ${term.glossary}" }
            term.toDomain()
        }
    }

    override suspend fun deleteTermsForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteTermsForDictionary(dictionaryId)
        }
    }

    // Kanji operations

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

    private suspend fun getKanjiCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getKanjiCountForDictionary(dictionaryId)
        }
    }

    override suspend fun deleteKanjiForDictionary(dictionaryId: Long) {
        handler.await(inTransaction = true) {
            dictionaryQueries.deleteKanjiForDictionary(dictionaryId)
        }
    }

    // Term meta operations

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

    private suspend fun getTermMetaCountForDictionary(dictionaryId: Long): Long {
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

    private suspend fun getKanjiMetaCountForDictionary(dictionaryId: Long): Long {
        return handler.awaitOneExecutable {
            dictionaryQueries.getKanjiMetaCountForDictionary(dictionaryId)
        }
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

    override suspend fun getAllMigrationStatuses(): List<DictionaryMigrationStatus> {
        return handler.awaitList {
            dictionaryQueries.getAllDictionaryMigrationStatuses {
                    dictId,
                    state,
                    stage,
                    progressText,
                    completedDicts,
                    totalDicts,
                    lastError,
                    updatedAt,
                ->
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
            dictionaryQueries.getAllDictionaryMigrationStatuses {
                    dictId,
                    state,
                    stage,
                    progressText,
                    completedDicts,
                    totalDicts,
                    lastError,
                    updatedAt,
                ->
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

    private companion object {
        const val FREQ_MODE = "freq"
        const val MIN_FREQ_ENTRY_COUNT = 5
    }
}
