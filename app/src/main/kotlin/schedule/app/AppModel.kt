package schedule.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import schedule.android.*
import schedule.ai.*
import schedule.core.*
import schedule.school.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import schedule.study.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScheduleApplication : Application()

class AppModel(application: Application) : AndroidViewModel(application) {
    val runtime = ScheduleRuntime(application)
    var state by mutableStateOf<ScheduleState?>(null); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var working by mutableStateOf(false); private set
    var aiConfig by mutableStateOf<AiConfiguration?>(null); private set
    var selectedChatModel by mutableStateOf<String?>(null); private set
    val currentChatModel: String? get() = selectedChatModel?.takeIf { id -> aiConfig?.models?.any { it.id == id } == true } ?: aiConfig?.model
    fun selectChatModel(id: String) {
        if (!agentRunning && aiConfig?.models?.any { it.id == id } == true) selectedChatModel = id
    }
    var schoolConfigured by mutableStateOf(false); private set
    var schoolSyncing by mutableStateOf(false); private set
    var schoolSyncStatus by mutableStateOf("自动同步当前学期全部课程与考试"); private set
    var chat by mutableStateOf<List<ChatEntry>>(emptyList()); private set
    var conversations by mutableStateOf<List<SavedConversation>>(emptyList()); private set
    var summaries by mutableStateOf<List<SavedSummary>>(emptyList()); private set
    var chatBusy by mutableStateOf(true); private set
    private val chatMutex = Mutex()
    private val chatPreferences = application.getSharedPreferences("chat_history", 0)
    private var conversationId = chatPreferences.getString("active", null) ?: java.util.UUID.randomUUID().toString()
    var agentRunning by mutableStateOf(false); private set
    var agentProgress by mutableStateOf(""); private set
    private data class ChatRequest(val text: String, val images: List<AiImage>, val contextId: String?, val pageContext: String?, val previous: List<ChatEntry>)
    private var lastChatRequest: ChatRequest? = null
    var courses by mutableStateOf<List<SchoolCourse>>(emptyList()); private set
    var materials by mutableStateOf<List<CourseMaterial>>(emptyList()); private set
    var review by mutableStateOf<ProposalReview?>(null)
    var studyContext by mutableStateOf<String?>(null)
    private var activeWork = 0
    private var materialRequest = 0
    private var cancelled = AtomicBoolean(false)
    private val chatFile = application.filesDir.resolve("schedule/chat.json").toPath()

