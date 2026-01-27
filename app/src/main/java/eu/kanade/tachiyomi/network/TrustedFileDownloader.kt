package eu.kanade.tachiyomi.network

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads zip files from trusted HTTP(S) sources.
 */
internal class TrustedFileDownloader(
    client: OkHttpClient,
    private val allowedHosts: Set<String>,
    private val maxBytes: Long,
    private val maxRedirects: Int = 5,
) {

    private val clientNoRedirects: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun downloadZipToFile(
        url: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val startUrl = url.toHttpUrlOrNull() ?: throw TrustedDownloadException(Reason.INVALID_URL)
        validateTrustedUrl(startUrl)

        destination.parentFile?.mkdirs()

        val partial = File(destination.parentFile, destination.name + ".partial")
        if (partial.exists()) partial.delete()

        var currentUrl = startUrl
        var redirects = 0

        try {
            downloadLoop@ while (true) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .header("Accept", "application/zip, application/octet-stream")
                    .build()

                val response = clientNoRedirects.newCall(request).execute()

                if (isRedirect(response)) {
                    response.close()
                    if (redirects >= maxRedirects) throw TrustedDownloadException(Reason.TOO_MANY_REDIRECTS)
                    val next = resolveRedirect(currentUrl, response) ?: throw TrustedDownloadException(Reason.INVALID_REDIRECT)
                    validateTrustedUrl(next)
                    currentUrl = next
                    redirects++
                    continue@downloadLoop
                }

                response.use {
                    if (!it.isSuccessful) {
                        throw TrustedDownloadException(Reason.HTTP_ERROR, httpCode = it.code)
                    }

                    val body = it.body

                    val contentLength = body.contentLength().takeIf { len -> len > 0L }
                    if (contentLength != null && contentLength > maxBytes) {
                        throw TrustedDownloadException(Reason.TOO_LARGE)
                    }

                    var totalRead = 0L
                    var lastReportedBytes = 0L
                    var lastReportNanos = System.nanoTime()

                    // Use interruptible NIO channels for better coroutine cancellation support
                    val inputChannel = Channels.newChannel(body.byteStream())
                    FileChannel.open(
                        partial.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).use { outputChannel ->
                        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            // Check for coroutine cancellation before each read
                            ensureActive()

                            buffer.clear()
                            val read = inputChannel.read(buffer)
                            if (read < 0) break

                            totalRead += read
                            if (totalRead > maxBytes) {
                                throw TrustedDownloadException(Reason.TOO_LARGE)
                            }

                            buffer.flip()
                            outputChannel.write(buffer)

                            val shouldReportByBytes = (totalRead - lastReportedBytes) >= PROGRESS_REPORT_BYTES
                            val shouldReportByTime = (System.nanoTime() - lastReportNanos) >= PROGRESS_REPORT_NANOS

                            if (shouldReportByBytes || shouldReportByTime) {
                                onProgress(totalRead, contentLength)
                                lastReportedBytes = totalRead
                                lastReportNanos = System.nanoTime()
                            }
                        }
                        outputChannel.force(true)
                    }

                    onProgress(totalRead, contentLength)
                }

                if (!partial.isFile || partial.length() <= 0L) {
                    throw TrustedDownloadException(Reason.EMPTY_BODY)
                }

                if (!looksLikeZip(partial)) {
                    throw TrustedDownloadException(Reason.NOT_A_ZIP)
                }


                try {
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                break@downloadLoop
            }
        } finally {
            // Clean up partial file on failure or cancellation
            if (partial.exists()) {
                partial.delete()
            }
        }

        destination
    }

    private fun validateTrustedUrl(url: HttpUrl) {
        if (url.scheme != "https") throw TrustedDownloadException(Reason.INSECURE_SCHEME)
        if (url.host !in allowedHosts) throw TrustedDownloadException(Reason.UNTRUSTED_HOST)
    }

    private fun isRedirect(response: Response): Boolean {
        return response.code in REDIRECT_CODES
    }

    private fun resolveRedirect(currentUrl: HttpUrl, response: Response): HttpUrl? {
        val location = response.header("Location")?.trim().orEmpty()
        if (location.isBlank()) return null
        return currentUrl.resolve(location)
    }

    private fun looksLikeZip(file: File): Boolean {
        // ZIP local file header: PK\u0003\u0004 (standard archive)
        val header = ByteArray(4)
        try {
            file.inputStream().use { input ->
                val read = input.read(header)
                if (read < 4) return false
            }
        } catch (_: IOException) {
            return false
        }

        return header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() &&
            header[3] == 4.toByte()
    }

    class TrustedDownloadException(
        val reason: Reason,
        val httpCode: Int? = null,
        cause: Throwable? = null,
    ) : IOException(buildMessage(reason, httpCode), cause) {
        companion object {
            private fun buildMessage(reason: Reason, httpCode: Int?): String {
                return when (reason) {
                    Reason.INVALID_URL -> "invalid_url"
                    Reason.INSECURE_SCHEME -> "insecure_scheme"
                    Reason.UNTRUSTED_HOST -> "untrusted_host"
                    Reason.TOO_MANY_REDIRECTS -> "too_many_redirects"
                    Reason.INVALID_REDIRECT -> "invalid_redirect"
                    Reason.HTTP_ERROR -> "http_error:${httpCode ?: -1}"
                    Reason.EMPTY_BODY -> "empty_body"
                    Reason.TOO_LARGE -> "too_large"
                    Reason.NOT_A_ZIP -> "not_a_zip"
                }
            }
        }
    }

    enum class Reason {
        INVALID_URL,
        INSECURE_SCHEME,
        UNTRUSTED_HOST,
        TOO_MANY_REDIRECTS,
        INVALID_REDIRECT,
        HTTP_ERROR,
        EMPTY_BODY,
        TOO_LARGE,
        NOT_A_ZIP,
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        // Add a delay between download progress updates
        const val PROGRESS_REPORT_BYTES: Long = 512L * 1024L

        // Cap the maximum, in case internet is too slow for progress to update often
        const val PROGRESS_REPORT_NANOS: Long = 250L * 1_000_000L
    }
}
