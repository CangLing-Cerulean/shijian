package schedule.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class Occurrence(val item: ScheduleItem, val originalStart: Long?, val startAt: Long?, val endAt: Long?, val deadlineAt: Long?, val completed: Boolean = false)
data class PlannedReminder(val id: String, val itemId: String, val title: String, val triggerAt: Long, val occurrenceStart: Long? = null)

object TimeEngine {
    fun occurrences(item: ScheduleItem, from: Long, until: Long, includeCompleted: Boolean = false): List<Occurrence> {
        require(until > from)
        item.validate()
        if (item.status == ItemStatus.DONE && !includeCompleted) return emptyList()
        val result = mutableListOf<Occurrence>()
        fun append(original: Long?, start: Long?, end: Long?, deadline: Long?) {
            val point = start ?: deadline ?: return
            val completed = item.status == ItemStatus.DONE || original in item.completedOccurrences
            if (completed && !includeCompleted) return
            if ((point < until && (if (end != null) end > from else point >= from)) || (deadline != null && deadline in from until until)) {
                result += Occurrence(item, original, start, end, deadline, completed)
            }
        }
        if (item.kind == ItemKind.DAILY) {
            val zone = ZoneId.of(item.zoneId)
            val firstDate = Instant.ofEpochMilli(item.startAt!!).atZone(zone).toLocalDate()
            var date = maxOf(firstDate, Instant.ofEpochMilli(from).atZone(zone).toLocalDate())
            val last = Instant.ofEpochMilli(until - 1).atZone(zone).toLocalDate()
            require(ChronoUnit.DAYS.between(date, last) <= 10000) { "单次查询范围过大" }
            while (!date.isAfter(last)) {
                item.dailyTimes.forEach { time ->
                    val at = date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
                    append(at, at, null, null)
                }
                date = date.plusDays(1)
            }
            return result.sortedBy { it.startAt }
        }
        val rule = item.repeat
        if (rule == null) {
            val end = item.endAt ?: item.startAt?.let { s -> item.estimatedMinutes?.let { Math.addExact(s, it * 60000L) } }
            append(item.startAt, item.startAt, end, item.deadlineAt)
            return result
        }
        val baseMillis = item.startAt ?: item.deadlineAt!!
        val base = Instant.ofEpochMilli(baseMillis).atZone(ZoneId.of(item.zoneId))
        val duration = item.endAt?.minus(baseMillis) ?: item.estimatedMinutes?.times(60000L) ?: 0L
        val days = rule.weekdays.ifEmpty { setOf(base.dayOfWeek.value) }.sorted()
        val firstDays = days.filter { it >= base.dayOfWeek.value }
        val monday = base.toLocalDate().minusDays(base.dayOfWeek.value - 1L)
        fun at(index: Long): Long? {
            val step = Math.multiplyExact(index, rule.interval.toLong())
            val date = when (rule.frequency) {
                RepeatFrequency.DAILY -> base.plusDays(step)
                RepeatFrequency.WEEKLY -> {
                    val firstWeek = index < firstDays.size
                    val offset = index - firstDays.size
                    val week = if (firstWeek) 0 else (offset / days.size + 1) * rule.interval
                    val day = if (firstWeek) firstDays[index.toInt()] else days[(offset % days.size).toInt()]
                    monday.plusWeeks(week).plusDays(day - 1L).atTime(base.toLocalTime()).atZone(base.zone)
                }
                RepeatFrequency.MONTHLY -> {
                    val month = YearMonth.from(base).plusMonths(step)
                    if (base.dayOfMonth > month.lengthOfMonth()) return null
                    month.atDay(base.dayOfMonth).atTime(base.toLocalTime()).atZone(base.zone)
                }
                RepeatFrequency.YEARLY -> {
                    val year = Math.toIntExact(base.year + step)
                    val month = YearMonth.of(year, base.month)
                    if (base.dayOfMonth > month.lengthOfMonth()) return null
                    month.atDay(base.dayOfMonth).atTime(base.toLocalTime()).atZone(base.zone)
                }
            }
            return date.toInstant().toEpochMilli()
        }
        fun indexNear(instant: Long): Long {
            val date = Instant.ofEpochMilli(instant).atZone(base.zone)
            return when (rule.frequency) {
                RepeatFrequency.DAILY -> ChronoUnit.DAYS.between(base.toLocalDate(), date.toLocalDate()) / rule.interval
                RepeatFrequency.WEEKLY -> {
                    val week = ChronoUnit.DAYS.between(monday, date.toLocalDate()) / (7L * rule.interval)
                    if (week <= 0) 0 else firstDays.size + (week - 1) * days.size
                }
                RepeatFrequency.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(base), YearMonth.from(date)) / rule.interval
                RepeatFrequency.YEARLY -> (date.year - base.year).toLong() / rule.interval
            }
        }
        fun allowed(index: Long, original: Long): Boolean {
            return index >= 0 && (rule.until == null || original <= rule.until)
        }
        val reach = if (rule.frequency == RepeatFrequency.WEEKLY) days.size.toLong() else 1L
        val first = maxOf(0, indexNear(Math.subtractExact(from, maxOf(duration, (item.deadlineAt ?: baseMillis) - baseMillis))) - reach)
        val last = maxOf(-1, indexNear(until) + reach)
        require(last - first <= 10000) { "单次查询范围过大" }
        if (first <= last) for (index in first..last) {
            val original = at(index) ?: continue
            if (allowed(index, original) && original !in item.exceptions) append(original,
                original.takeIf { item.startAt != null }, original.plus(duration).takeIf { item.endAt != null || item.estimatedMinutes != null },
                item.deadlineAt?.let { original + it - baseMillis })
        }
        item.exceptions.forEach { (original, edit) ->
            val near = indexNear(original)
            val index = (maxOf(0, near - reach)..maxOf(0, near + reach)).firstOrNull { at(it) == original }
            require(index != null && allowed(index, original)) { "例外日期不属于该重复安排" }
            if (!edit.cancelled) append(original, edit.startAt, edit.endAt, null)
        }
        return result.sortedBy { it.startAt }
    }

    fun conflicts(items: List<ScheduleItem>, from: Long, until: Long, involvedIds: Set<String>? = null): List<String> {
        val spans = items.flatMap { occurrences(it, from, until) }.filter { it.startAt != null && it.endAt != null }.sortedBy { it.startAt }
        val conflicts = mutableListOf<String>()
        for (i in spans.indices) for (j in i + 1 until spans.size) {
            val a = spans[i]; val b = spans[j]
            if (b.startAt!! >= a.endAt!!) break
            if (involvedIds != null && a.item.id !in involvedIds && b.item.id !in involvedIds) continue
            val overlapStart = maxOf(a.startAt!!, b.startAt!!, from)
            val overlapEnd = minOf(a.endAt!!, b.endAt!!, until)
            if (overlapStart >= overlapEnd) continue
            val zone = ZoneId.of(a.item.zoneId)
            val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val startText = Instant.ofEpochMilli(overlapStart).atZone(zone).format(format)
            val endText = Instant.ofEpochMilli(overlapEnd).atZone(zone).format(format)
            conflicts += "$startText — $endText（${zone.id}）：${a.item.title}与${b.item.title}的安排重叠"
        }
        return conflicts.distinct()
    }

    fun reminders(items: List<ScheduleItem>, now: Long, until: Long): List<PlannedReminder> = items.flatMap { item ->
        // Include starts beyond the horizon whose early reminders fall inside it.
        val lead = (item.reminders.maxOfOrNull { it.minutesBefore } ?: 0) * 60000L
        occurrences(item, now, Math.addExact(until, lead)).flatMap { occurrence ->
            item.reminders.mapNotNull { reminder ->
                val anchor = when (reminder.anchor) { TimeAnchor.START -> occurrence.startAt; TimeAnchor.DEADLINE -> occurrence.deadlineAt; TimeAnchor.END -> occurrence.endAt } ?: return@mapNotNull null
                val trigger = Math.subtractExact(anchor, reminder.minutesBefore * 60000L)
                if (trigger !in (now + 1) until until) return@mapNotNull null
                val key = "${item.id}:${occurrence.originalStart}:${reminder.anchor}:${reminder.minutesBefore}"
                PlannedReminder(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), item.id, item.title, trigger, occurrence.originalStart.takeIf { item.isRecurring })
            }
        }
    }.sortedBy { it.triggerAt }
}
