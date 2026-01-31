package mihon.data.ankidroid

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.util.isNotEmpty
import com.ichi2.anki.api.AddContentApi
import com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ankidroid.repository.AnkiDroidRepository
import mihon.domain.dictionary.model.DictionaryTermCard

class AnkiDroidRepositoryImpl(
    private val context: Context,
) : AnkiDroidRepository {

    private val appContext = context.applicationContext
    private val api by lazy { AddContentApi(appContext) }

    // Cache IDs to prevent repetitive DB queries
    private var cachedDeckId: Long? = null
    private var cachedModelId: Long? = null

    override suspend fun addCard(card: DictionaryTermCard): AnkiDroidRepository.Result = withContext(Dispatchers.IO) {
        // Check for AnkiDroid installation and card permissions, since they're needed for the API
        if (AddContentApi.getAnkiDroidPackageName(appContext) == null ||
            ContextCompat.checkSelfPermission(appContext, READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext AnkiDroidRepository.Result.NotAvailable
        }

        try {
            val deckId = cachedDeckId ?: getOrOnboardDeck(DECK_NAME)?.also { cachedDeckId = it }
            ?: return@withContext AnkiDroidRepository.Result.Error()

            val modelId = cachedModelId ?: getOrOnboardModel(MODEL_NAME)?.also { cachedModelId = it }
            ?: return@withContext AnkiDroidRepository.Result.Error()

            val duplicates = api.findDuplicateNotes(modelId, listOf(card.front))
            if (duplicates != null && duplicates.isNotEmpty()) {
                return@withContext AnkiDroidRepository.Result.Duplicate
            }

            val added = api.addNote(modelId, deckId, arrayOf(card.front, card.back), null)

            if (added > 0) AnkiDroidRepository.Result.Added else AnkiDroidRepository.Result.Error()
        } catch (e: Exception) {
            AnkiDroidRepository.Result.Error(e)
        }
    }

    private fun getOrOnboardDeck(name: String): Long? {
        val decks = api.deckList
        return decks?.entries?.firstOrNull { it.value == name }?.key
            ?: api.addNewDeck(name).takeIf { it > 0L }
    }

    private fun getOrOnboardModel(name: String): Long? {
        val models = api.getModelList(MIN_FIELDS)
        return models?.entries?.firstOrNull { it.value == name }?.key
            ?: api.addNewBasicModel(name).takeIf { it > 0L }
    }

    override suspend fun getDecks(): Map<Long, String> = withContext(Dispatchers.IO) {
        api.deckList ?: emptyMap()
    }

    override suspend fun getModels(minFields: Int): Map<Long, String> = withContext(Dispatchers.IO) {
        api.getModelList(minFields) ?: emptyMap()
    }

    override suspend fun getModelFields(modelId: Long): List<String> = withContext(Dispatchers.IO) {
        api.getFieldList(modelId)?.toList() ?: emptyList()
    }

    override suspend fun isApiAvailable(): Boolean = withContext(Dispatchers.IO) {
        AddContentApi.getAnkiDroidPackageName(appContext) != null
    }

    override suspend fun hasPermission(): Boolean = withContext(Dispatchers.IO) {
        ContextCompat.checkSelfPermission(appContext, READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val DECK_NAME = "Yomihon"
        private const val MODEL_NAME = "app.yomihon.dictionary"
        private const val MIN_FIELDS = 2
    }
}
