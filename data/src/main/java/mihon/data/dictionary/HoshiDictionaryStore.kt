package mihon.data.dictionary

import android.app.Application
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionaryParser
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.DictionaryStorageGateway
import mihon.domain.dictionary.service.DictionaryStorageImportOutcome
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

class HoshiDictionaryStore(
    private val application: Application,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryParser: DictionaryParser,
) : DictionarySearchBackend, DictionaryStorageGateway {
    private val hoshi = HoshiDicts()
    private val rebuildMutex = Mutex()
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var sessionState: SessionState? = null

    override suspend fun refreshSearchSession() {
        dirty.set(true)
        rebuildInternal(force = true)
    }

    private fun getDictionaryStorageParent(dictionaryId: Long): File {
        return File(application.filesDir, "dictionaries/hoshi/$dictionaryId").apply { mkdirs() }
    }

    override suspend fun importDictionary(
        archivePath: String,
        dictionaryId: Long,
        dictionaryTitle: String,
    ): DictionaryStorageImportOutcome = withContext(Dispatchers.IO) {
        assertUniqueDictionaryTitle(dictionaryId, dictionaryTitle)
        val parent = getDictionaryStorageParent(dictionaryId)
        val result = hoshi.importDictionary(archivePath, parent.absolutePath)
        val finalDir = result.storagePath.takeIf { path ->
            path.isNotBlank() && hasHoshiStorageMarker(path)
        }
        DictionaryStorageImportOutcome(
            success = result.success,
            storagePath = finalDir,
            termCount = result.termCount,
            metaCount = result.metaCount,
            mediaCount = result.mediaCount,
        )
    }

    override suspend fun validateImportedDictionary(
        storagePath: String,
        sampleExpression: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sampleExpression.isNullOrBlank()) {
            return@withContext hasHoshiStorageMarker(storagePath)
        }

        val handle = hoshi.createLookupObject()
        try {
            hoshi.rebuildQuery(handle, arrayOf(storagePath), arrayOf(storagePath), arrayOf(storagePath))
            hoshi.queryExact(handle, sampleExpression).isNotEmpty()
        } finally {
            hoshi.destroyLookupObject(handle)
        }
    }

    override suspend fun clearDictionaryStorage(dictionaryId: Long) {
        withContext(Dispatchers.IO) {
            getDictionaryStorageParent(dictionaryId).deleteRecursively()
        }
        dirty.set(true)
    }

    override suspend fun exactSearch(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionarySearchEntry> = withContext(Dispatchers.IO) {
        if (dictionaryIds.isEmpty()) return@withContext emptyList()

        val state = ensureSession()
        if (state.handle == 0L) return@withContext emptyList()

        val results = hoshi.queryExact(state.handle, expression)
        mapTermResults(
            termResults = results.toList(),
            allowedDictionaryIds = dictionaryIds.toSet(),
            state = state,
        )
    }

    override suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> = withContext(Dispatchers.IO) {
        if (expressions.isEmpty()) return@withContext emptyMap()
        if (dictionaryIds.isEmpty()) {
            return@withContext expressions.associateWith { emptyList() }
        }

        val state = ensureSession()
        if (state.handle == 0L) {
            return@withContext expressions.associateWith { emptyList() }
        }

        val allowedDictionaryIds = dictionaryIds.toSet()
        expressions.associateWith { expression ->
            hoshi.queryExact(state.handle, expression)
                .toList()
                .asSequence()
                .filter { it.expression == expression }
                .flatMap { termResult ->
                    buildMetaByDictionaryId(
                        termResult = termResult,
                        allowedDictionaryIds = allowedDictionaryIds,
                        state = state,
                    ).values.flatten()
                }
                .distinctBy(::metaKey)
                .toList()
        }
    }

    override suspend fun lookup(
        text: String,
        dictionaryIds: List<Long>,
        maxResults: Int,
    ): List<DictionaryLookupMatch> = withContext(Dispatchers.IO) {
        if (dictionaryIds.isEmpty()) return@withContext emptyList()

        val state = ensureSession()
        if (state.handle == 0L) return@withContext emptyList()

        hoshi.lookup(state.handle, text, maxResults).flatMap { result ->
            mapLookupResult(
                lookupResult = result,
                allowedDictionaryIds = dictionaryIds.toSet(),
                state = state,
            )
        }
    }

    private suspend fun ensureSession(): SessionState {
        val current = sessionState
        if (current != null && !dirty.get()) {
            return current
        }
        return rebuildInternal(force = false)
    }

    private suspend fun rebuildInternal(force: Boolean): SessionState = rebuildMutex.withLock {
        val existing = sessionState
        if (!force && existing != null && !dirty.get()) {
            return existing
        }

        val dictionaries = dictionaryRepository.getAllDictionaries()
            .filter {
                it.backend == DictionaryBackend.HOSHI &&
                    it.storageReady &&
                    !it.storagePath.isNullOrBlank() &&
                    hasHoshiStorageMarker(it.storagePath!!)
            }
            .sortedWith(compareBy<Dictionary> { it.priority }.thenBy { it.title })

        val newHandle = hoshi.createLookupObject()
        if (dictionaries.isNotEmpty()) {
            val paths = dictionaries.mapNotNull { it.storagePath }.toTypedArray()
            hoshi.rebuildQuery(newHandle, paths, paths, paths)
        }

        val newState = SessionState(
            handle = newHandle,
            dictionariesByTitle = dictionaries.groupBy { it.title },
            dictionariesByNormalizedTitle = dictionaries.groupBy { normalizeDictionaryTitle(it.title) },
        )

        sessionState = newState
        dirty.set(false)

        existing?.let { old ->
            if (old.handle != 0L) {
                hoshi.destroyLookupObject(old.handle)
            }
        }

        return newState
    }

    private fun mapLookupResult(
        lookupResult: LookupResult,
        allowedDictionaryIds: Set<Long>,
        state: SessionState,
    ): List<DictionaryLookupMatch> {
        return mapTermResult(
            termResult = lookupResult.term,
            allowedDictionaryIds = allowedDictionaryIds,
            state = state,
        ).map { entry ->
            DictionaryLookupMatch(
                matched = lookupResult.matched,
                deinflected = lookupResult.deinflected,
                process = lookupResult.process.toList(),
                term = entry.term,
                termMeta = entry.termMeta,
            )
        }
    }

    private fun mapTermResults(
        termResults: List<TermResult>,
        allowedDictionaryIds: Set<Long>,
        state: SessionState,
    ): List<DictionarySearchEntry> {
        return termResults.flatMap { termResult ->
            mapTermResult(
                termResult = termResult,
                allowedDictionaryIds = allowedDictionaryIds,
                state = state,
            )
        }
    }

    private fun mapTermResult(
        termResult: TermResult,
        allowedDictionaryIds: Set<Long>,
        state: SessionState,
    ): List<DictionarySearchEntry> {
        val metaByDictionaryId = buildMetaByDictionaryId(termResult, allowedDictionaryIds, state)

        return termResult.glossaries.mapNotNull { glossaryEntry ->
            val dictionaryId = resolveDictionaryId(
                title = glossaryEntry.dictName,
                allowedDictionaryIds = allowedDictionaryIds,
                state = state,
            ) ?: return@mapNotNull null

            DictionarySearchEntry(
                term = DictionaryTerm(
                    id = syntheticTermId(dictionaryId, termResult, glossaryEntry.glossary),
                    dictionaryId = dictionaryId,
                    expression = termResult.expression,
                    reading = termResult.reading,
                    definitionTags = glossaryEntry.definitionTags.ifBlank { null },
                    rules = termResult.rules.ifBlank { null },
                    score = termResult.score,
                    glossary = parseGlossary(glossaryEntry.glossary).also {
                        logcat {
                            "Hoshi Glossary: ${glossaryEntry.dictName} | ${termResult.expression} | ${glossaryEntry.glossary}"
                        }
                    },
                    sequence = null,
                    termTags = glossaryEntry.termTags.ifBlank { null },
                ),
                termMeta = metaByDictionaryId[dictionaryId].orEmpty(),
            )
        }
    }

    private fun buildMetaByDictionaryId(
        termResult: TermResult,
        allowedDictionaryIds: Set<Long>,
        state: SessionState,
    ): Map<Long, List<DictionaryTermMeta>> {
        val grouped = linkedMapOf<Long, MutableList<DictionaryTermMeta>>()

        termResult.frequencies.forEach { entry ->
            val dictionaryId = resolveDictionaryId(entry.dictName, allowedDictionaryIds, state)
                ?: return@forEach
            val list = grouped.getOrPut(dictionaryId) { mutableListOf() }
            entry.frequencies.forEach { frequency ->
                list += DictionaryTermMeta(
                    dictionaryId = dictionaryId,
                    expression = termResult.expression,
                    mode = TermMetaMode.FREQUENCY,
                    data = frequencyMetaJson(
                        value = frequency.value,
                        displayValue = frequency.displayValue,
                        reading = termResult.reading,
                    ),
                )
            }
        }

        termResult.pitches.forEach { entry ->
            val dictionaryId = resolveDictionaryId(entry.dictName, allowedDictionaryIds, state) ?: return@forEach
            val list = grouped.getOrPut(dictionaryId) { mutableListOf() }
            list += DictionaryTermMeta(
                dictionaryId = dictionaryId,
                expression = termResult.expression,
                mode = TermMetaMode.PITCH,
                data = pitchMetaJson(
                    reading = termResult.reading,
                    positions = entry.pitchPositions.toList(),
                ),
            )
        }

        return grouped
    }

    private fun resolveDictionaryId(
        title: String,
        allowedDictionaryIds: Set<Long>,
        state: SessionState,
    ): Long? {
        val exactMatch = state.dictionariesByTitle[title]
            ?.firstOrNull { it.id in allowedDictionaryIds }
            ?.id
        if (exactMatch != null) {
            return exactMatch
        }

        val normalizedTitle = normalizeDictionaryTitle(title)
        return state.dictionariesByNormalizedTitle[normalizedTitle]
            ?.firstOrNull { it.id in allowedDictionaryIds }
            ?.id
    }

    private fun normalizeDictionaryTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase()
    }

    private suspend fun assertUniqueDictionaryTitle(dictionaryId: Long, dictionaryTitle: String) {
        val normalizedTitle = normalizeDictionaryTitle(dictionaryTitle)
        check(
            dictionaryRepository.getAllDictionaries().none {
                it.id != dictionaryId &&
                    normalizeDictionaryTitle(it.title) == normalizedTitle
            },
        ) { "Dictionary title conflicts with an existing Hoshidicts-backed identity: $dictionaryTitle" }
    }

    private fun parseGlossary(rawGlossary: String): List<GlossaryEntry> {
        if (rawGlossary.isBlank()) return emptyList()

        return runCatching { dictionaryParser.parseGlossary(rawGlossary) }
            .getOrDefault(emptyList())
    }

    private fun frequencyMetaJson(value: Int, displayValue: String, reading: String): String {
        return buildJsonObject {
            put("value", value)
            put("displayValue", displayValue)
            put("reading", reading)
        }.toString()
    }

    private fun pitchMetaJson(reading: String, positions: List<Int>): String {
        return buildJsonObject {
            put("reading", reading)
            put(
                "pitches",
                buildJsonArray {
                    positions.forEach { position ->
                        add(
                            buildJsonObject {
                                put("position", position)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun syntheticTermId(
        dictionaryId: Long,
        termResult: TermResult,
        glossary: String,
    ): Long {
        val raw = "$dictionaryId|${termResult.expression}|${termResult.reading}|$glossary"
        return -raw.hashCode().toLong()
    }

    private data class SessionState(
        val handle: Long,
        val dictionariesByTitle: Map<String, List<Dictionary>>,
        val dictionariesByNormalizedTitle: Map<String, List<Dictionary>>,
    )

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        private fun metaKey(meta: DictionaryTermMeta): String {
            return "${meta.dictionaryId}|${meta.expression}|${meta.mode}|${meta.data}"
        }

        private fun hasHoshiStorageMarker(path: String): Boolean {
            return File(path, ".hoshidicts_1").exists() || File(path, ".hoshidicts_2").exists()
        }
    }
}
