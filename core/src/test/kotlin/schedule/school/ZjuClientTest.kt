package schedule.school

import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ZjuClientTest {
    private fun SchoolRequest.header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.joinToString(";")

    private class LoginFixture(val cmc: Boolean = false) {
        val requests = mutableListOf<SchoolRequest>()
        var submissions = 0
        var credentialsCalls = 0
        var casStatus = 302
        var refreshTarget = "/ready"
        var api: (SchoolRequest) -> SchoolResponse = { reply(if (cmc) """{"result":{"list":[]}}""" else """{"courses":[],"pages":1,"total":0}""") }
        val landing = "https://zjuam.zju.edu.cn/cas/login"
        val client = ZjuClient(CredentialsProvider {
            credentialsCalls++
            SchoolCredentials("fixture-user", "fixture-password")
        }, SchoolTransport { request ->
            requests += request
            when {
                request.uri.path.startsWith("/api/") || request.uri.path.startsWith("/courseapi/") -> api(request)
                request.uri.host == "tgmedia.cmc.zju.edu.cn" -> {
                    check(request.headers.entries.any { it.key.equals("Cookie", true) && it.value.any { value -> value.contains("iPlanetDirectoryPro=sso-cookie") } })
                    reply("", 302, mapOf("Location" to listOf("https://yjapi.cmc.zju.edu.cn/ready")))
                }
                request.uri.path == "/user/index" -> {
                    check(request.headers.entries.any { it.key.equals("Cookie", true) && it.value.any { value -> value.contains("iPlanetDirectoryPro=sso-cookie") } })
                    reply("""<meta http-equiv="refresh" content="0; url=$refreshTarget">""")
                }
                request.uri.path == "/cas/v2/getPubKey" -> {
                    check(request.headers.entries.any { it.key.equals("Cookie", true) && it.value.any { value -> value.contains("CASID=cas-cookie") } })
                    reply("""{"modulus":"ca1","exponent":"11"}""")
                }
                request.method == "POST" -> {
                    submissions++
                    val correct = request.headers["Origin"] == null && request.headers["Referer"] == null && request.headers["Accept"] == null &&
                        request.headers["Content-Type"] == listOf("application/x-www-form-urlencoded; charset=utf-8") && !request.headers["User-Agent"].isNullOrEmpty() &&
                        request.headers.entries.any { it.key.equals("Cookie", true) && it.value.any { value -> value.contains("CASID=cas-cookie") } }
                    val form = request.body!!.toString(Charsets.UTF_8)
                    check(form.contains("rememberMe=true") && !form.contains("authcode="))
                    check(request.uri.toString() == landing)
                    reply("", if (correct) casStatus else 403, mapOf(
                        "Location" to listOf("https://must-not-follow.zju.edu.cn/"),
                        "Set-Cookie" to listOf("iPlanetDirectoryPro=sso-cookie; Path=/; Secure")))
                }
                request.uri.path == "/cas/login" -> reply(
                    """<form id="fm1" action="/cas/login?execution=private-query"><input name="execution" value="private-token"></form>""",
                    headers = mapOf("Set-Cookie" to listOf("CASID=cas-cookie; Path=/; Secure")))
                request.uri.path == "/ready" -> reply("ok", headers = mapOf("Set-Cookie" to listOf(
                    if (request.uri.host == "yjapi.cmc.zju.edu.cn") "JWTUser=%7B%22sub%22%3A%2212%22%7D; Domain=.cmc.zju.edu.cn; Path=/; Secure" else "session=course-cookie; Path=/; Secure")))
                else -> error("Unexpected request: ${request.uri.host}${request.uri.path}")
            }
        })
        companion object {
            fun reply(text: String = "", status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
                SchoolResponse(status, headers, text.toByteArray())
        }
    }

    @Test fun casHeadersAndCookiesWorkForBothServicesWithoutRedirectLeakage() {
        for (cmc in listOf(false, true)) {
            val fixture = LoginFixture(cmc)
            if (cmc) fixture.client.fetchSchedule("2026-11-01") else fixture.client.listCourses()
            assertEquals(1, fixture.submissions)
            assertEquals("https://zjuam.zju.edu.cn/cas/login", fixture.requests.first().uri.toString())
            assertEquals("GET", fixture.requests.first().method)
            assertFalse(fixture.requests.any { it.uri.host == "must-not-follow.zju.edu.cn" })
            val ready = fixture.requests.single { it.uri.path == "/ready" }
            assertEquals("GET", ready.method)
            assertNull(ready.body)
            listOf("Origin", "Referer", "Content-Type", "Authorization").forEach { assertNull(ready.header(it)) }
            assertFalse(ready.header("Cookie").orEmpty().contains("CASID"))
            val api = fixture.requests.last()
            assertTrue(api.header("Cookie").orEmpty().contains(if (cmc) "JWTUser=" else "session=course-cookie"))
            assertFalse(api.header("Cookie").orEmpty().contains("CASID"))
        }
    }

    @Test fun coursesAndCmcShareOneSsoLogin() {
        val fixture = LoginFixture()
        fixture.client.listCourses()
        fixture.api = { LoginFixture.reply("""{"result":{"list":[]}}""") }
        fixture.client.fetchSchedule("2026-11-01")
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.credentialsCalls)
        assertTrue(fixture.requests.any { it.uri.path == "/user/index" })
        assertTrue(fixture.requests.any { it.uri.host == "tgmedia.cmc.zju.edu.cn" })
    }

    @Test fun hostileMetaRefreshIsBlockedBeforeSendingRequest() {
        val fixture = LoginFixture()
        fixture.refreshTarget = "https://evil.example/steal"
        rejects { fixture.client.listCourses() }
        assertEquals(1, fixture.submissions)
        assertFalse(fixture.requests.any { it.uri.host == "evil.example" })
        assertEquals("/user/index", fixture.requests.last().uri.path)
    }

    @Test fun ssoPreservesCourseMaterialsDownloadScheduleAndTranscriptFlows() {
        val fixture = LoginFixture()
        val pdf = "%PDF-fixture".toByteArray()
        val paths = mutableListOf<String>()
        fixture.api = { request ->
            paths += request.uri.path
            if (request.uri.host == "courses.zju.edu.cn") {
                assertTrue(request.header("Cookie").orEmpty().contains("session=course-cookie"))
                assertFalse(request.header("Cookie").orEmpty().contains("JWTUser="))
            } else {
                assertTrue(request.header("Cookie").orEmpty().contains("JWTUser="))
                assertFalse(request.header("Cookie").orEmpty().contains("session="))
            }
            when (request.uri.path) {
                "/api/my-courses" -> LoginFixture.reply("""{"courses":[{"id":7,"name":"数学","instructors":[]}],"pages":1,"total":1}""")
                "/api/courses/7/activities" -> LoginFixture.reply("""{"activities":[{"id":8,"type":"material","uploads":[{"id":9,"name":"lecture.pdf","size":${pdf.size},"allow_download":true,"status":"ready"}]}]}""")
                "/api/uploads/9/blob" -> SchoolResponse(200, mapOf("Content-Type" to listOf("application/pdf"), "Content-Length" to listOf(pdf.size.toString())), pdf)
                "/courseapi/v2/schedule/get-week-schedules" -> {
                    assertTrue(request.uri.rawQuery.contains("user_id=12"))
                    LoginFixture.reply("""{"result":{"list":[{"course":[{"id":91,"course_id":7,"course_title":"数学","start_at":100,"end_at":200}]}]}}""")
                }
                "/courseapi/v3/web-socket/search-trans-result" -> {
                    assertEquals("interactivemeta.cmc.zju.edu.cn", request.uri.host)
                    assertTrue(request.uri.rawQuery.contains("sub_id=91"))
                    LoginFixture.reply("""{"list":[{"all_content":[{"BeginSec":1.5,"Text":"内容","TransText":"translation"}]}]}""")
                }
                else -> error("Unexpected data path ${request.uri.path}")
            }
        }
        val course = fixture.client.listCourses().single()
        assertEquals("数学", course.name)
        val material = fixture.client.listMaterials(course.courseId).single()
        assertArrayEquals(pdf, fixture.client.downloadMaterial(material))
        val lesson = fixture.client.fetchSchedule("2026-11-01").single()
        assertEquals(course.courseId, lesson.courseId)
        assertEquals(100_000L, lesson.startMillis)
        val transcript = fixture.client.fetchTranscript(lesson.subId).single()
        assertEquals("内容", transcript.text)
        assertEquals("translation", transcript.translation)
        assertEquals(1.5, transcript.beginSeconds, 0.0)
        assertEquals(listOf("/api/my-courses", "/api/courses/7/activities", "/api/uploads/9/blob",
            "/courseapi/v2/schedule/get-week-schedules", "/courseapi/v3/web-socket/search-trans-result"), paths)
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.credentialsCalls)
        assertEquals(1, fixture.requests.count { it.uri.path == "/user/index" })
        assertEquals(1, fixture.requests.count { it.uri.host == "tgmedia.cmc.zju.edu.cn" })
    }

    @Test fun cachedApi401ReauthenticatesOnceAndSucceeds() {
        val fixture = LoginFixture()
        fixture.client.listCourses()
        var attempts = 0
        fixture.api = {
            attempts++
            if (attempts == 1) LoginFixture.reply(status = 401)
            else LoginFixture.reply("""{"courses":[],"pages":1,"total":0}""")
        }
        assertTrue(fixture.client.listCourses().isEmpty())
        assertEquals(2, attempts)
        assertEquals(2, fixture.submissions)
        assertEquals(2, fixture.credentialsCalls)
    }

    @Test fun repeatedApi403StopsAfterTwoAttemptsAndClearsSession() {
        val fixture = LoginFixture()
        fixture.client.listCourses()
        var attempts = 0
        fixture.api = { attempts++; LoginFixture.reply(status = 403) }
        rejects { fixture.client.listCourses() }
        assertEquals(2, attempts)
        assertEquals(2, fixture.submissions)
        fixture.api = { LoginFixture.reply("""{"courses":[],"pages":1,"total":0}""") }
        assertTrue(fixture.client.listCourses().isEmpty())
        assertEquals(3, fixture.submissions)
        assertEquals(3, fixture.credentialsCalls)
    }

    @Test fun apiLoginRedirectReauthenticatesWithoutFollowingApiRedirect() {
        val fixture = LoginFixture()
        var attempts = 0
        fixture.api = {
            attempts++
            if (attempts == 1) LoginFixture.reply(status = 302, headers = mapOf("Location" to listOf("https://zjuam.zju.edu.cn/unexpected")))
            else LoginFixture.reply("""{"courses":[],"pages":1,"total":0}""")
        }
        assertTrue(fixture.client.listCourses().isEmpty())
        assertEquals(2, attempts)
        assertEquals(2, fixture.submissions)
        assertFalse(fixture.requests.any { it.uri.path == "/unexpected" })
    }

    @Test fun cas403IsNotRetriedAndErrorDoesNotExposeSecrets() {
        val fixture = LoginFixture()
        fixture.casStatus = 403
        val message = try {
            fixture.client.listCourses()
            fail("Expected SchoolException")
            ""
        } catch (error: SchoolException) { error.message.orEmpty() }
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.credentialsCalls)
        assertTrue(message.contains("统一身份认证"))
        assertTrue(message.contains("403"))
        assertTrue(message.contains("zjuam.zju.edu.cn"))
        listOf("fixture-user", "fixture-password", "private-token", "private-query", "fixture-secret-service", "?").forEach {
            assertFalse("Error leaked $it", message.contains(it))
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected SchoolException") } catch (_: SchoolException) { }
    }

    @Test fun domainAndDateValidation() {
        listOf("http://courses.zju.edu.cn/", "https://zju.edu.cn.evil.example/", "https://user@zju.edu.cn/", "https://zju.edu.cn:444/").forEach {
            rejects { ZjuClient.schoolUri(it) }
        }
        assertEquals("courses.zju.edu.cn", ZjuClient.schoolUri("https://courses.zju.edu.cn/a").host)
        rejects { ZjuClient.validateDateRange("2026-02-29", "2026-03-01") }
        rejects { ZjuClient.validateDateRange("2026-01-01", "2026-02-01") }
        rejects { ZjuClient.validateDateRange("2026-03-02", "2026-03-01") }
        ZjuClient.validateDateRange("2028-02-29", "2028-03-30")
    }

    @Test fun rawRsaUsesPositiveUtf8AndHexExponent() {
        val password = "浙大"
        val modulus = "d5bbb96d30086ec484eba3d7f9caeb07"
        val expected = BigInteger(1, password.toByteArray(Charsets.UTF_8)).modPow(BigInteger("10001",16),BigInteger(modulus,16)).toString(16).padStart(128, '0')
        assertEquals(expected, ZjuClient.encryptPassword(password,modulus,"10001"))
        assertEquals("ae6".padStart(128, '0'), ZjuClient.encryptPassword("A", "ca1", "11"))
    }

    @Test fun materialPermissionsAndMissingStructures() {
        val root = Json.parseToJsonElement("""{"activities":[{"id":8,"type":"material","uploads":[
            {"id":1,"name":"lecture.PDF","size":9,"allow_download":true,"status":"ready"},
            {"id":2,"name":"closed.pdf","size":9,"allow_download":false,"status":"ready"},
            {"id":3,"name":"a.txt","size":9,"allow_download":true,"status":"ready"}]}]}""").jsonObject
        assertEquals(listOf(1L), ZjuClient.parseMaterials(root).map { it.uploadId })
        rejects { ZjuClient.parseMaterials(Json.parseToJsonElement("{}").jsonObject) }
        rejects { ZjuClient.parseMaterials(Json.parseToJsonElement("""{"success":false,"activities":[]}""").jsonObject) }
    }

    @Test fun loginCachingScheduleUnitsAndExplicitTranscript() {
        val requests = mutableListOf<SchoolRequest>()
        var credentialsCalls = 0
        var clock = 0L
        val transport = SchoolTransport { r ->
            requests += r
            fun response(text: String, status: Int = 200, headers: Map<String,List<String>> = emptyMap()) =
                SchoolResponse(status, headers, text.toByteArray())
            when {
                r.uri.host == "tgmedia.cmc.zju.edu.cn" -> response("""<meta http-equiv="refresh" content="0; url=https://yjapi.cmc.zju.edu.cn/ready">""")
                r.uri.path == "/cas/v2/getPubKey" -> response("""{"modulus":"ca1","exponent":"11"}""")
                r.method == "POST" -> response("",302,mapOf("Set-Cookie" to listOf("iPlanetDirectoryPro=sso-cookie; Path=/; Secure")))
                r.uri.path == "/cas/login" -> response("""<form id="fm1" action="/cas/login"><input value="token" name="execution"></form>""")
                r.uri.path == "/ready" -> response("ok",headers=mapOf("Set-Cookie" to listOf("JWTUser=%7B%22sub%22%3A%2212%22%7D; Path=/; Secure")))
                r.uri.path.contains("get-week-schedules") -> response("""{"result":{"list":[{"course":[{"id":91,"course_id":7,"course_title":"数学","start_at":100,"end_at":200}]}]}}""")
                r.uri.path.contains("search-trans-result") -> response("""{"list":[{"all_content":[{"BeginSec":1.5,"Text":"hello"}]}]}""")
                else -> error("Unexpected request")
            }
        }
        val client = ZjuClient(CredentialsProvider { credentialsCalls++; SchoolCredentials("fixture", "A") }, transport) { clock }
        val lesson = client.fetchSchedule("2026-11-01").single()
        assertEquals("91",lesson.subId); assertEquals("7",lesson.courseId)
        assertEquals(100_000L,lesson.startMillis)
        client.fetchSchedule("2026-11-02")
        assertEquals(1,credentialsCalls)
        assertFalse(requests.any { it.uri.path.contains("search-trans-result") })
        assertEquals(1.5,client.fetchTranscript("91").single().beginSeconds,0.0)
        clock = 900_000
        client.fetchSchedule("2026-11-03")
        assertEquals(2,credentialsCalls)
    }

    @Test fun redirectCannotEscapeSchoolAndAuthorizationIsNotRetried() {
        var count = 0
        val client = ZjuClient(CredentialsProvider { error("Must not request credentials") }, SchoolTransport {
            count++
            SchoolResponse(302,mapOf("Location" to listOf("https://evil.example/")),byteArrayOf())
        })
        rejects { client.listCourses() }
        assertEquals(1,count)
        val denied = ZjuClient(CredentialsProvider { error("Must not request credentials") }, SchoolTransport {
            SchoolResponse(429,emptyMap(),byteArrayOf())
        })
        rejects { denied.listCourses() }
    }
}
