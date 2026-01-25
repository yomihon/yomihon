package mihon.domain.ocr.repository

import android.graphics.Bitmap

interface OcrRepository {
    suspend fun recognizeText(image: Bitmap): String

    /**
     * Cleanup and release all OCR resources, which can take up lots of RAM.
     * Used for memory management when system is under pressure.
     */
    fun cleanup()
}
