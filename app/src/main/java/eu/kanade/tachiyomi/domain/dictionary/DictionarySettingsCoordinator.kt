package eu.kanade.tachiyomi.domain.dictionary

import android.app.Application
import eu.kanade.tachiyomi.data.dictionary.DictionaryImportJob
import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.repository.DictionaryMigrationStatusRepository
import mihon.domain.dictionary.repository.DictionaryRepository

sealed interface DictionaryImportRequest {
    data class LocalArchive(val uriString: String) : DictionaryImportRequest

    data class RemoteUrl(val url: String) : DictionaryImportRequest
}

interface DictionarySettingsCoordinator {
    fun observeDictionaries(): Flow<List<Dictionary>>

    fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>>

    fun isRunningFlow(): Flow<Boolean>

    fun startImport(request: DictionaryImportRequest)
}

internal class DictionarySettingsCoordinatorImpl(
    private val application: Application,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryMigrationStatusRepository: DictionaryMigrationStatusRepository,
) : DictionarySettingsCoordinator {

    override fun observeDictionaries(): Flow<List<Dictionary>> {
        return dictionaryRepository.subscribeToDictionaries()
    }

    override fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>> {
        return dictionaryMigrationStatusRepository.subscribeToMigrationStatuses()
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return DictionaryImportJob.isRunningFlow(application)
    }

    override fun startImport(request: DictionaryImportRequest) {
        when (request) {
            is DictionaryImportRequest.LocalArchive -> DictionaryImportJob.startFromUriString(application, request.uriString)
            is DictionaryImportRequest.RemoteUrl -> DictionaryImportJob.start(application, request.url)
        }
    }
}
