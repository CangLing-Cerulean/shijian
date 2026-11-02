package schedule.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import schedule.core.*
import java.net.HttpURLConnection
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException

/** OpenAI-compatible Chat Completions protocol. Endpoint is explicit, not guessed from a provider name. */
@Serializable data class AiModel(val id: String, val supportsImages: Boolean = false)
@Serializable data class AiConfiguration(val endpoint: String, val model: String, val supportsImages: Boolean = false, val fallbackModels: List<AiModel> = emptyList()) {
    val models: List<AiModel> get() = (listOf(AiModel(model, supportsImages)) + fallbackModels).distinctBy { it.id }
    val canUseImages: Boolean get() = models.any { it.supportsImages }
    fun validate() {
        AiEndpoints.chat(endpoint)
        require(models.all { it.id.isNotBlank() }) { "模型名称不能为空" }
    }
}
object AiEndpoints {
    fun chat(input: String): String {
        val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("API地址无效") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) { "API地址必须为不含凭据或查询参数的HTTPS地址" }
        val path = uri.path.orEmpty().trimEnd('/')
        val target = when { path.endsWith("/chat/completions") -> path; path.isEmpty() -> "/v1/chat/completions"; else -> "$path/chat/completions" }
        return URI("https", null, uri.host, uri.port, target, null, null).toASCIIString()
    }
    fun models(input: String): String = chat(input).removeSuffix("/chat/completions") + "/models"
}
data class AiImage(val mimeType: String, val bytes: ByteArray) {
    fun dataUrl(): String {
        require(bytes.size in 1..5 * 1024 * 1024 && mimeType in setOf("image/png", "image/jpeg", "image/webp")) { "图片格式或大小不支持" }
        return "data:$mimeType;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
data class ApiResponse(val status: Int, val body: String, val retryAfterMillis: Long? = null)
fun interface AiTransport {
    fun post(endpoint: String, apiKey: String, body: String): ApiResponse
    fun get(endpoint: String, apiKey: String): ApiResponse = throw UnsupportedOperationException("GET不可用")
}

class HttpsAiTransport : AiTransport {
    override fun post(endpoint: String, apiKey: String, body: String): ApiResponse {
        return request(endpoint, apiKey, body)
    }
    override fun get(endpoint: String, apiKey: String): ApiResponse = request(endpoint, apiKey, null)
    private fun request(endpoint: String, apiKey: String, body: String?): ApiResponse {
        AiConfiguration(endpoint, "validation").validate()
        require(apiKey.isNotBlank() && !apiKey.contains('\n') && !apiKey.contains('\r')) { "API密钥无效" }
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.instanceFollowRedirects = false // Never forward a key to a redirect host.
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val retryAfter = connection.getHeaderField("Retry-After")?.let { value ->
                    value.trim().toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(86400)?.times(1000)
                        ?: runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().minus(System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull()
                }
                return ApiResponse(status, "", retryAfter)
            }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 2 * 1024 * 1024) { "AI响应过大" }
                    output.write(buffer, 0, n)
                }
            }
            return ApiResponse(status, output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }
}

