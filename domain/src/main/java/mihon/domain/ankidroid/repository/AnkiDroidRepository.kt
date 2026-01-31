package mihon.domain.ankidroid.repository

import mihon.domain.dictionary.model.DictionaryTermCard

interface AnkiDroidRepository {
    suspend fun addCard(card: DictionaryTermCard): Result

    /**
     * Returns map of deck ID to deck name
     */
    suspend fun getDecks(): Map<Long, String>

    /**
     * Returns map of model ID to model name
     * @param minFields minimum number of fields the model must have
     */
    suspend fun getModels(minFields: Int = 1): Map<Long, String>

    /**
     * Returns list of field names for a model
     * @param modelId the model ID to get fields for
     */
    suspend fun getModelFields(modelId: Long): List<String>

    /**
     * Check if AnkiDroid API is available (AnkiDroid installed and API enabled)
     */
    suspend fun isApiAvailable(): Boolean

    /**
     * Check if permission to access AnkiDroid is granted
     */
    suspend fun hasPermission(): Boolean

    sealed interface Result {
        data object Added : Result
        data object Duplicate : Result
        data object NotAvailable : Result
        data class Error(val throwable: Throwable? = null) : Result
    }
}
