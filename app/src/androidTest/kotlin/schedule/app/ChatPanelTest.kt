package schedule.app

import io.noties.markwon.core.spans.StrongEmphasisSpan
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import schedule.ai.*
import schedule.android.*
import schedule.core.scheduleJson

class ChatPanelTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun markdownRendersStructureWithoutLiteralDelimiters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rendered = chatMarkdown(context).toMarkdown("# 标题\n\n**粗体**与`代码`\n\n- 第一项\n- 第二项\n\n> 引用\n\n```kotlin\nval x = 1\n```")
        assertTrue(rendered.toString().contains("标题"))
        assertFalse(rendered.toString().contains("**"))
        assertFalse(rendered.toString().contains("```"))
        assertTrue(rendered.getSpans(0, rendered.length, StrongEmphasisSpan::class.java).isNotEmpty())
    }

    @Test fun switchModelAndScrollWithoutMovingChatHeader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val savedProfiles = vault.get("ai.profiles")
        val savedLegacy = vault.get("ai.configuration")
        val file = context.filesDir.resolve("schedule/chat.json")
        val savedChat = file.takeIf { it.exists() }?.readBytes()
        try {
            vault.put("ai.profiles", null); vault.put("ai.configuration", null)
            ScheduleRuntime(context).configureAi(AiConfiguration("https://fixture.invalid/v1", "first", false, listOf(AiModel("second"))), "fixture-key")
            file.parentFile!!.mkdirs()
            val history = (1..30).map { ChatEntry("assistant", "## 消息 $it\n\n**重点**：阅读历史消息时保持位置。\n\n- 第一项\n- 第二项\n\n```kotlin\nval lesson = $it\n```") }
            file.writeText(scheduleJson.encodeToString(history), Charsets.UTF_8)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("chat_model").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chat_model").assertTextEquals("first").performClick()
                compose.onNodeWithText("second").performClick()
                compose.onNodeWithTag("chat_model").assertTextEquals("second")
                val before = compose.onNodeWithTag("chat_model").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
                compose.onNodeWithTag("chat_messages").performTouchInput { swipeUp() }
                compose.waitForIdle()
                assertEquals(before, compose.onNodeWithTag("chat_model").fetchSemanticsNode().boundsInRoot)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("chat-markdown.png").outputStream().use { stream -> screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
                screenshot.recycle()
            }
        } finally {
            vault.put("ai.profiles", savedProfiles); vault.put("ai.configuration", savedLegacy)
            if (savedChat == null) file.delete() else file.writeBytes(savedChat)
        }
    }

    @Test fun copyAndRetryRestoredImageMessageRequiresAttachments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.filesDir.resolve("schedule/chat.json")
        val savedChat = file.takeIf { it.exists() }?.readBytes()
        try {
            file.parentFile!!.mkdirs()
            file.writeText(scheduleJson.encodeToString(listOf(
                ChatEntry("user", "分析图片\n[附图 1 张]"),
                ChatEntry("assistant", "请求失败：测试", failed = true)
            )), Charsets.UTF_8)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("chat_retry").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chat_copy_1").assertHasClickAction().performClick()
                compose.onNodeWithTag("chat_retry").assertTextEquals("重试").performClick()
                compose.onNodeWithText("图片已不在内存中，请重新添加图片后发送。").assertIsDisplayed()
                compose.onNodeWithTag("chat_copy_0").assertHasClickAction()
                compose.onAllNodesWithTag("chat_retry").assertCountEquals(1)
            }
        } finally {
            if (savedChat == null) file.delete() else file.writeBytes(savedChat)
        }
    }
}
