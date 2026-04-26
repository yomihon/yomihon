package eu.kanade.tachiyomi.data.dictionary.audio

import kotlinx.coroutines.test.runTest
import mihon.domain.dictionary.audio.DictionaryAudioResult
import mihon.domain.dictionary.audio.DictionaryAudioSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DictionaryAudioRemoteFetcherTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchFirstAvailableAudio returns jpod101 result when available`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path.orEmpty().contains("/dictionary/japanese/audiomp3.php") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "audio/mpeg")
                            .setBody("mp3-audio")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val cacheDir = Files.createTempDirectory("dict-audio").toFile()
        val fetcher = DictionaryAudioRemoteFetcher(
            client = OkHttpClient(),
            jpodBaseUrl = server.url("/").toString().removeSuffix("/"),
            wikimediaBaseUrl = server.url("/").toString().removeSuffix("/"),
        )

        val result = fetcher.fetchFirstAvailableAudio("日本語", "にほんご", cacheDir, "cache_key")

        val success = assertInstanceOf(DictionaryAudioResult.Success::class.java, result)
        assertEquals(DictionaryAudioSource.JPOD101, success.audio.source)
        assertTrue(success.audio.file.isFile)
    }

    @Test
    fun `fetchFirstAvailableAudio falls back to wiktionary when jpod101 misses`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/dictionary/japanese/audiomp3.php") -> MockResponse().setResponseCode(404)
                    path.contains("list=search") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {"query":{"search":[{"title":"File:ja-日本語.ogg"}]}}
                                """.trimIndent(),
                            )
                    }
                    path.contains("prop=imageinfo") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {"query":{"pages":{"1":{"imageinfo":[{"url":"${server.url("/media/ja-日本語.ogg")}"}]}}}}
                                """.trimIndent(),
                            )
                    }
                    path.contains("/media/ja-") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "audio/ogg")
                            .setBody("ogg-audio")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val cacheDir = Files.createTempDirectory("dict-audio").toFile()
        val fetcher = DictionaryAudioRemoteFetcher(
            client = OkHttpClient(),
            jpodBaseUrl = server.url("/").toString().removeSuffix("/"),
            wikimediaBaseUrl = server.url("/").toString().removeSuffix("/"),
        )

        val result = fetcher.fetchFirstAvailableAudio("日本語", "にほんご", cacheDir, "cache_key")

        val success = assertInstanceOf(DictionaryAudioResult.Success::class.java, result)
        assertEquals(DictionaryAudioSource.WIKTIONARY, success.audio.source)
        assertTrue(success.audio.file.extension == "ogg")
    }

    @Test
    fun `fetchFirstAvailableAudio returns handled error when all sources fail`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(500)
            }
        }

        val cacheDir = Files.createTempDirectory("dict-audio").toFile()
        val fetcher = DictionaryAudioRemoteFetcher(
            client = OkHttpClient(),
            jpodBaseUrl = server.url("/").toString().removeSuffix("/"),
            wikimediaBaseUrl = server.url("/").toString().removeSuffix("/"),
        )

        val result = fetcher.fetchFirstAvailableAudio("日本語", "にほんご", cacheDir, "cache_key")

        assertInstanceOf(DictionaryAudioResult.Error::class.java, result)
    }
}
