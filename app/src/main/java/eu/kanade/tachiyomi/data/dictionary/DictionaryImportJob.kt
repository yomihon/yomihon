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
 * Supports importing from local file URIs, remote URLs, or a batch of URIs.
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
        val uriStrings = inputData.getStringArray(KEY_URIS)
        val uriString = inputData.getString(KEY_URI)
        val urlString = inputData.getString(KEY_URL)

        return when {
            uriStrings != null -> importBatch(uriStrings.map { it.toUri() })
            uriString != null || urlString != null -> importSingle(uriString, urlString)
            else -> Result.failure()
        }
    }

    /**
     * Imports a single dictionary from a URI or URL.
     * If the archive has no index.json but contains nested .zip files, imports them all inline.
     */
    private suspend fun importSingle(uriString: String?, urlString: String?): Result {
        var tempFile: File? = null
        var importedDictionaryId: Long? = null

        return try {
            val archiveFile = withContext(Dispatchers.IO) {
                when {
                    urlString != null -> downloadRemoteArchive(urlString)
                    else -> copyLocalArchive(uriString!!.toUri())
                }.also { tempFile = it }
            }

            importSingleArchive(archiveFile).also { outcome ->
                importedDictionaryId = outcome?.dictionaryId
            }

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

    /**
     * Imports multiple dictionaries from a list of URIs.
     * Each URI is processed independently — failures don't stop the batch.
     */
    private suspend fun importBatch(uris: List<Uri>): Result {
        var imported = 0
        var skipped = 0
        var failed = 0

        for (uri in uris) {
            var tempFile: File? = null
            var importedDictionaryId: Long? = null
            try {
                val archiveFile = withContext(Dispatchers.IO) {
                    copyLocalArchive(uri).also { tempFile = it }
                }
                importSingleArchive(archiveFile).also { outcome ->
                    importedDictionaryId = outcome?.dictionaryId
                }
                imported++
            } catch (e: CancellationException) {
                cleanupPartialImport(importedDictionaryId)
                throw e
            } catch (e: DictionaryImportException.AlreadyImported) {
                skipped++
                logcat(LogPriority.INFO) { "Batch: skipped already-imported dictionary" }
            } catch (e: Exception) {
                failed++
                logImportFailure(e)
                cleanupPartialImport(importedDictionaryId)
            } finally {
                runCatching { tempFile?.delete() }
            }
        }

        logcat(LogPriority.INFO) {
            "Batch import complete: $imported imported, $skipped skipped, $failed failed"
        }
        return Result.success()
    }

    /**
     * Tries to import a single archive file as a dictionary.
     * If it has no index.json, falls back to importing nested .zip entries.
     * Returns the ImportOutcome if a single dict was imported, or null for nested imports.
     */
    private suspend fun importSingleArchive(archiveFile: File): ImportOutcome? {
        return try {
            ParcelFileDescriptor.open(archiveFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                ArchiveReader(pfd).use { reader ->
                    extractAndImportDictionary(reader, archiveFile)
                }
            }
        } catch (e: DictionaryImportException.InvalidArchive) {
            // No index.json — check if this is a ZIP containing nested dictionary ZIPs
            val count = importNestedZips(archiveFile)
            if (count == 0) throw e
            null
        }
    }

    /**
     * Extracts nested .zip files from an archive and imports each one inline.
     * Returns the total number of dicts found (imported + skipped).
     */
    private suspend fun importNestedZips(archiveFile: File): Int = withContext(Dispatchers.IO) {
        // First pass: extract all nested .zip entries to temp files
        val nestedDir = File(context.cacheDir, "dictionary_nested").apply { mkdirs() }
        val nestedFiles = mutableListOf<File>()

        ParcelFileDescriptor.open(archiveFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            ArchiveReader(pfd).use { reader ->
                reader.useEntriesAndStreams { entry, stream ->
                    if (entry.isFile && entry.name.endsWith(".zip", ignoreCase = true)) {
                        val nestedFile = File(
                            nestedDir,
                            "nested_${System.currentTimeMillis()}_${nestedFiles.size}.zip",
                        )
                        nestedFile.outputStream().buffered().use { output ->
                            stream.copyTo(output)
                        }
                        nestedFiles.add(nestedFile)
                    }
                }
            }
        }

        if (nestedFiles.isEmpty()) return@withContext 0

        // Second pass: import each extracted dict
        var imported = 0
        var skipped = 0
        var failed = 0

        for (nestedFile in nestedFiles) {
            var importedDictionaryId: Long? = null
            try {
                val outcome = ParcelFileDescriptor.open(
                    nestedFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    ArchiveReader(pfd).use { reader ->
                        extractAndImportDictionary(reader, nestedFile)
                    }
                }
                importedDictionaryId = outcome.dictionaryId
                imported++
            } catch (e: CancellationException) {
                cleanupPartialImport(importedDictionaryId)
                throw e
            } catch (e: DictionaryImportException.AlreadyImported) {
                skipped++
            } catch (e: Exception) {
                failed++
                logImportFailure(e)
                cleanupPartialImport(importedDictionaryId)
            } finally {
                runCatching { nestedFile.delete() }
            }
        }

        // Clean up the temp directory
        runCatching { nestedDir.delete() }

        logcat(LogPriority.INFO) {
            "Nested import complete: $imported imported, $skipped skipped, $failed failed " +
                "(${nestedFiles.size} total ZIPs found)"
        }

        imported + skipped + failed
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

        private const val KEY_URI = "uri"
        private const val KEY_URL = "url"
        private const val KEY_URIS = "uris"

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

        fun startBatch(context: Context, uris: List<Uri>) {
            val inputData = workDataOf(
                KEY_URIS to uris.map { it.toString() }.toTypedArray(),
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
