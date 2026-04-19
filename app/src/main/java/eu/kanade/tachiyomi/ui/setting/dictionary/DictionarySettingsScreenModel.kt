package eu.kanade.tachiyomi.ui.setting.dictionary

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.dictionary.DictionaryImportJob
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.repository.DictionaryMigrationStatusRepository
import mihon.domain.dictionary.repository.DictionaryRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class DictionarySettingsScreenModel(
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
    private val dictionaryRepository: DictionaryRepository = Injekt.get(),
    private val dictionaryMigrationStatusRepository: DictionaryMigrationStatusRepository = Injekt.get(),
    private val context: Application = Injekt.get(),
) : StateScreenModel<DictionarySettingsScreenModel.State>(State()) {

    private var shouldShowImportCompletionToast = false
    private var hasObservedPendingImportRunning = false

    init {
        observeDictionaries()
        observeMigrationStatuses()

        screenModelScope.launch {
            DictionaryImportJob.isRunningFlow(context)
                .collectLatest { isRunning ->
                    if (shouldShowImportCompletionToast) {
                        if (isRunning) {
                            hasObservedPendingImportRunning = true
                        } else if (hasObservedPendingImportRunning) {
                            context.toast(MR.strings.dictionary_import_success.getString(context))
                            shouldShowImportCompletionToast = false
                            hasObservedPendingImportRunning = false
                        }
                    }

                    mutableState.update {
                        val newState = it.copy(isImporting = isRunning)
                        if (!isRunning && it.batchTotal > 0) {
                            newState.copy(batchTotal = 0, batchCompleted = 0)
                        } else {
                            newState
                        }
                    }
                }
        }
    }

    private fun observeDictionaries() {
        screenModelScope.launch {
            dictionaryRepository.subscribeToDictionaries().collectLatest { dictionaries ->
                mutableState.update {
                    val newCompleted = if (it.batchTotal > 0 && dictionaries.size > it.dictionaries.size) {
                        (it.batchCompleted + (dictionaries.size - it.dictionaries.size))
                            .coerceAtMost(it.batchTotal)
                    } else {
                        it.batchCompleted
                    }
                    it.copy(
                        dictionaries = dictionaries,
                        isLoading = false,
                        batchCompleted = newCompleted,
                    )
                }
            }
        }
    }

    private fun observeMigrationStatuses() {
        screenModelScope.launch {
            dictionaryMigrationStatusRepository.subscribeToMigrationStatuses().collectLatest { statuses ->
                val activeStatuses = statuses.filter { it.state != DictionaryMigrationState.COMPLETE }
                val currentStatus = activeStatuses.firstOrNull()
                mutableState.update {
                    it.copy(
                        migrationStatuses = activeStatuses,
                        isMigrating = activeStatuses.isNotEmpty(),
                        currentMigrationStatus = currentStatus,
                    )
                }
            }
        }
    }

    fun importDictionaryFromUri(uri: Uri) {
        shouldShowImportCompletionToast = true
        hasObservedPendingImportRunning = state.value.isImporting
        DictionaryImportJob.start(context, uri)
    }

    fun importDictionaryFromUrl(url: String) {
        shouldShowImportCompletionToast = true
        hasObservedPendingImportRunning = state.value.isImporting
        DictionaryImportJob.start(context, url)
    }

    fun importDictionariesFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        shouldShowImportCompletionToast = true
        hasObservedPendingImportRunning = state.value.isImporting
        mutableState.update { it.copy(batchTotal = uris.size, batchCompleted = 0) }
        DictionaryImportJob.startBatch(context, uris)
    }

    fun importDictionariesFromFolder(treeUri: Uri) {
        screenModelScope.launch {
            try {
                val zipUris = enumerateZipFilesInTree(treeUri)
                if (zipUris.isEmpty()) {
                    context.toast(MR.strings.no_zip_files_found.getString(context))
                    return@launch
                }
                importDictionariesFromUris(zipUris)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to read folder" }
                context.toast(MR.strings.no_zip_files_found.getString(context))
            }
        }
    }

    private suspend fun enumerateZipFilesInTree(treeUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val zipUris = mutableListOf<Uri>()

        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol)
                if (mime == "application/zip" || mime == "application/x-zip-compressed") {
                    val childDocId = cursor.getString(idCol)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    zipUris.add(childUri)
                }
            }
        }

        zipUris
    }

    fun updateDictionary(context: Context, dictionary: Dictionary) {
        screenModelScope.launch {
            try {
                dictionaryInteractor.updateDictionary(dictionary)
                context.toast(MR.strings.dictionary_update_success.getString(context))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to update dictionary" }
                context.toast(MR.strings.dictionary_update_fail.getString(context))
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
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to move dictionary down" }
            }
        }
    }

    fun autoSortDictionaries(context: Context) {
        screenModelScope.launch {
            try {
                dictionaryInteractor.autoSortDictionaries()
                context.toast(MR.strings.dictionary_auto_sort_success.getString(context))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to auto-sort dictionaries" }
                context.toast(MR.strings.dictionary_auto_sort_fail.getString(context))
            }
        }
    }

    fun deleteDictionary(context: Context, dictionaryId: Long) {
        screenModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, error = null) }
            try {
                dictionaryInteractor.deleteDictionary(dictionaryId)
                context.toast(MR.strings.dictionary_delete_success.getString(context))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete dictionary" }
                context.toast(MR.strings.dictionary_delete_fail.getString(context))
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
        val isMigrating: Boolean = false,
        val migrationStatuses: List<DictionaryMigrationStatus> = emptyList(),
        val currentMigrationStatus: DictionaryMigrationStatus? = null,
        val isDeleting: Boolean = false,
        val error: String? = null,
        val highlightedDictionaryId: Long? = null,
        val batchTotal: Int = 0,
        val batchCompleted: Int = 0,
    )
}
