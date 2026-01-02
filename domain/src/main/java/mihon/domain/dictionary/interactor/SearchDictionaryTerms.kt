package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import java.util.LinkedHashMap
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.Candidate
import mihon.domain.dictionary.service.InflectionType
import mihon.domain.dictionary.service.JapaneseDeinflector

/**
 * Interactor for searching dictionary terms.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
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
        if (sentence.isBlank() || dictionaryIds.isEmpty()) return ""

        // Remove leading punctuation and brackets
        val sanitized = sentence.trimStart { it in LEADING_PUNCTUATION }
        if (sanitized.isEmpty()) return ""

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
                        // Returns the matched word text
                        return substring
                    }
                }
            }
        }

        // No dictionary match found - return the first character as fallback
        return normalized.take(1)
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

    suspend fun getTermMeta(expressions: List<String>, dictionaryIds: List<Long>): Map<String, List<DictionaryTermMeta>> {
        val allMeta = mutableMapOf<String, MutableList<DictionaryTermMeta>>()

        expressions.forEach { expression ->
            val meta = dictionaryRepository.getTermMetaForExpression(expression, dictionaryIds)
            allMeta[expression] = meta.toMutableList()
        }

        return allMeta
    }

    private fun convertToKana(input: String): String {
        return input.trim().let { if (Wanakana.isRomaji(it) || Wanakana.isMixed(it)) Wanakana.toKana(it) else it }
    }
}

private const val MAX_RESULTS = 100
private const val MAX_WORD_LENGTH = 20
private val LEADING_PUNCTUATION = setOf(
    '「', '」', '『', '』', '（', '）', '(', ')', '【', '】',
    '〔', '〕', '《', '》', '〈', '〉',
    '・', '、', '。', '！', '？', '：', '；',
    ' ', '\u3000',      // space characters
    '\u201C', '\u201D', // quotation marks
    '\u2018', '\u2019', // single quotation marks
)
