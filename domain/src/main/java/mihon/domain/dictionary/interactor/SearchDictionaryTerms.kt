package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector
import java.util.LinkedHashMap

/**
 * Interactor for searching dictionary terms.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    data class FirstWordMatch(
        val word: String,
        val sourceOffset: Int,
        val sourceLength: Int,
    )

    suspend fun search(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        if (dictionaryIds.isEmpty()) return emptyList()

        // Converts romaji to kana to support romaji input
        val formattedQuery = convertToKana(query.trim())

        // Gather possible deinflections for the term
        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        // Group candidates with the same word result, but different inflection reasons
        val candidatesByTerm = candidateQueries.groupBy { it.term }

        val results = LinkedHashMap<Long, DictionaryTerm>(minOf(candidateQueries.size * 4, MAX_RESULTS * 2))

        candidateLoop@ for (candidate in candidateQueries) {
            val term = candidate.term
            if (term.isBlank()) continue

            val matches = dictionaryRepository.searchTerms(term, dictionaryIds)
            for (dbTerm in matches) {
                // Avoid duplicates
                if (dbTerm.id in results) continue

                // Validate parts of speech: match candidate conditions against DB entry's rules
                // Falls back to reading for better coverage
                val candidatesForTerm = candidatesByTerm[dbTerm.expression]
                    ?: candidatesByTerm[dbTerm.reading]

                if (candidatesForTerm != null && isValidMatch(dbTerm, candidatesForTerm)) {
                    results[dbTerm.id] = dbTerm
                    if (results.size >= MAX_RESULTS) break@candidateLoop
                }
            }
        }

        return results.values.toList()
    }

    /**
     * Parses the first word of a "sentence" based on the longest dictionary match.
     */
    suspend fun findFirstWord(sentence: String, dictionaryIds: List<Long>): String {
        return findFirstWordMatch(sentence, dictionaryIds).word
    }

    /**
     * Parses the first word of a sentence and returns both the normalized search word and
     * source position information for highlighting.
     */
    suspend fun findFirstWordMatch(sentence: String, dictionaryIds: List<Long>): FirstWordMatch {
        if (sentence.isBlank() || dictionaryIds.isEmpty()) return FirstWordMatch("", 0, 0)

        // Remove leading punctuation and brackets, while preserving offset in source text
        val leadingTrimmedCount = sentence.indexOfFirst { it !in LEADING_PUNCTUATION }
            .let { if (it == -1) sentence.length else it }
        val sanitized = sentence.drop(leadingTrimmedCount)
        if (sanitized.isEmpty()) return FirstWordMatch("", leadingTrimmedCount, 0)

        // Convert romaji to kana
        val normalized = convertToKana(sanitized)

        val maxLength = minOf(normalized.length, MAX_WORD_LENGTH)

        for (len in maxLength downTo 1) {
            val substring = normalized.take(len)
            val candidates = JapaneseDeinflector.deinflect(substring)

            for (candidate in candidates) {
                val term = candidate.term
                if (term.isBlank()) continue

                // Check if this candidate exists in the dictionary
                val matches = dictionaryRepository.searchTerms(term, dictionaryIds)
                if (matches.isNotEmpty()) {
                    val candidatesForTerm = candidates.filter { c ->
                        c.term == term || matches.any { m -> m.reading == c.term }
                    }
                    val validMatch = matches.any { dbTerm ->
                        isValidMatch(dbTerm, candidatesForTerm)
                    }
                    if (validMatch) {
                        val sourceLength = mapSourceLength(sanitized, substring)
                        return FirstWordMatch(
                            word = substring,
                            sourceOffset = leadingTrimmedCount,
                            sourceLength = sourceLength,
                        )
                    }
                }
            }
        }

        // No dictionary match found - return the first character as fallback
        val fallbackWord = normalized.take(1)
        return FirstWordMatch(
            word = fallbackWord,
            sourceOffset = leadingTrimmedCount,
            sourceLength = mapSourceLength(sanitized, fallbackWord),
        )
    }

    /**
     * Validates that a dictionary term matches at least one of the candidate conditions.
     *
     * A match is valid if:
     * 1. The candidate has no specific condition (ALL conditions)
     * 2. The DB entry has no rules specified
     * 3. The candidate's condition shares at least one bit with the DB entry's rules
     */
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
        val allMeta = mutableMapOf<String, MutableList<DictionaryTermMeta>>()

        expressions.forEach { expression ->
            val meta = dictionaryRepository.getTermMetaForExpression(expression, dictionaryIds)
            allMeta[expression] = meta.toMutableList()
        }

        return allMeta
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

    /*
    * Maps the length of the normalized prefix back to the source string, accounting for romaji
    */
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
private val LEADING_PUNCTUATION = setOf(
    '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
    '〔', '〕', '《', '》', '〈', '〉',
    '・', '、', '。', '！', '？', '：', '；',
    ' ', '\t', '\n', '\r', '\u3000', // whitespace characters
    '\u201C', '\u201D', // double quotation marks
    '\u2018', '\u2019', // single quotation marks
    '"', '\'', // ASCII quotes
    '.', ',', '…', "...", // punctuation and ellipsis
    '-', '\u2010', '\u2013', '\u2014', // hyphen variants
    '«', '»', '<', '>', '[', ']', '{', '}', '/', '\\',
    '〜', '\u301C', '\uFF5E', // tildes / wave dash
)
