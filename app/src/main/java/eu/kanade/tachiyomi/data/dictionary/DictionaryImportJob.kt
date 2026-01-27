package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.DictionaryImportNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.TrustedFileDownloader
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.ImportDictionary
import mihon.domain.dictionary.model.DictionaryImportException
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Worker for importing dictionary files in the background.
 * Supports importing from local file URIs or remote URLs.
 */
class DictionaryImportJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val dictionaryInteractor: DictionaryInteractor = Injekt.get()
    private val importDictionary: ImportDictionary = Injekt.get()
    private val dictionaryParser: DictionaryParser = Injekt.get()
    private val networkHelper: NetworkHelper = Injekt.get()

    private val notifier = DictionaryImportNotifier(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DICTIONARY_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.importing_dictionary))
            setAutoCancel(false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()

        return ForegroundInfo(
            Notifications.ID_DICTIONARY_IMPORT_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)
        val urlString = inputData.getString(KEY_URL)

        if (uriString == null && urlString == null) {
            return Result.failure(
                workDataOf(KEY_PROGRESS_ERROR to "No URI or URL provided"),
            )
        }

        setForegroundSafely()

        var tempFile: File? = null
        var dictionaryTitle: String? = null

        return try {
            withContext(Dispatchers.IO) {
                if (urlString != null) {
                    // URL download mode
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_STATE to STATE_DOWNLOADING,
                        ),
                    )
                    notifier.showDownloadingNotification(null)

                    val downloadsDir = File(context.cacheDir, "dictionary_downloads").apply { mkdirs() }
                    val destination = File(downloadsDir, "dictionary_${System.currentTimeMillis()}.zip")
                    tempFile = destination

                    val downloader = TrustedFileDownloader(
                        client = networkHelper.nonCloudflareClient,
                        allowedHosts = TRUSTED_DICTIONARY_HOSTS,
                        maxBytes = MAX_DICTIONARY_DOWNLOAD_BYTES,
                    )

                    downloader.downloadZipToFile(url = urlString, destination = destination) { downloaded, total ->
                        val progress = if (total != null && total > 0L) {
                            ((downloaded * 100f) / total.toFloat()).toInt().coerceIn(0, 100)
                        } else {
                            null
                        }
                        notifier.showDownloadingNotification(progress)
                    }

                    ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        ArchiveReader(pfd).use { reader ->
                            dictionaryTitle = extractAndImportDictionary(reader)
                        }
                    }
                } else {
                    // Local URI mode
                    val uri = uriString!!.toUri()
                    val file = UniFile.fromUri(context, uri)
                        ?: throw DictionaryImportException("Failed to open dictionary file")

                    if (!file.exists() || !file.isFile) {
                        throw DictionaryImportException("Invalid dictionary file")
                    }

                    setProgress(
                        workDataOf(
                            KEY_PROGRESS_STATE to STATE_PARSING,
                        ),
                    )

                    file.archiveReader(context).use { reader ->
                        dictionaryTitle = extractAndImportDictionary(reader)
                    }
                }
            }

            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_COMPLETE,
                    KEY_PROGRESS_DICTIONARY_TITLE to dictionaryTitle,
                ),
            )
            notifier.showCompleteNotification(dictionaryTitle ?: "Dictionary")
            Result.success(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_COMPLETE,
                    KEY_PROGRESS_DICTIONARY_TITLE to dictionaryTitle,
                ),
            )
        } catch (e: CancellationException) {
            logcat(LogPriority.INFO) { "Dictionary import cancelled" }
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to "Import cancelled",
                ),
            )
        } catch (e: TrustedFileDownloader.TrustedDownloadException) {
            val errorMessage = getDownloadErrorMessage(e)
            logcat(LogPriority.WARN, e) { "Failed to download dictionary: $errorMessage" }
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
            notifier.showErrorNotification(errorMessage)
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
        } catch (e: DictionaryImportException) {
            val errorMessage = if (e.message == "already_imported") {
                "Dictionary already imported"
            } else {
                e.message ?: "Failed to import dictionary"
            }
            logcat(LogPriority.WARN, e) { "Dictionary import error: $errorMessage" }
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
            notifier.showErrorNotification(errorMessage)
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to import dictionary"
            logcat(LogPriority.ERROR, e) { "Dictionary import failed: $errorMessage" }
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
            notifier.showErrorNotification(errorMessage)
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
        } finally {
            try {
                tempFile?.delete()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
            context.cancelNotification(Notifications.ID_DICTIONARY_IMPORT_PROGRESS)
        }
    }

    private suspend fun extractAndImportDictionary(reader: ArchiveReader): String {
        setProgress(
            workDataOf(
                KEY_PROGRESS_STATE to STATE_PARSING,
                KEY_PROGRESS_ENTRIES_IMPORTED to 0,
            ),
        )

        // Parse index.json
        val indexJson = reader.getInputStream("index.json")?.bufferedReader()?.use { it.readText() }
            ?: throw DictionaryImportException("index.json not found in dictionary archive")

        val index: DictionaryIndex = try {
            dictionaryParser.parseIndex(indexJson)
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }

        setProgress(
            workDataOf(
                KEY_PROGRESS_STATE to STATE_PARSING,
                KEY_PROGRESS_DICTIONARY_TITLE to index.title,
            ),
        )

        // Check if dictionary is already imported
        if (dictionaryInteractor.isDictionaryAlreadyImported(index.title, index.revision)) {
            throw DictionaryImportException("already_imported")
        }

        val styles = reader.getInputStream("styles.css")?.bufferedReader()?.use { it.readText() }

        val dictionaryId = importDictionary.createDictionary(index, styles)

        importDictionary.importIndexTags(index, dictionaryId)

        val tagRegex = Regex("^tag_bank_\\d+\\.json$")
        val termRegex = Regex("^term_bank_\\d+\\.json$")
        val kanjiRegex = Regex("^kanji_bank_\\d+\\.json$")
        val termMetaRegex = Regex("^term_meta_bank_\\d+\\.json$")
        val kanjiMetaRegex = Regex("^kanji_meta_bank_\\d+\\.json$")

        val totalEntries = AtomicInteger(0)
        var lastNotificationUpdate = System.currentTimeMillis()

        // Entry count progress updater - throttled to avoid excessive updates
        val onProgress: suspend (Int) -> Unit = { batchCount ->
            val newTotal = totalEntries.addAndGet(batchCount)
            val now = System.currentTimeMillis()

            // Only update notification every 500ms to avoid excessive overhead
            if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                lastNotificationUpdate = now
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_STATE to STATE_PARSING,
                        KEY_PROGRESS_ENTRIES_IMPORTED to newTotal,
                        KEY_PROGRESS_DICTIONARY_TITLE to index.title,
                    ),
                )
                notifier.showParsingNotification(newTotal)
            }
        }

        reader.useEntriesAndStreams { entry, stream ->
            if (!entry.isFile) return@useEntriesAndStreams

            val entryName = entry.name
            val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')

            // Skip index.json as it's already processed
            if (fileName == "index.json") return@useEntriesAndStreams

            try {
                var imported = false
                when {
                    fileName.matches(termMetaRegex) -> {
                        val termMeta = dictionaryParser.parseTermMetaBank(stream)
                        importDictionary.importTermMeta(termMeta, dictionaryId, onProgress)
                        imported = true
                    }
                    fileName.matches(kanjiMetaRegex) -> {
                        val kanjiMeta = dictionaryParser.parseKanjiMetaBank(stream)
                        importDictionary.importKanjiMeta(kanjiMeta, dictionaryId, onProgress)
                        imported = true
                    }
                    fileName.matches(termRegex) -> {
                        val terms = dictionaryParser.parseTermBank(stream, index.effectiveVersion)
                        importDictionary.importTerms(terms, dictionaryId, onProgress)
                        imported = true
                    }
                    fileName.matches(kanjiRegex) -> {
                        val kanji = dictionaryParser.parseKanjiBank(stream, index.effectiveVersion)
                        importDictionary.importKanji(kanji, dictionaryId, onProgress)
                        imported = true
                    }
                    fileName.matches(tagRegex) -> {
                        val tags = dictionaryParser.parseTagBank(stream)
                        importDictionary.importTags(tags, dictionaryId)
                        imported = true
                    }
                }
                if (imported) {
                    logcat(LogPriority.INFO) { "Successfully imported $fileName" }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to parse or import $fileName" }
            }
        }

        // Final progress update with total count
        setProgress(
            workDataOf(
                KEY_PROGRESS_STATE to STATE_PARSING,
                KEY_PROGRESS_ENTRIES_IMPORTED to totalEntries.get(),
                KEY_PROGRESS_DICTIONARY_TITLE to index.title,
            ),
        )

        return index.title
    }

    private fun getDownloadErrorMessage(e: TrustedFileDownloader.TrustedDownloadException): String {
        return when (e.reason) {
            TrustedFileDownloader.Reason.INVALID_URL,
            TrustedFileDownloader.Reason.INVALID_REDIRECT,
            TrustedFileDownloader.Reason.UNTRUSTED_HOST,
            TrustedFileDownloader.Reason.INSECURE_SCHEME,
            TrustedFileDownloader.Reason.TOO_MANY_REDIRECTS,
            -> "Invalid or untrusted URL"
            TrustedFileDownloader.Reason.TOO_LARGE -> "Dictionary file too large"
            TrustedFileDownloader.Reason.NOT_A_ZIP -> "File is not a valid ZIP archive"
            TrustedFileDownloader.Reason.HTTP_ERROR,
            TrustedFileDownloader.Reason.EMPTY_BODY,
            -> "Download failed"
        }
    }

    companion object {
        private const val TAG = "DictionaryImport"

        const val KEY_URI = "uri"
        const val KEY_URL = "url"
        const val KEY_IMPORT_ID = "import_id"

        const val KEY_PROGRESS_STATE = "progress_state"
        const val KEY_PROGRESS_ENTRIES_IMPORTED = "progress_entries_imported"
        const val KEY_PROGRESS_DICTIONARY_TITLE = "progress_dictionary_title"
        const val KEY_PROGRESS_ERROR = "progress_error"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_PARSING = "parsing"
        const val STATE_COMPLETE = "complete"
        const val STATE_ERROR = "error"

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L

        // Keep this strict since this is an in-app downloader.
        val TRUSTED_DICTIONARY_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
        )

        // 300 MiB safety limit to reduce risk of storage exhaustion.
        const val MAX_DICTIONARY_DOWNLOAD_BYTES: Long = 300L * 1024L * 1024L

        fun start(context: Context, uri: Uri) {
            val importId = System.currentTimeMillis()
            val inputData = workDataOf(
                KEY_URI to uri.toString(),
                KEY_IMPORT_ID to importId,
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun start(context: Context, url: String) {
            val importId = System.currentTimeMillis()
            val inputData = workDataOf(
                KEY_URL to url,
                KEY_IMPORT_ID to importId,
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .any { it.state == WorkInfo.State.RUNNING }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return context.workManager
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        }
    }
}
