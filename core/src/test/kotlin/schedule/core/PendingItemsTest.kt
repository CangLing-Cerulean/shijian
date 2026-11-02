package schedule.core

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class PendingItemsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 2)
    private val today = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val day = 86400000L

    @Test fun visibilityUsesStartDateInsteadOfDeadline() {
        val items = listOf(
            ScheduleItem("old", "未完成", startAt = today - 2 * day, deadlineAt = today - day),
            ScheduleItem("later", "今天晚上", startAt = today + 23 * 3600000, deadlineAt = today + 5 * day),
            ScheduleItem("future", "明天开始", startAt = today + day, deadlineAt = today + 5 * day),
            ScheduleItem("noStart", "未来截止", deadlineAt = today + 10 * day),
            ScheduleItem("done", "已完成", deadlineAt = today, status = ItemStatus.DONE)
        )
        assertEquals(setOf("old", "later", "noStart"), pendingItems(items, date, zone).map { it.item.id }.toSet())
    }

    @Test fun recurringItemsKeepPastUnfinishedAndExcludeCompletedAndFuture() {
        val item = ScheduleItem("repeat", "每天", startAt = today - 2 * day, deadlineAt = today + 3 * day,
            repeat = RepeatRule(RepeatFrequency.DAILY), completedOccurrences = setOf(today - day))
        assertEquals(listOf(today - 2 * day, today), pendingItems(listOf(item), date, zone).map { it.originalStart })
    }
}
