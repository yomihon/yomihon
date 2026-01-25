package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import mihon.data.ocr.di.OcrModule
import mihon.domain.ocr.interactor.OcrProcessor
import mihon.domain.ocr.model.OcrModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.getEnum

/**
 * Integration test for OCR repository that runs against the device/emulator assets and native inference libraries.
 */
//@Ignore("Requires real Android runtime/device, potentially lengthy to run.")
@RunWith(AndroidJUnit4::class)
class OcrRepositoryImplTest {
    private lateinit var ocrRepository: OcrRepositoryImpl
    private lateinit var context: Context
    private val ocrProcessor: OcrProcessor
        get() = OcrModule.provideOcrProcessor(context)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ocrRepository = OcrRepositoryImpl(context)
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

        OcrModule.cleanup()

        // Run OCR again - should auto re-initialize
        val secondRunText = ocrProcessor.getText(bitmap)

        assertNotNull("Result was null after cleanup", secondRunText)
        assertEquals("Text did not match after cleanup re-initialization", expectedText, secondRunText)
    }

    @Test
    fun ocrFastSpeedTest() = runTest(timeout = 4.minutes) {
        val resourceName = "mihon/data/ocr/ocr_test_image.base64"
        val expectedText = "でもだいたい見当はついてるの"
        val bitmap = getBitmap(resourceName)

        // Switch preference to fast model
        val prefStore = tachiyomi.core.common.preference.AndroidPreferenceStore(context)
        prefStore.getEnum("pref_ocr_model", OcrModel.LEGACY).set(OcrModel.FAST)

        // Warm up & measure end-to-end time at the caller level (includes repository init + inference)
        val start = System.nanoTime()
        val text = ocrProcessor.getText(bitmap)
        val totalMs = (System.nanoTime() - start) / 1_000_000
        android.util.Log.i("OcrRepositoryImplTest", "OCR(fast) Test: total time: $totalMs ms")

        assertNotNull(text)
        assertEquals(expectedText, text)
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
