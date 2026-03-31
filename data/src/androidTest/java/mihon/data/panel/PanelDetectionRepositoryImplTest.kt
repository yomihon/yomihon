package mihon.data.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mihon.domain.panel.model.PanelDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.ReadingDirection

@RunWith(AndroidJUnit4::class)
class PanelDetectionRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = PanelDetectionRepositoryImpl(context)

    @Test
    fun detectsTempleEntranceFivePanelsInRtlOrder() {
        assumeModelPresent()

        val bitmap = getBitmap("mihon/data/panel/temple-entrance-five-panels.png")
        val result = detectResult(
            key = "temple-entrance-five-panels",
            bitmap = bitmap,
            direction = ReadingDirection.RTL,
        )
        val panels = result.panels

        assertEquals(5, panels.size)
        assertRoughRegions(
            panels,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            listOf(
                ExpectedRegion(0.35f, 0.65f, 0.18f, 0.46f),
                ExpectedRegion(0.76f, 0.94f, 0.68f, 0.90f),
                ExpectedRegion(0.56f, 0.72f, 0.68f, 0.90f),
                ExpectedRegion(0.34f, 0.52f, 0.68f, 0.90f),
                ExpectedRegion(0.10f, 0.28f, 0.68f, 0.90f),
            ),
        )
    }

    @Test
    fun detectsCliffsidePathFourPanels() {
        assumeModelPresent()

        val bitmap = getBitmap("mihon/data/panel/cliffside-path-four-panels.png")
        val result = detectResult(
            key = "cliffside-path-four-panels",
            bitmap = bitmap,
            direction = ReadingDirection.RTL,
        )
        val panels = result.panels

        assertEquals(4, panels.size)
        assertContainsPanel(panels, bitmap, ExpectedRegion(0.12f, 0.42f, 0.32f, 0.62f))
        assertContainsPanel(panels, bitmap, ExpectedRegion(0.74f, 0.92f, 0.08f, 0.22f))
        assertContainsPanel(panels, bitmap, ExpectedRegion(0.74f, 0.92f, 0.34f, 0.58f))
        assertContainsPanel(panels, bitmap, ExpectedRegion(0.74f, 0.92f, 0.70f, 0.92f))
    }

    @Test
    fun detectsSchoolRivalrySixPanelsInRtlOrder() {
        assumeModelPresent()

        val bitmap = getBitmap("mihon/data/panel/school-rivalry-six-panels.png")
        val result = detectResult(
            key = "school-rivalry-six-panels",
            bitmap = bitmap,
            direction = ReadingDirection.RTL,
        )
        val panels = result.panels

        assertEquals(6, panels.size)

        assertRoughRegions(
            panels,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            listOf(
                ExpectedRegion(0.62f, 0.88f, 0.10f, 0.28f),
                ExpectedRegion(0.12f, 0.40f, 0.18f, 0.36f),
                ExpectedRegion(0.62f, 0.88f, 0.36f, 0.58f),
                ExpectedRegion(0.14f, 0.40f, 0.48f, 0.66f),
                ExpectedRegion(0.62f, 0.88f, 0.68f, 0.86f),
                ExpectedRegion(0.14f, 0.40f, 0.78f, 0.92f),
            ),
        )
    }

    @Test
    fun doesNotSpanCenterOnSyntheticDoublePageSpread() {
        assumeModelPresent()

        val left = getBitmap("mihon/data/panel/temple-entrance-five-panels.png")
        val right = getBitmap("mihon/data/panel/cliffside-path-four-panels.png")
        val spread = createBitmap(left.width + right.width, maxOf(left.height, right.height))
        Canvas(spread).apply {
            drawBitmap(left, 0f, 0f, null)
            drawBitmap(right, left.width.toFloat(), 0f, null)
        }

        val result = detectResult(
            key = "double-page-spread",
            bitmap = spread,
            direction = ReadingDirection.RTL,
        )
        val panels = result.panels

        assertTrue("Expected non-empty panel detection for synthetic spread", panels.isNotEmpty())
        val seamStart = spread.width * 0.48f
        val seamEnd = spread.width * 0.52f
        assertTrue(
            "No panel should heavily cross the central seam",
            panels.none { it.rect.left < seamStart && it.rect.right > seamEnd },
        )
    }

    @Test
    fun returnsEmptyForUnsupportedBlankPage() {
        assumeModelPresent()

        val blank = createBitmap(800, 1200)
        Canvas(blank).drawColor(Color.WHITE)
        val result = detectResult(
            key = "blank-page",
            bitmap = blank,
            direction = ReadingDirection.RTL,
        )
        val panels = result.panels

        assertTrue(panels.isEmpty())
    }

    @Test
    fun logsDetectionPerformance() {
        assumeModelPresent()

        val bitmap = getBitmap("mihon/data/panel/school-rivalry-six-panels.png")
        val samples = mutableListOf<Long>()

        runBlocking {
            repeat(10) { run ->
                val result = repository.detectPanels(
                    cacheKey = "perf-$run",
                    image = bitmap,
                    originalWidth = bitmap.width,
                    originalHeight = bitmap.height,
                    direction = ReadingDirection.RTL,
                )
                samples += result.totalMillis
                assertTrue("Expected panel detections during perf run", result.panels.isNotEmpty())
            }
        }

        println(
            "Panel detector perf ms: avg=${samples.average()} max=${samples.maxOrNull()} min=${samples.minOrNull()} samples=$samples",
        )
    }

    private fun detectResult(
        key: String,
        bitmap: Bitmap,
        direction: ReadingDirection,
    ): PanelDetectionResult {
        val result = runBlocking {
            repository.detectPanels(
                cacheKey = key,
                image = bitmap,
                originalWidth = bitmap.width,
                originalHeight = bitmap.height,
                direction = direction,
            )
        }
        return result
    }

    private fun assumeModelPresent() {
        assumeTrue(
            "Place data/src/main/assets/panel_detector/model.tflite before running panel detector tests",
            modelPresent(),
        )
    }

    private fun modelPresent(): Boolean {
        return runCatching {
            context.assets.open("panel_detector/model.tflite").use { true }
        }.getOrDefault(false)
    }

    private fun getBitmap(resourceName: String): Bitmap {
        val inputStream = javaClass.classLoader?.getResourceAsStream(resourceName)
        require(inputStream != null) { "Test image not found: $resourceName" }

        return inputStream.use {
            BitmapFactory.decodeStream(it)
        } ?: error("Bitmap could not be decoded from $resourceName")
    }

    private fun assertRoughRegions(
        panels: List<Panel>,
        pageWidth: Float,
        pageHeight: Float,
        expected: List<ExpectedRegion>,
    ) {
        assertEquals(expected.size, panels.size)
        expected.forEachIndexed { index, region ->
            assertTrue(
                "Panel $index center ${panels[index].rect.centerX()},${panels[index].rect.centerY()} was not in expected region $region",
                region.contains(panels[index], pageWidth, pageHeight),
            )
        }
    }

    private fun assertContainsPanel(
        panels: List<Panel>,
        bitmap: Bitmap,
        expected: ExpectedRegion,
    ) {
        assertTrue(
            "Did not find panel in expected region $expected. Actual centers: ${panels.map {
                it.rect.centerX() to it.rect.centerY()
            }}",
            panels.any { expected.contains(it, bitmap.width.toFloat(), bitmap.height.toFloat()) },
        )
    }

    private data class ExpectedRegion(
        val minXRatio: Float,
        val maxXRatio: Float,
        val minYRatio: Float,
        val maxYRatio: Float,
    ) {
        fun contains(
            panel: Panel,
            pageWidth: Float,
            pageHeight: Float,
        ): Boolean {
            val rect = panel.rect
            val xRatio = rect.centerX().toFloat() / pageWidth
            val yRatio = rect.centerY().toFloat() / pageHeight

            return xRatio in minXRatio..maxXRatio && yRatio in minYRatio..maxYRatio
        }
    }
}
