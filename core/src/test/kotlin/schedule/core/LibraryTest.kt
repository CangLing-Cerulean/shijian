package schedule.core

import org.junit.Assert.*
import org.junit.Test
import schedule.study.*
import schedule.ai.ChatEntry
import schedule.school.*
import java.nio.file.Files

class LibraryTest {
    @Test fun lessonMaterialsAreScopedDeduplicatedAndSummaryLinksPersist() {
        val path = Files.createTempDirectory("lesson-library-test").resolve("library.json")
        val store = LibraryStore(path)
        val material = LessonMaterial("lesson-one", "course", "物理", CourseMaterial("activity", 1, null, "课件.pdf", 10))
        store.linkMaterial(material); store.linkMaterial(material)
        val another = material.copy(itemId = "lesson-two")
        store.linkMaterial(another)
        val summary = SavedSummary(courseName = "物理", lessonStart = 1234, sections = listOf(SummarySection("课件1页", "正文")))
        store.saveSummary(summary, listOf(material))
        val chat = SavedConversation(messages = listOf(ChatEntry("assistant", "总结完成", summaryIds = listOf(summary.id))))
        store.update { it.copy(conversations = listOf(chat)) }
        val restored = LibraryStore(path).read()
        assertEquals(2, restored.lessonMaterials.size)
        assertEquals(listOf(summary.id), restored.conversations.single().messages.single().summaryIds)
        store.unlinkMaterial(material.id)
        assertEquals(listOf(another), store.read().lessonMaterials)
        assertEquals(listOf(summary), store.read().summaries)
    }
    @Test fun savesReopensAndDeletesOnlySelectedRecords() {
        val path = Files.createTempDirectory("library-test").resolve("library.json")
        val store = LibraryStore(path)
        val history = SavedConversation(messages = (1..250).map { ChatEntry("user", "消息$it") })
        store.update { it.copy(conversations = listOf(history)) }
        val first = SavedSummary(courseName = "物理", lessonStart = 1234, sections = listOf(SummarySection("讲次1 10秒", "正文")))
        val second = first.copy(id = "other")
        store.saveSummary(first); store.saveSummary(second)
        assertEquals(250, LibraryStore(path).read().conversations.single().messages.size)
        store.deleteSummaries(setOf(first.id))
        assertEquals(listOf(second), LibraryStore(path).read().summaries)
        assertEquals(listOf(history), store.read().conversations)
    }
    @Test fun filtersAcademicYearAndBothSeasonsWithoutOldOrUnknownCourses() {
        val term = AcademicTerm("2026-2027", 1)
        fun course(year: String, semester: String) = SchoolCourse("id", "课程", "", year, semester, emptyList())
        assertTrue(course("2026-2027", "2026-2027秋冬").belongsTo(term))
        assertTrue(course("2026-2027", "2026-2027冬").belongsTo(term))
        assertFalse(course("2025-2026", "2025-2026秋冬").belongsTo(term))
        assertFalse(course("2026-2027", "2026-2027春夏").belongsTo(term))
        assertFalse(course("2026-2027", "2026-2027短").belongsTo(term))
        assertFalse(course("", "").belongsTo(term))
    }
}
