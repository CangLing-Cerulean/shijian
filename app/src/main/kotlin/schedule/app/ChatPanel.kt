package schedule.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import schedule.ai.AiImage
import schedule.core.*
import java.io.InputStream

fun InputStream.readLimited(maximum: Int): ByteArray = use { input ->
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= maximum) { "文件超过大小限制" }; output.write(buffer, 0, size) }
    output.toByteArray()
}

@Composable fun ChatPanel(model: AppModel, contextId: String?, modifier: Modifier = Modifier, initialText: String = "", onClose: () -> Unit) {
    var input by remember { mutableStateOf(initialText) }
    var images by remember { mutableStateOf<List<AiImage>>(emptyList()) }
    var openedSummary by remember { mutableStateOf<schedule.study.SavedSummary?>(null) }
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    openedSummary?.let { summary ->
        SummaryReader(summary, modifier, onBack = { openedSummary = null }, onDelete = { model.deleteSummaries(setOf(summary.id)); openedSummary = null })
        return
    }
    var historyOpen by remember { mutableStateOf(false) }
    if (historyOpen) {
        ConversationHistory(model, modifier, onBack = { historyOpen = false }, onOpen = { id -> model.openConversation(id); input = ""; images = emptyList(); historyOpen = false })
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.launchWork {
            require(uris.size <= 4) { "最多选择4张图片" }
            images = withContext(Dispatchers.IO) { uris.map { uri ->
                AiImage(context.contentResolver.getType(uri) ?: "image/png", requireNotNull(context.contentResolver.openInputStream(uri)).readLimited(5 * 1024 * 1024)).also { it.dataUrl() }
            } }
        }
    }
    var modelMenu by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canScroll) ->
            if (scrolling) followLatest = !canScroll
        }
    }
    LaunchedEffect(model.chat.size) {
        if (model.chat.isNotEmpty() && followLatest && !listState.isScrollInProgress) listState.scrollToItem(model.chat.lastIndex)
    }
    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box {
                    Text(model.currentChatModel ?: "未配置模型",
                        Modifier.testTag("chat_model").clickable(enabled = !model.agentRunning && model.aiConfig != null) { modelMenu = true }.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        model.aiConfig?.models.orEmpty().forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.id) }, enabled = !model.agentRunning,
                                onClick = { model.selectChatModel(candidate.id); modelMenu = false })
                        }
                    }
                }
            }
            TextButton(onClick = { historyOpen = true; model.launchWork { model.reloadLibrary() } }, enabled = !model.agentRunning && !model.chatBusy) { Text("历史") }
            TextButton(onClick = { model.clearChat(); input = ""; images = emptyList() }, enabled = !model.agentRunning && !model.chatBusy) { Text("新对话") }
            GlyphButton(R.drawable.ic_x, "关闭", onClose)
        }
        model.state?.items?.find { it.id == contextId }?.let { Text(it.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        LazyColumn(Modifier.weight(1f).testTag("chat_messages"), state = listState, contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (model.chat.isEmpty()) item { Text("直接提问，或描述想安排的事情。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
            itemsIndexed(model.chat, key = { _, entry -> entry.id }) { index, entry ->
                val user = entry.role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                        Column {
                            if (user) SelectionContainer { Text(entry.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
                            else MarkdownMessage(entry.text, Modifier.fillMaxWidth().padding(14.dp))
                            entry.summaryIds.distinct().forEach { summaryId ->
                                OutlinedButton(onClick = { model.launchWork {
                                    openedSummary = withContext(Dispatchers.IO) { requireNotNull(model.runtime.library.read().summaries.find { it.id == summaryId }) { "这份总结已删除" } }
                                } }, modifier = Modifier.padding(horizontal = 14.dp).testTag("summary_open_$summaryId")) {
                                    Text("打开总结" + (model.summaries.find { it.id == summaryId }?.courseName?.let { " · $it" } ?: ""))
                                }
                            }
                            Row(Modifier.padding(horizontal = 6.dp)) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }, modifier = Modifier.testTag("chat_copy_$index")) { Text("复制") }
                                if (!user && index == model.chat.lastIndex && model.chat.any { it.role == "user" }) {
                                    TextButton(onClick = { followLatest = true; model.retryLastChat() }, enabled = !model.agentRunning, modifier = Modifier.testTag("chat_retry")) {
                                        Text(if (entry.failed || entry.text.startsWith("请求失败：")) "重试" else "重新生成")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(model.state?.proposals.orEmpty().filter { it.status == ProposalStatus.PENDING && it.basedOnRevision == model.state?.revision }.takeLast(8), key = { "proposal_${it.id}" }) { proposal ->
                OutlinedCard(onClick = { model.inspectProposal(proposal.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text(proposal.summary); Text(when (proposal.status) { ProposalStatus.PENDING -> "待审批 · ${proposal.changes.size}项修改"; ProposalStatus.APPROVED -> "已应用"; ProposalStatus.REJECTED -> "已拒绝" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (model.agentRunning) item(key = "progress") { Text(model.agentProgress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
        if (images.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Text("已附加 ${images.size} 张图片", style = MaterialTheme.typography.labelMedium); TextButton(onClick = { images = emptyList() }) { Text("移除") } }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { pickImages.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !model.agentRunning) { Glyph(R.drawable.ic_paperclip, "添加图片") }
            OutlinedTextField(input, { input = it }, placeholder = { Text("发消息") }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(24.dp))
            if (model.agentRunning) GlyphButton(R.drawable.ic_square, "停止") { model.stopAgent() }
            else IconButton(onClick = { val text = input; val attached = images; input = ""; images = emptyList(); followLatest = true; model.send(text, attached, contextId) }, enabled = !model.chatBusy && (input.isNotBlank() || images.isNotEmpty())) { Glyph(R.drawable.ic_arrow_up, "发送") }
        }
    }
}

@Composable fun ProposalPanel(model: AppModel, review: ProposalReview) {
    var editIndex by remember(review.proposal.id) { mutableStateOf<Int?>(null) }
    val pending = review.proposal.status == ProposalStatus.PENDING
    val stale = review.proposal.basedOnRevision != model.state?.revision
    if (editIndex != null) {
        val index = editIndex!!
        val change = review.proposal.changes[index]
        ItemEditor(change.replacement!!, model.working, onBack = { editIndex = null }, onSave = { replacement ->
            model.launchWork {
                val changes = review.proposal.changes.toMutableList().also { it[index] = change.copy(replacement = replacement) }
                withContext(Dispatchers.IO) { model.runtime.reviseProposal(review.proposal.id, changes, review.proposal.summary) }
                model.review = withContext(Dispatchers.IO) { model.runtime.reviewProposal(review.proposal.id) }; editIndex = null; model.refresh()
            }
        }, onError = { model.error = it }, allowEstimate = false)
        return
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 22.dp)) {
        Text("${if (pending) "待审批" else "方案"} · ${review.proposal.changes.size}项修改", style = MaterialTheme.typography.titleLarge)
        Text(review.proposal.summary, Modifier.padding(vertical = 12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(review.warnings) { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(review.changes) { index, change ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(change.after?.title ?: change.before?.title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (pending && change.after != null && !stale) GlyphButton(R.drawable.ic_pencil, "修改草案") { editIndex = index }
                        }
                        Text(change.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (change.after == null) Text("删除", color = MaterialTheme.colorScheme.error)
                        val before = change.before?.let { scheduleJson.encodeToJsonElement(it).jsonObject }.orEmpty()
                        val after = change.after?.let { scheduleJson.encodeToJsonElement(it).jsonObject }.orEmpty()
                        val keys = (before.keys + after.keys).filter { before[it] != after[it] && it != "id" && it != "schoolBaseline" }
                        keys.forEach { key ->
                            Text(FIELD_LABELS[key] ?: key, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                            if (change.before != null) Text("原：${fieldValue(key, before[key])}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (change.after != null) Text("改：${fieldValue(key, after[key])}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pending) {
                OutlinedButton(onClick = { model.reject(review.proposal.id) }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("拒绝") }
                Button(onClick = { model.approve(review.proposal.id) }, enabled = !model.working && !stale, modifier = Modifier.weight(1f)) { Text("批准") }
            } else TextButton(onClick = { model.review = null }) { Text("关闭") }
        }
    }
}
private val FIELD_LABELS = mapOf("title" to "名称", "kind" to "类型", "startAt" to "开始／提醒", "endAt" to "结束", "deadlineAt" to "截止时间", "notes" to "备注", "teacher" to "教师", "location" to "地点", "reminders" to "提醒", "dailyTimes" to "每日时刻", "estimatedMinutes" to "预计耗时", "priority" to "优先级", "courseId" to "课程编号", "parentId" to "父事项", "zoneId" to "时区", "sourceKey" to "学校讲次", "status" to "状态", "repeat" to "重复规则", "exceptions" to "单次调整", "completedOccurrences" to "已完成记录")
private fun fieldValue(key: String, value: JsonElement?): String {
    if (value == null || value == JsonNull) return "未设置"
    if (key in setOf("startAt", "endAt", "deadlineAt")) return dateTimeText(value.jsonPrimitive.long)
    if (key == "repeat") return repeatText(scheduleJson.decodeFromJsonElement<RepeatRule>(value))
    if (value is JsonPrimitive) return when (value.content) { "TASK" -> "截止时间"; "REMINDER" -> "提醒时间"; "EVENT" -> "时间安排"; "DAILY" -> "每日提醒"; "COURSE" -> "课程"; "true" -> "是"; "false" -> "否"; "OPEN" -> "未完成"; "DONE" -> "已完成"; else -> value.content.ifBlank { "空" } }
    return value.toString()
}
