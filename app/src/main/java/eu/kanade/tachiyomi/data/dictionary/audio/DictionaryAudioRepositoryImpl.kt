package eu.kanade.tachiyomi.data.dictionary.audio

import android.content.Context
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.audio.DictionaryAudio
import mihon.domain.dictionary.audio.DictionaryAudioRepository
import mihon.domain.dictionary.audio.DictionaryAudioResult
import mihon.domain.dictionary.audio.DictionaryAudioSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class DictionaryAudioRepositoryImpl(
    context: Context,
    networkHelper: NetworkHelper,
    private val remoteFetcher: DictionaryAudioRemoteFetcher = DictionaryAudioRemoteFetcher(networkHelper.client),
) : DictionaryAudioRepository {

    private val cacheDir = File(context.cacheDir, "dictionary_audio").apply { mkdirs() }
    private val fetchMutex = Mutex()

    override suspend fun fetchAudio(
        expression: String,
        reading: String,
    ): DictionaryAudioResult = withContext(Dispatchers.IO) {
        if (expression.isBlank()) {
            return@withContext DictionaryAudioResult.NotFound
        }

        fetchMutex.withLock {
            val cacheKey = buildCacheKey(expression, reading)
            findCachedAudio(cacheKey)?.let { cached ->
                return@withLock DictionaryAudioResult.Success(cached)
            }

            remoteFetcher.fetchFirstAvailableAudio(
                expression = expression,
                reading = reading,
                cacheDir = cacheDir,
                cacheKey = cacheKey,
            )
        }
    }

    private fun findCachedAudio(cacheKey: String): DictionaryAudio? {
        return cacheDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(cacheKey) }
            ?.let { file ->
                DictionaryAudio(
                    file = file,
                    mediaType = guessMediaType(file.extension),
                    source = when {
                        file.name.contains("_wiktionary_", ignoreCase = true) -> DictionaryAudioSource.WIKTIONARY
                        else -> DictionaryAudioSource.JPOD101
                    },
                )
            }
    }

    private fun buildCacheKey(expression: String, reading: String): String {
        val normalized = "${expression.trim()}|${reading.trim()}".lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val expressionPart = sanitizeForFilename(expression).take(24).ifBlank { "term" }
        val readingPart = sanitizeForFilename(reading).take(24).ifBlank { "reading" }
        return "${digest}_${expressionPart}_$readingPart"
    }
}

class DictionaryAudioRemoteFetcher(
    private val client: OkHttpClient,
    jpodBaseUrl: String = "https://assets.languagepod101.com",
    wikimediaBaseUrl: String = "https://commons.wikimedia.org",
    json: Json = Json { ignoreUnknownKeys = true },
) {

    private val downloader = AudioAssetDownloader(client)
    private val sources: List<DictionaryAudioProvider> = listOf(
        InnovativeLanguageAudioProvider(
            baseUrl = jpodBaseUrl,
            downloader = downloader,
        ),
        WikimediaJapaneseAudioProvider(
            baseUrl = wikimediaBaseUrl,
            downloader = downloader,
            json = json,
            client = client,
        ),
    )

    suspend fun fetchFirstAvailableAudio(
        expression: String,
        reading: String,
        cacheDir: File,
        cacheKey: String,
    ): DictionaryAudioResult {
        val request = AudioLookupRequest(
            expression = expression.trim(),
            reading = reading.trim(),
            cacheDir = cacheDir,
            cacheKey = cacheKey,
        )

        var firstFailure: Throwable? = null
        for (source in sources) {
            val result = runCatching { source.fetch(request) }
            result.exceptionOrNull()?.let { error ->
                if (firstFailure == null) {
                    firstFailure = error
                }
            }
            result.getOrNull()?.let { audio ->
                return DictionaryAudioResult.Success(audio)
            }
        }

        return firstFailure?.let(DictionaryAudioResult::Error) ?: DictionaryAudioResult.NotFound
    }
}

private data class AudioLookupRequest(
    val expression: String,
    val reading: String,
    val cacheDir: File,
    val cacheKey: String,
)

private interface DictionaryAudioProvider {
    suspend fun fetch(request: AudioLookupRequest): DictionaryAudio?
}

private class InnovativeLanguageAudioProvider(
    baseUrl: String,
    private val downloader: AudioAssetDownloader,
) : DictionaryAudioProvider {

    private val dictionaryEndpoint = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegments("dictionary/japanese/audiomp3.php")
        .build()

    override suspend fun fetch(request: AudioLookupRequest): DictionaryAudio? {
        val url = dictionaryEndpoint.newBuilder().apply {
            if (request.expression.shouldUseReadingOnlyLookup(request.reading)) {
                addQueryParameter("kana", request.reading)
            } else {
                addQueryParameter("kanji", request.expression)
                request.reading.takeIf(String::isNotBlank)?.let { addQueryParameter("kana", it) }
            }
        }.build()

        return downloader.download(
            url = url,
            cacheDir = request.cacheDir,
            cacheKey = request.cacheKey,
            source = DictionaryAudioSource.JPOD101,
            invalidContentHash = INVALID_JPOD101_AUDIO_SHA256,
        )
    }
}

