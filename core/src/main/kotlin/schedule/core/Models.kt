package schedule.core

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

@Serializable enum class ItemKind { TASK, REMINDER, EVENT, DAILY, COURSE }
@Serializable enum class ItemStatus { OPEN, DONE }
@Serializable enum class TimeAnchor { START, DEADLINE, END }
@Serializable enum class RepeatFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }
@Serializable data class RepeatRule(val frequency: RepeatFrequency, val interval: Int = 1, val until: Long? = null,
    val weekdays: Set<Int> = emptySet()) // ISO weekdays: Monday=1, Sunday=7; weekly only
@Serializable data class Reminder(val anchor: TimeAnchor, val minutesBefore: Int = 0)
@Serializable data class OccurrenceEdit(val cancelled: Boolean = false, val startAt: Long? = null, val endAt: Long? = null)
@Serializable data class SchoolBaseline(val title: String, val startAt: Long?, val endAt: Long?, val location: String, val teacher: String, val notes: String = "")

/** Epoch milliseconds are instants; zoneId preserves the user's local clock for repetition. */
@Serializable data class ScheduleItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: ItemKind = ItemKind.TASK,
    val startAt: Long? = null,
    val deadlineAt: Long? = null,
    val endAt: Long? = null,
    val estimatedMinutes: Int? = null,
    val zoneId: String = "Asia/Shanghai",
    val notes: String = "",
    val location: String = "",
    val priority: Int = 0,
    val parentId: String? = null,
    val courseId: String? = null,
    val status: ItemStatus = ItemStatus.OPEN,
    val reminders: List<Reminder> = emptyList(),
    val repeat: RepeatRule? = null,
    val exceptions: Map<Long, OccurrenceEdit> = emptyMap(),
    val sourceKey: String? = null,
    val teacher: String = "",
    val dailyTimes: List<String> = emptyList(),
    val completedOccurrences: Set<Long> = emptySet(),
    val schoolBaseline: SchoolBaseline? = null,
    val cmcLectureId: String? = null,
) {
    val isRecurring: Boolean get() = kind == ItemKind.DAILY || repeat != null
    fun baseline() = SchoolBaseline(title, startAt, endAt, location, teacher, notes)
    fun validate() {
        require(id.isNotBlank() && title.isNotBlank()) { "任务编号与标题不能为空" }
        ZoneId.of(zoneId)
        require(priority in 0..3) { "优先级必须在0至3之间" }
        require(estimatedMinutes == null || estimatedMinutes in 1..525600) { "预计耗时必须为正数" }
        if (kind == ItemKind.TASK) {
            require(startAt != null || deadlineAt != null) { "任务至少填写开始或截止时间" }
            require(endAt == null) { "任务截止时间不能作为活动结束时间" }
        } else if (kind == ItemKind.REMINDER) {
            require(startAt != null && endAt == null && deadlineAt == null && estimatedMinutes == null) { "提醒事项需要提醒时间，不使用截止时间或时长" }
        } else if (kind == ItemKind.DAILY) {
            require(startAt != null && endAt == null && deadlineAt == null && estimatedMinutes == null) { "每日提醒需要开始日期" }
            require(dailyTimes.isNotEmpty() && dailyTimes.size <= 20 && dailyTimes.distinct().size == dailyTimes.size) { "请填写1至20个不同的每日提醒时间" }
            dailyTimes.forEach { require(java.time.LocalTime.parse(it).second == 0 && java.time.LocalTime.parse(it).nano == 0) { "提醒时间精确到分钟" } }
            require(repeat == null) { "每日提醒无需重复规则" }
        } else {
            require(startAt != null && endAt != null && endAt > startAt) { "活动需要有效的开始和结束时间" }
            require(deadlineAt == null && estimatedMinutes == null) { "活动不使用任务截止时间或预计耗时" }
        }
        require(startAt == null || deadlineAt == null || startAt <= deadlineAt) { "开始时间不能晚于截止时间" }
        require(kind == ItemKind.DAILY || dailyTimes.isEmpty()) { "仅每日提醒使用多个时刻" }
        require(isRecurring || completedOccurrences.isEmpty()) { "单次事项使用整体完成状态" }
        require(!isRecurring || status == ItemStatus.OPEN) { "重复事项逐次记录完成" }
        require(completedOccurrences.size <= 20000) { "完成记录超过限制" }
        require(parentId != id) { "任务不能作为自己的子任务" }
        require(reminders.distinct().size == reminders.size && reminders.size <= 20) { "提醒重复或数量过多" }
        reminders.forEach { require(it.minutesBefore in 0..525600 && anchor(it.anchor) != null) { "提醒锚点不存在或提前量无效" } }
        repeat?.let {
            require(startAt != null || deadlineAt != null) { "重复安排需要开始或截止时间" }
            require(it.interval in 1..365) { "重复间隔无效" }
            require(it.until == null || it.until >= (startAt ?: deadlineAt!!)) { "重复结束时间早于开始时间" }
            require(it.weekdays.all { day -> day in 1..7 } && (it.weekdays.isEmpty() || it.frequency == RepeatFrequency.WEEKLY)) { "星期选择仅用于每周重复，周一至周日为1至7" }
        }
        require((repeat != null && kind in setOf(ItemKind.EVENT, ItemKind.COURSE)) || exceptions.isEmpty()) { "单次时间调整仅用于重复活动" }
        exceptions.values.forEach { e ->
            require(e.cancelled || (e.startAt != null && e.endAt != null && e.endAt > e.startAt)) { "调课需要完整的开始和结束时间" }
        }
    }
    fun anchor(anchor: TimeAnchor): Long? = when (anchor) { TimeAnchor.START -> startAt; TimeAnchor.DEADLINE -> deadlineAt; TimeAnchor.END -> endAt }
}

@Serializable data class Change(val itemId: String, val replacement: ScheduleItem? = null, val reason: String) // null means delete
@Serializable enum class ProposalStatus { PENDING, APPROVED, REJECTED }
@Serializable enum class ProposalOrigin { AI, SCHOOL }
@Serializable data class Proposal(
    val id: String = UUID.randomUUID().toString(), val basedOnRevision: Long,
    val changes: List<Change>, val summary: String, val createdAt: Long,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val origin: ProposalOrigin = ProposalOrigin.AI,
)
@Serializable enum class ThemePreference { SYSTEM, LIGHT, DARK }
@Serializable data class AppSettings(val theme: ThemePreference = ThemePreference.SYSTEM, val zoneId: String = "Asia/Shanghai")
@Serializable data class ScheduleState(
    val formatVersion: Int = 2,
    val revision: Long = 0,
    val items: List<ScheduleItem> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val settings: AppSettings = AppSettings(),
)
data class ReviewChange(val before: ScheduleItem?, val after: ScheduleItem?, val reason: String)
data class ProposalReview(val proposal: Proposal, val changes: List<ReviewChange>, val warnings: List<String>)
class StaleProposalException : IllegalStateException("日程已变化，请重新生成或修改提议后再审批")
