package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.EnglishDeinflector
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector

/**
 * Interactor for searching dictionary terms with multilingual support.
 * The parser (Japanese lookup vs. exact/direct lookup) is chosen automatically
 * by detecting the script of the query text, so no language hint is needed.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionarySearchBackend: DictionarySearchBackend,
) {
    data class FirstWordMatch(
        val word: String,
        val sourceOffset: Int,
        val sourceLength: Int,
        val isDictionaryMatch: Boolean = false,
    )

    private val dictionaryScriptCache = ConcurrentHashMap<Long, Set<Script>>()
    private val hoshiTermMetaCache = ConcurrentHashMap<String, List<DictionaryTermMeta>>()

    private val punctuationCharSet: Set<Char> get() = PUNCTUATION_CHARS

    /** Script families used to select the right search/segmentation pipeline. */
    private enum class Script { JAPANESE, KOREAN, CHINESE, ENGLISH }

    private data class DictionaryIdPartition(
        val legacyIds: List<Long>,
        val hoshiIds: List<Long>,
    )

    private fun Script.isNonCjk(): Boolean =
        this != Script.JAPANESE && this != Script.CHINESE && this != Script.KOREAN

    private suspend fun getAllowedScripts(dictionaryIds: List<Long>): Set<Script>? {
        val allowed = mutableSetOf<Script>()
        for (id in dictionaryIds) {
            val scripts = dictionaryScriptCache.getOrPut(id) {
                val dict = dictionaryRepository.getDictionary(id) ?: return@getOrPut emptySet()
                val src = dict.sourceLanguage.orEmpty()

                if (src.isEmpty() || src == "unrestricted") {
                    emptySet()
                } else {
                    val srcScript = mapLanguageToScript(src)
                    setOfNotNull(srcScript)
                }
            }
            if (scripts.isEmpty()) return null
            allowed.addAll(scripts)
        }
        return allowed.ifEmpty { null }
    }

    private fun mapLanguageToScript(language: String): Script? {
        val code = language.lowercase().substringBefore('-')
        return when (code) {
            "ja", "jpn" -> Script.JAPANESE
            "ko", "kor" -> Script.KOREAN
            "zh", "zho", "chi" -> Script.CHINESE
            "en", "eng" -> Script.ENGLISH
            else -> null
        }
    }

    private fun detectScript(text: String, allowedScripts: Set<Script>?): Script {
        var hasCjk = false
        var scanned = 0
        for (ch in text) {
            if (ch in punctuationCharSet || ch.isWhitespace()) continue
            if (ch in '\u3041'..'\u309F' || ch in '\u30A0'..'\u30FF') return Script.JAPANESE
            if (ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF') return Script.KOREAN
            if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                hasCjk = true
            } else if (ch.isLetter() && ch.code < 0x300) {
                return when {
                    allowedScripts == null -> Script.ENGLISH
                    Script.JAPANESE in allowedScripts && allowedScripts.size == 1 -> Script.JAPANESE
                    else -> Script.ENGLISH
                }
            }
            if (++scanned >= SCRIPT_DETECT_WINDOW) break
        }

        return if (hasCjk) {
            when {
                allowedScripts == null -> Script.JAPANESE
                Script.JAPANESE in allowedScripts -> Script.JAPANESE
                Script.CHINESE in allowedScripts -> Script.CHINESE
                Script.KOREAN in allowedScripts -> Script.KOREAN
                else -> Script.JAPANESE
            }
        } else {
            allowedScripts?.firstOrNull() ?: Script.JAPANESE
        }
    }

    private fun resolveScript(text: String, override: ParserLanguage, allowedScripts: Set<Script>?): Script =
        when (override) {
            ParserLanguage.AUTO -> detectScript(text, allowedScripts)
            ParserLanguage.JAPANESE -> Script.JAPANESE
            ParserLanguage.KOREAN -> Script.KOREAN
            ParserLanguage.CHINESE -> Script.CHINESE
            ParserLanguage.ENGLISH -> Script.ENGLISH
        }

    suspend fun search(
        query: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): List<DictionaryTerm> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val normalizedQuery = query.trim { it in punctuationCharSet || it.isWhitespace() }
        val allowedScripts = getAllowedScripts(dictionaryIds)
        val script = resolveScript(normalizedQuery, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> searchJa(normalizedQuery, dictionaryIds)
            Script.ENGLISH -> searchEn(normalizedQuery, dictionaryIds)
            else -> searchExact(normalizedQuery, dictionaryIds)
        }

        return if (primaryResult.isEmpty() && script.isNonCjk() && isJapaneseAllowed) {
            searchJa(normalizedQuery, dictionaryIds)
        } else {
            primaryResult
        }
    }

    private suspend fun partitionDictionaryIds(dictionaryIds: List<Long>): DictionaryIdPartition {
        val legacy = mutableListOf<Long>()
        val hoshi = mutableListOf<Long>()

        dictionaryIds.forEach { id ->
            val dictionary = dictionaryRepository.getDictionary(id)
            if (dictionary?.backend == DictionaryBackend.HOSHI && dictionary.storageReady) {
                hoshi += id
            } else {
                legacy += id
            }
        }

        return DictionaryIdPartition(
            legacyIds = legacy,
            hoshiIds = hoshi,
        )
    }

    private fun cacheBackendEntries(entries: List<DictionarySearchEntry>) {
        entries.forEach { entry ->
            hoshiTermMetaCache["${entry.term.dictionaryId}|${entry.term.expression}"] = entry.termMeta
        }
    }

    private fun cachedHoshiTermMeta(expression: String, dictionaryIds: List<Long>): List<DictionaryTermMeta> {
        return dictionaryIds.flatMap { dictionaryId ->
            hoshiTermMetaCache["$dictionaryId|$expression"].orEmpty()
        }
    }

    private suspend fun loadHoshiTermMeta(
        expression: String,
        requestedDictionaryIds: List<Long>,
    ): List<DictionaryTermMeta> {
        if (requestedDictionaryIds.isEmpty()) return emptyList()

        val requestedIdsSet = requestedDictionaryIds.toSet()
        val cached = cachedHoshiTermMeta(expression, requestedDictionaryIds)
        if (cached.isNotEmpty()) {
            return cached.distinctBy { meta ->
                "${meta.dictionaryId}|${meta.expression}|${meta.mode}|${meta.data}"
            }
        }

        val readyHoshiDictionaryIds = dictionaryRepository.getAllDictionaries()
            .asSequence()
            .filter { it.backend.name == "HOSHI" && it.storageReady }
            .map { it.id }
            .toList()

        if (readyHoshiDictionaryIds.isEmpty()) {
            return emptyList()
        }

        val exactEntries = dictionarySearchBackend.exactSearch(expression, readyHoshiDictionaryIds)
        cacheBackendEntries(exactEntries)
        val exactMeta = exactEntries
            .flatMap { it.termMeta }
            .filter { it.dictionaryId in requestedIdsSet }
        if (exactMeta.isNotEmpty()) {
            return exactMeta.distinctBy { meta ->
                "${meta.dictionaryId}|${meta.expression}|${meta.mode}|${meta.data}"
            }
        }

        val lookupMatches = dictionarySearchBackend.lookup(
            text = expression,
            dictionaryIds = readyHoshiDictionaryIds,
            maxResults = MAX_RESULTS,
        )
        val lookupEntries = lookupMatches.map { match ->
            DictionarySearchEntry(
                term = match.term,
                termMeta = match.termMeta,
            )
        }
        cacheBackendEntries(lookupEntries)
        val lookupMeta = lookupEntries
            .flatMap { it.termMeta }
            .filter { it.dictionaryId in requestedIdsSet }
        if (lookupMeta.isNotEmpty()) {
            return lookupMeta.distinctBy { meta ->
                "${meta.dictionaryId}|${meta.expression}|${meta.mode}|${meta.data}"
            }
        }

        return emptyList()
    }

    private suspend fun searchLegacyJapaneseDeinflected(
        query: String,
        dictionaryIds: List<Long>,
    ): List<DictionaryTerm> {
        val partition = DictionaryIdPartition(legacyIds = dictionaryIds, hoshiIds = emptyList())
        val formattedQuery = convertToKana(query.trim())
        if (formattedQuery.isBlank()) return emptyList()

        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.groupBy { it.term }
        val results = LinkedHashMap<String, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for (candidate in candidateQueries) {
            val term = candidate.term
            if (term.isBlank()) continue

            val matches = queryCandidates(term = term, isJapanese = true, partition = partition)

            for (entry in matches) {
                val dbTerm = entry.term
                val termKey = termKey(dbTerm)
                if (termKey in results) continue

                val candidatesForTerm = candidatesByTerm[dbTerm.expression]
                    ?: candidatesByTerm[dbTerm.reading]
                    ?: listOf(candidate)

                if (isValidMatch(dbTerm, candidatesForTerm)) {
                    results[termKey] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return sortTermsByPriority(results.values.toList())
    }

    private suspend fun searchEnglishDeinflected(
        query: String,
        dictionaryIds: List<Long>,
    ): List<DictionaryTerm> {
        val partition = partitionDictionaryIds(dictionaryIds)
        val formattedQuery = query.trim()
        if (formattedQuery.isBlank()) return emptyList()

        val candidateQueries = EnglishDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.groupBy { it.term.lowercase() }
        val results = LinkedHashMap<String, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for (candidate in candidateQueries) {
            val term = candidate.term
            if (term.isBlank()) continue

            val matches = queryCandidates(term = term, isJapanese = false, partition = partition)

            for (entry in matches) {
                val dbTerm = entry.term
                val termKey = termKey(dbTerm)
                if (termKey in results) continue

                val candidatesForTerm = candidatesByTerm[dbTerm.expression.lowercase()]
                    ?: candidatesByTerm[dbTerm.reading.lowercase()]
                    ?: listOf(candidate)

                if (isValidMatch(dbTerm, candidatesForTerm)) {
                    results[termKey] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return sortTermsByPriority(results.values.toList())
    }

    private suspend fun searchJa(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        val partition = partitionDictionaryIds(dictionaryIds)
        val results = LinkedHashMap<String, DictionaryTerm>(MAX_RESULTS * 2)

        if (partition.hoshiIds.isNotEmpty()) {
            val entries = dictionarySearchBackend.lookup(
                text = convertToKana(query.trim()),
                dictionaryIds = partition.hoshiIds,
                maxResults = MAX_RESULTS,
            ).map {
                DictionarySearchEntry(
                    term = it.term,
                    termMeta = it.termMeta,
                )
            }
            cacheBackendEntries(entries)
            entries.forEach { entry ->
                val key = termKey(entry.term)
                if (key !in results && results.size < MAX_RESULTS) {
                    results[key] = entry.term
                }
            }
        }

        if (partition.legacyIds.isNotEmpty() && results.size < MAX_RESULTS) {
            searchLegacyJapaneseDeinflected(query, partition.legacyIds)
                .forEach { term ->
                    val key = termKey(term)
                    if (key !in results && results.size < MAX_RESULTS) {
                        results[key] = term
                    }
                }
        }

        return sortTermsByPriority(results.values.toList())
    }

    private suspend fun searchExact(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        val partition = partitionDictionaryIds(dictionaryIds)
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = LinkedHashMap<String, DictionaryTerm>(MAX_RESULTS * 2)

        if (partition.legacyIds.isNotEmpty()) {
            dictionaryRepository.searchTerms(trimmed, partition.legacyIds).forEach { term ->
                val key = termKey(term)
                if (key !in results && results.size < MAX_RESULTS) {
                    results[key] = term
                }
            }
        }

        if (partition.hoshiIds.isNotEmpty()) {
            val entries = dictionarySearchBackend.exactSearch(trimmed, partition.hoshiIds)
            cacheBackendEntries(entries)
            entries.forEach { entry ->
                val key = termKey(entry.term)
                if (key !in results && results.size < MAX_RESULTS) {
                    results[key] = entry.term
                }
            }
        }

        val lowered = trimmed.lowercase()
        if (lowered != trimmed && results.size < MAX_RESULTS) {
            if (partition.legacyIds.isNotEmpty()) {
                dictionaryRepository.searchTerms(lowered, partition.legacyIds).forEach { term ->
                    val key = termKey(term)
                    if (key !in results && results.size < MAX_RESULTS) {
                        results[key] = term
                    }
                }
            }

            if (partition.hoshiIds.isNotEmpty()) {
                val entries = dictionarySearchBackend.exactSearch(lowered, partition.hoshiIds)
                cacheBackendEntries(entries)
                entries.forEach { entry ->
                    val key = termKey(entry.term)
                    if (key !in results && results.size < MAX_RESULTS) {
                        results[key] = entry.term
                    }
                }
            }
        }

        return sortTermsByPriority(results.values.toList())
    }

    private suspend fun searchEn(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return searchEnglishDeinflected(query, dictionaryIds)
    }

    suspend fun findFirstWord(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): String = findFirstWordMatch(sentence, dictionaryIds, parserLanguage).word

    suspend fun findFirstWordMatch(
        sentence: String,
        dictionaryIds: List<Long>,
        parserLanguage: ParserLanguage = ParserLanguage.AUTO,
    ): FirstWordMatch {
        if (sentence.isBlank() || dictionaryIds.isEmpty()) return FirstWordMatch("", 0, 0)

        val allowedScripts = getAllowedScripts(dictionaryIds)
        val script = resolveScript(sentence, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> firstWordJa(sentence, dictionaryIds)
            Script.ENGLISH -> firstWordEn(sentence, dictionaryIds)
            else -> firstWordDirect(sentence, dictionaryIds, script)
        }

        if (!script.isNonCjk() || !isJapaneseAllowed) {
            return primaryResult
        }

        val jaResult = firstWordJa(sentence, dictionaryIds)

        return chooseBetterMatch(primaryResult, jaResult)
    }

    private fun stripLeadingPunctuation(sentence: String): Pair<Int, String> {
        val leadingTrimmedCount = sentence.indexOfFirst { it !in punctuationCharSet }
            .let { if (it == -1) sentence.length else it }
        return leadingTrimmedCount to sentence.drop(leadingTrimmedCount)
    }

    private suspend fun findFirstLegacyJapaneseWord(
        sentence: String,
        dictionaryIds: List<Long>,
    ): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val normalized = convertToKana(sanitized)
        val actualMaxLength = minOf(normalized.length, MAX_WORD_LENGTH)

        for (len in actualMaxLength downTo 1) {
            val substring = normalized.take(len)

            val candidates = JapaneseDeinflector.deinflect(substring)
            for (candidate in candidates) {
                val term = candidate.term
                if (term.isBlank()) continue

                val matches = dictionaryRepository.searchTerms(term, dictionaryIds)

                if (matches.isNotEmpty()) {
                    val candidatesForTerm = candidates.filter { c ->
                        c.term == term || matches.any { m -> m.reading == c.term }
                    }

                    if (matches.any { dbTerm -> isValidMatch(dbTerm, candidatesForTerm) }) {
                        val sourceLength = mapSourceLength(sanitized, substring)
                        return FirstWordMatch(substring, leadingTrimmedCount, sourceLength, true)
                    }
                }
            }
        }

        val fallbackLength = mapSourceLength(sanitized, normalized.take(1))
        val fallbackWord = normalized.take(1)
        return FirstWordMatch(fallbackWord, leadingTrimmedCount, fallbackLength, false)
    }

    private suspend fun firstWordJa(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        val partition = partitionDictionaryIds(dictionaryIds)

        val legacyResult = if (partition.legacyIds.isNotEmpty()) {
            findFirstLegacyJapaneseWord(sentence = sentence, dictionaryIds = partition.legacyIds)
        } else {
            FirstWordMatch("", 0, 0)
        }

        val hoshiResult = if (partition.hoshiIds.isNotEmpty()) {
            firstWordJaHoshi(sentence, partition.hoshiIds)
        } else {
            FirstWordMatch("", 0, 0)
        }

        return chooseBetterMatch(legacyResult, hoshiResult)
    }

    private suspend fun firstWordJaHoshi(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val normalized = convertToKana(sanitized)
        val lookupResults = dictionarySearchBackend.lookup(
            text = normalized,
            dictionaryIds = dictionaryIds,
            maxResults = MAX_RESULTS,
        )

        if (lookupResults.isNotEmpty()) {
            val best = lookupResults.first()
            val sourceLength = mapSourceLength(sanitized, best.matched)
            cacheBackendEntries(
                listOf(
                    DictionarySearchEntry(
                        term = best.term,
                        termMeta = best.termMeta,
                    ),
                ),
            )
            return FirstWordMatch(
                word = best.matched,
                sourceOffset = leadingTrimmedCount,
                sourceLength = sourceLength,
                isDictionaryMatch = true,
            )
        }

        val fallbackWord = normalized.take(1)
        return FirstWordMatch(
            word = fallbackWord,
            sourceOffset = leadingTrimmedCount,
            sourceLength = mapSourceLength(sanitized, fallbackWord),
            isDictionaryMatch = false,
        )
    }

    private suspend fun firstWordEn(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        val partition = partitionDictionaryIds(dictionaryIds)
        return findFirstEnglishWord(sentence, partition)
    }

    private suspend fun findFirstEnglishWord(
        sentence: String,
        partition: DictionaryIdPartition,
    ): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val normalized = sanitized
        val actualMaxLength = minOf(normalized.length, 40)

        for (len in actualMaxLength downTo 1) {
            val substring = normalized.take(len)
            if (len > 1 && substring.last().isWhitespace()) continue

            val candidates = EnglishDeinflector.deinflect(substring)
            for (candidate in candidates) {
                val term = candidate.term
                if (term.isBlank()) continue

                val matches = queryCandidates(term, false, partition)
                if (matches.isEmpty()) continue

                val candidatesForTerm = candidates.filter { c ->
                    c.term == term ||
                        c.term.equals(term, ignoreCase = true) ||
                        matches.any { entry -> entry.term.reading.equals(c.term, ignoreCase = true) }
                }

                if (matches.any { entry -> isValidMatch(entry.term, candidatesForTerm) }) {
                    return FirstWordMatch(substring, leadingTrimmedCount, len, true)
                }
            }
        }

        val fallbackLength = calcFallbackWordLen(sanitized)
        val fallbackWord = sanitized.take(fallbackLength)
        return FirstWordMatch(fallbackWord, leadingTrimmedCount, fallbackLength, false)
    }

    private suspend fun firstWordDirect(sentence: String, dictionaryIds: List<Long>, script: Script): FirstWordMatch {
        val partition = partitionDictionaryIds(dictionaryIds)
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val maxLength = minOf(sanitized.length, 40)

        for (len in maxLength downTo 1) {
            val substring = sanitized.take(len)
            if (len > 1 && substring.last().isWhitespace()) continue

            if (queryCandidates(substring, false, partition).isNotEmpty()) {
                return FirstWordMatch(substring, leadingTrimmedCount, len, true)
            }

            if (script == Script.ENGLISH) {
                val lowered = substring.lowercase()
                if (lowered != substring && queryCandidates(lowered, false, partition).isNotEmpty()) {
                    return FirstWordMatch(substring, leadingTrimmedCount, len, true)
                }
            }
        }

        val fallbackLength = if (script.isNonCjk()) calcFallbackWordLen(sanitized) else 1
        val fallbackWord = sanitized.take(fallbackLength)
        return FirstWordMatch(fallbackWord, leadingTrimmedCount, fallbackLength, false)
    }

    private suspend fun queryCandidates(
        term: String,
        isJapanese: Boolean,
        partition: DictionaryIdPartition,
    ): List<DictionarySearchEntry> {
        val results = mutableListOf<DictionarySearchEntry>()

        if (partition.legacyIds.isNotEmpty()) {
            dictionaryRepository.searchTerms(term, partition.legacyIds)
                .forEach { results += DictionarySearchEntry(it, emptyList()) }
        }

        if (partition.hoshiIds.isNotEmpty()) {
            val exactEntries = dictionarySearchBackend.exactSearch(term, partition.hoshiIds)
            cacheBackendEntries(exactEntries)
            results += exactEntries
        }

        if (!isJapanese) {
            val lowered = term.lowercase()
            if (lowered != term) {
                if (partition.legacyIds.isNotEmpty()) {
                    dictionaryRepository.searchTerms(lowered, partition.legacyIds)
                        .forEach { results += DictionarySearchEntry(it, emptyList()) }
                }
                if (partition.hoshiIds.isNotEmpty()) {
                    val exactEntries = dictionarySearchBackend.exactSearch(lowered, partition.hoshiIds)
                    cacheBackendEntries(exactEntries)
                    results += exactEntries
                }
            }
        }

        return results
    }

    private suspend fun sortTermsByPriority(terms: List<DictionaryTerm>): List<DictionaryTerm> {
        val priorityCache = terms.map { it.dictionaryId }.distinct().associateWith { dictionaryId ->
            dictionaryRepository.getDictionary(dictionaryId)?.priority ?: Int.MAX_VALUE
        }

        return terms.sortedWith(
            compareBy<DictionaryTerm> { priorityCache[it.dictionaryId] ?: Int.MAX_VALUE }
                .thenByDescending { it.score }
                .thenBy { it.expression },
        )
    }

    private fun chooseBetterMatch(first: FirstWordMatch, second: FirstWordMatch): FirstWordMatch {
        return when {
            second.isDictionaryMatch && !first.isDictionaryMatch -> second
            first.isDictionaryMatch && !second.isDictionaryMatch -> first
            second.sourceLength > first.sourceLength -> second
            else -> first
        }
    }

    private fun termKey(term: DictionaryTerm): String {
        return "${term.dictionaryId}|${term.expression}|${term.reading}|${term.definitionTags}|${term.termTags}"
    }

    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || (c in punctuationCharSet && c != '\'' && c != '\u2019')

    private fun calcFallbackWordLen(sanitized: String): Int {
        if (sanitized.isEmpty()) return 0

        val boundaryIndex = sanitized.indexOfFirst { isBoundary(it) }
        return when (boundaryIndex) {
            -1 -> sanitized.length
            0 -> 1
            else -> boundaryIndex
        }
    }

    private fun isValidMatch(term: DictionaryTerm, candidates: List<Candidate>): Boolean {
        val dbRuleMask = InflectionType.parseRules(term.rules)

        for (candidate in candidates) {
            if (candidate.conditions == InflectionType.ALL) return true
            if (dbRuleMask == InflectionType.UNSPECIFIED) return true
            if (InflectionType.conditionsMatch(candidate.conditions, dbRuleMask)) return true
        }
        return false
    }

    suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> {
        val partition = partitionDictionaryIds(dictionaryIds)
        return expressions.associateWith { expression ->
            buildList {
                if (partition.legacyIds.isNotEmpty()) {
                    addAll(dictionaryRepository.getTermMetaForExpression(expression, partition.legacyIds))
                }
                if (partition.hoshiIds.isNotEmpty()) {
                    addAll(loadHoshiTermMeta(expression, partition.hoshiIds))
                }
            }
        }
    }

    private fun convertToKana(input: String): String {
        return input.trim().let {
            if (it.any(Char::isLatinLetter) || Wanakana.isRomaji(it) || Wanakana.isMixed(it)) {
                Wanakana.toKana(it)
            } else {
                it
            }
        }
    }

    private fun mapSourceLength(source: String, normalizedPrefix: String): Int {
        if (normalizedPrefix.isEmpty()) return 0

        for (index in 1..source.length) {
            val convertedPrefix = convertToKana(source.take(index))
            if (convertedPrefix.length >= normalizedPrefix.length &&
                convertedPrefix.startsWith(normalizedPrefix)
            ) {
                return index
            }
        }

        return minOf(source.length, normalizedPrefix.length)
    }
}

private fun Char.isLatinLetter(): Boolean =
    (this in 'a'..'z') || (this in 'A'..'Z')

private const val MAX_RESULTS = 100
private const val MAX_WORD_LENGTH = 20
private const val SCRIPT_DETECT_WINDOW = 30

private val PUNCTUATION_CHARS: Set<Char> = setOf(
    '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
    '〔', '〕', '《', '》', '〈', '〉',
    '・', '、', '。', '！', '？', '：', '；',
    ' ', '\t', '\n', '\r', '\u3000',
    '\u201C', '\u201D',
    '\u2018', '\u2019',
    '"', '\'',
    '.', ',', '…',
    '-', '\u2010', '\u2013', '\u2014',
    '«', '»', '<', '>', '[', ']', '{', '}', '/', '\\',
    '〜', '\u301C', '\uFF5E',
)
