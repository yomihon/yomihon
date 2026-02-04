package eu.kanade.tachiyomi.ui.setting.anki

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.ankidroid.repository.AnkiDroidRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ankidroid.service.AnkiDroidPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnkiSettingsScreenModel(
    private val ankiDroidRepository: AnkiDroidRepository = Injekt.get(),
    private val ankiDroidPreferences: AnkiDroidPreferences = Injekt.get(),
) : StateScreenModel<AnkiSettingsScreenModel.State>(State()) {

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        screenModelScope.launch {
            try {
                // Load saved preferences
                val savedDeckId = ankiDroidPreferences.deckId().get()
                val savedModelId = ankiDroidPreferences.modelId().get()
                val savedDeckName = ankiDroidPreferences.deckName().get()
                val savedModelName = ankiDroidPreferences.modelName().get()
                val savedFieldMappings = ankiDroidPreferences.fieldMappings().get()

                mutableState.update {
                    it.copy(
                        selectedDeckId = savedDeckId,
                        selectedModelId = savedModelId,
                        deckName = savedDeckName,
                        modelName = savedModelName,
                        fieldMappings = savedFieldMappings,
                    )
                }

                // Load data from AnkiDroid API
                loadAnkiData()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load initial state" }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load initial state",
                    )
                }
            }
        }
    }

    fun loadAnkiData() {
        screenModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val isApiAvailable = ankiDroidRepository.isApiAvailable()
                val hasPermission = if (isApiAvailable) ankiDroidRepository.hasPermission() else false

                if (!isApiAvailable || !hasPermission) {
                    mutableState.update {
                        it.copy(
                            isApiAvailable = isApiAvailable,
                            hasPermission = hasPermission,
                            isLoading = false,
                        )
                    }
                    return@launch
                }

                val decks = ankiDroidRepository.getDecks()
                val models = ankiDroidRepository.getModels()

                // Get current selections from state
                var selectedDeckId = state.value.selectedDeckId
                var selectedModelId = state.value.selectedModelId
                val preferredDeckName = state.value.deckName
                val preferredModelName = state.value.modelName

                // If deck ID is invalid or not in available decks, get or create the preferred deck
                if (selectedDeckId <= 0 || !decks.containsKey(selectedDeckId)) {
                    val deckId = ankiDroidRepository.getOrCreateDeck(preferredDeckName, selectedDeckId)
                    if (deckId != null) {
                        selectedDeckId = deckId
                        ankiDroidPreferences.deckId().set(deckId)
                    }
                }

                // If model ID is invalid or not in available models, get or create the preferred model
                if (selectedModelId <= 0 || !models.containsKey(selectedModelId)) {
                    val modelId = ankiDroidRepository.getOrCreateModel(preferredModelName, selectedDeckId, selectedModelId)
                    if (modelId != null) {
                        selectedModelId = modelId
                        ankiDroidPreferences.modelId().set(modelId)
                    }
                }

                // Reload decks/models in case new ones were created
                val updatedDecks = ankiDroidRepository.getDecks()
                val updatedModels = ankiDroidRepository.getModels()

                // Load model fields if a model is selected
                val modelFields = if (selectedModelId > 0) {
                    ankiDroidRepository.getModelFields(selectedModelId)
                } else {
                    emptyList()
                }

                mutableState.update {
                    it.copy(
                        isApiAvailable = true,
                        hasPermission = true,
                        decks = updatedDecks,
                        models = updatedModels,
                        modelFields = modelFields,
                        selectedDeckId = selectedDeckId,
                        selectedModelId = selectedModelId,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load AnkiDroid data" }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load AnkiDroid data",
                    )
                }
            }
        }
    }

    fun selectDeck(deckId: Long) {
        mutableState.update { it.copy(selectedDeckId = deckId) }
        ankiDroidPreferences.deckId().set(deckId)
    }

    fun selectModel(modelId: Long) {
        screenModelScope.launch {
            mutableState.update { it.copy(selectedModelId = modelId, isLoading = true) }
            ankiDroidPreferences.modelId().set(modelId)
            try {
                val modelFields = if (modelId > 0) {
                    ankiDroidRepository.getModelFields(modelId)
                } else {
                    emptyList()
                }
                mutableState.update {
                    it.copy(
                        modelFields = modelFields,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load model fields" }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load model fields",
                    )
                }
            }
        }
    }

    fun updateDeckName(name: String) {
        mutableState.update { it.copy(deckName = name) }
        ankiDroidPreferences.deckName().set(name)
    }

    fun updateFieldMapping(ankiField: String, appVariable: String) {
        mutableState.update { currentState ->
            val updatedMappings = currentState.fieldMappings.toMutableMap()
            updatedMappings[ankiField] = appVariable
            ankiDroidPreferences.fieldMappings().set(updatedMappings)
            currentState.copy(fieldMappings = updatedMappings)
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    @Immutable
    data class State(
        val isApiAvailable: Boolean = false,
        val hasPermission: Boolean = false,
        val decks: Map<Long, String> = emptyMap(),
        val models: Map<Long, String> = emptyMap(),
        val modelFields: List<String> = emptyList(),
        val selectedDeckId: Long = -1L,
        val selectedModelId: Long = -1L,
        val deckName: String = "Yomihon",
        val modelName: String = "Yomihon Card",
        val fieldMappings: Map<String, String> = emptyMap(),
        val isLoading: Boolean = true,
        val error: String? = null,
    )

    companion object {
        val APP_FIELDS = listOf(
            "reading",
            "expression",
            "glossary",
            "sentence",
            "pitchAccent",
            "frequency",
            "picture",
        )
    }
}
