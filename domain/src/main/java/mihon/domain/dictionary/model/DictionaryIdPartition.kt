package mihon.domain.dictionary.model

data class DictionaryIdPartition(
    val legacyIds: List<Long>,
    val hoshiIds: List<Long>,
)

fun partitionDictionaryIdsByBackend(
    dictionaryIds: List<Long>,
    dictionariesById: Map<Long, Dictionary>,
): DictionaryIdPartition {
    val legacyIds = mutableListOf<Long>()
    val hoshiIds = mutableListOf<Long>()

    dictionaryIds.forEach { id ->
        val dictionary = dictionariesById[id]
        if (dictionary?.backend == DictionaryBackend.HOSHI && dictionary.storageReady) {
            hoshiIds += id
        } else {
            legacyIds += id
        }
    }

    return DictionaryIdPartition(
        legacyIds = legacyIds,
        hoshiIds = hoshiIds,
    )
}
