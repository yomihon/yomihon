package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.TrustedFileDownloader
import eu.kanade.tachiyomi.util.system.workManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.data.dictionary.HoshiDictionaryStore
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryBackend
import mihon.domain.dictionary.model.DictionaryImportException
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import tachiyomi.core.common.util.system.logcat
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
    private val dictionaryParser: DictionaryParser = Injekt.get()
    private val networkHelper: NetworkHelper = Injekt.get()
    private val hoshiDictionaryStore: HoshiDictionaryStore = Injekt.get()

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)
        val urlString = inputData.getString(KEY_URL)

        if (uriString == null && urlString == null) {
            return Result.failure(workDataOf(KEY_PROGRESS_ERROR to "No URI or URL provided"))
        }

        var tempFile: File? = null
        var importedDictionaryId: Long? = null
        var importedStorageParent: File? = null

        return try {
            val archiveFile = withContext(Dispatchers.IO) {
                when {
                    urlString != null -> downloadRemoteArchive(urlString)
                    else -> copyLocalArchive(uriString!!.toUri())
                }.also { tempFile = it }
            }

            val outcome = ParcelFileDescriptor.open(archiveFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                ArchiveReader(pfd).use { reader ->
                    extractAndImportDictionary(reader, archiveFile)
                }
            }

            importedDictionaryId = outcome.dictionaryId
            importedStorageParent = outcome.storageParent

            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_COMPLETE,
                    KEY_PROGRESS_DICTIONARY_TITLE to outcome.title,
                ),
            )
            Result.success(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_COMPLETE,
                    KEY_PROGRESS_DICTIONARY_TITLE to outcome.title,
                ),
            )
        } catch (e: CancellationException) {
            logcat(LogPriority.INFO) { "Dictionary import cancelled" }
            cleanupPartialImport(importedDictionaryId, importedStorageParent)
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to "Import cancelled",
                ),
            )
        } catch (e: TrustedFileDownloader.TrustedDownloadException) {
            val errorMessage = getDownloadErrorMessage(e)
            logcat(LogPriority.WARN, e) { "Failed to download dictionary: $errorMessage" }
            cleanupPartialImport(importedDictionaryId, importedStorageParent)
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
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
            cleanupPartialImport(importedDictionaryId, importedStorageParent)
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Failed to import dictionary"
            logcat(LogPriority.ERROR, e) { "Dictionary import failed: $errorMessage" }
            cleanupPartialImport(importedDictionaryId, importedStorageParent)
            setProgress(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
            Result.failure(
                workDataOf(
                    KEY_PROGRESS_STATE to STATE_ERROR,
                    KEY_PROGRESS_ERROR to errorMessage,
                ),
            )
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    private suspend fun downloadRemoteArchive(url: String): File = withContext(Dispatchers.IO) {
        setProgress(workDataOf(KEY_PROGRESS_STATE to STATE_DOWNLOADING))

        val downloadsDir = File(context.cacheDir, "dictionary_downloads").apply { mkdirs() }
        val destination = File(downloadsDir, "dictionary_${System.currentTimeMillis()}.zip")

        val downloader = TrustedFileDownloader(
            client = networkHelper.nonCloudflareClient,
            allowedHosts = TRUSTED_DICTIONARY_HOSTS,
            maxBytes = MAX_DICTIONARY_DOWNLOAD_BYTES,
        )

        downloader.downloadZipToFile(url = url, destination = destination) { _, _ -> }

        destination
    }

    private suspend fun copyLocalArchive(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = UniFile.fromUri(context, uri)
            ?: throw DictionaryImportException("Failed to open dictionary file")

        if (!file.exists() || !file.isFile) {
            throw DictionaryImportException("Invalid dictionary file")
        }

        setProgress(workDataOf(KEY_PROGRESS_STATE to STATE_PARSING))

        val cacheDir = File(context.cacheDir, "dictionary_imports").apply { mkdirs() }
        val destination = File(cacheDir, "dictionary_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: throw DictionaryImportException("Failed to read dictionary file")

        destination
    }

    private suspend fun extractAndImportDictionary(
        reader: ArchiveReader,
        archiveFile: File,
    ): ImportOutcome {
        setProgress(
            workDataOf(
                KEY_PROGRESS_STATE to STATE_PARSING,
                KEY_PROGRESS_ENTRIES_IMPORTED to 0,
            ),
        )

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

        if (dictionaryInteractor.isDictionaryAlreadyImported(index.title, index.revision)) {
            throw DictionaryImportException("already_imported")
        }

        val styles = reader.getInputStream("styles.css")?.bufferedReader()?.use { it.readText() }

        val dictionaryId = dictionaryInteractor.createDictionary(
            index = index,
            styles = styles,
            backend = DictionaryBackend.HOSHI,
            storageReady = false,
        )

        val storageParent = hoshiDictionaryStore.getDictionaryStorageParent(dictionaryId)

        setProgress(
            workDataOf(
                KEY_PROGRESS_STATE to STATE_IMPORTING,
                KEY_PROGRESS_DICTIONARY_TITLE to index.title,
            ),
        )

        val dictionary = Dictionary(
            id = dictionaryId,
            title = index.title,
            revision = index.revision,
            version = index.effectiveVersion,
            author = index.author,
            url = index.url,
            description = index.description,
            attribution = index.attribution,
            styles = styles,
            sourceLanguage = index.sourceLanguage,
            targetLanguage = index.targetLanguage,
            backend = DictionaryBackend.HOSHI,
            storageReady = false,
        )

        val importOutcome = hoshiDictionaryStore.importDictionary(archiveFile.absolutePath, dictionary)
        if (!importOutcome.success || importOutcome.storagePath.isNullOrBlank()) {
            throw DictionaryImportException("Failed to import dictionary into hoshidicts")
        }

        dictionaryInteractor.updateDictionary(
            dictionary.copy(
                storagePath = importOutcome.storagePath,
                storageReady = true,
            ),
        )

        hoshiDictionaryStore.markDirty()
        hoshiDictionaryStore.rebuildSession()

        return ImportOutcome(
            dictionaryId = dictionaryId,
            title = index.title,
            storageParent = storageParent,
        )
    }

    private fun cleanupPartialImport(dictionaryId: Long?, storageParent: File?) {
        if (dictionaryId != null) {
            runCatching {
                runBlocking {
                    dictionaryInteractor.deleteDictionary(dictionaryId)
                }
            }
        }
        storageParent?.let { parent ->
            runCatching { parent.deleteRecursively() }
        }
        hoshiDictionaryStore.markDirty()
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
        const val TAG = "DictionaryImport"

        const val KEY_URI = "uri"
        const val KEY_URL = "url"
        const val KEY_IMPORT_ID = "import_id"

        const val KEY_PROGRESS_STATE = "progress_state"
        const val KEY_PROGRESS_ENTRIES_IMPORTED = "progress_entries_imported"
        const val KEY_PROGRESS_DICTIONARY_TITLE = "progress_dictionary_title"
        const val KEY_PROGRESS_ERROR = "progress_error"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_PARSING = "parsing"
        const val STATE_IMPORTING = "importing"
        const val STATE_COMPLETE = "complete"
        const val STATE_ERROR = "error"

        val TRUSTED_DICTIONARY_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        const val MAX_DICTIONARY_DOWNLOAD_BYTES: Long = 300L * 1024L * 1024L

        fun start(context: Context, uri: Uri) {
            val inputData = workDataOf(
                KEY_URI to uri.toString(),
                KEY_IMPORT_ID to System.currentTimeMillis(),
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun start(context: Context, url: String) {
            val inputData = workDataOf(
                KEY_URL to url,
                KEY_IMPORT_ID to System.currentTimeMillis(),
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager
                .getWorkInfosByTag(TAG)
                .get()
                .any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return context.workManager
                .getWorkInfosByTagLiveData(TAG)
                .asFlow()
                .map { list ->
                    list.any {
                        it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                }
        }
    }

    private data class ImportOutcome(
        val dictionaryId: Long,
        val title: String,
        val storageParent: File,
    )
}
