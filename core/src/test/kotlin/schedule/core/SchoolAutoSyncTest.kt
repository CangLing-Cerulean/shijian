package schedule.core

import org.junit.Assert.*
import org.junit.Test

class SchoolAutoSyncTest {
    @Test fun localTimeRangeRemainsAtomicWhenSchoolMovesTheClass() {
        val service = ScheduleService(MemoryStateStore())
        val lesson = ScheduleItem("zju:one", "课程", ItemKind.COURSE, startAt = 1000, endAt = 2000, sourceKey = "one")
        service.syncSchoolSchedule(listOf(lesson))
        service.saveByUser(service.snapshot().items.single().copy(endAt = 2200))
        val updated = service.syncSchoolSchedule(listOf(lesson.copy(startAt = 3000, endAt = 4000))).items.single()
        assertEquals(1000L, updated.startAt)
        assertEquals(2200L, updated.endAt)
        assertEquals(3000L, updated.schoolBaseline!!.startAt)
    }
    @Test fun importsWholeSemesterAndExamsIdempotentlyWhilePreservingUserEdits() {
        val service = ScheduleService(MemoryStateStore())
        val lessons = (1..160).map { index -> ScheduleItem("zju:$index", "课程 $index", ItemKind.COURSE,
            startAt = index * 86400000L, endAt = index * 86400000L + 3600000, sourceKey = "$index", location = "东楼") }
        val exam = ScheduleItem("zju:exam:one", "考试 · 数学", ItemKind.EVENT, startAt = 180 * 86400000L,
            endAt = 180 * 86400000L + 7200000, sourceKey = "exam:one", location = "考场一")
        val initial = service.syncSchoolSchedule(lessons + exam)
        assertEquals(161, initial.items.size)
        assertTrue(initial.proposals.isEmpty())
        assertEquals(initial.revision, service.syncSchoolSchedule(lessons + exam).revision)
        service.saveByUser(initial.items.last().copy(location = "自定义位置", notes = "带学生证"))
        val changed = service.syncSchoolSchedule(listOf(exam.copy(startAt = exam.startAt!! + 60000, location = "考场二")))
        assertEquals(161, changed.items.size)
        val updated = changed.items.last()
        assertEquals(exam.startAt!! + 60000, updated.startAt)
        assertEquals("自定义位置", updated.location)
        assertEquals("带学生证", updated.notes)
        assertEquals("考场二", updated.schoolBaseline!!.location)
    }
    @Test fun invalidBatchDoesNotPartiallyOverwriteCalendar() {
        val service = ScheduleService(MemoryStateStore())
        val exam = ScheduleItem("zju:exam:a", "考试", ItemKind.EVENT, startAt = 1000, endAt = 2000, sourceKey = "exam:a")
        assertThrows(IllegalArgumentException::class.java) { service.syncSchoolSchedule(listOf(exam, exam.copy(id = "bad"))) }
        assertTrue(service.snapshot().items.isEmpty())
    }
}
