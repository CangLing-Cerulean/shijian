package schedule.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import schedule.android.CredentialVault
import schedule.school.*
import java.time.LocalDate
import java.time.ZoneId

/** Explicit opt-in: performs read-only school requests using this device's saved account. */
class LiveSchoolConnectionTest {
    @Test fun readCoursesAndSchedule() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveSchool") == "true")
        val vault = CredentialVault(instrumentation.targetContext)
        val client = ZjuClient(CredentialsProvider {
            val account = JSONObject(requireNotNull(vault.get("school.account")) { "学校账号未配置" })
            SchoolCredentials(account.getString("username"), account.getString("password"))
        })
        val failures = mutableListOf<String>()
        try { client.listCourses() } catch (e: SchoolException) { failures += "课程列表：${e.message}" }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        try { client.fetchSchedule(today) } catch (e: SchoolException) { failures += "同步课表：${e.message}" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