private class WikimediaJapaneseAudioProvider(
    baseUrl: String,
    private val downloader: AudioAssetDownloader,
    private val json: Json,
    private val client: OkHttpClient,
) : DictionaryAudioProvider {

    private val apiUrl = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegments("w/api.php")
        .build()

    override suspend fun fetch(request: AudioLookupRequest): DictionaryAudio? {
        val expression = request.expression
        if (expression.isBlank()) {
            return null
        }

        val titles = findCandidateTitles(expression)
        for (title in titles) {
            resolveFileUrl(title)?.let { fileUrl ->
                downloader.download(
                    url = fileUrl.toHttpUrl(),
                    cacheDir = request.cacheDir,
                    cacheKey = request.cacheKey,
                    source = DictionaryAudioSource.WIKTIONARY,
                    preferredExtension = "ogg",
                )?.let { return it }
            }
        }

        return null
    }

    private suspend fun findCandidateTitles(expression: String): List<String> {
        val searchPattern = "ja(-[a-zA-Z]{2})?-${Regex.escape(expression)}[0-9]*\\.ogg"
        val url = apiUrl.newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", "intitle:/$searchPattern/i")
            .addQueryParameter("srnamespace", "6")
            .addQueryParameter("origin", "*")
            .build()

        val body = client.newCall(GET(url)).awaitSuccess().use { response ->
            response.body.string()
        }

        val results = json.parseToJsonElement(body).jsonObject["query"]
            ?.jsonObject
            ?.get("search")
            ?.jsonArray
            ?: JsonArray(emptyList())

        return results.mapNotNull { entry ->
            entry.jsonObject["title"]?.jsonPrimitive?.contentOrNull
        }.filter(WIKIMEDIA_JAPANESE_AUDIO_FILE::matches)
    }

    private suspend fun resolveFileUrl(title: String): String? {
        val url = apiUrl.newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("titles", title)
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url")
            .addQueryParameter("origin", "*")
            .build()

        val body = client.newCall(GET(url)).awaitSuccess().use { response ->
            response.body.string()
        }
        val pages = json.parseToJsonElement(body).jsonObject["query"]
            ?.jsonObject
            ?.get("pages")
            ?.jsonObject
            ?: JsonObject(emptyMap())

        return pages.values.firstNotNullOfOrNull { page ->
            page.jsonObject["imageinfo"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }
}

private class AudioAssetDownloader(
    private val client: OkHttpClient,
) {

    suspend fun download(
        url: HttpUrl,
        cacheDir: File,
        cacheKey: String,
        source: DictionaryAudioSource,
        preferredExtension: String? = null,
        invalidContentHash: String? = null,
    ): DictionaryAudio? {
        val response = client.newCall(GET(url)).awaitSuccess()
        response.use {
            val body = it.body
            if (body.contentLength() == 0L) {
                return null
            }

            val bytes = body.bytes()
            if (bytes.isEmpty()) {
                return null
            }

            if (invalidContentHash != null && bytes.sha256() == invalidContentHash) {
                return null
            }

            val extension = preferredExtension
                ?: inferExtension(it.header("Content-Type"), url)
                ?: "bin"
            val outputFile = File(
                cacheDir,
                "${cacheKey}_${source.name.lowercase(Locale.ROOT)}.$extension",
            )
            outputFile.sink().buffer().use { sink ->
                sink.write(bytes)
            }

            return DictionaryAudio(
                file = outputFile,
                mediaType = it.header("Content-Type") ?: guessMediaType(extension),
                source = source,
            )
        }
    }

    private fun inferExtension(contentType: String?, url: HttpUrl): String? {
        return when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            else -> url.pathSegments.lastOrNull()
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf(String::isNotBlank)
        }
    }

    private fun ByteArray.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}

private fun String.shouldUseReadingOnlyLookup(reading: String): Boolean {
    if (reading.isBlank() || reading != this) {
        return false
    }
    return all { it.code in 0x3040..0x30FF }
}

private val WIKIMEDIA_JAPANESE_AUDIO_FILE =
    Regex("^File:ja(-\\w\\w)?-.+\\d*\\.ogg$", RegexOption.IGNORE_CASE)

private const val INVALID_JPOD101_AUDIO_SHA256 =
    "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"

internal fun sanitizeForFilename(value: String): String {
    return value.trim()
        .replace("\\s+".toRegex(), "_")
        .replace("[^\\p{L}\\p{Nd}_-]".toRegex(), "")
}

internal fun guessMediaType(extension: String): String? {
    return when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}
