package schedule.school

import java.math.BigInteger
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable data class SchoolCourse(val courseId: String, val name: String, val courseCode: String,
    val academicYear: String, val semester: String, val teachers: List<String>)
@Serializable data class SchoolLesson(val subId: String, val courseId: String?, val title: String,
    val teacher: String, val room: String, val status: String, val startMillis: Long, val endMillis: Long)
@Serializable data class TranscriptSegment(val beginSeconds: Double, val text: String, val translation: String?)
@Serializable data class CourseMaterial(val activityId: String, val uploadId: Long, val referenceId: Long?,
    val name: String, val expectedBytes: Long, val activityTitle: String = "")

/** Credentials are requested only when a new in-memory session is necessary. */
class SchoolCredentials(val username: String, val password: String) {
    override fun toString() = "SchoolCredentials(redacted)"
}
fun interface CredentialsProvider { fun get(): SchoolCredentials }
open class SchoolException(message: String) : Exception(message)
private class SchoolSessionException(message: String) : SchoolException(message)
data class SchoolRequest(val uri: URI, val method: String, val headers: Map<String, List<String>>,
    val body: ByteArray?, val maximumBytes: Int)
data class SchoolResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}
/** Implementations must disable automatic redirects and enforce maximumBytes before buffering. */
fun interface SchoolTransport { fun execute(request: SchoolRequest): SchoolResponse }

class UrlConnectionTransport : SchoolTransport {
    override fun execute(request: SchoolRequest): SchoolResponse {
        val connection = request.uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.requestMethod = request.method
            request.headers.forEach { (key, values) -> values.forEach { connection.addRequestProperty(key, it) } }
            request.body?.let { connection.doOutput = true; connection.outputStream.use { out -> out.write(it) } }
            val status = connection.responseCode
            if (connection.contentLengthLong > request.maximumBytes) throw SchoolException("响应超过大小限制")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size().toLong() + count > request.maximumBytes) throw SchoolException("响应超过大小限制")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            return SchoolResponse(status, connection.headerFields.filterKeys { it != null }, bytes)
        } catch (error: SchoolException) { throw error
        } catch (_: Exception) { throw SchoolException("学校连接失败或超时")
        } finally { connection.disconnect() }
    }
}

