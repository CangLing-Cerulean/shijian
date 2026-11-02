package schedule.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import schedule.core.*
import java.time.OffsetDateTime

class RepeatEditorTest {
    @get:Rule val compose = createComposeRule()
    @Test fun endRemindersRoundTripSeparatelyFromStartReminders() {
        val start = OffsetDateTime.parse("2026-11-02T09:00:00+08:00").toInstant().toEpochMilli()
        var saved: ScheduleItem? = null
        val initial = ScheduleItem(title = "起止提醒", kind = ItemKind.EVENT, startAt = start, endAt = start + 3 * 3600000,
            reminders = listOf(Reminder(TimeAnchor.START, 5), Reminder(TimeAnchor.END, 10)))
        compose.setContent { MaterialTheme { ItemEditor(initial, false, {}, { saved = it }, { throw AssertionError(it) }) } }
        compose.onNodeWithText("结束前提醒（分钟）").performScrollTo().performTextReplacement("15, 0")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Reminder(TimeAnchor.START, 5), Reminder(TimeAnchor.END, 15), Reminder(TimeAnchor.END, 0)), saved!!.reminders)
            assertEquals(listOf(start - 5 * 60000, start + 3 * 3600000 - 15 * 60000, start + 3 * 3600000),
                TimeEngine.reminders(listOf(saved!!), start - 3600000, start + 4 * 3600000).map { it.triggerAt })
        }
    }
    @Test fun editorSavesWeekendTimeWindowAndReminder() {
        val start = OffsetDateTime.parse("2026-11-02T09:00:00+08:00").toInstant().toEpochMilli()
        var saved: ScheduleItem? = null
        val initial = ScheduleItem(title = "周末打卡", kind = ItemKind.EVENT, startAt = start, endAt = start + 3 * 3600000, reminders = listOf(Reminder(TimeAnchor.START,10)))
        compose.setContent { MaterialTheme { ItemEditor(initial, false, {}, { saved = it }, { throw AssertionError(it) }) } }
        compose.onNodeWithText("每周").performScrollTo().performClick()
        compose.onNodeWithText("重复次数（留空不限）").assertDoesNotExist()
        compose.onNodeWithText("六").performScrollTo().performClick()
        compose.onNodeWithText("日").performScrollTo().performClick()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(setOf(6,7), saved!!.repeat!!.weekdays)
            assertEquals(listOf(Reminder(TimeAnchor.START,10)), saved!!.reminders)
            assertEquals(2, TimeEngine.occurrences(saved!!, start, start + 7 * 86400000L).size)
        }
    }
}
