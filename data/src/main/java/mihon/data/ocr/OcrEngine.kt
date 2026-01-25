package mihon.data.ocr

import android.graphics.Bitmap

/**
 * Interface for OCR engine implementations.
 * Each engine handles its own model initialization, preprocessing, and inference.
 */
internal interface OcrEngine {
    /**
     * Recognizes text from the given bitmap image.
     * @param image The input bitmap to process
     * @return The recognized text
     */
    suspend fun recognizeText(image: Bitmap): String

    /**
     * Releases all resources held by this engine.
     */
    fun close()
}
