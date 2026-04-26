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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.domain.dictionary.exception.DictionaryImportException
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import mihon.domain.dictionary.service.DictionaryStorageGateway
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

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
    private val dictionaryStorageGateway: DictionaryStorageGateway = Injekt.get()

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)
        val urlString = inputData.getString(KEY_URL)

        if (uriString == null && urlString == null) {
            return Result.failure()
        }

        var tempFile: File? = null
        var importedDictionaryId: Long? = null

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
            Result.success()
        } catch (e: CancellationException) {
            logcat(LogPriority.INFO) { "Dictionary import cancelled" }
            cleanupPartialImport(importedDictionaryId)
            throw e
        } catch (e: Exception) {
            logImportFailure(e)
            cleanupPartialImport(importedDictionaryId)
            Result.failure()
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    private suspend fun downloadRemoteArchive(url: String): File = withContext(Dispatchers.IO) {
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
            ?: throw DictionaryImportException.InvalidArchive("Failed to open dictionary file")

        if (!file.exists() || !file.isFile) {
            throw DictionaryImportException.InvalidArchive("Invalid dictionary file")
        }

        val cacheDir = File(context.cacheDir, "dictionary_imports").apply { mkdirs() }
        val destination = File(cacheDir, "dictionary_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        } ?: throw DictionaryImportException.InvalidArchive("Failed to read dictionary file")

        destination
    }

    private suspend fun extractAndImportDictionary(
        reader: ArchiveReader,
        archiveFile: File,
    ): ImportOutcome {
        val indexJson = reader.getInputStream("index.json")?.bufferedReader()?.use { it.readText() }
            ?: throw DictionaryImportException.InvalidArchive("index.json not found in dictionary archive")

        val index: DictionaryIndex = try {
            dictionaryParser.parseIndex(indexJson)
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }

        if (dictionaryInteractor.isDictionaryAlreadyImported(index.title, index.revision)) {
            throw DictionaryImportException.AlreadyImported
        }

        val styles = reader.getInputStream("styles.css")?.bufferedReader()?.use { it.readText() }

        val dictionary = dictionaryInteractor.createDictionary(
            index = index,
            styles = styles,
        )

        val importOutcome = dictionaryStorageGateway.importDictionary(
            archivePath = archiveFile.absolutePath,
            dictionaryId = dictionary.id,
            dictionaryTitle = dictionary.title,
        )
        if (!importOutcome.success || importOutcome.storagePath.isNullOrBlank()) {
            throw DictionaryImportException.ImportFailed("Failed to import dictionary into hoshidicts")
        }

        dictionaryInteractor.updateDictionary(
            dictionary.copy(
                storagePath = importOutcome.storagePath,
                storageReady = true,
            ),
        )

        dictionaryStorageGateway.refreshSearchSession()

        return ImportOutcome(
            dictionaryId = dictionary.id,
        )
    }

    private suspend fun cleanupPartialImport(dictionaryId: Long?) {
        if (dictionaryId != null) {
            runCatching { dictionaryInteractor.deleteDictionary(dictionaryId) }
            runCatching { dictionaryStorageGateway.clearDictionaryStorage(dictionaryId) }
        }
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

    private fun logImportFailure(error: Exception) {
        when (error) {
            is TrustedFileDownloader.TrustedDownloadException -> {
                val errorMessage = getDownloadErrorMessage(error)
                logcat(LogPriority.WARN, error) { "Failed to download dictionary: $errorMessage" }
            }
            is DictionaryImportException -> {
                val errorMessage = error.message ?: "Failed to import dictionary"
                logcat(LogPriority.WARN, error) { "Dictionary import error: $errorMessage" }
            }
            else -> {
                val errorMessage = error.message ?: "Failed to import dictionary"
                logcat(LogPriority.ERROR, error) { "Dictionary import failed: $errorMessage" }
            }
        }
    }

    companion object {
        const val TAG = "DictionaryImport"

        const val KEY_URI = "uri"
        const val KEY_URL = "url"

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
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(
                DictionaryWorkNames.IMPORT_AND_MIGRATION,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun start(context: Context, url: String) {
            val inputData = workDataOf(
                KEY_URL to url,
            )
            val request = OneTimeWorkRequestBuilder<DictionaryImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(
                DictionaryWorkNames.IMPORT_AND_MIGRATION,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
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
    )
}
