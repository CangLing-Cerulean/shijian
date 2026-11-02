package schedule.school

import kotlinx.serialization.json.*
import schedule.core.ItemKind
import schedule.core.ScheduleItem
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class AcademicTerm(val year: String, val half: Int) {
    init { require(half in 1..2) }
    val id: String get() = "$year-$half"
    val seasons: List<String> get() = if (half == 1) listOf("1|秋", "1|冬") else listOf("2|春", "2|夏")
}

data class ExamSchedule(val items: List<ScheduleItem>, val pending: List<String>)

/** Converts undergraduate academic-office rows into dated Shanghai calendar instances. */
object AcademicSchedule {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val compactDate = DateTimeFormatter.BASIC_ISO_DATE
    private data class LessonIdentity(val name: String, val teachingClass: String, val weekday: Int,
        val first: Int, val count: Int, val parity: String)
    private data class LessonRow(val identity: LessonIdentity, val teacher: String, val location: String,
        val start: LocalTime, val end: LocalTime, val declaredHalves: Set<Int>, val requestedHalf: Int)
    private data class DatedLesson(val row: LessonRow, val original: LocalDate, val day: LocalDate)

    fun termOn(date: LocalDate): AcademicTerm {
        val startYear = date.year - if (date.monthValue < 9) 1 else 0
        return AcademicTerm("$startYear-${startYear + 1}", if (date.monthValue in 2..8) 2 else 1)
    }

