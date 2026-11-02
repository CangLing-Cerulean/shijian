package schedule.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DailyScheduleTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun completionOnlyAffectsOneClockOnOneDayAndBackupKeepsIt() {
        val service = ScheduleService(MemoryStateStore())
        val day = at("2026-11-01T16:00:00Z")
        val item = ScheduleItem("daily", "签到", ItemKind.DAILY, startAt = day, dailyTimes = listOf("08:00", "21:00"), reminders = listOf(Reminder(TimeAnchor.START)))
        service.saveByUser(item)
        val first = day + 8 * 3600000
        service.completeByUser(item.id, first)
        val active = service.snapshot().items.single()
        assertEquals(3, TimeEngine.occurrences(active, day, day + 2 * 86400000).size)
        assertEquals(4, TimeEngine.occurrences(active, day, day + 2 * 86400000, true).size)
        assertEquals(3, TimeEngine.reminders(listOf(active), day, day + 2 * 86400000).size)
        val restored = ScheduleService(MemoryStateStore()); restored.restoreBackupByUser(service.exportBackup())
        assertEquals(setOf(first), restored.snapshot().items.single().completedOccurrences)
        service.completeByUser(item.id, first, false)
        assertEquals(4, TimeEngine.occurrences(service.snapshot().items.single(), day, day + 2 * 86400000).size)
        assertThrows(IllegalArgumentException::class.java) { service.completeByUser(item.id, first + 60000) }
    }
    @Test fun courseManualFieldsSurviveSchoolRefreshAndAiCanProposeLocalEdit() {
        val service = ScheduleService(MemoryStateStore())
        val remote = ScheduleItem("zju:1", "课程", ItemKind.COURSE, startAt = 1000, endAt = 2000, sourceKey = "1", teacher = "陈文清", location = "东楼")
        service.approve(service.proposeSchoolRefresh(listOf(remote), 0)!!.id)
        service.saveByUser(service.snapshot().items.single().copy(location = "我的教室"))
        service.approve(service.proposeSchoolRefresh(listOf(remote.copy(location = "西楼", teacher = "李明远")), service.snapshot().revision)!!.id)
        val result = service.snapshot().items.single()
        assertEquals("我的教室", result.location); assertEquals("李明远", result.teacher)
        val proposal = service.propose(service.snapshot().revision, listOf(Change(result.id, result.copy(startAt = 1100), "用户要求")), "调整本地课程")
        service.approve(proposal.id); assertEquals(1100L, service.snapshot().items.single().startAt)
    }
    @Test fun movedRepeatingOccurrenceCanBeCompletedAndMidnightDoesNotLeakIntoNextDay() {
        val service = ScheduleService(MemoryStateStore())
        val item = ScheduleItem("event", "活动", ItemKind.EVENT, startAt = 1000, endAt = 2000,
            repeat = RepeatRule(RepeatFrequency.DAILY), exceptions = mapOf(1000L to OccurrenceEdit(startAt = 5000, endAt = 6000)))
        service.saveByUser(item); service.completeByUser(item.id, 1000)
        assertTrue(TimeEngine.occurrences(service.snapshot().items.single(), 5000, 6001).isEmpty())
        val single = ScheduleItem("span", "跨日活动", ItemKind.EVENT, startAt = 1000, endAt = 2000)
        assertTrue(TimeEngine.occurrences(single, 2000, 3000).isEmpty())
    }
}
