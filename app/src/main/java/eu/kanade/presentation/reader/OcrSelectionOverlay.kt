package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun OcrSelectionOverlay(
    onRegionSelected: (RectF) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var startPoint by remember { mutableStateOf<Offset?>(null) }
    var endPoint by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onCancel() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startPoint = offset
                        endPoint = offset
                    },
                    onDrag = { change, _ ->
                        endPoint = change.position
                    },
                    onDragEnd = {
                        val start = startPoint
                        val end = endPoint
                        if (start != null && end != null) {
                            val left = min(start.x, end.x)
                            val top = min(start.y, end.y)
                            val right = max(start.x, end.x)
                            val bottom = max(start.y, end.y)

                            // Only trigger if the selection is large enough (at least 20x20 pixels)
                            if (abs(right - left) > 20 && abs(bottom - top) > 20) {
                                val rect = RectF(left, top, right, bottom)
                                onRegionSelected(rect)
                            } else {
                                onCancel()
                            }
                        } else {
                            onCancel()
                        }
                    },
                    onDragCancel = {
                        onCancel()
                    },
                )
            },
    ) {
        // Draw the selection rectangle
        val start = startPoint
        val end = endPoint
        if (start != null && end != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = min(start.x, end.x)
                val top = min(start.y, end.y)
                val width = abs(end.x - start.x)
                val height = abs(end.y - start.y)

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
