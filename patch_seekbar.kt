import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun CustomSeekBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }
    val activeProgress = if (isDragging) dragProgress else progress
    
    val thumbRadius by animateDpAsState(targetValue = if (isDragging) 6.dp else 0.dp)
    val trackHeight by animateDpAsState(targetValue = if (isDragging) 4.dp else 2.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(dragProgress)
                    },
                    onDragEnd = {
                        isDragging = false
                        onProgressChangeFinished()
                    },
                    onDragCancel = {
                        isDragging = false
                        onProgressChangeFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(dragProgress)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(dragProgress)
                        if (tryAwaitRelease()) {
                            isDragging = false
                            onProgressChangeFinished()
                        }
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val width = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .background(Color.White.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(activeProgress.coerceIn(0f, 1f))
                .height(trackHeight)
                .background(VibeTubeRed)
        )
        if (thumbRadius > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = (width * activeProgress.coerceIn(0f, 1f)) - thumbRadius)
                    .size(thumbRadius * 2)
                    .background(VibeTubeRed, CircleShape)
            )
        }
    }
}
