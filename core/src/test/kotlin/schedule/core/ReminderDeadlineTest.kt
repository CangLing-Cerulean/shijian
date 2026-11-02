package schedule.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderDeadlineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 2)
    private val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val day = 86400000L

    @Test fun remindersAppearOnlyOnTheirActualDateWithoutDeletingHistory() {
        val reminder = ScheduleItem("reminder", "提醒", ItemKind.REMINDER, startAt = today + 8 * 3600000,
            reminders = listOf(Reminder(TimeAnchor.START)))
        reminder.validate()
        assertTrue(pendingItems(listOf(reminder), date.minusDays(1), zone).isEmpty())
        assertEquals(1, pendingItems(listOf(reminder), date, zone).size)
        assertTrue(pendingItems(listOf(reminder), date.plusDays(1), zone).isEmpty())
        assertEquals(1, TimeEngine.occurrences(reminder, today, today + day).size)
        assertEquals(1, TimeEngine.reminders(listOf(reminder), today, today + day).size)
    }

    @Test fun recurringRemindersDoNotAccumulatePastDays() {
        val reminder = ScheduleItem("reminder", "每天提醒", ItemKind.REMINDER, startAt = today - 7 * day,
            repeat = RepeatRule(RepeatFrequency.DAILY))
        assertEquals(listOf(today), pendingItems(listOf(reminder), date, zone).map { it.startAt })
        assertTrue(pendingItems(listOf(reminder.copy(completedOccurrences = setOf(today))), date, zone).isEmpty())
    }

    @Test fun deadlinesStayBeforeAndAfterDueDateUntilCompleted() {
        val task = ScheduleItem("deadline", "截止", deadlineAt = today + 2 * day)
        assertEquals(1, pendingItems(listOf(task), date, zone).size)
        assertEquals(1, pendingItems(listOf(task), date.plusDays(5), zone).size)
        assertTrue(pendingItems(listOf(task.copy(status = ItemStatus.DONE)), date.plusDays(5), zone).isEmpty())
    }

    @Test fun nextRepeatingDeadlineIsVisibleBeforeItsDate() {
        val task = ScheduleItem("deadline", "每周截止", deadlineAt = today + 2 * day,
            repeat = RepeatRule(RepeatFrequency.WEEKLY))
        assertEquals(listOf(today + 2 * day), pendingItems(listOf(task), date, zone).map { it.deadlineAt })
        val later = pendingItems(listOf(task), date.plusDays(3), zone)
        assertEquals(listOf(today + 2 * day, today + 9 * day), later.map { it.deadlineAt })
    }

    @Test fun deadlineClockDistinguishesRemainingAndOverdueDurations() {
        assertEquals("距截止2天 3小时 4分钟", deadlineStatus(today + (2 * 1440 + 3 * 60 + 4) * 60000L, today))
        assertEquals("已逾期1小时 2分钟", deadlineStatus(today, today + 62 * 60000))
        assertEquals("距截止不足1分钟", deadlineStatus(today + 1, today))
        assertEquals("已到截止时间", deadlineStatus(today, today))
        assertEquals("已逾期不足1分钟", deadlineStatus(today, today + 1))
    }
}
