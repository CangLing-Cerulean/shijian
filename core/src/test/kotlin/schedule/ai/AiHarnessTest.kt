package schedule.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import schedule.core.scheduleJson
import java.util.concurrent.CancellationException

class AiHarnessTest {
    @Test fun oversizedResultIsReadInPagesWithoutLossOrTurnFailure() {
        val value = buildJsonObject { put("transcript", "课堂内容\\n😀".repeat(10000)) }
        val collected = StringBuilder()
        var turns = 0
        val harness = AiHarness(client { request ->
            if (++turns == 1) call("large") else {
                val page = scheduleJson.parseToJsonElement(request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                assertEquals(collected.length, page.getValue("offset").jsonPrimitive.int)
                collected.append(page.getValue("content").jsonPrimitive.content)
                val next = page["nextOffset"]?.jsonPrimitive?.intOrNull
                if (next == null) answer("读取完成") else call("read_tool_result", buildJsonObject { put("resultId", page.getValue("resultId")); put("offset", next) }.toString())
            }
        }, listOf(HarnessTool(schema("large"), "读取转录") { ToolExecution(value) }))
        assertEquals("读取完成", harness.respond("读取全部结果").text)
        assertEquals(value.toString(), collected.toString())
        assertTrue(turns > 2)
    }
    private fun client(respond: (JsonObject) -> JsonObject) = AiClient(AiConfiguration("https://example.com/v1", "model"), { "fixture" }, AiTransport { _, _, body ->
        val message = respond(scheduleJson.parseToJsonElement(body).jsonObject)
        ApiResponse(200, buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
            put("finish_reason", if ("tool_calls" in message) "tool_calls" else "stop"); put("message", message)
        }) } }.toString())
    })
    private fun answer(text: String) = buildJsonObject { put("role", "assistant"); put("content", text) }
    private fun call(name: String, args: String = "{}") = buildJsonObject {
        put("role", "assistant"); put("content", JsonNull)
        putJsonArray("tool_calls") { add(buildJsonObject { put("id", "call-test"); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", args) } }) }
    }
    private fun schema(name: String) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name); put("description", "Returns a sample value.")
            putJsonObject("parameters") { put("type", "object"); put("properties", buildJsonObject { }) }
        }
    }

    @Test fun generalConversationHasNoDedicatedPersonaOrInjectedCalendar() {
        val harness = AiHarness(client { request ->
            val system = request.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content
            assertFalse(system.contains("你是用户的日程"))
            assertFalse(system.contains("使用中文"))
            assertFalse(system.contains("selectedItem"))
            answer("The answer is 42.")
        }, emptyList())
        assertEquals("The answer is 42.", harness.respond("Answer in English.").text)
    }

    @Test fun arbitraryToolRunsWithoutScheduleService() {
        var turns = 0
        val tool = HarnessTool(schema("sample"), "Sample") { arguments ->
            assertEquals(7, arguments.getValue("value").jsonPrimitive.int)
            ToolExecution(JsonPrimitive(14))
        }
        val harness = AiHarness(client { request ->
            if (++turns == 1) call("sample", """{"value":7}""") else {
                val message = request.getValue("messages").jsonArray.last().jsonObject
                assertEquals("tool", message.getValue("role").jsonPrimitive.content)
                assertEquals("14", message.getValue("content").jsonPrimitive.content)
                answer("14")
            }
        }, listOf(tool))
        assertEquals("14", harness.respond("Use sample.").text)
        assertEquals(2, turns)
    }

    @Test fun pauseAndCancellationAreEnforcedByRuntime() {
        var executions = 0
        val tool = HarnessTool(schema("review"), "Review") {
            executions++
            ToolExecution(buildJsonObject { put("id", "pending") }, "Awaiting approval")
        }
        val harness = AiHarness(client { call("review") }, listOf(tool))
        val result = harness.respond("Prepare a change")
        assertEquals("Awaiting approval", result.text)
        assertEquals("pending", result.pausedResult!!.jsonObject.getValue("id").jsonPrimitive.content)
        assertThrows(CancellationException::class.java) { harness.respond("Stop", cancelled = { true }) }
        assertEquals(1, executions)
    }
}
