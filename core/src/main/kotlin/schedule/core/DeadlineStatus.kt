package schedule.core

/** Minute precision without showing a negative countdown after the deadline. */
fun deadlineStatus(deadline: Long, now: Long): String {
    if (deadline == now) return "已到截止时间"
    val overdue = now > deadline
    val milliseconds = if (overdue) now - deadline else deadline - now
    if (milliseconds < 60000) return if (overdue) "已逾期不足1分钟" else "距截止不足1分钟"
    val totalMinutes = milliseconds / 60000
    val days = totalMinutes / 1440
    val hours = totalMinutes % 1440 / 60
    val minutes = totalMinutes % 60
    val duration = buildList {
        if (days > 0) add("${days}天")
        if (hours > 0) add("${hours}小时")
        if (minutes > 0) add("${minutes}分钟")
    }.joinToString(" ")
    return (if (overdue) "已逾期" else "距截止") + duration
}
