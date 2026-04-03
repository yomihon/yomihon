package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.partitionDictionaryIdsByBackend
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.DictionarySearchGateway
import mihon.domain.dictionary.service.EnglishDeinflector
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector
import java.util.LinkedHashMap

/**
 * Interactor for searching dictionary terms with multilingual support.
 * The parser (Japanese lookup vs. exact/direct lookup) is chosen automatically
 * by detecting the script of the query text, so no language hint is needed.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionarySearchGateway: DictionarySearchGateway,
) {
    data class FirstWordMatch(
        val word: String,
        val sourceOffset: Int,
        val sourceLength: Int,
        val isDictionaryMatch: Boolean = false,
    )

    private val punctuationCharSet: Set<Char> get() = PUNCTUATION_CHARS

    /** Script families used to select the right search/segmentation pipeline. */
    private enum class Script { JAPANESE, KOREAN, CHINESE, ENGLISH }

    private data class SearchContext(
        val dictionariesById: Map<Long, Dictionary>,
        val prioritiesById: Map<Long, Int>,
    )

    private fun Script.isNonCjk(): Boolean =
        this != Script.JAPANESE && this != Script.CHINESE && this != Script.KOREAN

    private suspend fun buildSearchContext(dictionaryIds: Collection<Long>): SearchContext {
        val dictionaries = dictionaryRepository.getAllDictionaries()
            .filter { it.id in dictionaryIds }
        return SearchContext(
            dictionariesById = dictionaries.associateBy { it.id },
            prioritiesById = dictionaries.associate { it.id to it.priority },
        )
    }

    private fun getAllowedScripts(dictionaryIds: List<Long>, context: SearchContext): Set<Script>? {
        val allowed = mutableSetOf<Script>()
        for (id in dictionaryIds) {
            val dict = context.dictionariesById[id] ?: continue
            val src = dict.sourceLanguage.orEmpty()

            val scripts = if (src.isEmpty() || src == "unrestricted") {
                emptySet()
            } else {
                setOfNotNull(mapLanguageToScript(src))
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

        val context = buildSearchContext(dictionaryIds)
        val normalizedQuery = query.trim { it in punctuationCharSet || it.isWhitespace() }
        val allowedScripts = getAllowedScripts(dictionaryIds, context)
        val script = resolveScript(normalizedQuery, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> searchJa(normalizedQuery, dictionaryIds, context)
            Script.ENGLISH -> searchEn(normalizedQuery, dictionaryIds, context)
            else -> searchExact(normalizedQuery, dictionaryIds, context)
        }

        return if (primaryResult.isEmpty() && script.isNonCjk() && isJapaneseAllowed) {
            searchJa(normalizedQuery, dictionaryIds, context)
        } else {
            primaryResult
        }
    }

    private suspend fun searchLegacyJapaneseDeinflected(
        query: String,
        dictionaryIds: List<Long>,
        prioritiesById: Map<Long, Int>,
    ): List<DictionaryTerm> {
        val formattedQuery = convertToKana(query.trim())
        if (formattedQuery.isBlank()) return emptyList()

        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.groupBy { it.term }
        val results = LinkedHashMap<String, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for ((term, groupedCandidates) in candidatesByTerm) {
            if (term.isBlank()) continue

            val matches = dictionarySearchGateway.exactSearch(term, dictionaryIds)

            for (entry in matches) {
                val dbTerm = entry.term
                val termKey = termKey(dbTerm)
                if (termKey in results) continue

                val candidatesForTerm = candidatesByTerm[dbTerm.expression]
                    ?: candidatesByTerm[dbTerm.reading]
                    ?: groupedCandidates

                if (isValidMatch(dbTerm, candidatesForTerm)) {
                    results[termKey] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return sortTermsByPriority(results.values.toList(), prioritiesById)
    }

    private suspend fun searchEnglishDeinflected(
        query: String,
        dictionaryIds: List<Long>,
        prioritiesById: Map<Long, Int>,
    ): List<DictionaryTerm> {
        val formattedQuery = query.trim()
        if (formattedQuery.isBlank()) return emptyList()

        val candidateQueries = EnglishDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.groupBy { it.term.lowercase() }
        val results = LinkedHashMap<String, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for ((term, groupedCandidates) in candidatesByTerm) {
            if (term.isBlank()) continue

            val matches = queryCandidates(term = term, isJapanese = false, dictionaryIds = dictionaryIds)

            for (entry in matches) {
                val dbTerm = entry.term
                val termKey = termKey(dbTerm)
                if (termKey in results) continue

                val candidatesForTerm = candidatesByTerm[dbTerm.expression.lowercase()]
                    ?: candidatesByTerm[dbTerm.reading.lowercase()]
                    ?: groupedCandidates

                if (isValidMatch(dbTerm, candidatesForTerm)) {
                    results[termKey] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return sortTermsByPriority(results.values.toList(), prioritiesById)
    }

    private suspend fun searchJa(
        query: String,
        dictionaryIds: List<Long>,
        context: SearchContext,
    ): List<DictionaryTerm> {
        val normalizedQuery = convertToKana(query.trim())
        val split = partitionDictionaryIdsByBackend(dictionaryIds, context.dictionariesById)
        val results = LinkedHashMap<String, DictionaryTerm>(MAX_RESULTS * 2)
        val lookupRanksByKey = mutableMapOf<String, Int>()

        if (split.hoshiIds.isNotEmpty()) {
            dictionarySearchGateway.lookup(
                text = normalizedQuery,
                dictionaryIds = split.hoshiIds,
                maxResults = MAX_RESULTS,
            ).forEach { match ->
                val key = termKey(match.term)
                val rank = lookupMatchRank(
                    term = match.term,
                    matched = match.matched,
                    query = normalizedQuery,
                )
                if (key !in results && results.size < MAX_RESULTS) {
                    results[key] = match.term
                }
                if (key in results) {
                    lookupRanksByKey[key] = minOf(lookupRanksByKey[key] ?: Int.MAX_VALUE, rank)
                }
            }
        }

        if (results.size < MAX_RESULTS && split.legacyIds.isNotEmpty()) {
            searchLegacyJapaneseDeinflected(query, split.legacyIds, context.prioritiesById)
                .forEach { term ->
                    val key = termKey(term)
                    if (key !in results && results.size < MAX_RESULTS) {
                        results[key] = term
                        lookupRanksByKey[key] = exactMatchRank(term, normalizedQuery)
                    }
                }
        }

        return sortTermsByLookupExactness(
            terms = results.values.toList(),
            prioritiesById = context.prioritiesById,
            lookupRanksByKey = lookupRanksByKey,
        )
    }

    private suspend fun searchExact(
        query: String,
        dictionaryIds: List<Long>,
        context: SearchContext,
    ): List<DictionaryTerm> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val results = LinkedHashMap<String, DictionaryTerm>(MAX_RESULTS * 2)

        dictionarySearchGateway.exactSearch(trimmed, dictionaryIds)
            .forEach { entry ->
                val key = termKey(entry.term)
                if (key !in results && results.size < MAX_RESULTS) {
                    results[key] = entry.term
                }
            }

        val lowered = trimmed.lowercase()
        if (lowered != trimmed && results.size < MAX_RESULTS) {
            dictionarySearchGateway.exactSearch(lowered, dictionaryIds)
                .forEach { entry ->
                    val key = termKey(entry.term)
                    if (key !in results && results.size < MAX_RESULTS) {
                        results[key] = entry.term
                    }
                }
        }

        return sortTermsByExactMatch(
            terms = results.values.toList(),
            query = trimmed,
            prioritiesById = context.prioritiesById,
        )
    }

    private suspend fun searchEn(
        query: String,
        dictionaryIds: List<Long>,
        context: SearchContext,
    ): List<DictionaryTerm> {
        return searchEnglishDeinflected(query, dictionaryIds, context.prioritiesById)
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

        val context = buildSearchContext(dictionaryIds)
        val allowedScripts = getAllowedScripts(dictionaryIds, context)
        val script = resolveScript(sentence, parserLanguage, allowedScripts)
        val isJapaneseAllowed = allowedScripts == null || Script.JAPANESE in allowedScripts

        val primaryResult = when (script) {
            Script.JAPANESE -> firstWordJa(sentence, dictionaryIds, context)
            Script.ENGLISH -> firstWordEn(sentence, dictionaryIds)
            else -> firstWordDirect(sentence, dictionaryIds, script)
        }

        if (!script.isNonCjk() || !isJapaneseAllowed) {
            return primaryResult
        }

        val jaResult = firstWordJa(sentence, dictionaryIds, context)

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

                val matches = dictionarySearchGateway.exactSearch(term, dictionaryIds).map { it.term }

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

    private suspend fun firstWordJa(
        sentence: String,
        dictionaryIds: List<Long>,
        context: SearchContext,
    ): FirstWordMatch {
        val split = partitionDictionaryIdsByBackend(dictionaryIds, context.dictionariesById)

        val legacyResult = if (split.legacyIds.isNotEmpty()) {
            findFirstLegacyJapaneseWord(sentence = sentence, dictionaryIds = split.legacyIds)
        } else {
            FirstWordMatch("", 0, 0)
        }

        val hoshiResult = if (split.hoshiIds.isNotEmpty()) {
            firstWordJaLookup(sentence, split.hoshiIds)
        } else {
            FirstWordMatch("", 0, 0)
        }

        return when {
            split.legacyIds.isEmpty() -> hoshiResult
            split.hoshiIds.isEmpty() -> legacyResult
            else -> chooseBetterMatch(legacyResult, hoshiResult)
        }
    }

    private suspend fun firstWordJaLookup(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val normalized = convertToKana(sanitized)
        val lookupResults = dictionarySearchGateway.lookup(
            text = normalized,
            dictionaryIds = dictionaryIds,
            maxResults = MAX_RESULTS,
        )

        if (lookupResults.isNotEmpty()) {
            val best = lookupResults.first()
            val sourceLength = mapSourceLength(sanitized, best.matched)
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
        return findFirstEnglishWord(sentence, dictionaryIds)
    }

    private suspend fun findFirstEnglishWord(
        sentence: String,
        dictionaryIds: List<Long>,
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

                val matches = queryCandidates(term, false, dictionaryIds)
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
        val (leadingTrimmedCount, sanitized) = stripLeadingPunctuation(sentence)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        val maxLength = minOf(sanitized.length, 40)

        for (len in maxLength downTo 1) {
            val substring = sanitized.take(len)
            if (len > 1 && substring.last().isWhitespace()) continue

            if (queryCandidates(substring, false, dictionaryIds).isNotEmpty()) {
                return FirstWordMatch(substring, leadingTrimmedCount, len, true)
            }

            if (script == Script.ENGLISH) {
                val lowered = substring.lowercase()
                if (lowered != substring && queryCandidates(lowered, false, dictionaryIds).isNotEmpty()) {
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
        dictionaryIds: List<Long>,
    ): List<mihon.domain.dictionary.service.DictionarySearchEntry> {
        val results = mutableListOf<mihon.domain.dictionary.service.DictionarySearchEntry>()
        results += dictionarySearchGateway.exactSearch(term, dictionaryIds)

        if (!isJapanese) {
            val lowered = term.lowercase()
            if (lowered != term) {
                results += dictionarySearchGateway.exactSearch(lowered, dictionaryIds)
            }
        }

        return results
    }

    private fun sortTermsByPriority(
        terms: List<DictionaryTerm>,
        prioritiesById: Map<Long, Int>,
    ): List<DictionaryTerm> {
        if (terms.isEmpty()) return emptyList()

        return terms.sortedWith(
            compareBy<DictionaryTerm> { prioritiesById[it.dictionaryId] ?: Int.MAX_VALUE }
                .thenByDescending { it.score },
        )
    }

    private fun sortTermsByExactMatch(
        terms: List<DictionaryTerm>,
        query: String,
        prioritiesById: Map<Long, Int>,
    ): List<DictionaryTerm> {
        if (terms.isEmpty()) return emptyList()

        return terms.sortedWith(
            compareBy<DictionaryTerm> { exactMatchRank(it, query) }
                .thenBy { prioritiesById[it.dictionaryId] ?: Int.MAX_VALUE }
                .thenByDescending { it.score },
        )
    }

    private fun sortTermsByLookupExactness(
        terms: List<DictionaryTerm>,
        prioritiesById: Map<Long, Int>,
        lookupRanksByKey: Map<String, Int>,
    ): List<DictionaryTerm> {
        if (terms.isEmpty()) return emptyList()

        return terms.sortedWith(
            compareBy<DictionaryTerm> { lookupRanksByKey[termKey(it)] ?: Int.MAX_VALUE }
                .thenBy { prioritiesById[it.dictionaryId] ?: Int.MAX_VALUE }
                .thenByDescending { it.score },
        )
    }

    private fun exactMatchRank(term: DictionaryTerm, query: String): Int {
        return when {
            term.expression == query -> 0
            term.reading == query -> 1
            else -> 2
        }
    }

    private fun lookupMatchRank(term: DictionaryTerm, matched: String, query: String): Int {
        return when {
            term.expression == query -> 0
            term.reading == query -> 1
            matched == query -> 2
            else -> 3
        }
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
        if (expressions.isEmpty()) return emptyMap()
        if (dictionaryIds.isEmpty()) {
            return expressions.associateWith { emptyList() }
        }
        return dictionarySearchGateway.getTermMeta(expressions, dictionaryIds)
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
