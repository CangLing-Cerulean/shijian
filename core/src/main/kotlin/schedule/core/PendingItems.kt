package schedule.core

import java.time.LocalDate
import java.time.ZoneId

/** Open occurrences available by the end of this date; deadlines never hide a one-off task. */
fun pendingItems(items: List<ScheduleItem>, date: LocalDate, zone: ZoneId): List<Occurrence> {
    val todayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return items.filter { it.kind != ItemKind.COURSE && it.status == ItemStatus.OPEN }.flatMap { item ->
        if (item.kind == ItemKind.REMINDER || item.kind == ItemKind.DAILY) {
            TimeEngine.occurrences(item, todayStart, until)
        } else if (!item.isRecurring) {
            if (item.startAt != null && item.startAt >= until) emptyList()
            else listOf(Occurrence(item, item.startAt, item.startAt, item.endAt, item.deadlineAt))
        } else {
            // Query in bounded chunks so long-running daily series retain unfinished occurrences.
            var from = item.startAt ?: item.deadlineAt!!
            from = minOf(from, item.exceptions.values.mapNotNull { it.startAt }.minOrNull() ?: from)
            val nextDeadline = item.kind == ItemKind.TASK && item.startAt == null
            val queryEnd = if (nextDeadline) {
                val rule = item.repeat!!
                val endDate = when (rule.frequency) {
                    RepeatFrequency.DAILY -> date.plusDays(rule.interval.toLong() + 1)
                    RepeatFrequency.WEEKLY -> date.plusWeeks(rule.interval.toLong() + 1)
                    RepeatFrequency.MONTHLY -> date.plusMonths(rule.interval.toLong() + 1)
                    RepeatFrequency.YEARLY -> date.plusYears(rule.interval.toLong() + 1)
                }
                maxOf(until, endDate.atStartOfDay(zone).toInstant().toEpochMilli(), from + 1)
            } else until
            val result = mutableListOf<Occurrence>()
            while (from < queryEnd) {
                val end = minOf(queryEnd, java.time.Instant.ofEpochMilli(from).atZone(zone).plusYears(1).toInstant().toEpochMilli())
                result += TimeEngine.occurrences(item, from, end)
                from = end
            }
            val unique = result.distinctBy { it.originalStart }
            val futureDeadline = if (nextDeadline) unique.filter { it.deadlineAt!! >= until }.minByOrNull { it.deadlineAt!! } else null
            unique.filter { (it.startAt ?: it.deadlineAt!!) < until || it == futureDeadline }
        }
    }.sortedBy { it.deadlineAt ?: it.startAt ?: Long.MIN_VALUE }
}