class AiClient(private val config: AiConfiguration, private val apiKey: () -> String, private val transport: AiTransport = HttpsAiTransport(), private val diagnostics: (String) -> Unit = {}, private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    private class RequestFailure(message: String) : IllegalStateException(message)
    init { config.validate() }
    fun userMessage(user: String, images: List<AiImage> = emptyList()): JsonObject {
        require(images.size <= 4 && (images.isEmpty() || config.canUseImages)) { "当前模型未开启图片支持，或图片数量过多" }
        return buildJsonObject {
            put("role", "user")
            if (images.isEmpty()) put("content", user) else putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", user) })
                images.forEach { image -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", image.dataUrl()) } }) }
            }
        }
    }
    fun turn(messages: List<JsonObject>, tools: JsonArray = JsonArray(emptyList()), cancelled: () -> Boolean = { false }): JsonObject {
        val hasImages = messages.any { message -> (message["content"] as? JsonArray)?.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image_url" } == true }
        val candidates = config.models.filter { !hasImages || it.supportsImages }
        require(candidates.isNotEmpty()) { "没有可处理图片的模型" }
        val failures = mutableListOf<String>()
        for ((index, model) in candidates.withIndex()) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            try { return request(model.id, messages, tools, cancelled) }
            catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException() }
            catch (e: Exception) {
                val reason = when (e) {
                    is RequestFailure -> e.message!!
                    is java.net.SocketTimeoutException -> "连接或等待回复超时"
                    is java.net.UnknownHostException -> "无法解析AI服务域名"
                    is javax.net.ssl.SSLException -> "HTTPS安全连接失败"
                    is java.net.ConnectException -> "无法连接AI服务"
                    is java.io.IOException -> "网络连接中断"
                    else -> "客户端处理异常"
                }
                report(buildJsonObject { put("stage", "failure"); put("category", e.javaClass.simpleName); put("reason", reason) })
                failures.add("候选模型${index + 1}：$reason")
            }
        }
        error("AI请求失败：" + failures.joinToString("；"))
    }
    private fun report(value: JsonObject) { runCatching { diagnostics(buildJsonObject { put("at", Instant.now().toString()); value.forEach { (key, entry) -> put(key, entry) } }.toString()) } }
    private fun request(model: String, messages: List<JsonObject>, tools: JsonArray, cancelled: () -> Boolean): JsonObject {
        val request = buildJsonObject {
            put("model", model); put("stream", false); put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) { put("tools", tools); put("tool_choice", "auto") }
        }
        if (request.toString().length > 30 * 1024 * 1024) throw RequestFailure("对话过长，请新建对话")
        report(buildJsonObject { put("stage", "request"); put("messages", messages.size); put("tools", tools.size) })
        var response = transport.post(AiEndpoints.chat(config.endpoint), apiKey(), request.toString())
        var retries = 0
        while (response.status == 429 && retries < MAX_RATE_RETRIES) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            val wait = response.retryAfterMillis?.coerceAtLeast(1000) ?: (5000L shl retries)
            if (wait > MAX_RATE_WAIT_MS) break
            report(buildJsonObject { put("stage", "rate_limit_wait"); put("status", 429); put("waitMillis", wait); put("retry", retries + 1) })
            pause(wait)
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            retries++
            response = transport.post(AiEndpoints.chat(config.endpoint), apiKey(), request.toString())
        }
        report(buildJsonObject { put("stage", "response"); put("status", response.status); put("characters", response.body.length) })
        if (response.status !in 200..299) throw RequestFailure(when (response.status) { 401, 403 -> "AI服务拒绝访问，请检查密钥和模型权限"; 429 -> "AI服务限流或配额不足（HTTP 429），请稍后重试或检查接口额度"; in 300..399 -> "API地址发生重定向，请填写最终接口地址"; else -> "AI请求失败（HTTP ${response.status}）" })
        val root = try { scheduleJson.parseToJsonElement(response.body) as? JsonObject } catch (_: Exception) { null }
            ?: throw RequestFailure("AI服务返回的内容不是有效JSON")
        val choice = ((root["choices"] as? JsonArray)?.singleOrNull() as? JsonObject) ?: throw RequestFailure("AI响应缺少唯一的choices结果")
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish !in setOf("stop", "tool_calls")) throw RequestFailure(when (finish) {
            "length" -> "模型输出达到长度上限，回复被截断"
            "content_filter" -> "AI服务拦截了本次回复"
            else -> "AI响应结束标记缺失或不受支持"
        })
        val message = choice["message"] as? JsonObject ?: throw RequestFailure("AI响应缺少message")
        if ((message["role"] as? JsonPrimitive)?.contentOrNull != "assistant") throw RequestFailure("AI消息角色无效")
        if ((message["content"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() && (message["tool_calls"] as? JsonArray).isNullOrEmpty()) throw RequestFailure("AI返回空内容")
        report(buildJsonObject { put("stage", "accepted"); put("toolCalls", (message["tool_calls"] as? JsonArray)?.size ?: 0) })
        return message
    }
    companion object {
        private const val MAX_RATE_RETRIES = 2
        private const val MAX_RATE_WAIT_MS = 30000L
    }
    fun complete(system: String, user: String, images: List<AiImage> = emptyList()): String {
        require(images.size <= 4 && (images.isEmpty() || config.canUseImages)) { "当前模型未开启图片支持，或图片数量过多" }
        val userContent: JsonElement = if (images.isEmpty()) JsonPrimitive(user) else buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", user) })
            images.forEach { image -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", image.dataUrl()) } }) }
        }
        val message = turn(listOf(buildJsonObject { put("role", "system"); put("content", system) }, buildJsonObject { put("role", "user"); put("content", userContent) }))
        require(message["tool_calls"]?.jsonArray.isNullOrEmpty()) { "总结接口不接受工具调用" }
        val content = message["content"]?.jsonPrimitive?.contentOrNull
        return requireNotNull(content?.takeIf { it.isNotBlank() }) { "AI返回空内容" }
    }
}

data class AiProbeResult(val connected: Boolean, val supportsImages: Boolean, val detail: String)

