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
import schedule.core.*
import schedule.school.CourseMaterial
import schedule.study.*
import java.time.LocalDate
import java.time.ZoneId

class LessonMaterialsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun notesLessonCategoriesAndChatSummaryLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf("state.json", "chat.json", "library.json").associateWith { context.filesDir.resolve("schedule/$it") }
        val originals = files.mapValues { it.value.takeIf { f -> f.exists() }?.readBytes() }
        val vault = CredentialVault(context); val account = vault.get("school.account")
        val start = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() + 9 * 3600000L
        val course = ScheduleItem(id = "lesson-test", title = "当堂物理", kind = ItemKind.COURSE, startAt = start, endAt = start + 3600000,
            teacher = "测试教师", notes = "来源：教务\n教学班：fixture\n保留自填备注")
        val summary = SavedSummary(id = "summary-link", courseName = course.title, lessonStart = start, lessonEnd = course.endAt, sections = listOf(SummarySection("当堂讲次10秒", "# 当堂总结\n\n**核心知识**")))
        try {
            vault.put("school.account", null)
            files.values.first().parentFile!!.mkdirs()
            files.getValue("state.json").writeText(scheduleJson.encodeToString(ScheduleState(items = listOf(course))), Charsets.UTF_8)
            files.getValue("chat.json").writeText(scheduleJson.encodeToString(listOf(ChatEntry("user", "总结本次课"), ChatEntry("assistant", "已保存总结", summaryIds = listOf(summary.id)))), Charsets.UTF_8)
            files.getValue("library.json").writeText(scheduleJson.encodeToString(StudyLibrary(summaries = listOf(summary, summary.copy(id = "other-lesson", lessonStart = start - 86400000)),
                lessonMaterials = listOf(LessonMaterial(course.id, "lms-fixture", course.title, CourseMaterial("activity", 1, null, "当堂课件.pdf", 1))))), Charsets.UTF_8)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: AppModel
                scenario.onActivity { model = ViewModelProvider(it)[AppModel::class.java] }
                compose.waitUntil(5000) { model.state != null && !model.chatBusy }
                compose.onNodeWithText(course.title).performClick()
                compose.onNodeWithText("保留自填备注").assertExists()
                compose.onNodeWithText("来源：", substring = true).assertDoesNotExist()
                compose.onNodeWithText("教学班：", substring = true).assertDoesNotExist()
                compose.onNodeWithText("编辑").performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("新的课堂备注")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(5000) { model.state?.items?.single()?.notes == "新的课堂备注" }
                compose.onNodeWithText("AI填写").performScrollTo().performClick()
                compose.onNodeWithText("请根据当前事项及已有资料帮我填写备注；信息不够时先询问，仅修改备注并提交待审批草案。").assertExists()
                compose.onNodeWithContentDescription("关闭").performClick()
                compose.onNodeWithText("资料").performScrollTo().performClick()
                compose.onNodeWithText("原始资料").assertIsDisplayed()
                compose.onNodeWithText("当堂课件.pdf").assertIsDisplayed()
                compose.onNodeWithText("课堂转录").performClick()
                compose.onNodeWithText("智云课堂").assertIsDisplayed()
                compose.waitUntil(5000) { compose.onAllNodesWithText("请配置学校账号").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText("生成总结").performClick()
                compose.onNodeWithText(dateTimeText(start - 86400000, "Asia/Shanghai")).assertDoesNotExist()
                val image = instrumentation.uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("lesson-materials.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                compose.onNodeWithContentDescription("与助手对话").performClick()
                compose.onNodeWithTag("summary_open_${summary.id}").performScrollTo().performClick()
                compose.onNodeWithText("当堂讲次10秒").assertIsDisplayed()
                val reader = instrumentation.uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("chat-summary-link.png").outputStream().use { reader.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; reader.recycle()
                compose.onNodeWithText("删除总结").performClick()
                compose.waitUntil(5000) { model.summaries.none { it.id == summary.id } }
                compose.onNodeWithTag("summary_open_${summary.id}").performScrollTo().performClick()
                compose.waitUntil(5000) { model.error == "这份总结已删除" || compose.onAllNodesWithText("这份总结已删除").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            vault.put("school.account", account)
            files.forEach { (name, file) -> originals[name]?.let { file.writeBytes(it) } ?: file.delete() }
        }
    }
}
