package mihon.domain.panel.interactor

import android.graphics.Bitmap
import mihon.domain.panel.model.PanelDetectionResult
import mihon.domain.panel.repository.PanelDetectionRepository
import tachiyomi.core.common.util.system.ReadingDirection

class DetectPanels(
    private val panelDetectionRepository: PanelDetectionRepository,
) {
    suspend fun await(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult {
        return panelDetectionRepository.detectPanels(
            cacheKey = cacheKey,
            image = image,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            direction = direction,
        )
    }
}