class AiDiscovery(private val transport: AiTransport = HttpsAiTransport()) {
    fun discover(endpoint: String, key: String): List<String> {
        val url = AiEndpoints.models(endpoint)
        try {
            val response = transport.get(url, key)
            check(response.status in 200..299)
            val data = scheduleJson.parseToJsonElement(response.body).jsonObject.getValue("data").jsonArray
            return data.map { it.jsonObject.getValue("id").jsonPrimitive.content }.filter { it.isNotBlank() }.distinct()
        } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException() }
        catch (_: Exception) { error("获取模型列表失败，请检查地址、密钥及服务状态") }
    }
    fun probe(endpoint: String, key: String, model: String): AiProbeResult {
        val client = AiClient(AiConfiguration(endpoint, model, true), { key }, transport)
        try { client.complete("Reply briefly.", "Reply OK.") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return AiProbeResult(false, false, "文本连接测试失败，请检查地址、密钥及模型权限") }
        return try {
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC")
            val result = client.complete("Identify the image content accurately.", "What solid color is this image? Answer with one English color word only.", listOf(AiImage("image/png", png)))
            if (result.trim().trim('.', '!', '"').equals("red", ignoreCase = true)) AiProbeResult(true, true, "文本连接及图片内容识别通过")
            else AiProbeResult(true, false, "文本连接通过；图片识别能力未验证")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { AiProbeResult(true, false, "文本连接通过；图片测试失败，图片能力未验证") }
    }
}

object AiDraftParser {
    fun changes(actions: JsonArray, state: ScheduleState): List<Change> {
        require(actions.size <= 100) { "单次提议过大" }
        val items = state.items.associateBy { it.id }
        return actions.map { node ->
            val action = node.jsonObject
            require(action.keys == setOf("operation", "itemId", "fields", "reason")) { "AI调整字段无效" }
            val id = action.getValue("itemId").jsonPrimitive.content
            val reason = action.getValue("reason").jsonPrimitive.content
            val fields = action.getValue("fields").jsonObject
            val old = items[id]
            val replacement = when (action.getValue("operation").jsonPrimitive.content) {
                "create" -> { require(old == null) { "新增编号已存在" }; patch(ScheduleItem(id = id, title = ""), fields) }
                "update" -> patch(requireNotNull(old) { "修改目标不存在" }, fields)
                "delete" -> { require(old != null && fields.isEmpty()) { "删除请求无效" }; null }
                else -> error("不支持的AI操作")
            }
            Change(id, replacement, reason)
        }
    }
    private fun patch(old: ScheduleItem, fields: JsonObject): ScheduleItem {
        val allowed = setOf("title", "kind", "startAt", "deadlineAt", "endAt", "notes", "location", "teacher", "priority", "parentId", "courseId", "dailyTimes", "reminders", "repeat")
        require(fields.keys.all { it in allowed }) { "AI试图修改受保护字段" }
        fun text(key: String, previous: String): String = if (key in fields) fields.getValue(key).jsonPrimitive.content.also { require(fields[key] != JsonNull) } else previous
        fun optionalText(key: String, previous: String?): String? = if (key !in fields) previous else fields[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        fun time(key: String, previous: Long?): Long? = if (key !in fields) previous else optionalText(key, null)?.let { Instant.parse(it).toEpochMilli() }
        return old.copy(
            title = text("title", old.title), kind = ItemKind.valueOf(text("kind", old.kind.name)),
            startAt = time("startAt", old.startAt), deadlineAt = time("deadlineAt", old.deadlineAt), endAt = time("endAt", old.endAt),
            notes = text("notes", old.notes), location = text("location", old.location),
            priority = fields["priority"]?.jsonPrimitive?.int ?: old.priority,
            parentId = optionalText("parentId", old.parentId), courseId = optionalText("courseId", old.courseId),
            teacher = text("teacher", old.teacher),
            dailyTimes = fields["dailyTimes"]?.let { scheduleJson.decodeFromJsonElement<List<String>>(it) } ?: old.dailyTimes,
            reminders = fields["reminders"]?.let { scheduleJson.decodeFromJsonElement<List<Reminder>>(it) } ?: old.reminders,
            repeat = if ("repeat" !in fields) old.repeat else fields["repeat"]?.takeUnless { it == JsonNull }?.jsonObject?.let { rule ->
                require(rule.keys.all { it in setOf("frequency", "interval", "until", "weekdays") }) { "重复规则字段无效" }
                RepeatRule(RepeatFrequency.valueOf(rule.getValue("frequency").jsonPrimitive.content),
                    rule["interval"]?.jsonPrimitive?.int ?: 1,
                    rule["until"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it).toEpochMilli() },
                    rule["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet().orEmpty())
            },
        )
    }
    fun itemContext(item: ScheduleItem): JsonObject = buildJsonObject {
        val base = scheduleJson.encodeToJsonElement(item).jsonObject
        base.forEach { (key, value) ->
            if (key in setOf("startAt", "deadlineAt", "endAt")) put(key, value.takeUnless { it == JsonNull }?.jsonPrimitive?.long?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
            else if (key == "repeat" && item.repeat != null) put(key, buildJsonObject {
                value.jsonObject.forEach { (field, entry) ->
                    if (field == "until") put(field, item.repeat.until?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                    else put(field, entry)
                }
            }) else put(key, value)
        }
    }
}
