package schedule.ai

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import schedule.core.scheduleJson
import java.util.concurrent.CancellationException

class AiClientTest {
    @Test fun rateLimitRetriesSameRequestAndRespectsServerDelay() {
        val waits = mutableListOf<Long>()
        val bodies = mutableListOf<String>()
        val client = AiClient(AiConfiguration("https://example.com", "model"), { "key" }, AiTransport { _, _, body ->
            bodies += body
            if (bodies.size == 1) ApiResponse(429, "", 7000) else reply()
        }, pause = { waits += it })
        assertEquals("OK", client.complete("s", "u"))
        assertEquals(listOf(7000L), waits)
        assertEquals(bodies.first(), bodies.last())
    }
    @Test fun persistentRateLimitIsBounded() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val client = AiClient(AiConfiguration("https://example.com", "model"), { "key" }, AiTransport { _, _, _ -> calls++; ApiResponse(429, "") }, pause = { waits += it })
        assertTrue(assertThrows(IllegalStateException::class.java) { client.complete("s", "u") }.message!!.contains("429"))
        assertEquals(3, calls)
        assertEquals(listOf(5000L, 10000L), waits)
    }
    @Test fun diagnosticSeparatesTimeoutAndTruncationWithoutLoggingContent() {
        val reports = mutableListOf<String>()
        val config = AiConfiguration("https://example.com", "model")
        val timeout = AiClient(config, { "secret" }, AiTransport { _, _, _ -> throw java.net.SocketTimeoutException("secret") }, reports::add)
        assertTrue(assertThrows(IllegalStateException::class.java) { timeout.complete("private", "private") }.message!!.contains("超时"))
        val truncated = AiClient(config, { "secret" }, AiTransport { _, _, _ -> ApiResponse(200, """{"choices":[{"finish_reason":"length","message":{"content":"private"}}]}""") }, reports::add)
        assertTrue(assertThrows(IllegalStateException::class.java) { truncated.complete("private", "private") }.message!!.contains("截断"))
        assertFalse(reports.joinToString().contains("secret"))
        assertFalse(reports.joinToString().contains("private"))
    }
    private fun reply(text: String = "OK") = ApiResponse(200, buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") { put("role", "assistant"); put("content", text) }
        }) }
    }.toString())
    @Test fun endpointNormalizationAndDiscovery() {
        for (input in listOf("https://example.com", "https://example.com/v1/", "https://example.com/v1/chat/completions")) {
            assertEquals("https://example.com/v1/chat/completions", AiEndpoints.chat(input))
        }
        assertEquals("https://example.com/custom/models", AiEndpoints.models("https://example.com/custom"))
        assertThrows(IllegalArgumentException::class.java) { AiEndpoints.chat("https://key@example.com") }
        val discovery = AiDiscovery(object : AiTransport {
            override fun post(endpoint: String, apiKey: String, body: String) = error("unexpected POST")
            override fun get(endpoint: String, apiKey: String): ApiResponse {
                assertEquals("https://example.com/v1/models", endpoint)
                return ApiResponse(200, """{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}""")
            }
        })
        assertEquals(listOf("a", "b"), discovery.discover("https://example.com", "key"))
    }
    @Test fun fallbackPreservesHistoryAndRoutesImages() {
        val attempted = mutableListOf<String>()
        val config = AiConfiguration("https://example.com", "primary", false, listOf(AiModel("backup"), AiModel("vision", true)))
        val client = AiClient(config, { "key" }, AiTransport { endpoint, _, body ->
            assertEquals("https://example.com/v1/chat/completions", endpoint)
            val request = scheduleJson.parseToJsonElement(body).jsonObject
            val model = request.getValue("model").jsonPrimitive.content
            attempted.add(model)
            if (model == "primary") ApiResponse(503, "secret") else reply()
        })
        assertEquals("OK", client.complete("system", "hello"))
        assertEquals(listOf("primary", "backup"), attempted)
        attempted.clear()
        val image = client.userMessage("picture", listOf(AiImage("image/png", byteArrayOf(1))))
        client.turn(listOf(image, client.userMessage("follow up")))
        assertEquals(listOf("vision"), attempted)
    }
    @Test fun errorsAreSanitizedAndCancellationStopsFallback() {
        val config = AiConfiguration("https://example.com", "a", fallbackModels = listOf(AiModel("b")))
        var calls = 0
        val client = AiClient(config, { "secret" }, AiTransport { _, _, _ -> calls++; error("secret provider body") })
        val failure = assertThrows(IllegalStateException::class.java) { client.complete("s", "u") }
        assertEquals(2, calls)
        assertFalse(failure.message!!.contains("secret"))
        calls = 0
        val cancelled = AiClient(config, { "key" }, AiTransport { _, _, _ -> calls++; throw CancellationException() })
        assertThrows(CancellationException::class.java) { cancelled.complete("s", "u") }
        assertEquals(1, calls)
    }
    @Test fun probeRequiresVisualAnswer() {
        var count = 0
        val verified = AiDiscovery(AiTransport { _, _, body ->
            count++
            if (count == 2) assertTrue(body.contains("data:image/png;base64,"))
            reply(if (count == 1) "OK" else "red")
        }).probe("https://example.com", "key", "model")
        assertTrue(verified.connected); assertTrue(verified.supportsImages)
        val ignored = AiDiscovery(AiTransport { _, _, _ -> reply() }).probe("https://example.com", "key", "model")
        assertTrue(ignored.connected); assertFalse(ignored.supportsImages)
        assertTrue(ignored.detail.contains("未验证"))
    }
    @Test fun emptyAssistantFallsBackAndHttpReasonSurvives() {
        var calls = 0
        val config = AiConfiguration("https://example.com", "a", fallbackModels = listOf(AiModel("b")))
        val client = AiClient(config, { "key" }, AiTransport { _, _, _ -> if (++calls == 1) reply("") else reply("backup") })
        assertEquals("backup", client.complete("s", "u"))
        assertEquals(2, calls)
        val denied = AiClient(config, { "key" }, AiTransport { _, _, _ -> ApiResponse(401, "secret") })
        val error = assertThrows(IllegalStateException::class.java) { denied.complete("s", "u") }
        assertTrue(error.message!!.contains("拒绝访问"))
        assertFalse(error.message!!.contains("secret"))
    }
}
