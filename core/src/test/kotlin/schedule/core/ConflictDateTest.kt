package schedule.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ConflictDateTest {
    private val monday = Instant.parse("2026-11-02T01:00:00Z").toEpochMilli()
    private val day = 86400000L
    private fun event(id: String, start: Long) = ScheduleItem(id, id, ItemKind.EVENT, startAt = start, endAt = start + 3600000)

    @Test fun sameWeekdayOnDifferentDatesIsNotAConflict() {
        val first = event("本周", monday)
        val second = event("下周", monday + 7 * day)
        assertTrue(TimeEngine.conflicts(listOf(first, second), monday - 1, monday + 14 * day).isEmpty())
    }

    @Test fun alternatingWeeksRespectActualDatesAndShowOverlapDate() {
        val series = event("隔周", monday).copy(repeat = RepeatRule(RepeatFrequency.WEEKLY, interval = 2))
        assertTrue(TimeEngine.conflicts(listOf(series, event("空周", monday + 7 * day)), monday - 1, monday + 30 * day).isEmpty())
        val result = TimeEngine.conflicts(listOf(series, event("有课周", monday + 14 * day)), monday - 1, monday + 30 * day)
        assertEquals(1, result.size)
        assertTrue(result.single().contains("2026-11-16 09:00"))
        assertTrue(result.single().contains("2026-11-16 10:00"))
    }

    @Test fun cancelledAndMovedOccurrencesUseTheirActualTime() {
        val series = event("重复", monday).copy(repeat = RepeatRule(RepeatFrequency.WEEKLY),
            exceptions = mapOf(monday to OccurrenceEdit(cancelled = true),
                monday + 7 * day to OccurrenceEdit(startAt = monday + 8 * day, endAt = monday + 8 * day + 3600000)))
        assertTrue(TimeEngine.conflicts(listOf(series, event("已取消日", monday), event("原日期", monday + 7 * day)), monday - 1, monday + 10 * day).isEmpty())
        assertEquals(1, TimeEngine.conflicts(listOf(series, event("调整后", monday + 8 * day)), monday - 1, monday + 10 * day).size)
    }

    @Test fun reviewDoesNotIncludeUnrelatedExistingConflicts() {
        val service = ScheduleService(MemoryStateStore(), Clock.fixed(Instant.ofEpochMilli(monday - day), ZoneOffset.UTC))
        service.saveByUser(event("旧冲突一", monday)); service.saveByUser(event("旧冲突二", monday))
        val proposal = service.propose(service.snapshot().revision, listOf(Change("新增", event("新增", monday + day), "新建")), "安排")
        assertTrue(service.review(proposal.id).warnings.isEmpty())
    }

    @Test fun pointReminderAndDeadlineDoNotReserveTime() {
        val reminder = ScheduleItem("r", "点提醒", ItemKind.REMINDER, startAt = monday)
        val deadline = ScheduleItem("d", "截止", deadlineAt = monday)
        assertTrue(TimeEngine.conflicts(listOf(event("活动", monday), reminder, deadline), monday - 1, monday + day).isEmpty())
    }
}
