package schedule.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Fractions keep the chosen position inside the usable area when its size changes. */
@Composable fun DraggableAssistant(onClick: () -> Unit) {
    var horizontal by rememberSaveable { mutableFloatStateOf(1f) }
    var vertical by rememberSaveable { mutableFloatStateOf(1f) }
    val buttonSize = 48.dp
    val margin = 16.dp
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density = LocalDensity.current
        val travelX = with(density) { (maxWidth - buttonSize - margin * 2).coerceAtLeast(0.dp).toPx() }
        val travelY = with(density) { (maxHeight - buttonSize - margin * 2).coerceAtLeast(0.dp).toPx() }
        val insetX = with(density) { minOf(margin, ((maxWidth - buttonSize) / 2).coerceAtLeast(0.dp)).toPx() }
        val insetY = with(density) { minOf(margin, ((maxHeight - buttonSize) / 2).coerceAtLeast(0.dp)).toPx() }
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.absoluteOffset {
                IntOffset((insetX + horizontal * travelX).roundToInt(), (insetY + vertical * travelY).roundToInt())
            }.size(buttonSize).pointerInput(travelX, travelY) {
                // Touch slop distinguishes a tap from a drag, cancelling the button's click on drag.
                detectDragGestures { change, movement ->
                    change.consume()
                    if (travelX > 0) horizontal = ((horizontal * travelX + movement.x) / travelX).coerceIn(0f, 1f)
                    if (travelY > 0) vertical = ((vertical * travelY + movement.y) / travelY).coerceIn(0f, 1f)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
        ) { Glyph(R.drawable.ic_bot, "与助手对话", Modifier.size(26.dp)) }
    }
}
