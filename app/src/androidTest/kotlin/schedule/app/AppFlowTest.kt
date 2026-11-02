package schedule.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import schedule.android.ScheduleRuntime
import schedule.core.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.encodeToString

class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: ScheduleRuntime
    private val start = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    @Before fun prepare() {
        runtime = ScheduleRuntime(context)
        runtime.restoreBackup(scheduleJson.encodeToString(ScheduleState()))
        context.filesDir.resolve("schedule/chat.json").delete()
        runtime.saveItem(ScheduleItem("lecture", "复变函数", ItemKind.COURSE, startAt = start + 9 * 3600000, endAt = start + 10 * 3600000, location = "东一教学楼 302", teacher = "陈文清"))
        runtime.saveItem(ScheduleItem("daily", "签到与签退", ItemKind.DAILY, startAt = start, dailyTimes = listOf("08:00", "21:00"), reminders = listOf(Reminder(TimeAnchor.START))))
        val service = ScheduleService(FileStateStore(context.filesDir.resolve("schedule/state.json").toPath()))
        service.propose(service.snapshot().revision, listOf(Change("proposed", ScheduleItem("proposed", "待审批作业", deadlineAt = start + 20 * 3600000), "用户要求")), "新增作业")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10000) { compose.onAllNodesWithText("复变函数").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun close() { scenario.close() }
    @Test fun permissionGuideShowsSeparateStatusesAndSurvivesRecreation() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("权限引导").performScrollTo().performClick()
        compose.onNodeWithText("通知提醒").assertExists()
        compose.onNodeWithText("准时提醒").assertExists()
        compose.onNodeWithText("电池优化").performScrollTo().assertExists()
        compose.onNodeWithText("需手动检查").performScrollTo().assertExists()
        scenario.recreate()
        compose.onNodeWithText("权限引导").assertExists()
        screenshot("permission-guide")
        compose.onNodeWithContentDescription("返回").performScrollTo().performClick()
        compose.onNodeWithText("AI 接口").assertExists()
        assertEquals("时笺", context.applicationInfo.loadLabel(context.packageManager).toString())
    }
    @Test fun reminderDatesAndDeadlineCountersAreDistinct() {
        runtime.saveItem(ScheduleItem("reminder-today", "当天提醒", ItemKind.REMINDER, startAt = start + 18 * 3600000))
        runtime.saveItem(ScheduleItem("reminder-old", "昨天提醒", ItemKind.REMINDER, startAt = start - 3600000))
        runtime.saveItem(ScheduleItem("reminder-future", "明天提醒", ItemKind.REMINDER, startAt = start + 86400000))
        runtime.saveItem(ScheduleItem("deadline-old", "过去截止", deadlineAt = start - 86400000))
        runtime.saveItem(ScheduleItem("deadline-future", "未来截止", deadlineAt = start + 3 * 86400000))
        scenario.recreate()
        compose.onNodeWithText("事项").performClick()
        compose.onNodeWithText("当天提醒").performScrollTo().assertExists()
        compose.onNodeWithText("昨天提醒").assertDoesNotExist()
        compose.onNodeWithText("明天提醒").assertDoesNotExist()
        compose.onNodeWithText("过去截止").performScrollTo().assertExists()
        compose.onNodeWithText("已逾期", substring = true).assertExists()
        compose.onNodeWithText("未来截止").performScrollTo().assertExists()
        compose.onNodeWithText("距截止", substring = true).assertExists()
        screenshot("reminder-deadline")
        compose.onNodeWithText("创建").performClick()
        compose.onNodeWithText("截止时间").performClick()
        compose.onNodeWithText("名称").performTextInput("新截止事项")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { runtime.snapshot().items.any { it.title == "新截止事项" } }
        val item = runtime.snapshot().items.single { it.title == "新截止事项" }
        assertEquals(ItemKind.TASK, item.kind)
        assertNull(item.startAt)
        assertNotNull(item.deadlineAt)
    }
    @Test fun dateRollerReturnsTodayToCenterAfterIdle() {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        compose.onNodeWithTag("date-roller").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6500)
        compose.waitForIdle()
        val roller = compose.onNodeWithTag("date-roller").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("roller-date-$today").fetchSemanticsNode().boundsInRoot
        assertTrue("当天应在滚轮正中", kotlin.math.abs(roller.center.x - date.center.x) < 3f)
        screenshot("date-roller-centered")
    }
    @Test fun itemsEntryShowsAvailableTasksAndSeparateCreation() {
        runtime.saveItem(ScheduleItem("available", "今天可做", startAt = start + 22 * 3600000, deadlineAt = start + 3 * 86400000))
        runtime.saveItem(ScheduleItem("future", "明天才开始", startAt = start + 86400000, deadlineAt = start + 3 * 86400000))
        runtime.saveItem(ScheduleItem("overdue", "此前未完成", deadlineAt = start - 86400000))
        runtime.saveItem(ScheduleItem("no-start", "以后截止", deadlineAt = start + 5 * 86400000))
        scenario.recreate()
        compose.onNodeWithText("事项").performClick()
        compose.onNodeWithText("今天可做").assertExists()
        compose.onNodeWithText("明天才开始").assertDoesNotExist()
        compose.onNodeWithText("此前未完成").performScrollTo().assertExists()
        compose.onNodeWithText("以后截止").performScrollTo().assertExists()
        screenshot("items")
        compose.onNodeWithText("创建").performClick()
        compose.onNodeWithText("新建事项").assertExists()
    }
    private fun screenshot(name: String) {
        compose.waitUntil(10000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        // System-bar transitions are outside Compose's test clock.
        Thread.sleep(400)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
    @Test fun dayMonthTeacherAndDailyCompletion() {
        compose.onNodeWithText("东一教学楼 302 · 陈文清").assertExists()
        compose.onNodeWithText("周", useUnmergedTree = true).assertDoesNotExist()
        screenshot("day")
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5000) { runtime.snapshot().items.first { it.id == "daily" }.completedOccurrences.size == 1 }
        assertEquals(1, TimeEngine.occurrences(runtime.snapshot().items.first { it.id == "daily" }, start, start + 86400000).size)
        compose.onNodeWithText("月").performClick()
        screenshot("month")
        compose.onNodeWithTag("month-fold").performClick()
        compose.onNodeWithText("30").assertDoesNotExist()
        screenshot("month-folded")
        compose.onNodeWithContentDescription("下个月").performClick()
        compose.onNodeWithContentDescription("与助手对话").assertExists()
    }
    @Test fun manualCreationAndCourseEditPersist() {
        compose.onNodeWithText("事项").performClick()
        compose.onNodeWithText("创建").performClick()
        compose.onNodeWithText("每日多次").assertDoesNotExist()
        compose.onNodeWithText("起止时间").assertExists()
        compose.onNodeWithText("每天", substring = false).performClick()
        compose.onNodeWithText("名称").performTextInput("每日复习")
        scenario.recreate()
        compose.onNodeWithText("每日复习").assertExists()
        screenshot("editor")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { runtime.snapshot().items.any { it.title == "每日复习" } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("新建事项").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("日程").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("复变函数").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("复变函数").performClick()
        screenshot("details")
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.onNodeWithText("名称").performTextReplacement("复变函数（调课）")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5000) { runtime.snapshot().items.any { it.title == "复变函数（调课）" } }
    }
    @Test fun proposalApprovalAndGlobalChatAndTheme() {
        compose.onNodeWithContentDescription("与助手对话").performClick()
        compose.onNodeWithText("新增作业").performScrollTo().performClick()
        compose.onNodeWithText("批准").performClick()
        compose.waitUntil(5000) { runtime.snapshot().items.any { it.id == "proposed" } }
        compose.onNodeWithText("发消息").performTextInput("你好")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(10000) {
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = node != null &&
                ((node.isVisibleToUser && node.text?.toString() == "请求失败：请先配置AI接口") || (0 until node.childCount).any { contains(node.getChild(it)) })
            contains(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        }
        screenshot("chat")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil(5000) { runtime.snapshot().settings.theme == ThemePreference.DARK }
        compose.waitForIdle()
        scenario.onActivity { assertFalse(WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightNavigationBars) }
        screenshot("settings-dark")
        compose.onNodeWithContentDescription("与助手对话").assertExists()
    }
}
