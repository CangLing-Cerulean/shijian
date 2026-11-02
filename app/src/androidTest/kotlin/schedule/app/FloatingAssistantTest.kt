package schedule.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingAssistantTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragDoesNotClickAndPositionStaysInsideResizedArea() {
        var clicks = 0
        val width = mutableStateOf(300.dp)
        val height = mutableStateOf(430.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width.value, height.value).testTag("assistant-area")) {
                    DraggableAssistant { clicks++ }
                }
            }
        }
        val robot = compose.onNodeWithContentDescription("与助手对话")
        val area = compose.onNodeWithTag("assistant-area")
        val initial = robot.fetchSemanticsNode().boundsInRoot
        robot.performTouchInput { swipe(center, center + Offset(-100f, -150f), 400) }
        val moved = robot.fetchSemanticsNode().boundsInRoot
        assertTrue(moved.left < initial.left - 30f)
        assertTrue(moved.top < initial.top - 30f)
        assertEquals(0, clicks)
        robot.performClick()
        assertEquals(1, clicks)

        val topLeft = area.fetchSemanticsNode().boundsInRoot.topLeft - moved.topLeft + Offset(1f, 1f)
        robot.performTouchInput { swipe(center, topLeft, 400) }
        var bounds = robot.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= area.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(bounds.top >= area.fetchSemanticsNode().boundsInRoot.top)
        assertEquals(1, clicks)

        val bottomRight = area.fetchSemanticsNode().boundsInRoot.bottomRight - bounds.topLeft - Offset(1f, 1f)
        robot.performTouchInput { swipe(center, bottomRight, 400) }
        compose.runOnIdle { width.value = 200.dp; height.value = 280.dp }
        bounds = robot.fetchSemanticsNode().boundsInRoot
        val smaller = area.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.right <= smaller.right && bounds.bottom <= smaller.bottom)
        assertTrue(bounds.left >= smaller.left && bounds.top >= smaller.top)
        robot.performClick()
        assertEquals(2, clicks)
    }
}
