package schedule.ai

import kotlinx.serialization.json.*
import schedule.core.*
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException

data class AgentResult(val text: String, val proposal: Proposal? = null)
fun interface StudyTools { fun execute(name: String, arguments: JsonObject): JsonElement }

/** Domain tools and approval handling for the generic tool harness. */
class ScheduleAgent(private val schedules: ScheduleService, private val client: AiClient,
    private val study: StudyTools, private val clock: Clock = Clock.systemUTC()) {
    fun respond(instruction: String, history: List<ChatEntry> = emptyList(), images: List<AiImage> = emptyList(),
        contextItemId: String? = null, pageContext: String? = null, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): AgentResult {
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已停止") }
        fun runtimeContext(): JsonObject {
            val initial = schedules.snapshot()
            return buildJsonObject {
                put("now", Instant.ofEpochMilli(clock.millis()).toString()); put("zoneId", initial.settings.zoneId)
                put("revision", initial.revision)
                pageContext?.let { put("currentPage", it.take(2000)) }
                initial.items.find { it.id == contextItemId }?.let { put("selectedItem", AiDraftParser.itemContext(it)) }
                putJsonArray("proposals") { initial.proposals.takeLast(12).forEach { p -> add(buildJsonObject { put("id", p.id); put("summary", p.summary); put("status", p.status.name) }) } }
            }
        }
        val tools = TOOL_SCHEMAS.map { definition ->
            val toolSchema = definition.jsonObject
            val name = toolSchema.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
            HarnessTool(toolSchema, TOOL_LABELS[name] ?: "正在调用工具") { args ->
                val result = when (name) {
                        "runtime_context" -> runtimeContext()
                        "calendar" -> {
                            val state = schedules.snapshot()
                            val offset = args["offset"]?.jsonPrimitive?.int ?: 0
                            require(offset >= 0)
                            val query = args["query"]?.jsonPrimitive?.content.orEmpty()
                            val items = state.items.filter { query.isBlank() || it.title.contains(query, true) }
                            val from = args["from"]?.jsonPrimitive?.contentOrNull
                            val until = args["until"]?.jsonPrimitive?.contentOrNull
                            require((from == null) == (until == null)) { "日期查询必须同时给出from和until" }
                            if (from != null && until != null) {
                                val start = Instant.parse(from).toEpochMilli(); val end = Instant.parse(until).toEpochMilli()
                                require(end > start && end - start <= 366L * 86400000) { "查询范围为1年以内" }
                                val occurrences = items.flatMap { TimeEngine.occurrences(it, start, end) }.sortedBy { it.startAt ?: it.deadlineAt }
                                val page = occurrences.drop(offset).take(20)
                                buildJsonObject {
                                    put("revision", state.revision); put("total", occurrences.size); put("offset", offset)
                                    put("nextOffset", if (offset + page.size < occurrences.size) JsonPrimitive(offset + page.size) else JsonNull)
                                    put("occurrences", JsonArray(page.map { occurrence -> buildJsonObject {
                                        put("item", AiDraftParser.itemContext(occurrence.item))
                                        put("originalStart", occurrence.originalStart?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                                        put("startAt", occurrence.startAt?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                                        put("endAt", occurrence.endAt?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                                        put("deadlineAt", occurrence.deadlineAt?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                                    } }))
                                }
                            } else {
                                val page = items.drop(offset).take(20)
                                buildJsonObject { put("revision", state.revision); put("total", items.size); put("offset", offset); put("nextOffset", if (offset + page.size < items.size) JsonPrimitive(offset + page.size) else JsonNull); put("items", JsonArray(page.map(AiDraftParser::itemContext))) }
                            }
                        }
                        "conflicts" -> {
                            val from = Instant.parse(args.text("from")).toEpochMilli(); val until = Instant.parse(args.text("until")).toEpochMilli()
                            require(until > from && until - from <= 366L * 86400000) { "查询范围为1年以内" }
                            JsonArray(TimeEngine.conflicts(schedules.snapshot().items, from, until).map(::JsonPrimitive))
                        }
                        "propose_changes" -> {
                            val state = schedules.snapshot()
                            val revision = args.getValue("revision").jsonPrimitive.long
                            if (revision != state.revision) throw StaleProposalException()
                            val changes = AiDraftParser.changes(args.getValue("changes").jsonArray, state)
                            checkCancelled()
                            val proposal = schedules.propose(revision, changes, args.text("summary"))
                            return@HarnessTool ToolExecution(buildJsonObject { put("proposalId", proposal.id) }, "${proposal.summary}\n\n已生成待审批方案，确认后生效。")
                        }
                        in STUDY_NAMES -> study.execute(name, args)
                        else -> error("未授权的工具")
                    }
                checkCancelled()
                if (name == "sync_courses" && result is JsonObject) {
                    result["proposalId"]?.jsonPrimitive?.contentOrNull?.let { id ->
                        val proposal = schedules.snapshot().proposals.single { it.id == id && it.origin == ProposalOrigin.SCHOOL && it.status == ProposalStatus.PENDING }
                        return@HarnessTool ToolExecution(result, "${proposal.summary}\n\n请确认课程变化后批准。")
                    }
                }
                ToolExecution(result)
            }
        }
        val result = AiHarness(client, tools).respond(instruction, history, images, cancelled, progress)
        val proposalId = (result.pausedResult as? JsonObject)?.get("proposalId")?.jsonPrimitive?.contentOrNull
        val proposal = proposalId?.let { id -> schedules.snapshot().proposals.single { it.id == id && it.status == ProposalStatus.PENDING } }
        return AgentResult(result.text, proposal)
    }
}

fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content.also { require(it.isNotBlank()) { "$name 不能为空" } }
private val STUDY_NAMES = setOf("school_courses", "school_schedule", "sync_courses", "course_materials", "transcript", "summarize_sources", "lesson_sources")
private val TOOL_LABELS = mapOf("calendar" to "查询日程", "conflicts" to "检查时间冲突", "propose_changes" to "生成待审批方案", "school_courses" to "获取课程", "school_schedule" to "获取讲次", "sync_courses" to "生成课表同步方案", "course_materials" to "获取教师资料", "transcript" to "读取课堂转录", "summarize_sources" to "总结所选资料")
private fun schema(name: String, description: String, properties: String, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name); put("description", description + when (name) {
            "propose_changes" -> "修改前读取calendar版本和目标；仅按用户明确要求修改，等待界面审批。create使用新唯一编号；update/delete目标必须存在；delete.fields为空；保留未请求修改字段。课程可提出本地修改，不能新造学校课程或来源。提醒时间用REMINDER：startAt为提醒时刻，endAt/deadlineAt为空，仅当天显示。截止事项用TASK：deadlineAt为截止时刻，可选startAt为计划开始；未完成时持续显示倒计时和逾期时长，endAt为空；有起止时间段的待办用EVENT，支持逐次完成。REMINDER、TASK和EVENT均可使用repeat：{frequency:DAILY|WEEKLY|MONTHLY|YEARLY,interval:正整数,weekdays:[1至7],until:ISO时间或null}；weekdays仅用于WEEKLY，1周一7周日，工作日[1,2,3,4,5]，周末[6,7]，不填为开始日星期。每周间隔以开始日所在周的周一为锚点，跳过开始日前的日期。MONTHLY为同日号，YEARLY为同月日，不存在的日期跳过；until包含该时刻。repeat=null取消重复。每日18:30–22:30用EVENT加DAILY重复，周末两个时间段建两条EVENT加WEEKLY和[6,7]。DAILY类型仅用于每天多个瞬时时刻提醒，startAt为启用日当地零点，dailyTimes为HH:mm数组，endAt/deadlineAt为空，且至少包含START提醒。时间必须带Z或时区偏移，使用runtime_context时区解释本地日期；信息不足先询问。estimatedMinutes只由用户亲自填写，禁止推测、填入或修改，不得转换类型或删除重建绕过。完成状态只由用户确认。"
            "school_courses", "school_schedule", "course_materials", "sync_courses", "transcript", "summarize_sources" -> "仅在用户要求范围内获取学校资料。禁止虚构来源、教师、摘要、页码和时间戳；LMS课程编号与CMC课程/讲次编号不同，不能根据相似名称猜测映射。引用转录附讲次编号及时间秒数，PDF附文件名及页码；无法读取时如实说明。"
            else -> ""
        })
        putJsonObject("parameters") {
            put("type", "object"); put("properties", scheduleJson.parseToJsonElement(properties)); put("additionalProperties", false)
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
    }
}
private val TOOL_SCHEMAS = JsonArray(listOf(
    schema("runtime_context", "读取当前时间、时区、日程版本、当前页面、选中事项和最近提议。解释本地日期或处理当前页面、选中事项前先调用。", "{}"),
    schema("calendar", "读取本地事项及版本，每页20项；修改前先读取。判断某天是否有安排时同时传from和until（带时区的ISO时间），返回按实际日期展开的occurrences，包含重复间隔、起止日期、调课及取消的影响；只按星期几推断当天有课或冲突是错误的。未传日期范围时返回事项定义，不能把定义当作每天实际发生的记录。使用返回的nextOffset继续分页，query按名称过滤。notes为用户备注。", """{"query":{"type":"string"},"offset":{"type":"integer"},"from":{"type":"string"},"until":{"type":"string"}}"""),
    schema("conflicts", "按实际日期展开后检查已有安排的重叠，返回具体冲突日期和时段。必须按需要核对的日期传入from和until，不能凭星期相同认定有冲突。点提醒、纯截止时刻没有持续时间，不视为占用时段；没有用户预计耗时的任务无法完整检查。新方案涉及事项的实际冲突会在审批页另行计算。", """{"from":{"type":"string"},"until":{"type":"string"}}""", listOf("from", "until")),
    schema("propose_changes", "提交待用户审批的修改草案，随后暂停；不会直接修改日程。fields只列实际修改：title,kind,startAt,deadlineAt,endAt,notes,location,teacher,priority,parentId,courseId,dailyTimes,reminders,repeat。时间为ISO8601偏移格式或null。reminders为[{anchor:START|DEADLINE|END,minutesBefore:非负整数}]。REMINDER提醒时刻用START，TASK截止前提醒用DEADLINE，EVENT和COURSE可分别设置START开始前及END结束前提醒；例如结束前10分钟为{anchor:END,minutesBefore:10}，结束时为{anchor:END,minutesBefore:0}，开始前5分钟并结束前10分钟需同时保留两条。修改reminders会替换整组，仅修改用户指定部分时必须保留其他锚点的既有提醒；不填该字段保留原值，[]关闭全部提醒。", """{"revision":{"type":"integer"},"summary":{"type":"string"},"changes":{"type":"array","items":{"type":"object","properties":{"operation":{"type":"string","enum":["create","update","delete"]},"itemId":{"type":"string"},"fields":{"type":"object"},"reason":{"type":"string"}},"required":["operation","itemId","fields","reason"],"additionalProperties":false}}}""", listOf("revision", "summary", "changes")),
    schema("school_courses", "仅获取当前学期学习平台课程及真实教师全名，含LMS课程编号。", "{}"),
    schema("lesson_sources", "总结某堂课前先从calendar取得itemId，再调用本工具，核验智云讲次和本学期LMS课程。讲次按名称教师及时间匹配；候选不唯一时询问用户。课件活动标题仅供选材，结合转录核对内容，不宣称未经核对的PDF属于当堂。生成课程总结必须调用summarize_sources，使总结保存在资料库。", """{"itemId":{"type":"string"}}""", listOf("itemId")),
    schema("school_schedule", "获取日期范围内的智云讲次，编号与LMS课程编号不同。", """{"from":{"type":"string"},"until":{"type":"string"}}""", listOf("from", "until")),
    schema("sync_courses", "获取本科教务当前完整学期课表和考试，与本地已自动同步的安排比较；仅有变更时生成待审批提议后暂停。不从智云另建一套课程，不直接修改日程。", "{}"),
    schema("course_materials", "列出指定LMS课程的教师PDF/PPT/PPTX文件，不下载全文。", """{"courseId":{"type":"string"}}""", listOf("courseId")),
    schema("transcript", "按讲次读取转录，每页100段含秒数；offset继续分页。仅在用户请求时获取。", """{"lectureId":{"type":"string"},"offset":{"type":"integer"}}""", listOf("lectureId")),
    schema("summarize_sources", "生成并自动保存课程总结，返回savedSummaryId；用户可在资料的总结库查看或删除。可同时提供一个讲次和一份PDF的1至4页，分别标注来源。先用lesson_sources查来源。所有总结必须提供本地课程itemId，以保存实际课程和上课时间；未确定哪堂课时先询问用户。转录另提供lectureId；PDF另提供courseId、uploadId、pages。禁止编造课程关联。可选fromSeconds、untilSeconds缩小转录范围。不接受任意网址。", """{"instruction":{"type":"string"},"itemId":{"type":"string"},"lectureId":{"type":"string"},"fromSeconds":{"type":"number"},"untilSeconds":{"type":"number"},"courseId":{"type":"string"},"uploadId":{"type":"integer"},"pages":{"type":"array","items":{"type":"integer"},"maxItems":4}}""", listOf("instruction", "itemId")),
))
