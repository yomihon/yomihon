package mihon.domain.panel.model

import android.graphics.Rect
import tachiyomi.core.common.util.system.Panel

data class PanelDetectionResult(
    val panels: List<Panel>,
    val debugPanels: List<DebugPanelDetection> = emptyList(),
    val debugBubbles: List<DebugPanelDetection> = emptyList(),
    val preprocessMillis: Long = 0,
    val inferenceMillis: Long = 0,
    val totalMillis: Long = 0,
    val cacheHit: Boolean = false,
) {
    companion object {
        val EMPTY = PanelDetectionResult(emptyList())
    }
}

data class DebugPanelDetection(
    val rect: Rect,
    val confidence: Float,
)
