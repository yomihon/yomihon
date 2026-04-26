package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import eu.kanade.tachiyomi.data.ocr.openCroppedBitmap
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

interface ReaderSelectionBitmapSource {
    fun selectionPageInput(): OcrPageInput?
}

data class ReaderSelectionCapture(
    val page: ReaderPage,
    val sourceRect: Rect,
    val screenRect: RectF,
    val bitmapSource: ReaderSelectionBitmapSource? = null,
) {
    suspend fun decodeBitmap(): Bitmap? {
        return bitmapSource?.selectionPageInput()?.openCroppedBitmap(sourceRect)
    }
}

data class ReaderSelectionRegion(
    val screenRect: RectF,
)

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean

    /**
     * Applies the active OCR overlay to the visible page views.
     *
     * Returns true when the overlay is applied to a visible page or when [overlay] is null.
     */
    fun setActiveOcrOverlay(overlay: ReaderActiveOcrOverlay?): Boolean = overlay == null

    fun resolveSelectionCaptures(region: ReaderSelectionRegion): List<ReaderSelectionCapture> = emptyList()
}
