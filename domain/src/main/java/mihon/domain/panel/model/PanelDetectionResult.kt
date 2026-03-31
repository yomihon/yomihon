package mihon.domain.panel.model

import tachiyomi.core.common.util.system.Panel

data class PanelDetectionResult(
    val panels: List<Panel>,
    val preprocessMillis: Long = 0,
    val inferenceMillis: Long = 0,
    val totalMillis: Long = 0,
    val cacheHit: Boolean = false,
) {
    companion object {
        val EMPTY = PanelDetectionResult(emptyList())
    }
}
