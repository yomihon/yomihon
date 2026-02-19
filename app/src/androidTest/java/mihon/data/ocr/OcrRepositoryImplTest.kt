package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mihon.domain.ocr.interactor.OcrProcessor
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.getEnum
import kotlin.time.Duration.Companion.minutes

/**
 * Integration test for OCR repository that runs against the device/emulator assets and native inference libraries.
 */
@Ignore("Requires real Android runtime/device, potentially lengthy to run.")
@RunWith(AndroidJUnit4::class)
class OcrRepositoryImplTest {
    private lateinit var ocrRepository: OcrRepository
    private lateinit var context: Context
    private lateinit var ocrProcessor: OcrProcessor

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ocrRepository = OcrRepositoryImpl(context)
        ocrProcessor = OcrProcessor(ocrRepository)
    }

    @Test
    fun ocrTest() = runTest(timeout = 4.minutes) {
        val testCases = listOf(
            "mihon/data/ocr/ocr_test_image.base64" to "でもだいたい見当はついてるの",
            "mihon/data/ocr/ocr_test_image2.base64" to "ぼくが復活する前に",
        )

        for ((resourceName, expectedText) in testCases) {
            val bitmap = getBitmap(resourceName)

            val text = ocrProcessor.getText(bitmap)

            assertNotNull(text)
            assertEquals(expectedText, text)
        }
    }

    @Test
    fun ocrCleanupTest() = runTest(timeout = 4.minutes) {
        val resourceName = "mihon/data/ocr/ocr_test_image.base64"
        val expectedText = "でもだいたい見当はついてるの"
        val bitmap = getBitmap(resourceName)

        // Ensure OCR is initialized and working
        val firstRunText = ocrProcessor.getText(bitmap)
        assertEquals("First run failed", expectedText, firstRunText)

        ocrRepository.cleanup()

        // Run OCR again - should auto re-initialize
        val secondRunText = ocrProcessor.getText(bitmap)

        assertNotNull("Result was null after cleanup", secondRunText)
        assertEquals("Text did not match after cleanup re-initialization", expectedText, secondRunText)
    }

    @Test
    fun ocrFastSpeedTest() = runTest(timeout = 4.minutes) {
        // Switch preference to fast model
        val prefStore = tachiyomi.core.common.preference.AndroidPreferenceStore(context)
        prefStore.getEnum("pref_ocr_model", OcrModel.LEGACY).set(OcrModel.FAST)

        // Warm up & measure end-to-end time at the caller level (includes repository init + inference)
        val testCases = listOf(
            "mihon/data/ocr/ocr_test_image.base64" to "でもだいたい見当はついてるの",
            "mihon/data/ocr/ocr_test_image2.base64" to "ぼくが復活する前に",
            "mihon/data/ocr/ocr_test_image.base64" to "でもだいたい見当はついてるの",
            "mihon/data/ocr/ocr_test_image.base64" to "でもだいたい見当はついてるの",
            "mihon/data/ocr/ocr_test_image2.base64" to "ぼくが復活する前に",
            "mihon/data/ocr/ocr_test_image2.base64" to "ぼくが復活する前に",
        )

        for ((resourceName, expectedText) in testCases) {
            val bitmap = getBitmap(resourceName)

            val text = ocrProcessor.getText(bitmap)

            assertNotNull(text)
            assertEquals(expectedText, text)
        }
    }

    private fun getBitmap(resourceName: String): Bitmap {
        val inputStream = javaClass.classLoader?.getResourceAsStream(resourceName)
        require(inputStream != null) { "Test image not found: $resourceName" }

        val base64 = inputStream.bufferedReader().use { it.readText().trim() }
        val bytes = Base64.decode(base64, Base64.DEFAULT)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        require(bitmap != null) { "Bitmap could not be decoded from $resourceName" }

        return bitmap
    }
}
