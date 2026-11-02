package schedule.app

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
import schedule.android.*
import schedule.core.scheduleJson
import schedule.study.*

class LibraryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun archiveOpenDeleteAndSummaryLibrarySurviveRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatFile = context.filesDir.resolve("schedule/chat.json")
        val libraryFile = context.filesDir.resolve("schedule/library.json")
        val originalChat = chatFile.takeIf { it.exists() }?.readBytes()
        val originalLibrary = libraryFile.takeIf { it.exists() }?.readBytes()
        val prefs = context.getSharedPreferences("chat_history", 0)
        val active = prefs.getString("active", null)
        val vault = CredentialVault(context); val account = vault.get("school.account")
        try {
            vault.put("school.account", null)
            chatFile.parentFile!!.mkdirs()
            chatFile.writeText(scheduleJson.encodeToString(listOf(ChatEntry("user", "保留这段旧聊天"), ChatEntry("assistant", "**历史回答**"))), Charsets.UTF_8)
            libraryFile.writeText(scheduleJson.encodeToString(StudyLibrary()), Charsets.UTF_8)
            val store = LibraryStore(libraryFile.toPath())
            store.saveSummary(SavedSummary(courseName = "测试物理", lessonStart = 1793577600000L, sections = listOf(SummarySection("智云讲次fixture 10秒", "# 测试总结\n\n**重要内容**"))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: AppModel
                scenario.onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
                compose.waitUntil(5000) { !model.chatBusy }
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.onNodeWithText("新对话").performClick()
                compose.waitUntil(5000) { !model.chatBusy && model.chat.isEmpty() && model.conversations.size == 1 }
                compose.onNodeWithText("历史").performClick()
                compose.onNodeWithText("保留这段旧聊天").performClick()
                compose.waitUntil(5000) { !model.chatBusy && model.chat.size == 2 }
                compose.onNodeWithContentDescription("关闭").performClick()
                scenario.recreate()
                scenario.onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
                compose.waitUntil(5000) { !model.chatBusy }
                assertEquals("保留这段旧聊天", model.chat.first().text)
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.onNodeWithText("历史").performClick()
                compose.onNodeWithText("删除当前").performClick()
                compose.waitUntil(5000) { !model.chatBusy && model.chat.isEmpty() }
                assertTrue(store.read().conversations.isEmpty())
                compose.onNodeWithContentDescription("关闭").performClick()
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("资料").performScrollTo().performClick()
                compose.onNodeWithText("总结").performClick()
                compose.onNodeWithText("测试物理").performClick()
                compose.onNodeWithText("智云讲次fixture 10秒").assertIsDisplayed()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("summary-library.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
                compose.onNodeWithText("删除总结").performClick()
                compose.waitUntil(5000) { model.summaries.isEmpty() }
                assertTrue(store.read().summaries.isEmpty())
            }
        } finally {
            vault.put("school.account", account)
            if (originalChat == null) chatFile.delete() else chatFile.writeBytes(originalChat)
            if (originalLibrary == null) libraryFile.delete() else libraryFile.writeBytes(originalLibrary)
            prefs.edit().putString("active", active).commit()
        }
    }
}
