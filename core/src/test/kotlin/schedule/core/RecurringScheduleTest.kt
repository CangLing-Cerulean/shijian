package schedule.core

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime
import kotlinx.serialization.json.*
import schedule.ai.AiDraftParser

class RecurringScheduleTest {
    private fun at(day: String, time: String = "00:00") = OffsetDateTime.parse("${day}T$time:00+08:00").toInstant().toEpochMilli()
    private fun event(start: String, end: String, rule: RepeatRule) = ScheduleItem(title = "打卡", kind = ItemKind.EVENT,
        startAt = at("2026-11-02", start), endAt = at("2026-11-02", end), repeat = rule, reminders = listOf(Reminder(TimeAnchor.START, 10)))
    @Test fun dailyEveningsAndWeekendWindowsHaveIndependentCompletionsAndReminders() {
        val evening = event("18:30", "22:30", RepeatRule(RepeatFrequency.DAILY))
        val morning = event("09:00", "12:00", RepeatRule(RepeatFrequency.WEEKLY, weekdays = setOf(6,7)))
        val afternoon = event("12:00", "17:00", RepeatRule(RepeatFrequency.WEEKLY, weekdays = setOf(6,7)))
        val from = at("2026-11-02"); val until = at("2026-11-09")
        assertEquals(7, TimeEngine.occurrences(evening, from, until).size)
        val weekends = TimeEngine.occurrences(morning, from, until)
        assertEquals(listOf(at("2026-11-07", "09:00"), at("2026-11-08", "09:00")), weekends.map { it.startAt })
        assertEquals(11, TimeEngine.reminders(listOf(evening, morning, afternoon), from, until).size)
        assertTrue(TimeEngine.reminders(listOf(morning), from, until).any { it.triggerAt == at("2026-11-07", "08:50") })
        val service = ScheduleService(MemoryStateStore(ScheduleState(items = listOf(morning))))
        service.completeByUser(morning.id, weekends.first().originalStart)
        assertEquals(1, TimeEngine.occurrences(service.snapshot().items.single(), from, until).size)
    }
    @Test fun alternateWeeksAndUntilRespectSelectedDays() {
        val item = event("09:00", "12:00", RepeatRule(RepeatFrequency.WEEKLY, interval = 2, until = at("2026-11-21", "09:00"), weekdays = setOf(6,7)))
        assertEquals(listOf("2026-11-07", "2026-11-08", "2026-11-21").map { at(it, "09:00") }, TimeEngine.occurrences(item, at("2026-11-02"), at("2027-01-01")).map { it.startAt })
        assertEquals(1, TimeEngine.occurrences(item.copy(repeat = item.repeat!!.copy(until = at("2026-11-07", "09:00"))), at("2026-11-02"), at("2026-12-01")).size)
    }
    @Test fun deadlineOnlyTaskCanRepeatAndBeCompleted() {
        val item = ScheduleItem(title = "提交", deadlineAt = at("2026-11-02", "20:00"), repeat = RepeatRule(RepeatFrequency.DAILY), reminders = listOf(Reminder(TimeAnchor.DEADLINE,10)))
        assertEquals(3, TimeEngine.occurrences(item, at("2026-11-02"), at("2026-11-05")).size)
        val service = ScheduleService(MemoryStateStore(ScheduleState(items = listOf(item))))
        service.completeByUser(item.id, at("2026-11-03", "20:00"))
        assertEquals(2, TimeEngine.occurrences(service.snapshot().items.single(), at("2026-11-02"), at("2026-11-05")).size)
    }
    @Test fun aiCanCreateWeekendRepeatWithoutProtectedFieldAccess() {
        val changes = scheduleJson.parseToJsonElement("""[{"operation":"create","itemId":"weekend","reason":"用户指定","fields":{"title":"打卡","kind":"EVENT","startAt":"2026-11-02T09:00:00+08:00","endAt":"2026-11-02T12:00:00+08:00","repeat":{"frequency":"WEEKLY","weekdays":[6,7]},"reminders":[{"anchor":"START","minutesBefore":10}]}}]""").jsonArray
        val item = AiDraftParser.changes(changes, ScheduleState()).single().replacement!!
        item.validate()
        assertEquals(setOf(6,7), item.repeat!!.weekdays)
    }
    @Test fun leapDayYearlySkipsInvalidDates() {
        val item = ScheduleItem(title = "纪念", kind = ItemKind.EVENT, startAt = at("2024-02-29", "09:00"), endAt = at("2024-02-29", "10:00"), repeat = RepeatRule(RepeatFrequency.YEARLY))
        assertEquals(listOf(at("2024-02-29", "09:00"), at("2028-02-29", "09:00"), at("2032-02-29", "09:00")), TimeEngine.occurrences(item, at("2024-01-01"), at("2033-01-01")).map { it.startAt })
    }
}
