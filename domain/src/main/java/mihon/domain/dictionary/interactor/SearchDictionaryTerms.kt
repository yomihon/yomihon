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
        val formattedQuery = query.trim().let { if (Wanakana.isRomaji(it) || Wanakana.isMixed(it)) Wanakana.toKana(it) else it}

        // Gather possible deinflections for the term
        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val candidatesByTerm = candidateQueries.associateBy(
            keySelector = { it.term },
            valueTransform = { candidate ->
                candidateQueries.filter { it.term == candidate.term }
            }
        )

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
}

private const val MAX_RESULTS = 100