    init { refresh(); launchWork {
        if (Files.exists(chatFile)) chat = withContext(Dispatchers.IO) { scheduleJson.decodeFromString<List<ChatEntry>>(String(Files.readAllBytes(chatFile), Charsets.UTF_8)) }
        reloadLibrary(); chatBusy = false; pruneSchoolSyncMessages()
    } }
    suspend fun reloadLibrary() { val loaded = withContext(Dispatchers.IO) { runtime.library.read() }; conversations = loaded.conversations.filterNot { it.id == conversationId }.sortedByDescending { it.updatedAt }; summaries = loaded.summaries.sortedByDescending { it.createdAt } }
    fun deleteSummaries(ids: Set<String>) = launchWork { withContext(Dispatchers.IO) { runtime.library.deleteSummaries(ids) }; reloadLibrary() }
    fun deleteConversations(ids: Set<String>) = launchWork { withContext(Dispatchers.IO) { runtime.library.update { it.copy(conversations = it.conversations.filterNot { c -> c.id in ids }) } }; reloadLibrary() }
    fun openConversation(id: String? = null) {
        if (agentRunning || chatBusy) return
        chatBusy = true
        launchWork { try {
            val current = SavedConversation(id = conversationId, messages = chat)
            val selected = withContext(Dispatchers.IO) {
                runtime.library.update { library -> library.copy(conversations = library.conversations.filterNot { it.id == current.id } + listOfNotNull(current.takeIf { it.messages.isNotEmpty() })) }
                    .conversations.find { it.id == id }
            }
            if (id != null) require(selected != null) { "对话已删除" }
            conversationId = selected?.id ?: java.util.UUID.randomUUID().toString()
            chat = selected?.messages.orEmpty(); lastChatRequest = null
            persistChat(); reloadLibrary()
        } finally { chatBusy = false } }
    }
    fun deleteCurrentConversation() {
        if (agentRunning || chatBusy) return
        chatBusy = true
        launchWork { try {
            withContext(Dispatchers.IO) { runtime.library.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == conversationId }) } }
            conversationId = java.util.UUID.randomUUID().toString(); chat = emptyList(); lastChatRequest = null
            persistChat(); reloadLibrary()
        } finally { chatBusy = false } }
    }
    fun launchWork(block: suspend () -> Unit) = viewModelScope.launch {
        activeWork++; working = true
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "操作失败，请重试" } finally { activeWork--; working = activeWork > 0 }
    }
    fun refresh() = launchWork {
        val loaded = withContext(Dispatchers.IO) { Triple(runtime.snapshot(), runtime.aiConfiguration(), runtime.hasSchoolAccount()) }
        state = loaded.first; aiConfig = loaded.second; schoolConfigured = loaded.third
        pruneSchoolSyncMessages()
        if (schoolConfigured) syncSchool()
        withContext(Dispatchers.IO) { runtime.refreshReminders() }
    }
    fun mutation(action: () -> MutationResult, after: () -> Unit = {}) = launchWork {
        val result = withContext(Dispatchers.IO) { action() }
        state = result.state
        notice = result.reminders.error ?: if (result.reminders.results.values.any { it != ScheduleResult.SCHEDULED_EXACT && it != ScheduleResult.EXPIRED }) "日程已保存，请在设置中开启通知和准时提醒权限" else null
        after()
    }
    fun save(item: ScheduleItem, after: () -> Unit) = mutation({ runtime.saveItem(item) }, after)
    fun complete(occurrence: Occurrence) = mutation({ runtime.completeTask(occurrence.item.id, occurrence.originalStart.takeIf { occurrence.item.isRecurring }, !occurrence.completed) })
    fun delete(id: String, after: () -> Unit) = mutation({ runtime.deleteItem(id) }, after)
    fun inspectProposal(id: String) = launchWork { review = withContext(Dispatchers.IO) { runtime.reviewProposal(id) } }
    fun approve(id: String) = mutation({ runtime.approveProposal(id) }) { review = null; notice = "方案已应用"; pruneSchoolSyncMessages() }
    fun reject(id: String) = launchWork { state = withContext(Dispatchers.IO) { runtime.rejectProposal(id) }; review = null; pruneSchoolSyncMessages() }
    fun theme(value: ThemePreference) = launchWork { state = withContext(Dispatchers.IO) { runtime.updateSettings(requireNotNull(state).settings.copy(theme = value)) } }
    fun configureAi(config: AiConfiguration, key: String, after: () -> Unit) = launchWork { withContext(Dispatchers.IO) { runtime.configureAi(config, key) }; aiConfig = config; after() }
    fun configureSchool(username: String, password: String, after: () -> Unit) = launchWork { withContext(Dispatchers.IO) { runtime.configureSchool(username, password) }; schoolConfigured = true; after(); syncSchool(force = true) }
    fun syncSchool(force: Boolean = false) {
        if (schoolSyncing) return
        if (!schoolConfigured) { if (force) error = "请先配置学校账号"; return }
        schoolSyncing = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runtime.syncSchoolSchedule(force) }
                state = result.state; schoolSyncStatus = result.status
                if (review?.proposal?.origin == ProposalOrigin.SCHOOL && result.state.proposals.none { it.id == review?.proposal?.id }) review = null
                pruneSchoolSyncMessages()
                if (force) notice = "同步已结束，详情见课表与考试"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { schoolSyncStatus = e.message ?: "学校同步失败"; if (force) error = schoolSyncStatus
            } finally { schoolSyncing = false }
        }
    }
    fun loadCourses() = launchWork { courses = withContext(Dispatchers.IO) { runtime.listCourses() } }
    fun loadMaterials(id: String) = launchWork { val request = ++materialRequest; materials = emptyList(); val result = withContext(Dispatchers.IO) { runtime.listCourseMaterialsByUser(id) }; if (request == materialRequest) materials = result }
    fun send(text: String, images: List<AiImage>, contextId: String?) {
        if (agentRunning || chatBusy || (text.isBlank() && images.isEmpty())) return
        val request = ChatRequest(text, images.toList(), contextId, studyContext, chat)
        lastChatRequest = request
        chat = chat + ChatEntry("user", text.ifBlank { "请查看图片" } + if (images.isNotEmpty()) "\n[附图 ${images.size} 张]" else "")
        runChatRequest(request)
    }
    fun retryLastChat() {
        if (agentRunning || chatBusy || chat.lastOrNull()?.role != "assistant") return
        val userIndex = chat.indexOfLast { it.role == "user" }
        if (userIndex < 0) return
        val request = lastChatRequest ?: run {
            val text = chat[userIndex].text
            if (Regex("\\n\\[附图 \\d+ 张]$").containsMatchIn(text)) {
                error = "图片已不在内存中，请重新添加图片后发送。"
                return
            }
            ChatRequest(text, emptyList(), null, null, chat.take(userIndex))
        }
        lastChatRequest = request
        runChatRequest(request, chat.last().proposalId, userIndex)
    }
    private fun runChatRequest(request: ChatRequest, replacedProposalId: String? = null, userIndex: Int? = null) {
        val modelId = currentChatModel
        val cancellation = AtomicBoolean(false)
        val savedSummaryIds = mutableListOf<String>()
        agentRunning = true; cancelled = cancellation
        viewModelScope.launch {
            try {
                persistChat()
                if (replacedProposalId != null) {
                    state = withContext(Dispatchers.IO) {
                        val snapshot = runtime.snapshot()
                        if (snapshot.proposals.any { it.id == replacedProposalId && it.status == ProposalStatus.PENDING }) runtime.rejectProposal(replacedProposalId) else snapshot
                    }
                }
                if (userIndex != null) chat = chat.take(userIndex + 1)
                val result = withContext(Dispatchers.IO) {
                    runtime.agent(cancellation::get, modelId, { savedSummaryIds.add(it) }).respond(request.text, request.previous, request.images, request.contextId, request.pageContext, cancellation::get) { status -> viewModelScope.launch { agentProgress = status } }
                }
                chat = chat + ChatEntry("assistant", result.text, proposalId = result.proposal?.id, summaryIds = savedSummaryIds.toList())
                state = withContext(Dispatchers.IO) { runtime.snapshot() }
            } catch (e: CancellationException) { chat = chat + ChatEntry("assistant", "已停止本次请求。已生成的草案仍需审批。", summaryIds = savedSummaryIds.toList())
            } catch (e: Exception) { chat = chat + ChatEntry("assistant", "请求失败：${e.message ?: "请检查接口设置"}", failed = true, proposalId = replacedProposalId, summaryIds = savedSummaryIds.toList())
            } finally {
                agentRunning = false; agentProgress = ""
                pruneSchoolSyncMessages()
                try { persistChat(); reloadLibrary() } catch (e: Exception) { error = "对话保存失败：${e.message}" }
            }
        }
    }
    fun stopAgent() { cancelled.set(true); agentProgress = "正在停止" }
    fun clearChat() = openConversation()
    fun pruneSchoolSyncMessages() {
        if (agentRunning) return
        val snapshot = state ?: return
        if (snapshot.proposals.any { it.status == ProposalStatus.PENDING && it.origin == ProposalOrigin.SCHOOL }) return
        val notification = Regex("(?:课程同步|课表与考试同步)：\\d+项变更\\n\\n请确认课程变化后批准。")
        val remaining = chat.filterNot { it.role == "assistant" && notification.matches(it.text) }
        if (remaining.size != chat.size) {
            chat = remaining
            lastChatRequest = lastChatRequest?.let { request -> request.copy(previous = request.previous.filterNot { it.role == "assistant" && notification.matches(it.text) }) }
            launchWork { persistChat() }
        }
    }
    private suspend fun persistChat() {
        val messages = chat; val id = conversationId
        chatMutex.withLock { withContext(Dispatchers.IO) {
        Files.createDirectories(chatFile.parent)
        val temporary = Files.createTempFile(chatFile.parent, "chat-", ".tmp")
        try { Files.write(temporary, scheduleJson.encodeToString(messages).toByteArray(Charsets.UTF_8)); Files.move(temporary, chatFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); chatPreferences.edit().putString("active", id).commit() }
        finally { Files.deleteIfExists(temporary) }
        } }
    }
    override fun onCleared() { cancelled.set(true); super.onCleared() }
}
