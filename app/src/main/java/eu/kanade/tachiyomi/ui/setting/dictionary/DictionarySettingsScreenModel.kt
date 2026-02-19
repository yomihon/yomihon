package eu.kanade.tachiyomi.ui.setting.dictionary

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.dictionary.DictionaryImportJob
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.model.Dictionary
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DictionarySettingsScreenModel(
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
) : StateScreenModel<DictionarySettingsScreenModel.State>(State()) {

    init {
        loadDictionaries()

        // Observe dictionary import job state
        screenModelScope.launch {
            DictionaryImportJob.isRunningFlow(Injekt.get<android.app.Application>())
                .collectLatest { isRunning ->
                    mutableState.update { it.copy(isImporting = isRunning) }
                    if (!isRunning) {
                        // Refresh dictionary list when import completes
                        loadDictionaries()
                    }
                }
        }
    }

    private fun loadDictionaries() {
        screenModelScope.launch {
            try {
                val dictionaries = dictionaryInteractor.getAllDictionaries()
                mutableState.update {
                    it.copy(
                        dictionaries = dictionaries,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load dictionaries" }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load dictionaries",
                    )
                }
            }
        }
    }

    fun importDictionaryFromUri(context: Context, uri: Uri) {
        DictionaryImportJob.start(context, uri)
    }

    fun importDictionaryFromUrl(context: Context, url: String) {
        DictionaryImportJob.start(context, url)
    }

    fun updateDictionary(context: Context, dictionary: Dictionary) {
        screenModelScope.launch {
            try {
                dictionaryInteractor.updateDictionary(dictionary)
                loadDictionaries()
                context.toast(MR.strings.dictionary_update_success.getString(context))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to update dictionary" }
                context.toast(e.message ?: MR.strings.dictionary_update_fail.getString(context))
            }
        }
    }

    fun moveDictionaryUp(dictionary: Dictionary) {
        screenModelScope.launch {
            try {
                val dictionaries = state.value.dictionaries
                val currentIndex = dictionaries.indexOfFirst { it.id == dictionary.id }

                // Can't move up if already at the top (index 0 = highest priority)
                if (currentIndex <= 0) return@launch

                val aboveDictionary = dictionaries[currentIndex - 1]
                dictionaryInteractor.swapDictionaryPriorities(dictionary, aboveDictionary)
                mutableState.update { it.copy(highlightedDictionaryId = dictionary.id) }
                loadDictionaries()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to move dictionary up" }
            }
        }
    }

    fun moveDictionaryDown(dictionary: Dictionary) {
        screenModelScope.launch {
            try {
                val dictionaries = state.value.dictionaries
                val currentIndex = dictionaries.indexOfFirst { it.id == dictionary.id }

                // Can't move down if already at the bottom
                if (currentIndex < 0 || currentIndex >= dictionaries.size - 1) return@launch

                val belowDictionary = dictionaries[currentIndex + 1]
                dictionaryInteractor.swapDictionaryPriorities(dictionary, belowDictionary)
                mutableState.update { it.copy(highlightedDictionaryId = dictionary.id) }
                loadDictionaries()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to move dictionary down" }
            }
        }
    }

    fun deleteDictionary(context: Context, dictionaryId: Long) {
        screenModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, error = null) }
            try {
                dictionaryInteractor.deleteDictionary(dictionaryId)
                loadDictionaries()
                context.toast(MR.strings.dictionary_delete_success.getString(context))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete dictionary" }
                context.toast(e.message ?: MR.strings.dictionary_delete_fail.getString(context))
            } finally {
                mutableState.update { it.copy(isDeleting = false) }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun clearHighlight() {
        mutableState.update { it.copy(highlightedDictionaryId = null) }
    }

    @Immutable
    data class State(
        val dictionaries: List<Dictionary> = emptyList(),
        val isLoading: Boolean = true,
        val isImporting: Boolean = false,
        val importProgress: String? = null,
        val importedCount: Int = 0,
        val isDeleting: Boolean = false,
        val error: String? = null,
        val highlightedDictionaryId: Long? = null,
    )
}
