package app.simplenarrator.audiobooks.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Schlanke Positionsleiste: dünne Spur, runder Griff, der beim Ziehen wächst.
 * [onChange] meldet die Position während des Ziehens, [onCommit] beim Loslassen.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val thumb by animateDpAsState(if (dragging) 22.dp else 15.dp, label = "griff")
    val shown = (if (dragging) dragValue else fraction).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                description?.let { contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { v ->
                    commit(v.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos -> commit((pos.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        dragging = true
                        dragValue = (pos.x / size.width).coerceIn(0f, 1f)
                        change(dragValue)
                    },
                    onDragEnd = {
                        commit(dragValue)
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                ) { pointer, delta ->
                    pointer.consume()
                    dragValue = (dragValue + delta / size.width).coerceIn(0f, 1f)
                    change(dragValue)
                }
            },
    ) {
        val h = 5.dp.toPx()
        val r = thumb.toPx() / 2
        val left = 11.dp.toPx().coerceAtMost(size.width / 2)
        val width = size.width - 2 * left
        val y = size.height / 2
        drawRoundRect(trackColor, Offset(left, y - h / 2), Size(width, h), CornerRadius(h / 2))
        drawRoundRect(color, Offset(left, y - h / 2), Size(width * shown, h), CornerRadius(h / 2))
        drawCircle(color, r, Offset(left + width * shown, y))
    }
}