/** Synchronous, read-only client. Android callers must execute on their IO dispatcher. */
class ZjuClient(private val credentials: CredentialsProvider,
    private val transport: SchoolTransport = UrlConnectionTransport(),
    private val diagnostics: (String) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis) {
    private data class Session(val cookies: CookieManager, val created: Long, val userId: String?)
    private data class SsoSession(val cookie: HttpCookie, val created: Long, val casCookies: CookieManager)
    private var coursesSession: Session? = null
    private var cmcSession: Session? = null
    private var ssoSession: SsoSession? = null
    private var academicSession: Session? = null

    @Synchronized fun clearSessions() { coursesSession = null; cmcSession = null; ssoSession = null; academicSession = null }

    /** One explicit credential check; no service requests or automatic retries. */
    @Synchronized fun verifyCredentials() { clearSessions(); loginSso() }

    @Synchronized fun listCourses(): List<SchoolCourse> = withSession(false) { session ->
        val conditions = "{\"status\":[\"ongoing\",\"notStarted\",\"ended\"],\"keyword\":\"\"}"
        val rows = mutableListOf<SchoolCourse>()
        for (page in 1..100) {
            val root = json(session, "$COURSES/api/my-courses?conditions=${encode(conditions)}&page=$page&page_size=50")
            val list = root.array("courses")
            val pages = root["pages"]?.jsonPrimitive?.intOrNull ?: fail("课程分页信息缺失")
            if (pages < page || pages > 100) fail("课程分页超出限制或无效")
            list.forEach { element ->
                val c = element.obj()
                rows += SchoolCourse(c.id("id"), c.requiredText("name"), c.text("course_code"),
                    c["academic_year"]?.takeUnless { it is JsonNull }?.obj()?.text("name") ?: "",
                    c["semester"]?.takeUnless { it is JsonNull }?.obj()?.text("name") ?: "",
                    c.array("instructors").map { it.obj().requiredText("name") })
            }
            if (rows.size > 5000) fail("课程数量超过限制")
            if (page == pages) {
                root["total"]?.jsonPrimitive?.intOrNull?.let { if (it != rows.size) fail("课程分页结果不完整") }
                return@withSession rows
            }
            if (list.isEmpty()) fail("课程分页提前结束")
        }
        fail("课程分页超过限制")
    }

    @Synchronized fun fetchSchedule(startDate: String, endDate: String = startDate): List<SchoolLesson> {
        validateDateRange(startDate, endDate)
        return withSession(true) { s ->
            val root = json(s, "$YJAPI/courseapi/v2/schedule/get-week-schedules?user_id=${encode(s.userId!!)}&tenant_id=112&start_at=$startDate&end_at=$endDate")
            root.objectValue("result").array("list").flatMap { day -> day.obj().array("course").map { row ->
                val c = row.obj()
                val start = c.secondsMillis("start_at")
                val end = c.secondsMillis("end_at")
                if (end <= start) fail("课表时间区间无效")
                SchoolLesson(c.id("id"), c["course_id"]?.takeUnless { it is JsonNull }?.let { c.id("course_id") },
                    c.requiredText("course_title"), c.text("teacher_name"), c.text("room_name"), c.text("status_label"), start, end)
            } }
        }
    }

    @Synchronized fun fetchTranscript(subId: String): List<TranscriptSegment> {
        validId(subId)
        return withSession(true) { session ->
            val root = json(session, "$INTERACTIVE/courseapi/v3/web-socket/search-trans-result?sub_id=${encode(subId)}&format=json")
            val list = root.array("list")
            if (list.isEmpty()) return@withSession emptyList()
            list.flatMap { it.obj().array("all_content") }.map {
                val c = it.obj()
                val seconds = c["BeginSec"]?.jsonPrimitive?.doubleOrNull ?: fail("转录时间缺失")
                if (!seconds.isFinite() || seconds < 0) fail("转录时间无效")
                TranscriptSegment(seconds, c.requiredText("Text"), c["TransText"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content)
            }
        }
    }

    @Synchronized fun listMaterials(courseId: String): List<CourseMaterial> {
        validId(courseId)
        return withSession(false) { parseMaterials(json(it, "$COURSES/api/courses/$courseId/activities")) }
    }

    @Synchronized fun fetchAcademicSchedule(term: AcademicTerm, calendar: JsonObject): List<schedule.core.ScheduleItem> {
        val rows = term.seasons.associateWith { season ->
            val form = "xnm=${encode(term.year)}&xqm=${encode(season)}&captcha_value=null"
            val result = academicJson("/jwglxt/kbcx/xskbcx_cxXsKb.html", form)
            result["kbList"] as? JsonArray ?: fail("教务课表响应缺少 kbList")
        }
        return AcademicSchedule.lessons(term, rows, calendar)
    }

    @Synchronized fun fetchExams(): ExamSchedule = AcademicSchedule.exams(academicJson(
        "/jwglxt/xskscx/kscx_cxXsgrksIndex.html?doType=query&queryModel.showCount=5000"))

    private fun academicJson(path: String, form: String? = null): JsonObject {
        val session = academicSession?.takeIf { nowMillis() - it.created in 0 until 900_000 } ?: loginAcademic()
        val cookies = session.cookies.cookieStore.cookies.filter { it.name in setOf("JSESSIONID", "route") && !it.hasExpired() }
        val headers = mutableMapOf(
            "Cookie" to listOf(cookies.joinToString("; ") { "${it.name}=${it.value}" }),
            "Referer" to listOf("$ACADEMIC/jwglxt/xtgl/index_initMenu.html"),
            "User-Agent" to listOf(USER_AGENT),
            "Accept" to listOf("application/json, text/javascript, */*; q=0.01"),
            "X-Requested-With" to listOf("XMLHttpRequest"), "Connection" to listOf("close"))
        if (form != null) headers["Content-Type"] = listOf("application/x-www-form-urlencoded; charset=utf-8")
        val response = transport.execute(SchoolRequest(schoolUri(ACADEMIC + path), "POST", headers, form?.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024))
        if (response.status in setOf(301, 302, 303, 307, 308, 401, 403)) {
            academicSession = null
            throw SchoolSessionException("教务登录已失效（HTTP ${response.status}），请重新同步")
        }
        if (response.status !in 200..299) fail("教务接口请求失败（HTTP ${response.status}）")
        val text = response.body.toString(Charsets.UTF_8)
        if ("captcha_error" in text) fail("教务系统要求验证码，请先在教务网站完成验证后重新同步")
        if (text.trim() == "null" && path.contains("xskbcx")) return buildJsonObject { put("kbList", JsonArray(emptyList())) }
        return parseObject(text)
    }

    private fun loginAcademic(): Session {
        val sso = ssoSession?.takeIf { nowMillis() - it.created in 0 until 120_000 && !it.cookie.hasExpired() } ?: loginSso()
        val callback = "$ACADEMIC/jwglxt/xtgl/login_ssologin.html"
        val casUri = schoolUri("$CAS_LOGIN?service=${encode(callback)}")
        val exchange = transport.execute(SchoolRequest(casUri, "GET",
            mapOf("Cookie" to listOf(cookieHeader(sso.casCookies, casUri)), "User-Agent" to listOf(USER_AGENT)), null, 1024 * 1024))
        if (exchange.status !in setOf(301,302,303,307,308)) {
            val account = credentials.get()
            runCatching { diagnostics(buildJsonObject {
                put("stage", "academic service ticket"); put("status", exchange.status)
                put("responseCookieNames", JsonArray(responseCookies(exchange).map { JsonPrimitive(it.name) }))
                put("responseText", diagnosticText(exchange.body.toString(Charsets.UTF_8), listOf(account.username, account.password, sso.cookie.value)))
            }.toString()) }
            fail("教务统一认证未返回登录跳转（HTTP ${exchange.status}）")
        }
        val location = URI(CAS_LOGIN).resolve(exchange.header("Location") ?: fail("教务登录跳转缺失"))
        if (location.host != "zdbk.zju.edu.cn" || location.path != "/jwglxt/xtgl/login_ssologin.html") fail("教务登录跳转目标异常")
        val secure = schoolUri(location.toString().replaceFirst("http://", "https://"))
        // The one-use ticket is consumed by the callback; no SSO cookie is sent to this service.
        val response = transport.execute(SchoolRequest(secure, "GET", emptyMap(), null, 1024 * 1024))
        if (response.status !in 200..399) fail("教务登录入口访问失败（HTTP ${response.status}）")
        val cookies = responseCookies(response).filter { it.name in setOf("JSESSIONID", "route") && it.value.isNotBlank() && !it.hasExpired() }
        if (cookies.map { it.name }.toSet() != setOf("JSESSIONID", "route")) fail("教务登录缺少业务会话")
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        cookies.forEach { jar.cookieStore.add(URI(ACADEMIC), it) }
        return Session(jar, nowMillis(), null).also { academicSession = it }
    }

    @Synchronized fun downloadMaterial(material: CourseMaterial, maximumBytes: Int = 50 * 1024 * 1024): ByteArray {
        if (maximumBytes <= 0 || material.expectedBytes !in 1..maximumBytes.toLong()) fail("课件超过下载大小限制")
        if (material.uploadId <= 0 || (material.referenceId != null && material.referenceId <= 0)) fail("资料编号无效")
        val extension = material.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("pdf", "ppt", "pptx")) fail("资料类型不支持")
        val path = material.referenceId?.let { "reference/$it" } ?: material.uploadId.toString()
        val response = withSession(false) { request(it.cookies, "$COURSES/api/uploads/$path/blob", follow = true,
            maximum = maximumBytes, download = true).second }
        val bytes = response.body
        if (bytes.size.toLong() != material.expectedBytes) fail("课件大小不符")
        response.header("Content-Length")?.let { if (it.toLongOrNull() != bytes.size.toLong()) fail("课件大小不符") }
        if (response.header("Content-Type")?.let { it.contains("html", true) || it.contains("json", true) } == true) fail("下载返回网页或接口错误")
        val signature = when (extension) {
            "pdf" -> bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
            "ppt" -> bytes.take(8).toByteArray().contentEquals(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()))
            else -> bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
        }
        if (!signature) fail("课件内容签名无效")
        return bytes
    }

    private fun <T> withSession(cmc: Boolean, operation: (Session) -> T): T {
        val current = session(cmc)
        try { return operation(current) } catch (_: SchoolSessionException) {
            if (cmc) cmcSession = null else coursesSession = null
            ssoSession = null
        }
        // Only retry a read once after an authentication response; never loop CAS submissions.
        val renewed = session(cmc)
        try { return operation(renewed) } catch (e: SchoolSessionException) {
            if (cmc) cmcSession = null else coursesSession = null
            ssoSession = null
            throw e
        }
    }

    private fun session(cmc: Boolean): Session {
        val cached = if (cmc) cmcSession else coursesSession
        if (cached != null && nowMillis() - cached.created in 0 until 900_000) return cached
        val cachedSso = ssoSession?.takeIf { nowMillis() - it.created in 0 until 120_000 && !it.cookie.hasExpired() }
        val sso = cachedSso ?: loginSso()
        try { return loginService(cmc, sso) } catch (e: SchoolSessionException) {
            ssoSession = null
            // A reused SSO cookie may expire early. A fresh rejected login is not repeated.
            if (cachedSso == null) throw e
            return loginService(cmc, loginSso())
        }
    }

    /** Elychron flow: authenticate CAS first, then establish each service's own session. */
    private fun loginSso(): SsoSession {
        val landing = casRequest(CAS_LOGIN)
        if (landing.status != 200) fail("统一认证登录页请求失败（HTTP ${landing.status}）")
        val cookies = responseCookies(landing).toMutableList()
        val html = landing.body.toString(Charsets.UTF_8)
        val tag = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
            .firstOrNull { attribute(it, "name") == "execution" } ?: fail("CAS 登录表单缺少 execution")
        val execution = attribute(tag, "value") ?: fail("CAS 登录表单缺少 execution")
        val publicKeyResponse = casRequest("https://zjuam.zju.edu.cn/cas/v2/getPubKey", cookies)
        if (publicKeyResponse.status != 200) fail("统一认证公钥请求失败（HTTP ${publicKeyResponse.status}）")
        cookies.addAll(responseCookies(publicKeyResponse))
        val pub = parseObject(publicKeyResponse.body.toString(Charsets.UTF_8))
        val secret = credentials.get()
        if (secret.username.isBlank() || secret.password.isEmpty()) fail("请提供学校账号和密码")
        val form = mapOf("username" to secret.username, "password" to encryptPassword(secret.password, pub.requiredText("modulus"), pub.requiredText("exponent")),
            "execution" to execution, "_eventId" to "submit", "rememberMe" to "true")
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = casRequest(CAS_LOGIN, cookies, form)
        val submittedCookies = responseCookies(response)
        val cookie = submittedCookies.lastOrNull { it.name == "iPlanetDirectoryPro" && it.value.isNotBlank() && !it.hasExpired() }
        if (cookie == null) {
            val text = response.body.toString(Charsets.UTF_8)
            val hiddenValues = listOf(secret.username, secret.password, execution) + cookies.map { it.value } + submittedCookies.map { it.value } + listOf(encryptPassword(secret.password, pub.requiredText("modulus"), pub.requiredText("exponent")))
            val report = buildJsonObject {
                put("stage", "CAS password submission")
                put("status", response.status)
                put("contentType", response.header("Content-Type").orEmpty().take(100))
                put("requestCookieNames", JsonArray(cookies.map { JsonPrimitive(it.name.take(80)) }))
                put("responseCookieNames", JsonArray(submittedCookies.map { JsonPrimitive(it.name.take(80)) }))
                put("responseText", diagnosticText(text, hiddenValues))
                put("passwordHasBoundaryWhitespace", secret.password != secret.password.trim())
                put("passwordHasControlCharacters", secret.password.any { it.code < 32 })
            }
            runCatching { diagnostics(report.toString()) }
            fail(loginRejection(text, response.status, submittedCookies.map { it.name }, secret))
        }
        // This school SSO cookie is explicitly shared with school services by Elychron.
        val shared = (cookie.clone() as HttpCookie).apply { domain = ".zju.edu.cn"; path = "/"; secure = true }
        val casJar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        casJar.put(URI(CAS_LOGIN), landing.headers)
        casJar.put(URI("https://zjuam.zju.edu.cn/cas/v2/getPubKey"), publicKeyResponse.headers)
        casJar.put(URI(CAS_LOGIN), response.headers)
        casJar.cookieStore.cookies.filter { it.name == shared.name }.forEach { casJar.cookieStore.remove(URI(CAS_LOGIN), it) }
        casJar.cookieStore.add(URI(CAS_LOGIN), shared)
        runCatching { diagnostics(buildJsonObject {
            put("stage", "CAS session established")
            put("responseCookieNames", JsonArray(submittedCookies.map { JsonPrimitive(it.name) }))
            put("sessionCookieNames", JsonArray(casJar.cookieStore.cookies.map { JsonPrimitive(it.name) }))
        }.toString()) }
        return SsoSession(shared, nowMillis(), casJar).also { ssoSession = it }
    }

    /** CAS has three fixed same-host requests. Preserve the response cookie list exactly
     * as Elychron 1.4.1 does; service cookie policy is applied only after authentication. */
    private fun casRequest(url: String, cookies: List<HttpCookie> = emptyList(), form: String? = null): SchoolResponse {
        val uri = schoolUri(url)
        require(uri.host == "zjuam.zju.edu.cn" && uri.path in setOf("/cas/login", "/cas/v2/getPubKey"))
        val headers = mutableMapOf("User-Agent" to listOf(USER_AGENT))
        if (cookies.isNotEmpty()) headers["Cookie"] = listOf(cookies.joinToString("; ") { "${it.name}=${it.value}" })
        if (form != null) headers["Content-Type"] = listOf("application/x-www-form-urlencoded; charset=utf-8")
        val response = try { transport.execute(SchoolRequest(uri, if (form == null) "GET" else "POST", headers, form?.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024)) }
            catch (e: SchoolException) { throw e } catch (_: Exception) { fail("学校连接失败或超时") }
        if (response.body.size > 8 * 1024 * 1024) fail("响应超过大小限制")
        if (response.status in setOf(401, 403)) throw SchoolSessionException("统一身份认证访问被拒绝（HTTP ${response.status}，${uri.host}${uri.path}），当前输入未保存")
        if (response.status == 429) fail("学校请求过于频繁，请稍后操作")
        if (response.status !in 200..399) fail("统一认证接口失败（HTTP ${response.status}）")
        return response
    }

    private fun responseCookies(response: SchoolResponse): List<HttpCookie> =
        response.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.flatMap {
            try { HttpCookie.parse(it) } catch (_: IllegalArgumentException) { fail("统一认证响应的 Cookie 格式无法解析，当前输入未保存") }
        }

    private fun loginService(cmc: Boolean, sso: SsoSession): Session {
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        sso.casCookies.cookieStore.cookies.forEach { jar.cookieStore.add(URI(CAS_LOGIN), it.clone() as HttpCookie) }
        val result = request(jar, if (cmc) "https://tgmedia.cmc.zju.edu.cn/" else "$COURSES/user/index", follow = true)
        if (result.first.host == "zjuam.zju.edu.cn" || result.first.host == "identity.zju.edu.cn") {
            throw SchoolSessionException("学校登录停在认证页面，请重新登录")
        }
        val user = if (cmc) {
            val raw = jar.cookieStore.get(URI(YJAPI)).lastOrNull { it.name == "JWTUser" && !it.hasExpired() }?.value ?: fail("智云登录后缺少 JWTUser")
            val info = parseObject(URLDecoder.decode(raw, "UTF-8"))
            listOf("sub", "user_id", "id").firstNotNullOfOrNull { key -> info[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content }
                ?.also { validId(it) } ?: fail("登录后缺少用户编号")
        } else {
            if (result.first.host != "courses.zju.edu.cn" || jar.cookieStore.get(URI("$COURSES/api/my-courses")).none { it.name == "session" && !it.hasExpired() }) {
                throw SchoolSessionException("学在浙大登录后未取得业务会话")
            }
            null
        }
        return Session(jar, nowMillis(), user).also { if (cmc) cmcSession = it else coursesSession = it }
    }

    private fun json(s: Session, url: String): JsonObject = parseObject(request(s.cookies, url).second.body.toString(Charsets.UTF_8))

    private fun request(jar: CookieManager, url: String, follow: Boolean = false,
        maximum: Int = 8 * 1024 * 1024, download: Boolean = false): Pair<URI, SchoolResponse> {
        var uri = schoolUri(url)
        repeat(16) { hop ->
            val headers = mutableMapOf("User-Agent" to listOf(USER_AGENT))
            if (!follow) headers["Accept"] = listOf("application/json")
            if (!download || uri.host == "courses.zju.edu.cn") {
                // HttpCookie may infer obsolete RFC 2965 version 1 from Max-Age.
                // Browsers and Elychron send one Cookie header containing name=value pairs.
                val cookies = cookieHeader(jar, uri)
                if (cookies.isNotBlank()) headers["Cookie"] = listOf(cookies)
            }
            val r = try { transport.execute(SchoolRequest(uri, "GET", headers, null, maximum)) }
                catch (e: SchoolException) { throw e } catch (_: Exception) { fail("学校连接失败或超时") }
            if (r.body.size > maximum) fail("响应超过大小限制")
            if (!download || uri.host == "courses.zju.edu.cn") jar.put(uri, r.headers)
            if (r.status in setOf(401,403)) {
                runCatching { diagnostics(buildJsonObject {
                    put("stage", "service login"); put("status", r.status)
                    put("endpoint", "${uri.host}${uri.path}")
                    put("requestCookieNames", JsonArray(headers["Cookie"].orEmpty().flatMap { it.split(';') }.map { JsonPrimitive(it.substringBefore('=').trim()) }))
                    put("responseCookieNames", JsonArray(responseCookies(r).map { JsonPrimitive(it.name) }))
                }.toString()) }
                val stage = if (uri.host == "zjuam.zju.edu.cn") "统一身份认证" else if (uri.path.startsWith("/api/") || uri.path.startsWith("/courseapi/")) "学校数据接口" else "学校登录入口"
                throw SchoolSessionException("$stage 访问被拒绝（HTTP ${r.status}，${uri.host}${uri.path}）。请检查账号权限或学校登录验证")
            }
            if (r.status == 429) fail("学校请求过于频繁，请稍后操作")
            if (r.status in setOf(301,302,303,307,308)) {
                if (!follow) throw SchoolSessionException("学校接口要求重新登录（${uri.host}）")
                if (hop == 15) fail("学校重定向次数过多")
                uri = schoolUri(uri.resolve(r.header("Location") ?: fail("重定向缺少目标")).toString())
            } else {
                if (r.status !in 200..299) fail("学校接口失败 HTTP ${r.status}")
                val refresh = if (follow && !download) metaRefresh(r.body.toString(Charsets.UTF_8), uri) else null
                if (refresh != null) {
                    if (hop == 15) fail("学校重定向次数过多")
                    uri = refresh
                    return@repeat
                }
                return uri to r
            }
        }
        fail("学校重定向次数过多")
    }

    companion object {
        private fun cookieHeader(jar: CookieManager, uri: URI): String {
            jar.cookieStore.cookies.forEach { it.version = 0 }
            return jar.get(uri, emptyMap()).entries.filter { it.key.equals("Cookie", true) }.flatMap { it.value }.filter { it.isNotBlank() }.joinToString("; ")
        }
        private const val CAS_LOGIN = "https://zjuam.zju.edu.cn/cas/login"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36 Edg/110.0.1587.63"
        private const val COURSES = "https://courses.zju.edu.cn"
        private const val ACADEMIC = "https://zdbk.zju.edu.cn"
        private const val YJAPI = "https://yjapi.cmc.zju.edu.cn"
        private const val INTERACTIVE = "https://interactivemeta.cmc.zju.edu.cn"
        fun schoolUri(value: String): URI {
            val uri = try { URI(value) } catch (_: Exception) { fail("学校目标地址无效") }
            val host = uri.host?.lowercase() ?: fail("学校目标地址无效")
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1,443) ||
                !(host == "zju.edu.cn" || host.endsWith(".zju.edu.cn"))) fail("目标必须为学校 HTTPS 域名")
            return uri
        }
        fun encryptPassword(password: String, modulusHex: String, exponentHex: String): String {
            if (!Regex("[0-9a-fA-F]+").matches(modulusHex) || !Regex("[0-9a-fA-F]+").matches(exponentHex)) fail("CAS 公钥无效")
            val n = BigInteger(modulusHex, 16); val e = BigInteger(exponentHex, 16)
            if (n.signum() <= 0 || e.signum() <= 0 || n.bitLength() > 16384 || e.bitLength() > 64) fail("CAS 公钥无效")
            return BigInteger(1, password.toByteArray(Charsets.UTF_8)).modPow(e, n).toString(16).padStart(128, '0')
        }
        fun validateDateRange(start: String, end: String) {
            val pattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
            if (!pattern.matches(start) || !pattern.matches(end)) fail("日期须为 YYYY-MM-DD")
            val days = try { ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) } catch (_: Exception) { fail("日期无效") }
            if (days !in 0..30) fail("课表日期范围须为 1 至 31 天")
        }
        fun parseMaterials(root: JsonObject): List<CourseMaterial> {
            checkBusiness(root)
            val seen = mutableSetOf<String>()
            return root.array("activities").flatMap { element ->
                val activity = element.obj()
                if (activity.text("type") != "material") emptyList() else activity.array("uploads").mapNotNull { row ->
                    val u = row.obj(); val id = u.id("id").toLongOrNull() ?: fail("资料编号无效")
                    if (u["deleted"]?.jsonPrimitive?.booleanOrNull == true || u["allow_download"]?.jsonPrimitive?.booleanOrNull != true || u.text("status") != "ready") return@mapNotNull null
                    val name = u.requiredText("name")
                    if (name.substringAfterLast('.', "").lowercase() !in setOf("pdf","ppt","pptx")) return@mapNotNull null
                    val size = u["size"]?.jsonPrimitive?.longOrNull ?: fail("资料大小缺失")
                    if (size <= 0) fail("资料大小无效")
                    val ref = u["reference_id"]?.takeUnless { it is JsonNull }?.let { u.id("reference_id").toLongOrNull() ?: fail("资料引用编号无效") }
                    if (!seen.add("${activity.id("id")}:$id:$ref")) return@mapNotNull null
                    CourseMaterial(activity.id("id"), id, ref, name, size, activity.text("title"))
                }
            }
        }
        private fun attribute(tag: String, name: String) = Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)
        private fun diagnosticText(body: String, hiddenValues: List<String>): String {
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            var text = if (json != null) {
                json.filterKeys { it in setOf("message", "msg", "error", "error_description", "code", "status") }.map { (key, value) ->
                    "$key: " + ((value as? JsonPrimitive)?.contentOrNull ?: "[structured value omitted]")
                }.joinToString("; ").ifBlank { "JSON response; message field absent" }
            } else {
                body.replace(Regex("<!--.*?-->|<(script|style|textarea)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<[^>]*>"), " ")
            }
            text = decodeEntities(text)
            hiddenValues.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { text = text.replace(it, "[hidden]") }
            text = text.replace(Regex("(?i)(token|ticket|execution|session|cookie|password)\\s*[:=]\\s*[^\\s;]+"), "$1=[hidden]")
                .replace(Regex("\\s+"), " ").trim()
            return if (text.length <= 3500) text else text.take(2500) + " […] " + text.takeLast(1000)
        }
        private fun decodeEntities(text: String): String = text.replace(Regex("&(#x[0-9a-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);", RegexOption.IGNORE_CASE)) { match ->
            val entity = match.groupValues[1].lowercase()
            val scalar = when { entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16); entity.startsWith('#') -> entity.drop(1).toIntOrNull(); else -> null }
            if (scalar != null && scalar in 1..0x10ffff && scalar !in 0xd800..0xdfff) String(Character.toChars(scalar))
            else mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")[entity] ?: match.value
        }
        private fun loginRejection(html: String, status: Int, cookieNames: List<String>, credentials: SchoolCredentials): String {
            val encoded = Regex("<(?:p|span|div)\\b[^>]*id\\s*=\\s*[\"'](?:errormsg|msg|msg\\.errors|error-message)[\"'][^>]*>(.*?)</(?:p|span|div)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1).orEmpty().replace(Regex("<[^>]*>"), "")
            val message = decodeEntities(encoded).replace(Regex("\\s+"), " ").trim()
            val safeMessage = message.replace(credentials.username, "[已隐藏]").replace(credentials.password, "[已隐藏]").take(180)
            return when {
                listOf("锁定", "冻结", "次数", "频繁").any { it in message } -> "学校账号已锁定或尝试过于频繁，请按官网提示解除限制后再验证"
                "验证码" in message -> "学校要求验证码，请先在官网完成验证"
                listOf("密码", "用户名", "用户", "账号").any { it in message } && listOf("错误", "不正确", "不存在", "不匹配").any { it in message } -> "学号或密码不正确，请检查后重新输入"
                safeMessage.isNotBlank() -> "学校返回：$safeMessage（HTTP $status）；当前输入未保存"
                else -> "认证提交返回 HTTP $status，但没有返回 SSO 登录凭据。" +
                    (if (cookieNames.any { it.equals("CASTGC", true) || it.equals("TGC", true) }) "服务器返回了其他类型的认证 Cookie，需要核对登录协议。" else "暂不能判断是账号问题还是登录协议不匹配。") +
                    "当前输入未保存。"
            }
        }
        private fun metaRefresh(html: String, source: URI): URI? {
            val tag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }
                .firstOrNull { attribute(it, "http-equiv")?.equals("refresh", true) == true } ?: return null
            val content = attribute(tag, "content") ?: return null
            val target = Regex("(?:^|;)\\s*url\\s*=\\s*(.+)", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
                ?.trim()?.trim('\'', '"')?.replace("&amp;", "&") ?: return null
            return schoolUri(source.resolve(target).toString())
        }
        private fun parseObject(text: String): JsonObject {
            val root = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { fail("学校接口返回无效 JSON，请检查会话") }
            checkBusiness(root)
            return root
        }
        private fun checkBusiness(root: JsonObject) {
            if (root["success"]?.jsonPrimitive?.booleanOrNull == false || root["error"]?.let { it !is JsonNull && it != JsonPrimitive(false) && it != JsonPrimitive("") } == true) fail("学校接口报告业务错误")
            root["code"]?.jsonPrimitive?.content?.let { if (it !in setOf("0", "200")) fail("学校接口报告业务错误") }
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun validId(value: String) { if (!Regex("[0-9]+").matches(value) || value.all { it == '0' }) fail("学校编号无效") }
        private fun JsonElement.obj() = this as? JsonObject ?: fail("学校接口对象结构缺失")
        private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: fail("学校接口列表 $key 缺失")
        private fun JsonObject.objectValue(key: String) = this[key] as? JsonObject ?: fail("学校接口对象 $key 缺失")
        private fun JsonObject.text(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""
        private fun JsonObject.requiredText(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: fail("学校接口字段 $key 缺失")
        private fun JsonObject.id(key: String) = requiredText(key).also { validId(it) }
        private fun JsonObject.secondsMillis(key: String): Long {
            val seconds = this[key]?.jsonPrimitive?.longOrNull ?: fail("课表时间缺失")
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) fail("课表时间无效")
            return seconds * 1000
        }
        private fun fail(message: String): Nothing = throw SchoolException(message)
    }
}
