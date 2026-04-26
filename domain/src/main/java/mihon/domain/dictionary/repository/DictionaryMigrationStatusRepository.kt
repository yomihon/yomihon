package mihon.domain.dictionary.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.DictionaryMigrationStatus

interface DictionaryMigrationStatusRepository {
    suspend fun upsertMigrationStatus(status: DictionaryMigrationStatus)
    suspend fun getAllMigrationStatuses(): List<DictionaryMigrationStatus>
    fun subscribeToMigrationStatuses(): Flow<List<DictionaryMigrationStatus>>
    suspend fun deleteMigrationStatus(dictionaryId: Long)
}
