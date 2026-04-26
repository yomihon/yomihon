package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryMigrationStage
import mihon.domain.dictionary.model.DictionaryMigrationState
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.repository.DictionaryLegacyRepository
import mihon.domain.dictionary.repository.DictionaryMigrationStatusRepository
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.DictionaryArchiveBuilder
import mihon.domain.dictionary.service.DictionaryStorageGateway
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class DictionaryMigrationJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val dictionaryRepository: DictionaryRepository = Injekt.get()
    private val dictionaryLegacyRepository: DictionaryLegacyRepository = Injekt.get()
    private val migrationStatusRepository: DictionaryMigrationStatusRepository = Injekt.get()
    private val archiveBuilder: DictionaryArchiveBuilder = Injekt.get()
    private val dictionaryStorageGateway: DictionaryStorageGateway = Injekt.get()
    private val notifier = DictionaryMigrationNotifier(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DICTIONARY_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.dictionary_migration_in_progress))
            setAutoCancel(false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()

        return ForegroundInfo(
            Notifications.ID_DICTIONARY_MIGRATION_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()

        return try {
            val (dictionaries, statusesById) = loadPendingDictionariesWithStatuses()
            if (dictionaries.isEmpty()) {
                return Result.success()
            }

            var completed = dictionaries.count { statusesById[it.id]?.state == DictionaryMigrationState.COMPLETE }

            dictionaries.forEach { dictionary ->
                currentCoroutineContext().ensureActive()

                val existingStatus = statusesById[dictionary.id]
                if (existingStatus?.state == DictionaryMigrationState.COMPLETE) {
                    return@forEach
                }

                val currentDictionary = dictionaryRepository.getDictionary(dictionary.id) ?: return@forEach
                try {
                    migrateDictionary(
                        dictionary = currentDictionary,
                        completed = completed,
                        total = dictionaries.size,
                    )
                    completed += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateStatus(
                        dictionary = currentDictionary,
                        state = DictionaryMigrationState.ERROR,
                        stage = DictionaryMigrationStage.ERROR,
                        completed = completed,
                        total = dictionaries.size,
                        progressText = e.message ?: stageDisplayName(DictionaryMigrationStage.ERROR),
                        error = e.message ?: "Migration failed",
                    )
                    throw e
                }
            }

            notifier.showMigrationCompleteNotification()
            Result.success()
        } catch (e: CancellationException) {
            logcat(LogPriority.INFO) { "Dictionary migration cancelled" }
            Result.failure()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Dictionary migration failed" }
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_DICTIONARY_MIGRATION_PROGRESS)
        }
    }

    private suspend fun loadPendingDictionariesWithStatuses():
        Pair<List<Dictionary>, Map<Long, DictionaryMigrationStatus>> {
        val statusesByDictionaryId = migrationStatusRepository.getAllMigrationStatuses()
            .associateBy { it.dictionaryId }

        val dictionaries = dictionaryRepository.getAllDictionaries()
            .sortedWith(compareBy<Dictionary> { it.priority }.thenBy { it.title })

        val pending = dictionaries.filter { dictionary ->
            val status = statusesByDictionaryId[dictionary.id]
            when {
                dictionary.backend == DictionaryBackend.LEGACY_DB ->
                    status?.state != DictionaryMigrationState.ERROR
                status == null -> false
                else ->
                    status.state != DictionaryMigrationState.COMPLETE &&
                        status.state != DictionaryMigrationState.ERROR
            }
        }

        return pending to statusesByDictionaryId
    }

    private suspend fun migrateDictionary(
        dictionary: Dictionary,
        completed: Int,
        total: Int,
    ) {
        val resumeAction = DictionaryMigrationRecovery.resumeAction(
            dictionary = dictionary,
            counts = dictionaryLegacyRepository.getLegacyRowCounts(dictionary.id),
        )
        when (resumeAction) {
            DictionaryMigrationResumeAction.MARK_COMPLETE -> {
                completeDictionary(dictionary, completed + 1, total)
                return
            }
            DictionaryMigrationResumeAction.CLEANUP_LEGACY_ROWS -> {
                updateStatus(
                    dictionary = dictionary,
                    state = DictionaryMigrationState.RUNNING,
                    stage = DictionaryMigrationStage.CLEANING_UP,
                    completed = completed,
                    total = total,
                )
                deleteLegacyRows(dictionary.id)
                completeDictionary(dictionary, completed + 1, total)
                return
            }
            DictionaryMigrationResumeAction.MIGRATE_FROM_LEGACY -> Unit
        }

        val tempDir = File(context.cacheDir, "dictionary-migration/${dictionary.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        dictionaryStorageGateway.clearDictionaryStorage(dictionary.id)

        try {
            val archiveFile = File(tempDir, "reconstructed.zip")
            updateStatus(
                dictionary = dictionary,
                state = DictionaryMigrationState.RUNNING,
                stage = DictionaryMigrationStage.BUILDING_ARCHIVE,
                completed = completed,
                total = total,
            )

            val archive = archiveBuilder.buildArchive(
                dictionary = dictionary,
                destinationPath = archiveFile.absolutePath,
            ) { progress ->
                updateStatus(
                    dictionary = dictionary,
                    state = DictionaryMigrationState.RUNNING,
                    stage = DictionaryMigrationStage.BUILDING_ARCHIVE,
                    completed = completed,
                    total = total,
                    progressText = progress.message,
                )
            }

            updateStatus(
                dictionary = dictionary,
                state = DictionaryMigrationState.RUNNING,
                stage = DictionaryMigrationStage.IMPORTING,
                completed = completed,
                total = total,
            )

            val importOutcome = dictionaryStorageGateway.importDictionary(
                archivePath = archive.archivePath,
                dictionaryId = dictionary.id,
                dictionaryTitle = dictionary.title,
            )
            val storagePath = importOutcome.storagePath
                ?: throw IllegalStateException("Imported dictionary has no storage path")
            if (!importOutcome.success) {
                throw IllegalStateException("Failed to import migrated dictionary ${dictionary.title}")
            }

            updateStatus(
                dictionary = dictionary,
                state = DictionaryMigrationState.RUNNING,
                stage = DictionaryMigrationStage.VALIDATING,
                completed = completed,
                total = total,
            )

            val isValid = dictionaryStorageGateway.validateImportedDictionary(
                storagePath = storagePath,
                sampleExpression = archive.sampleExpression,
            )
            if (!isValid) {
                throw IllegalStateException("Validation failed for ${dictionary.title}")
            }

            dictionaryRepository.updateDictionaryStorage(
                dictionaryId = dictionary.id,
                backend = DictionaryBackend.HOSHI,
                storagePath = storagePath,
                storageReady = true,
            )

            updateStatus(
                dictionary = dictionary,
                state = DictionaryMigrationState.RUNNING,
                stage = DictionaryMigrationStage.REBUILDING_SESSION,
                completed = completed,
                total = total,
            )
            dictionaryStorageGateway.refreshSearchSession()

            updateStatus(
                dictionary = dictionary,
                state = DictionaryMigrationState.RUNNING,
                stage = DictionaryMigrationStage.CLEANING_UP,
                completed = completed,
                total = total,
            )
            deleteLegacyRows(dictionary.id)
            completeDictionary(dictionary, completed + 1, total)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun deleteLegacyRows(dictionaryId: Long) {
        withContext(Dispatchers.IO) {
            dictionaryLegacyRepository.deleteKanjiMetaForDictionary(dictionaryId)
            dictionaryLegacyRepository.deleteKanjiForDictionary(dictionaryId)
            dictionaryLegacyRepository.deleteTermMetaForDictionary(dictionaryId)
            dictionaryLegacyRepository.deleteTermsForDictionary(dictionaryId)
            dictionaryLegacyRepository.deleteTagsForDictionary(dictionaryId)
        }
    }

    private suspend fun completeDictionary(dictionary: Dictionary, completed: Int, total: Int) {
        updateStatus(
            dictionary = dictionary,
            state = DictionaryMigrationState.COMPLETE,
            stage = DictionaryMigrationStage.COMPLETE,
            completed = completed,
            total = total,
        )
        migrationStatusRepository.deleteMigrationStatus(dictionary.id)
    }

    private suspend fun updateStatus(
        dictionary: Dictionary,
        state: DictionaryMigrationState,
        stage: DictionaryMigrationStage,
        completed: Int,
        total: Int,
        progressText: String? = null,
        error: String? = null,
    ) {
        val stageText = progressText ?: stageDisplayName(stage)
        migrationStatusRepository.upsertMigrationStatus(
            DictionaryMigrationStatus(
                dictionaryId = dictionary.id,
                state = state,
                stage = stage,
                progressText = stageText,
                completedDictionaries = completed,
                totalDictionaries = total,
                lastError = error,
            ),
        )
        notifier.showMigrationProgressNotification(
            dictionaryTitle = dictionary.title,
            stage = stageText,
            completed = completed,
            total = total,
        )
    }

    private fun stageDisplayName(stage: DictionaryMigrationStage): String {
        return when (stage) {
            DictionaryMigrationStage.QUEUED -> context.stringResource(MR.strings.dictionary_migration_stage_queued)
            DictionaryMigrationStage.BUILDING_ARCHIVE -> context.stringResource(
                MR.strings.dictionary_migration_stage_building_archive,
            )
            DictionaryMigrationStage.IMPORTING -> context.stringResource(
                MR.strings.dictionary_migration_stage_importing,
            )
            DictionaryMigrationStage.VALIDATING -> context.stringResource(
                MR.strings.dictionary_migration_stage_validating,
            )
            DictionaryMigrationStage.REBUILDING_SESSION -> context.stringResource(
                MR.strings.dictionary_migration_stage_rebuilding_session,
            )
            DictionaryMigrationStage.CLEANING_UP -> context.stringResource(
                MR.strings.dictionary_migration_stage_cleaning_up,
            )
            DictionaryMigrationStage.COMPLETE -> context.stringResource(MR.strings.dictionary_migration_stage_complete)
            DictionaryMigrationStage.ERROR -> context.stringResource(MR.strings.dictionary_migration_stage_error)
        }
    }

    companion object {
        private const val TAG = "DictionaryMigration"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DictionaryMigrationJob>()
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(
                DictionaryWorkNames.IMPORT_AND_MIGRATION,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        suspend fun isScheduledOrRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
            context.workManager
                .getWorkInfosByTag(TAG)
                .get()
                .any {
                    it.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        it.state == androidx.work.WorkInfo.State.RUNNING ||
                        it.state == androidx.work.WorkInfo.State.BLOCKED
                }
        }
    }
}
