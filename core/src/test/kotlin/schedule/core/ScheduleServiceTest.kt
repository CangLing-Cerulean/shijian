package schedule.core

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduleServiceTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private fun task(id: String = "task", start: Long? = now + 3600000, due: Long? = null, minutes: Int? = null) = ScheduleItem(id, "学习", startAt = start, deadlineAt = due, estimatedMinutes = minutes)
    @Test fun threeTimeModesAndMissingDuration() {
        listOf(task(), task(start = null, due = now + 1), task(due = now + 7200000)).forEach { it.validate(); assertNull(it.estimatedMinutes) }
        assertThrows(IllegalArgumentException::class.java) { task(start = null).validate() }
        assertThrows(IllegalArgumentException::class.java) { task(due = now).validate() }
    }
    @Test fun proposalsDoNotChangeScheduleUntilApproved() {
        val service = ScheduleService(MemoryStateStore(), clock)
        val item = task(); service.saveByUser(item)
        val proposal = service.propose(1, listOf(Change(item.id, item.copy(startAt = now + 7200000), "按用户要求推迟")), "推迟")
        assertEquals(item, service.snapshot().items.single())
        assertEquals(item, service.review(proposal.id).changes.single().before)
        assertTrue(service.review(proposal.id).warnings.any { "预计耗时" in it })
        service.approve(proposal.id)
        assertEquals(now + 7200000, service.snapshot().items.single().startAt)
        assertThrows(IllegalArgumentException::class.java) { service.approve(proposal.id) }
    }
    @Test fun staleAndRejectedPlansCannotApply() {
        val service = ScheduleService(MemoryStateStore(), clock)
        val item = task(); service.saveByUser(item)
        val p = service.propose(1, listOf(Change(item.id, item.copy(title = "新标题"), "修改")), "修改")
        service.saveByUser(item.copy(notes = "用户新安排"))
        assertThrows(StaleProposalException::class.java) { service.approve(p.id) }
        service.reject(p.id)
        assertThrows(IllegalArgumentException::class.java) { service.approve(p.id) }
        assertEquals("用户新安排", service.snapshot().items.single().notes)
    }
    @Test fun removedLockDoesNotBlockExistingItems() {
        val item = scheduleJson.decodeFromString<ScheduleItem>("""{"id":"previous","title":"原事项","deadlineAt":2000000000000,"locked":true}""")
        val service = ScheduleService(MemoryStateStore(), clock)
        service.saveByUser(item)
        val proposal = service.propose(service.snapshot().revision, listOf(Change(item.id, item.copy(title = "已修改"), "修改")), "修改")
        service.approve(proposal.id)
        assertEquals("已修改", service.snapshot().items.single().title)
        assertFalse(service.exportBackup().contains("\"locked\""))
    }
    @Test fun estimatesAreProtectedAndBatchIsAtomic() {
        val service = ScheduleService(MemoryStateStore(), clock)
        val item = task(minutes = 60); service.saveByUser(item)
        assertThrows(IllegalArgumentException::class.java) { service.propose(1, listOf(Change(item.id, item.copy(estimatedMinutes = 90), "估计")), "错误") }
        assertThrows(IllegalArgumentException::class.java) { service.propose(1, listOf(Change(item.id, item.copy(kind = ItemKind.EVENT, endAt = now + 7200000, estimatedMinutes = null), "变类型")), "错误") }
        val replacementEvent = ScheduleItem("new", item.title, ItemKind.EVENT, startAt = now + 3600000, endAt = now + 7200000)
        assertThrows(IllegalArgumentException::class.java) { service.propose(1, listOf(Change(item.id, null, "删除"), Change("new", replacementEvent, "重建")), "错误") }
        assertThrows(IllegalArgumentException::class.java) { service.propose(1, listOf(Change(item.id, item.copy(title = "会失败"), "修改"), Change("missing", null, "删除")), "错误") }
        assertEquals(item, service.snapshot().items.first { it.id == item.id })
        assertTrue(service.snapshot().proposals.isEmpty())
    }
    @Test fun subtaskCyclesAndDeletingParentsFailWithoutDataLoss() {
        val service = ScheduleService(MemoryStateStore(), clock)
        service.saveByUser(task("p")); service.saveByUser(task("c").copy(parentId = "p"))
        assertThrows(IllegalArgumentException::class.java) { service.saveByUser(task("p").copy(parentId = "c")) }
        assertThrows(IllegalArgumentException::class.java) { service.deleteByUser("p") }
        assertEquals(2, service.snapshot().items.size)
    }
    @Test fun persistentStateBackupAndCorruptionFailClosed() {
        val directory = Files.createTempDirectory("schedule-test")
        val path = directory.resolve("state.json")
        try {
            val service = ScheduleService(FileStateStore(path), clock)
            service.saveByUser(task())
            val p = service.propose(1, listOf(Change("task", null, "删除")), "删除")
            assertEquals(1, ScheduleService(FileStateStore(path)).snapshot().items.size)
            val backup = service.exportBackup()
            service.approve(p.id)
            service.restoreBackupByUser(backup)
            assertEquals(1, service.snapshot().items.size)
            assertTrue(service.snapshot().proposals.isEmpty())
            Files.write(path, "broken".toByteArray())
            assertThrows(Exception::class.java) { service.saveByUser(task("other")) }
            assertEquals("broken", String(Files.readAllBytes(path)))
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun schoolRefreshRequiresApprovalAndKeepsLocalNotes() {
        val service = ScheduleService(MemoryStateStore(), clock)
        val lesson = ScheduleItem("zju:123", "课程", ItemKind.COURSE, startAt = now + 1000, endAt = now + 4000, sourceKey = "123")
        val p = service.proposeSchoolRefresh(listOf(lesson), 0)!!
        assertTrue(service.snapshot().items.isEmpty())
        service.approve(p.id)
        service.saveByUser(service.snapshot().items.single().copy(notes = "带作业"))
        val refresh = service.proposeSchoolRefresh(listOf(lesson.copy(location = "新教室")), service.snapshot().revision)!!
        service.approve(refresh.id)
        assertEquals("带作业", service.snapshot().items.single().notes)
        assertNull(service.proposeSchoolRefresh(emptyList(), service.snapshot().revision))
        assertEquals(1, service.snapshot().items.size)
    }
}
