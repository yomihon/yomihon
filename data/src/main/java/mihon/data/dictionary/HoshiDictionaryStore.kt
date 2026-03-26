package mihon.data.dictionary

import android.app.Application
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import java.io.ByteArrayInputStream
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry

class HoshiDictionaryStore(
    private val application: Application,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryParser: DictionaryParserImpl = DictionaryParserImpl(),
) : DictionarySearchBackend {

    private val hoshi = HoshiDicts()
    private val rebuildMutex = Mutex()
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var sessionState: SessionState? = null

    suspend fun rebuildSession() {
        rebuildInternal(force = true)
    }

    fun markDirty() {
        dirty.set(true)
    }

    fun getDictionaryStorageParent(dictionaryId: Long): File {
        return File(application.filesDir, "dictionaries/hoshi/$dictionaryId").apply { mkdirs() }
    }

    suspend fun importDictionary(
        zipPath: String,
        dictionary: Dictionary,
    ): HoshiImportOutcome = withContext(Dispatchers.IO) {
        val parent = getDictionaryStorageParent(dictionary.id)
        val result = hoshi.importDictionary(zipPath, parent.absolutePath)
        val finalDir = parent.listFiles()?.firstOrNull { File(it, ".hoshidicts_1").exists() }
        HoshiImportOutcome(
            success = result.success,
            storagePath = finalDir?.absolutePath,
            termCount = result.termCount,
            metaCount = result.metaCount,
            mediaCount = result.mediaCount,
        )
    }

    suspend fun validateImportedDictionary(
        storagePath: String,
        sampleExpression: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sampleExpression.isNullOrBlank()) {
            return@withContext File(storagePath, ".hoshidicts_1").exists()
        }

        val handle = hoshi.createLookupObject()
        try {
            hoshi.rebuildQuery(handle, arrayOf(storagePath), arrayOf(storagePath), arrayOf(storagePath))
            hoshi.queryExact(handle, sampleExpression).isNotEmpty()
        } finally {
            hoshi.destroyLookupObject(handle)
        }
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
                    File(it.storagePath!!, ".hoshidicts_1").exists()
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
        val aggregatedMeta = metaByDictionaryId.values.flatten()

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
                    score = 0,
                    glossary = parseGlossary(glossaryEntry.glossary),
                    sequence = null,
                    termTags = glossaryEntry.termTags.ifBlank { null },
                ),
                termMeta = aggregatedMeta,
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
                val data = buildString {
                    append("{\"value\":")
                    append(frequency.value)
                    append(",\"displayValue\":")
                    appendQuotedJsonString(frequency.displayValue)
                    append("}")
                }
                list += DictionaryTermMeta(
                    dictionaryId = dictionaryId,
                    expression = termResult.expression,
                    mode = TermMetaMode.FREQUENCY,
                    data = data,
                )
            }
        }

        termResult.pitches.forEach { entry ->
            val dictionaryId = resolveDictionaryId(entry.dictName, allowedDictionaryIds, state) ?: return@forEach
            val list = grouped.getOrPut(dictionaryId) { mutableListOf() }
            val data = buildString {
                append("{\"reading\":")
                appendQuotedJsonString(termResult.reading)
                append(",\"pitches\":[")
                append(entry.pitchPositions.joinToString(",") { "{\"position\":$it}" })
                append("]}")
            }
            list += DictionaryTermMeta(
                dictionaryId = dictionaryId,
                expression = termResult.expression,
                mode = TermMetaMode.PITCH,
                data = data,
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

    private fun parseGlossary(rawGlossary: String): List<GlossaryEntry> {
        if (rawGlossary.isBlank()) return emptyList()

        val fakeBank = """[["expr","read",null,"",0,$rawGlossary,0,null]]"""
        return dictionaryParser
            .parseTermBank(
                stream = ByteArrayInputStream(fakeBank.toByteArray(Charsets.UTF_8)),
                version = 3,
            )
            .firstOrNull()
            ?.glossary
            .orEmpty()
    }

    private fun syntheticTermId(
        dictionaryId: Long,
        termResult: TermResult,
        glossary: String,
    ): Long {
        val raw = "$dictionaryId|${termResult.expression}|${termResult.reading}|$glossary"
        return -raw.hashCode().toLong()
    }

    private fun StringBuilder.appendQuotedJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    data class HoshiImportOutcome(
        val success: Boolean,
        val storagePath: String?,
        val termCount: Long,
        val metaCount: Long,
        val mediaCount: Long,
    )

    private data class SessionState(
        val handle: Long,
        val dictionariesByTitle: Map<String, List<Dictionary>>,
        val dictionariesByNormalizedTitle: Map<String, List<Dictionary>>,
    )

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
