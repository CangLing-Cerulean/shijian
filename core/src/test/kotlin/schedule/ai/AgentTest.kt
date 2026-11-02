package schedule.ai

import org.junit.Test
import org.junit.Assert.*
import schedule.core.*
import kotlinx.serialization.json.*
import java.util.concurrent.CancellationException

class AgentTest {
    @Test fun agentCanProposeStartAndEndRemindersTogether() {
        val service = ScheduleService(MemoryStateStore())
        val agent = ScheduleAgent(service, client { body ->
            val definitions = body.getValue("tools").jsonArray.toString()
            assertTrue(definitions.contains("结束前10分钟"))
            assertTrue(definitions.contains("REMINDER"))
            call("propose_changes", """{"revision":0,"summary":"设置起止提醒","changes":[{"operation":"create","itemId":"event","fields":{"title":"活动","kind":"EVENT","startAt":"2026-11-02T09:00:00+08:00","endAt":"2026-11-02T12:00:00+08:00","reminders":[{"anchor":"START","minutesBefore":5},{"anchor":"END","minutesBefore":10}]},"reason":"按用户要求"}]}""")
        }, noStudy)
        val result = agent.respond("开始前5分钟和结束前10分钟提醒")
        assertTrue(service.snapshot().items.isEmpty())
        service.approve(result.proposal!!.id)
        val item = service.snapshot().items.single()
        assertEquals(listOf(Reminder(TimeAnchor.START, 5), Reminder(TimeAnchor.END, 10)), item.reminders)
        val reminders = TimeEngine.reminders(listOf(item), item.startAt!! - 3600000, item.endAt!! + 1)
        assertEquals(listOf(item.startAt!! - 5 * 60000, item.endAt!! - 10 * 60000), reminders.map { it.triggerAt })
    }
    @Test fun datedCalendarUsesOccurrencesInsteadOfWeekdayDefinitions() {
        val service = ScheduleService(MemoryStateStore())
        val start = java.time.Instant.parse("2026-11-02T01:00:00Z").toEpochMilli()
        service.saveByUser(ScheduleItem("course", "隔周课程", ItemKind.COURSE, startAt = start, endAt = start + 3600000,
            repeat = RepeatRule(RepeatFrequency.WEEKLY, interval = 2)))
        var rounds = 0
        val agent = ScheduleAgent(service, client { body ->
            when (++rounds) {
                1 -> call("calendar", """{"from":"2026-11-09T00:00:00+08:00","until":"2026-11-10T00:00:00+08:00"}""")
                2 -> {
                    val result = scheduleJson.parseToJsonElement(body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    assertEquals(0, result.getValue("total").jsonPrimitive.int)
                    assertTrue(result.getValue("occurrences").jsonArray.isEmpty())
                    call("calendar", """{"from":"2026-11-16T00:00:00+08:00","until":"2026-11-17T00:00:00+08:00"}""")
                }
                else -> {
                    val result = scheduleJson.parseToJsonElement(body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
                    assertEquals("2026-11-16T01:00:00Z", result.getValue("occurrences").jsonArray.single().jsonObject.getValue("startAt").jsonPrimitive.content)
                    reply("已按实际日期核对")
                }
            }
        }, noStudy)
        assertEquals("已按实际日期核对", agent.respond("核查日期").text)
    }
    private fun reply(text: String) = buildJsonObject { put("role", "assistant"); put("content", text) }
    private fun call(name: String, args: String) = buildJsonObject {
        put("role", "assistant"); put("content", JsonNull)
        putJsonArray("tool_calls") { add(buildJsonObject { put("id", "call-1"); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", args) } }) }
    }
    private fun client(responder: (JsonObject) -> JsonObject) = AiClient(AiConfiguration("https://example.com/v1/chat/completions", "model"), { "test-key" }, AiTransport { _, _, body ->
        val message = responder(scheduleJson.parseToJsonElement(body).jsonObject)
        ApiResponse(200, buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("finish_reason", if ("tool_calls" in message) "tool_calls" else "stop"); put("message", message) }) } }.toString())
    })
    private val noStudy = StudyTools { _, _ -> error("unexpected study call") }
    @Test fun ordinaryConversationDoesNotFetchOrWrite() {
        val service = ScheduleService(MemoryStateStore())
        val agent = ScheduleAgent(service, client { reply("你好") }, noStudy)
        assertEquals("你好", agent.respond("你好").text)
        assertEquals(0, service.snapshot().items.size)
        assertEquals(0, service.snapshot().proposals.size)
    }
    @Test fun pageAndItemContextAreReadThroughTool() {
        val service = ScheduleService(MemoryStateStore())
        service.saveByUser(ScheduleItem("selected", "private-item-title", startAt = 1000))
        var rounds = 0
        val agent = ScheduleAgent(service, client { body ->
            if (++rounds == 1) {
                assertFalse(body.toString().contains("private-item-title"))
                assertFalse(body.toString().contains("private-page-data"))
                call("runtime_context", "{}")
            } else {
                val last = body.getValue("messages").jsonArray.last().jsonObject
                assertEquals("tool", last.getValue("role").jsonPrimitive.content)
                assertTrue(last.toString().contains("private-item-title"))
                assertTrue(last.toString().contains("private-page-data"))
                reply("Context received")
            }
        }, noStudy)
        assertEquals("Context received", agent.respond("Read the selected item", contextItemId = "selected", pageContext = "private-page-data").text)
    }
    @Test fun toolLoopReadsThenCreatesOnlyPendingProposal() {
        val service = ScheduleService(MemoryStateStore())
        var rounds = 0
        val agent = ScheduleAgent(service, client { body ->
            rounds++
            if (rounds == 1) call("calendar", "{}") else {
                assertEquals("tool", body.getValue("messages").jsonArray.last().jsonObject.getValue("role").jsonPrimitive.content)
                call("propose_changes", """{"revision":0,"summary":"新增每日签到","changes":[{"operation":"create","itemId":"daily","fields":{"title":"签到","kind":"DAILY","startAt":"2026-11-02T00:00:00+08:00","dailyTimes":["08:00","21:00"],"reminders":[{"anchor":"START","minutesBefore":0}]},"reason":"按用户要求"}]}""")
            }
        }, noStudy)
        val result = agent.respond("从11月2日开始每天8点和21点提醒签到")
        assertEquals(2, rounds); assertTrue(service.snapshot().items.isEmpty())
        assertNotNull(result.proposal); service.approve(result.proposal!!.id)
        assertEquals(ItemKind.DAILY, service.snapshot().items.single().kind)
    }
    @Test fun guessedDurationAndUnknownToolsReturnErrorsWithoutWrites() {
        val service = ScheduleService(MemoryStateStore())
        var round = 0
        val agent = ScheduleAgent(service, client { body ->
            round++
            when (round) {
                1 -> call("propose_changes", """{"revision":0,"summary":"错误","changes":[{"operation":"create","itemId":"t","fields":{"title":"学习","startAt":"2026-11-02T01:00:00Z","estimatedMinutes":60},"reason":"估计"}]}""")
                2 -> { assertTrue(body.toString().contains("受保护字段")); call("approve", "{}") }
                else -> { assertTrue(body.toString().contains("未授权")); reply("需要你填写耗时") }
            }
        }, noStudy)
        agent.respond("安排学习"); assertTrue(service.snapshot().items.isEmpty()); assertTrue(service.snapshot().proposals.isEmpty())
    }
    @Test fun concurrentEditMakesDraftStaleAndCancellationStopsBeforeTool() {
        val service = ScheduleService(MemoryStateStore())
        var round = 0
        val agent = ScheduleAgent(service, client {
            round++
            if (round == 1) { service.saveByUser(ScheduleItem("user", "新安排", startAt = 1000)); call("propose_changes", """{"revision":0,"summary":"新增","changes":[{"operation":"create","itemId":"ai","fields":{"title":"学习","startAt":"2026-11-02T01:00:00Z"},"reason":"安排"}]}""") }
            else reply("日程已变化，请重新读取")
        }, noStudy)
        agent.respond("安排学习"); assertTrue(service.snapshot().proposals.isEmpty())
        assertThrows(CancellationException::class.java) { agent.respond("继续", cancelled = { true }) }
        assertEquals(2, round)
    }
    @Test fun configurationRejectsInsecureEndpoint() {
        assertThrows(IllegalArgumentException::class.java) { AiConfiguration("http://example.com", "m").validate() }
        assertThrows(IllegalArgumentException::class.java) { AiConfiguration("https://key@example.com", "m").validate() }
        assertThrows(IllegalArgumentException::class.java) { client { reply("x") }.userMessage("s", listOf(AiImage("image/png", byteArrayOf(1)))) }
    }
}
