package eu.kanade.presentation.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.min

@Composable
fun OcrSelectionOverlay(
    onCancel: () -> Unit,
    startPoint: Offset?,
    endPoint: Offset?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onCancel() },
                )
            },
    ) {
        // Draw the selection rectangle
        if (startPoint != null && endPoint != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = min(startPoint.x, endPoint.x)
                val top = min(startPoint.y, endPoint.y)
                val width = abs(endPoint.x - startPoint.x)
                val height = abs(endPoint.y - startPoint.y)

                // Draw selection rectangle
                drawRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                )

                // Draw border
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 3f),
                )
            }
        }

        // Instruction text
        if (startPoint == null) {
            Text(
                text = stringResource(MR.strings.ocr_select_region),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
            )
        }
    }
}
