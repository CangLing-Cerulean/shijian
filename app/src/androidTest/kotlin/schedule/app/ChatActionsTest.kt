package schedule.app

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import schedule.ai.ChatEntry
import schedule.android.CredentialVault
import schedule.core.scheduleJson

class ChatActionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun regenerateThenRetryReplacesOneReplyAndCopyUsesOriginalText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val profiles = vault.get("ai.profiles"); val legacy = vault.get("ai.configuration"); val account = vault.get("school.account")
        val file = context.filesDir.resolve("schedule/chat.json")
        val original = file.takeIf { it.exists() }?.readBytes()
        try {
            vault.put("ai.profiles", null); vault.put("ai.configuration", null); vault.put("school.account", null)
            file.parentFile!!.mkdirs()
            file.writeText(scheduleJson.encodeToString(listOf(ChatEntry("user", "只保留一条提问"), ChatEntry("assistant", "**原始回复**"))), Charsets.UTF_8)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: AppModel
                scenario.onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.onNodeWithText("重新生成").performScrollTo().performClick()
                compose.waitUntil(5000) { !model.agentRunning && model.chat.lastOrNull()?.failed == true }
                assertEquals(2, model.chat.size)
                assertEquals("只保留一条提问", model.chat.first().text)
                compose.onNodeWithText("重试").performScrollTo().performClick()
                compose.waitUntil(5000) { !model.agentRunning && model.chat.size == 2 && model.chat.last().failed }
                compose.onNodeWithTag("chat_copy_1").performScrollTo().performClick()
                compose.runOnIdle {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    assertEquals(model.chat.last().text, clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
                assertEquals(1, model.chat.count { it.role == "user" })
                compose.waitUntil(5000) { file.readText(Charsets.UTF_8).contains("请求失败") }
            }
        } finally {
            vault.put("ai.profiles", profiles); vault.put("ai.configuration", legacy); vault.put("school.account", account)
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }
}
