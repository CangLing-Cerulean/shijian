package schedule.school

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import schedule.core.ItemKind
import java.time.*

class AcademicScheduleTest {
    private val term = AcademicTerm("2026-2027", 1)
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun row(half: String = "秋冬", parity: String = "2", teacher: String = "张老师", room: String = "东1", day: Int = 1) = obj("""
        {"kcb":"数学(甲)&amp;方法<br>教学班1<br>$teacher<br>${room}zwf", "xxq":"$half", "xqj":$day,"djj":1,"skcd":2,"dsz":"$parity","sfqd":0}
    """)
    private fun calendar(holiday: String = "{}", exchange: String = "{}") = obj("""
        {"startEnd":["20260914","20261108","20261109","20270103"],
         "sessionTime":[["00:00","00:00"],["08:00","08:45"],["08:50","09:35"]],
         "holiday":$holiday,"exchange":$exchange}
    """)
    private fun date(millis: Long?) = Instant.ofEpochMilli(millis!!).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
    @Test fun academicYearAndSeasonBoundaries() {
        assertEquals(term, AcademicSchedule.termOn(LocalDate.parse("2027-01-31")))
        assertEquals(AcademicTerm("2026-2027", 2), AcademicSchedule.termOn(LocalDate.parse("2027-02-01")))
        assertEquals(AcademicTerm("2027-2028", 1), AcademicSchedule.termOn(LocalDate.parse("2027-09-01")))
        assertEquals(listOf("1|秋", "1|冬"), term.seasons)
    }
    @Test fun wholeTermBeyondThirtyDaysIncludesWinterAndDeduplicatesSeasonResponses() {
        val rows = JsonArray(listOf(row()))
        val items = AcademicSchedule.lessons(term, mapOf("1|秋" to rows, "1|冬" to rows), calendar())
        assertEquals(16, items.size)
        assertEquals("2026-09-14", date(items.first().startAt))
        assertEquals("2026-12-28", date(items.last().startAt))
        assertTrue(items.all { it.kind == ItemKind.COURSE && it.repeat == null && it.sourceKey!!.startsWith("zdbk:") })
        assertEquals("数学（甲）&方法", items.first().title)
        val clock = Instant.ofEpochMilli(items.first().endAt!!).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime()
        assertEquals(LocalTime.of(9, 35), clock)
    }
    @Test fun oddEvenWeeksResetAtWinterAndMissingHalfUsesOnlyRequestedSeason() {
        val odd = AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(row(parity = "0")))), calendar())
        assertEquals(8, odd.size)
        assertTrue(odd.any { date(it.startAt) == "2026-11-09" })
        assertFalse(odd.any { date(it.startAt) == "2026-11-16" })
        val even = AcademicSchedule.lessons(term, mapOf("1|冬" to JsonArray(listOf(row(half = "?", parity = "1")))), calendar())
        assertEquals(listOf("2026-11-16", "2026-11-30", "2026-12-14", "2026-12-28"), even.map { date(it.startAt) })
    }
    @Test fun holidayRemovedBeforeExchangesAndIdsRemainStableOnRoomTeacherChange() {
        val cal = calendar("""{"20260921":"假日"}""", """{"2026091420260920":"调休"}""")
        val original = AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(row()))), cal)
        val changed = AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(row(teacher = "李老师", room = "西2")))), cal)
        assertEquals("2026-09-20", date(original.first().startAt))
        assertFalse(original.any { date(it.startAt) == "2026-09-21" })
        assertEquals(original.map { it.id }, changed.map { it.id })
        assertEquals(original.map { it.courseId }, changed.map { it.courseId })
        assertEquals("西2", changed.first().location)
        val plain = AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(row()))), calendar())
        assertEquals(plain.first().id, original.first().id)
    }
    @Test fun explicitAutumnOverridesUnknownWinterDuplicate() {
        val items = AcademicSchedule.lessons(term, mapOf(
            "1|秋" to JsonArray(listOf(row(half = "秋"))),
            "1|冬" to JsonArray(listOf(row(half = "")))), calendar())
        assertEquals(8, items.size)
        assertEquals("2026-11-02", date(items.last().startAt))
    }
    @Test fun eachHalfKeepsItsPublishedTeacherAndRoom() {
        val items = AcademicSchedule.lessons(term, mapOf(
            "1|秋" to JsonArray(listOf(row(half = "秋", teacher = "秋老师", room = "秋教室"))),
            "1|冬" to JsonArray(listOf(row(half = "冬", teacher = "冬老师", room = "冬教室")))), calendar())
        assertEquals("秋教室", items.first().location)
        assertEquals("冬教室", items.last().location)
        assertEquals("冬老师", items.last().teacher)
    }
    @Test fun segmentIdentitySurvivesFirstPeriodChangeAndSeparatesTwoSegments() {
        fun segment(first: Int) = JsonObject(row(half = "秋").toMutableMap().apply {
            put("djj", JsonPrimitive(first)); put("skcd", JsonPrimitive(1))
        })
        fun lessons(vararg first: Int) = AcademicSchedule.lessons(term,
            mapOf("1|秋" to JsonArray(first.map { segment(it) })), calendar())
        val original = lessons(1)
        val changed = lessons(2)
        assertEquals(original.map { it.id }, changed.map { it.id })
        assertNotEquals(original.first().startAt, changed.first().startAt)
        val two = lessons(2, 1, 1)
        val firstDay = two.filter { date(it.startAt) == "2026-09-14" }
        assertEquals(2, firstDay.size)
        assertNotEquals(firstDay[0].id, firstDay[1].id)
        assertEquals(original.first().id, firstDay[0].id)
        assertEquals(two.map { it.id }, lessons(1, 2).map { it.id })
    }
    @Test fun bothExamTypesParseAndKeepIdentityWhenTimeChanges() {
        val root = obj("""{"items":[{"xkkh":"(2026-2027-1)-123","kcmc":"数学(甲)",
          "qzkssj":"2026年11月3日（周二）08:00至10:00","qzjsmc":"东1","qzzwxh":"12",
          "kssj":"2027/1/4 (13:00—15:00)","jsmc":"西2","zwxh":"23"}]}""")
        val result = AcademicSchedule.exams(root)
        assertEquals(2, result.items.size); assertTrue(result.pending.isEmpty())
        assertEquals("考试 · 数学（甲）（期中）", result.items.first().title)
        assertEquals("2027-01-04", date(result.items.last().startAt))
        assertTrue(result.items.first().notes.contains("12"))
        assertTrue(result.items.all { it.kind == ItemKind.EVENT && it.sourceKey!!.startsWith("exam:") })
        val changed = AcademicSchedule.exams(obj(root.toString().replace("13:00—15:00", "14:00—16:00")))
        assertEquals(result.items.map { it.id }, changed.items.map { it.id })
    }
    @Test fun incompleteOrInvalidExamsStayPendingAndEmptyFieldsAreAbsent() {
        for (raw in listOf("待定", "11月3日 08:00-10:00", "2026-11-03", "2026-02-30 08:00-10:00", "2026-11-03 10:00-08:00")) {
            val result = AcademicSchedule.exams(obj("""{"items":[{"xkkh":"123","kcmc":"数学","kssj":"$raw","qzkssj":null}]}"""))
            assertTrue(result.items.isEmpty()); assertEquals(listOf("数学（期末）：$raw"), result.pending)
        }
        assertTrue(AcademicSchedule.exams(obj("""{"items":[{"kssj":""}]}""")).pending.isEmpty())
    }
    @Test fun invalidActiveRowsThrowAndFilteredRowsAreIgnored() {
        for (invalid in listOf(row().toMutableMap().apply { put("xqj", JsonPrimitive(8)) },
            row().toMutableMap().apply { put("skcd", JsonPrimitive(0)) },
            row().toMutableMap().apply { put("kcb", JsonPrimitive("broken")) })) {
            try {
                AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(JsonObject(invalid)))), calendar())
                fail("Expected SchoolException")
            } catch (_: SchoolException) { }
        }
        assertTrue(AcademicSchedule.lessons(term, mapOf("1|秋" to JsonArray(listOf(obj("""{"kcb":null}"""),
            obj("""{"kcb":"invalid","sfyjskc":1}""")))), calendar()).isEmpty())
    }
}
