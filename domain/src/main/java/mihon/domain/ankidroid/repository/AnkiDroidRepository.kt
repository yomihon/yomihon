package mihon.domain.ankidroid.repository

import mihon.domain.dictionary.model.DictionaryTermCard

interface AnkiDroidRepository {
    suspend fun addCard(card: DictionaryTermCard): Result

    /**
     * Bulk check which of the given expressions already have notes in AnkiDroid.
     * Returns a set of expressions (first-field values) that have matching notes.
     * Returns an empty set if AnkiDroid is unavailable or permission is missing.
     */
    suspend fun findExistingNotes(expressions: List<String>): Set<String>

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

    /**
     * Get or create a deck by name, checking an optional ID first for robustness
     * @param name the deck name
     * @param id the optional deck ID to verify existence first
     * @return the deck ID, or null if creation failed
     */
    suspend fun getOrCreateDeck(name: String, id: Long = -1L): Long?

    /**
     * Get or create the Yomihon Card model, checking an optional ID first
     * @param name the model name
     * @param deckId the deck ID to associate with the model
     * @param id the optional model ID to verify existence first
     * @return the model ID, or null if creation failed
     */
    suspend fun getOrCreateModel(name: String, deckId: Long, id: Long = -1L): Long?

    sealed interface Result {
        data object Added : Result
        data object Duplicate : Result
        data object NotAvailable : Result
        data class Error(val throwable: Throwable? = null) : Result
    }
}
