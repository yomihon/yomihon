package mihon.domain.dictionary.service

interface DictionaryStorageGateway {
    suspend fun importDictionary(
        archivePath: String,
        dictionaryId: Long,
        dictionaryTitle: String,
    ): DictionaryStorageImportOutcome

    suspend fun validateImportedDictionary(
        storagePath: String,
        sampleExpression: String?,
    ): Boolean

    suspend fun refreshSearchSession()

    suspend fun clearDictionaryStorage(dictionaryId: Long)
}

data class DictionaryStorageImportOutcome(
    val success: Boolean,
    val storagePath: String?,
    val termCount: Long,
    val metaCount: Long,
    val mediaCount: Long,
)
