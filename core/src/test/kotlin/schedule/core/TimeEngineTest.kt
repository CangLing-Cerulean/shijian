package schedule.core

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class TimeEngineTest {
    private fun time(s: String) = Instant.parse(s).toEpochMilli()
    @Test fun deadlineWindowDoesNotBlockCalendarAndUnknownDurationStaysUnknown() {
        val start = time("2026-11-01T00:00:00Z")
        val task = ScheduleItem("t", "任务", startAt = start, deadlineAt = start + 3 * 86400000)
        val event = ScheduleItem("e", "活动", ItemKind.EVENT, startAt = start + 3600000, endAt = start + 7200000)
        assertNull(TimeEngine.occurrences(task, start, start + 4 * 86400000).single().endAt)
        assertTrue(TimeEngine.conflicts(listOf(task, event), start, start + 4 * 86400000).isEmpty())
        assertEquals(1, TimeEngine.conflicts(listOf(task.copy(estimatedMinutes = 120), event), start, start + 4 * 86400000).size)
    }
    @Test fun recurringLocalTimeSurvivesDstAndSingleOccurrenceCanMoveOrCancel() {
        val start = time("2026-10-31T13:00:00Z") // 09:00 New York before DST ends
        val second = time("2026-11-01T14:00:00Z")
        val event = ScheduleItem("e", "活动", ItemKind.EVENT, startAt = start, endAt = start + 3600000, zoneId = "America/New_York", repeat = RepeatRule(RepeatFrequency.DAILY, until = time("2026-11-02T14:00:00Z")))
        val occurrences = TimeEngine.occurrences(event, start, start + 5 * 86400000)
        assertEquals(second, occurrences[1].startAt)
        val changed = event.copy(exceptions = mapOf(start to OccurrenceEdit(cancelled = true), second to OccurrenceEdit(startAt = second + 3600000, endAt = second + 7200000)))
        val edited = TimeEngine.occurrences(changed, start, start + 5 * 86400000)
        assertEquals(2, edited.size); assertEquals(second + 3600000, edited.first().startAt)
    }
    @Test fun remindersAreFutureOnlyUniqueAndCompletionCancelsThem() {
        val now = time("2026-11-01T00:00:00Z")
        val item = ScheduleItem("t", "任务", startAt = now + 3600000, deadlineAt = now + 7200000, reminders = listOf(Reminder(TimeAnchor.START, 5), Reminder(TimeAnchor.DEADLINE, 10)))
        val alarms = TimeEngine.reminders(listOf(item), now, now + 86400000)
        assertEquals(2, alarms.size); assertEquals(2, alarms.map { it.id }.distinct().size)
        assertEquals(now + 3300000, alarms.first().triggerAt)
        assertTrue(TimeEngine.reminders(listOf(item.copy(status = ItemStatus.DONE)), now, now + 86400000).isEmpty())
        assertTrue(TimeEngine.reminders(listOf(item), now + 7200000, now + 86400000).isEmpty())
    }
    @Test fun monthlyDay31SkipsShortMonths() {
        val start = time("2026-01-31T01:00:00Z")
        val item = ScheduleItem("m", "月度", ItemKind.EVENT, startAt = start, endAt = start + 1000, repeat = RepeatRule(RepeatFrequency.MONTHLY))
        val occurrences = TimeEngine.occurrences(item, start, time("2026-05-01T00:00:00Z"))
        assertEquals(2, occurrences.size)
        assertEquals(time("2026-03-31T01:00:00Z"), occurrences.last().startAt)
    }
    @Test fun oldSeriesAndDistantExceptionsDoNotBreakShortQueries() {
        val start = time("1990-01-01T01:00:00Z")
        val from = time("2026-11-01T00:00:00Z")
        val item = ScheduleItem("old", "每天", ItemKind.EVENT, startAt = start, endAt = start + 3600000, repeat = RepeatRule(RepeatFrequency.DAILY))
        assertEquals(1, TimeEngine.occurrences(item, from, from + 86400000).size)
        val distant = start + 30000L * 86400000
        assertEquals(1, TimeEngine.occurrences(item.copy(exceptions = mapOf(distant to OccurrenceEdit(cancelled = true))), from, from + 86400000).size)
    }
}
