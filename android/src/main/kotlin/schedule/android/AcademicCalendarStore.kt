package schedule.android

import android.content.Context
import kotlinx.serialization.json.*
import schedule.school.AcademicTerm
import schedule.school.SchoolException
import schedule.school.SchoolRequest
import schedule.school.UrlConnectionTransport
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Public semester calendar data, separate from authenticated university requests. */
internal class AcademicCalendarStore(private val context: Context) {
    fun load(term: AcademicTerm): JsonObject {
        require(Regex("[0-9]{4}-[0-9]{4}-[12]").matches(term.id))
        val directory = context.filesDir.resolve("schedule/calendars").also { it.mkdirs() }
        val saved = directory.resolve("${term.id}.json")
        if (saved.exists()) return parse(saved.readText(Charsets.UTF_8))
        val bundled = runCatching { context.assets.open("calendar/${term.id}.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        if (bundled != null) return parse(bundled)
        val uri = URI("https://raw.githubusercontent.com/Elyyyyyyyyxer/Elychron/main/assets/calendar/${term.id}.json")
        val response = UrlConnectionTransport().execute(SchoolRequest(uri, "GET", emptyMap(), null, 128 * 1024))
        if (response.status != 200) throw SchoolException("${term.id} 学期校历尚不可用，保留现有课表")
        val text = response.body.toString(Charsets.UTF_8)
        val calendar = parse(text)
        val temporary = Files.createTempFile(directory.toPath(), "calendar-", ".tmp")
        try {
            Files.write(temporary, text.toByteArray(Charsets.UTF_8))
            Files.move(temporary, saved.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return calendar
    }
    private fun parse(text: String): JsonObject {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["startEnd"]?.jsonArray?.size == 4 && root["sessionTime"]?.jsonArray?.isNotEmpty() == true) { "学期校历格式无效" }
        return root
    }
}