    fun lessons(term: AcademicTerm, seasonalRows: Map<String, JsonArray>, calendar: JsonObject): List<ScheduleItem> = checked("课表或校历无法解析") {
        if (seasonalRows.values.all { it.isEmpty() }) return@checked emptyList()
        val bounds = calendar["startEnd"]?.jsonArray?.map { LocalDate.parse(it.jsonPrimitive.content, compactDate) }
            ?: error("校历缺少起止日期")
        require(bounds.size == 4 && bounds[0] <= bounds[1] && bounds[1] < bounds[2] && bounds[2] <= bounds[3])
        val times = calendar["sessionTime"]?.jsonArray ?: error("校历缺少节次")
        val holidays = (calendar["holiday"] as? JsonObject).orEmpty().keys.map { LocalDate.parse(it, compactDate) }.toSet()
        val exchanges = mutableMapOf<LocalDate, LocalDate>()
        (calendar["exchange"] as? JsonObject).orEmpty().keys.forEach {
            require(it.length == 16)
            val a = LocalDate.parse(it.take(8), compactDate)
            val b = LocalDate.parse(it.drop(8), compactDate)
            require(exchanges[a] == null && exchanges[b] == null && a != b)
            exchanges[a] = b; exchanges[b] = a
        }
        val rows = mutableListOf<LessonRow>()
        term.seasons.forEach { season ->
            seasonalRows[season].orEmpty().forEach rowLoop@ { element ->
                val row = element.jsonObject
                if (row["kcb"] == null || row["kcb"] is JsonNull || row.text("sfyjskc") == "1") return@rowLoop
                val block = row.text("kcb").split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), limit = 4)
                require(block.size == 4 && "zwf" in block[3]) { "课表课程信息不完整" }
                val name = clean(block[0]); val teachingClass = clean(block[1]); val teacher = clean(block[2])
                val location = clean(block[3].substringBefore("zwf"))
                require(name.isNotBlank())
                val weekday = row.text("xqj").toInt(); require(weekday in 1..7)
                val first = row.text("djj").toInt(); val count = row.text("skcd").toInt()
                require(first >= 1 && count > 0 && count <= times.size && first.toLong() + count <= times.size)
                fun clock(index: Int, endpoint: Int): LocalTime {
                    val pair = times[index].jsonArray; require(pair.size == 2)
                    return LocalTime.parse(pair[endpoint].jsonPrimitive.content)
                }
                val start = clock(first, 0); val end = clock(first + count - 1, 1)
                require(end > start)
                val declared = row.text("xxq")
                val declaredHalves = (0..1).filter { index -> declared.any { it in if (index == 0) "秋春" else "冬夏" } }.toSet()
                val parity = row.text("dsz").let { if (it in setOf("0", "1")) it else "all" }
                rows += LessonRow(LessonIdentity(name, teachingClass, weekday, first, count, parity),
                    teacher, location, start, end, declaredHalves, term.seasons.indexOf(season))
            }
        }
        val dated = mutableListOf<DatedLesson>()
        rows.groupBy { it.identity }.values.forEach { duplicates ->
                val explicit = duplicates.flatMap { it.declaredHalves }.toSet()
                val halves = explicit.ifEmpty { duplicates.map { it.requestedHalf }.toSet() }
                val identity = duplicates.first().identity
                for (halfIndex in halves.sorted()) {
                    val row = duplicates.firstOrNull { halfIndex in it.declaredHalves && it.requestedHalf == halfIndex }
                        ?: duplicates.firstOrNull { halfIndex in it.declaredHalves }
                        ?: duplicates.firstOrNull { it.requestedHalf == halfIndex }
                        ?: duplicates.first()
                    val begin = bounds[halfIndex * 2]; val finish = bounds[halfIndex * 2 + 1]
                    val monday = begin.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    var original = begin
                    while (original <= finish) {
                        val odd = (ChronoUnit.DAYS.between(monday, original) / 7) % 2 == 0L
                        val matchesWeek = when (identity.parity) { "0" -> odd; "1" -> !odd; else -> true }
                        if (original.dayOfWeek.value == identity.weekday && matchesWeek && original !in holidays) {
                            val day = exchanges[original] ?: original
                            dated += DatedLesson(row, original, day)
                        }
                        original = original.plusDays(1)
                    }
                }
        }
        // Number actual segments in their original teaching day, before holiday exchanges.
        // Clock changes preserve identity as long as the segment order remains the same.
        dated.groupBy { Triple(it.row.identity.name, it.row.identity.teachingClass, it.original) }.values.flatMap { dayRows ->
            dayRows.distinctBy { it.row.identity.first to it.row.identity.count }
                .sortedWith(compareBy<DatedLesson> { it.row.identity.first }.thenBy { it.row.identity.count })
                .mapIndexed { segment, lesson ->
                    val row = lesson.row; val identity = row.identity
                    val key = "zdbk:" + hash(term.id, identity.name, identity.teachingClass, lesson.original.toString(), segment.toString())
                    ScheduleItem(id = "zju:$key", title = identity.name, kind = ItemKind.COURSE,
                        startAt = millis(lesson.day, row.start), endAt = millis(lesson.day, row.end),
                        location = row.location, teacher = row.teacher,
                        courseId = "zdbk:" + hash(term.id, identity.name, identity.teachingClass), sourceKey = key,
                        notes = "")
                }
        }.sortedWith(compareBy<ScheduleItem> { it.startAt }.thenBy { it.sourceKey })
    }

    fun exams(root: JsonObject): ExamSchedule = checked("考试信息无法解析") {
        val items = linkedMapOf<String, ScheduleItem>(); val pending = linkedSetOf<String>()
        val rows = root["items"] as? JsonArray ?: error("考试列表缺失")
        rows.forEach { element ->
            val row = element.jsonObject
            for ((type, fields) in listOf("mid" to listOf("qzkssj", "qzjsmc", "qzzwxh"), "final" to listOf("kssj", "jsmc", "zwxh"))) {
                val raw = row.text(fields[0]).trim()
                if (raw.isEmpty()) continue
                val name = clean(row.text("kcmc")); val selection = row.text("xkkh")
                require(name.isNotBlank() && selection.isNotBlank())
                val label = if (type == "mid") "期中" else "期末"
                val parsed = examTime(raw)
                if (parsed == null) { pending += "$name（$label）：$raw"; continue }
                val key = "exam:" + hash(selection, type)
                items[key] = ScheduleItem(id = "zju:$key", title = "考试 · $name（$label）", kind = ItemKind.EVENT,
                    startAt = parsed.first, endAt = parsed.second, location = clean(row.text(fields[1])),
                    courseId = "zdbk:" + hash(selection), sourceKey = key,
                    notes = "来源：浙江大学本科教务考试安排\n座位号：${clean(row.text(fields[2]))}\n学校时间：$raw")
            }
        }
        ExamSchedule(items.values.sortedBy { it.startAt }, pending.toList())
    }

    private fun examTime(raw: String): Pair<Long, Long>? {
        return runCatching {
        val text = clean(raw).replace('：', ':')
        val date = Regex("(?<![0-9])([0-9]{4})\\s*[-/.年]\\s*([0-9]{1,2})\\s*[-/.月]\\s*([0-9]{1,2})(?:日)?(?![0-9])").find(text) ?: return null
        val clock = Regex("(?<![0-9])([0-9]{1,2}):([0-9]{2})\\s*[-—–－~～至]+\\s*([0-9]{1,2}):([0-9]{2})(?![0-9])").find(text) ?: return null
        val day = LocalDate.of(date.groupValues[1].toInt(), date.groupValues[2].toInt(), date.groupValues[3].toInt())
        val from = LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt())
        val until = LocalTime.of(clock.groupValues[3].toInt(), clock.groupValues[4].toInt())
        if (until <= from) return null
        millis(day, from) to millis(day, until)
        }.getOrNull()
    }

    private fun millis(day: LocalDate, time: LocalTime) = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun hash(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("|") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun clean(raw: String): String = raw.replace(Regex("<[^>]*>"), "").replace(Regex("&(#x[0-9a-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);", RegexOption.IGNORE_CASE)) {
        val entity = it.groupValues[1].lowercase()
        val scalar = when { entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16); entity.startsWith('#') -> entity.drop(1).toIntOrNull(); else -> null }
        if (scalar != null && scalar in 1..0x10ffff && scalar !in 0xd800..0xdfff) String(Character.toChars(scalar))
        else mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")[entity] ?: it.value
    }.replace('(', '（').replace(')', '）').trim()
    private inline fun <T> checked(message: String, block: () -> T): T = try { block() }
        catch (e: SchoolException) { throw e } catch (_: Exception) { throw SchoolException(message) }
}
