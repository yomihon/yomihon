package mihon.data.dictionary

import mihon.domain.dictionary.model.DictionaryIdPartition
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.partitionDictionaryIdsByBackend
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryLookupMatch
import mihon.domain.dictionary.service.DictionarySearchBackend
import mihon.domain.dictionary.service.DictionarySearchEntry
import mihon.domain.dictionary.service.DictionarySearchGateway

class DictionarySearchGatewayImpl(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionarySearchBackend: DictionarySearchBackend,
) : DictionarySearchGateway {

    override suspend fun exactSearch(
        expression: String,
        dictionaryIds: List<Long>,
    ): List<DictionarySearchEntry> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val partition = partitionDictionaryIds(dictionaryIds)
        return buildList {
            if (partition.legacyIds.isNotEmpty()) {
                addAll(
                    dictionaryRepository.searchTerms(expression, partition.legacyIds)
                        .map { DictionarySearchEntry(it, emptyList()) },
                )
            }
            if (partition.hoshiIds.isNotEmpty()) {
                addAll(dictionarySearchBackend.exactSearch(expression, partition.hoshiIds))
            }
        }
    }

    override suspend fun lookup(
        text: String,
        dictionaryIds: List<Long>,
        maxResults: Int,
    ): List<DictionaryLookupMatch> {
        if (dictionaryIds.isEmpty()) return emptyList()

        val hoshiIds = partitionDictionaryIds(dictionaryIds).hoshiIds
        if (hoshiIds.isEmpty()) return emptyList()

        return dictionarySearchBackend.lookup(text, hoshiIds, maxResults)
    }

    override suspend fun getTermMeta(
        expressions: List<String>,
        dictionaryIds: List<Long>,
    ): Map<String, List<DictionaryTermMeta>> {
        if (expressions.isEmpty()) return emptyMap()
        if (dictionaryIds.isEmpty()) {
            return expressions.associateWith { emptyList() }
        }

        val partition = partitionDictionaryIds(dictionaryIds)
        val requestedIds = dictionaryIds.toSet()

        return expressions.associateWith { expression ->
            buildList {
                if (partition.legacyIds.isNotEmpty()) {
                    addAll(dictionaryRepository.getTermMetaForExpression(expression, partition.legacyIds))
                }
                if (partition.hoshiIds.isNotEmpty()) {
                    addAll(
                        dictionarySearchBackend.getTermMeta(
                            listOf(expression),
                            partition.hoshiIds,
                        )[expression].orEmpty()
                            .filter { it.dictionaryId in requestedIds },
                    )
                }
            }
        }
    }

    private suspend fun partitionDictionaryIds(dictionaryIds: List<Long>): DictionaryIdPartition {
        val dictionariesById = dictionaryRepository.getAllDictionaries().associateBy { it.id }
        return partitionDictionaryIdsByBackend(dictionaryIds, dictionariesById)
    }
}
