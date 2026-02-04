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
import tachiyomi.domain.ankidroid.service.AnkiDroidPreferences

class AnkiDroidRepositoryImpl(
    context: Context,
    private val ankiDroidPreferences: AnkiDroidPreferences,
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
            // Get deck and model from preferences
            val preferredDeckId = ankiDroidPreferences.deckId().get()
            val preferredDeckName = ankiDroidPreferences.deckName().get()
            val preferredModelId = ankiDroidPreferences.modelId().get()
            val preferredModelName = ankiDroidPreferences.modelName().get()

            val deckId = getOrCreateDeck(name = preferredDeckName, id = preferredDeckId)
                ?: return@withContext AnkiDroidRepository.Result.Error()

            val modelId = getOrCreateModel(name = preferredModelName, deckId = deckId, id = preferredModelId)
                ?: return@withContext AnkiDroidRepository.Result.Error()

            // Get model fields and build content array based on field mappings
            val modelFields = api.getFieldList(modelId)?.toList() ?: emptyList()
            if (modelFields.isEmpty()) {
                return@withContext AnkiDroidRepository.Result.Error()
            }

            val fieldMappings = ankiDroidPreferences.fieldMappings().get()
            val fieldValues = buildFieldValues(card, modelFields, fieldMappings)

            // Check for duplicates using the first field value
            if (fieldValues.isNotEmpty() && fieldValues[0].isNotBlank()) {
                val duplicates = api.findDuplicateNotes(modelId, listOf(fieldValues[0]))
                if (duplicates != null && duplicates.isNotEmpty()) {
                    return@withContext AnkiDroidRepository.Result.Duplicate
                }
            }

            val added = api.addNote(modelId, deckId, fieldValues, card.tags)

            if (added > 0) AnkiDroidRepository.Result.Added else AnkiDroidRepository.Result.Error()
        } catch (e: Exception) {
            AnkiDroidRepository.Result.Error(e)
        }
    }


    /**
     * Build an array of field values based on model fields and field mappings.
     */
    private fun buildFieldValues(
        card: DictionaryTermCard,
        modelFields: List<String>,
        fieldMappings: Map<String, String>,
    ): Array<String> {
        return modelFields.map { noteField ->
            val appField = fieldMappings[noteField]
            if (appField != null) {
                card.getFieldValue(appField)
            } else {
                ""
            }
        }.toTypedArray()
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

    override suspend fun getOrCreateDeck(name: String, id: Long): Long? = withContext(Dispatchers.IO) {
        try {
            if (id > 0) {
                val decks = api.deckList ?: emptyMap()
                if (decks.containsKey(id)) {
                    cachedDeckId = id
                    return@withContext id
                }
            }

            val decks = api.deckList ?: emptyMap()
            val existingDeck = decks.entries.firstOrNull { it.value == name }
            if (existingDeck != null) {
                cachedDeckId = existingDeck.key
                return@withContext existingDeck.key
            }

            val newDeckId = api.addNewDeck(name)
            if (newDeckId > 0) {
                cachedDeckId = newDeckId
                newDeckId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getOrCreateModel(name: String, deckId: Long, id: Long): Long? = withContext(Dispatchers.IO) {
        try {
            if (id > 0) {
                val models = api.modelList ?: emptyMap()
                if (models.containsKey(id)) {
                    cachedModelId = id
                    return@withContext id
                }
            }

            val models = api.modelList ?: emptyMap()
            val existingModel = models.entries.firstOrNull { it.value == name }
            if (existingModel != null) {
                cachedModelId = existingModel.key
                return@withContext existingModel.key
            }

            val newModelId = api.addNewCustomModel(
                name,
                YOMIHON_FIELDS,
                YOMIHON_CARD_NAMES,
                YOMIHON_QFMT,
                YOMIHON_AFMT,
                YOMIHON_CSS,
                deckId,
                null,
            )
            if (newModelId > 0) {
                cachedModelId = newModelId
                newModelId
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Yomihon Card default model definition
        private val YOMIHON_FIELDS = arrayOf(
            "Expression",
            "Reading",
            "Glossary",
            "Sentence",
            "Pitch Accent",
            "Frequency",
            "Picture",
        )

        private val YOMIHON_CARD_NAMES = arrayOf("Yomihon Card")

        private val YOMIHON_QFMT = arrayOf(
            """
            <div class="expression">{{Expression}}</div>
            {{#Reading}}<div class="reading">{{Reading}}</div>{{/Reading}}
            {{#Picture}}<div class="picture">{{Picture}}</div>{{/Picture}}
            """.trimIndent(),
        )

        private val YOMIHON_AFMT = arrayOf(
            """
            <div class="expression">{{Expression}}</div>
            {{#Reading}}<div class="reading">{{Reading}}</div>{{/Reading}}
            <hr>
            <div class="glossary">{{Glossary}}</div>
            {{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
            {{#Pitch Accent}}<div class="pitch">{{Pitch Accent}}</div>{{/Pitch Accent}}
            {{#Frequency}}<div class="frequency">{{Frequency}}</div>{{/Frequency}}
            {{#Picture}}<div class="picture">{{Picture}}</div>{{/Picture}}
            """.trimIndent(),
        )

        private const val YOMIHON_CSS = """
.card {
    font-family: "Hiragino Kaku Gothic Pro", "Meiryo", sans-serif;
    font-size: 20px;
    text-align: center;
    color: #1a1a1a;
    background-color: #fffaf0;
}
.expression {
    font-size: 48px;
    font-weight: bold;
    margin-bottom: 10px;
}
.reading {
    font-size: 24px;
    color: #666;
    margin-bottom: 10px;
}
.glossary {
    font-size: 20px;
    text-align: left;
    margin: 15px;
    white-space: pre-wrap;
}
.sentence {
    font-size: 18px;
    color: #444;
    margin: 10px;
    font-style: italic;
}
.pitch {
    font-size: 16px;
    color: #888;
    margin: 5px;
}
.frequency {
    font-size: 14px;
    color: #aaa;
    margin: 5px;
}
.picture img {
    max-width: 300px;
    max-height: 300px;
}
"""
    }
}
