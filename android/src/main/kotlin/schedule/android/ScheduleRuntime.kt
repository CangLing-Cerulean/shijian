package schedule.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import schedule.ai.*
import schedule.core.*
import schedule.school.*
import schedule.study.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

data class ReminderSyncReport(val results: Map<String, ScheduleResult> = emptyMap(), val error: String? = null)
data class MutationResult(val state: ScheduleState, val reminders: ReminderSyncReport)
data class SchoolSyncResult(val state: ScheduleState, val status: String)

/** UI-independent facade. All methods involving disk/network must be called on a background thread. */
class ScheduleRuntime(context: Context) {
    private val context = context.applicationContext
    private val schedules = ScheduleService(FileStateStore(this.context.filesDir.resolve("schedule/state.json").toPath()))
    private val vault = CredentialVault(this.context)
    private val aiProfiles = AiProfileStore(vault::get, vault::put)
    private val scheduler = ReminderScheduler(this.context)
    val library = LibraryStore(this.context.filesDir.resolve("schedule/library.json").toPath())
    private var currentCourses: Pair<String, List<SchoolCourse>>? = null
    private val knownLectures = java.util.concurrent.ConcurrentHashMap<String, SchoolLesson>()
    private val schoolSyncPreferences = this.context.getSharedPreferences("school_sync", Context.MODE_PRIVATE)
    private val school by lazy {
        ZjuClient(CredentialsProvider {
            val account = JSONObject(requireNotNull(vault.get("school.account")) { "请配置学校账号" })
            SchoolCredentials(account.getString("username"), account.getString("password"))
        }, diagnostics = ::saveSchoolDiagnostic)
    }
    private fun saveSchoolDiagnostic(report: String) {
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val filename = when (JSONObject(report).optString("stage")) {
            "CAS session established" -> "school-session-diagnostic.json"
            "academic service ticket" -> "school-academic-diagnostic.json"
            else -> "school-service-diagnostic.json"
        }
        context.cacheDir.resolve(filename).writeText(report, Charsets.UTF_8)
    }

    fun snapshot() = schedules.snapshot()
    fun occurrences(from: Long, until: Long) = snapshot().items.flatMap { TimeEngine.occurrences(it, from, until) }.sortedBy { it.startAt ?: it.deadlineAt }
    fun conflicts(from: Long, until: Long) = TimeEngine.conflicts(snapshot().items, from, until)
    fun saveItem(item: ScheduleItem) = mutate { schedules.saveByUser(item) }
    fun deleteItem(id: String) = mutate { schedules.deleteByUser(id) }
    fun completeTask(id: String, occurrenceStart: Long? = null, completed: Boolean = true) = mutate { schedules.completeByUser(id, occurrenceStart, completed) }
    fun approveProposal(id: String) = mutate { schedules.approve(id) }
    fun rejectProposal(id: String) = schedules.reject(id)
    fun reviewProposal(id: String) = schedules.review(id)
    fun reviseProposal(id: String, changes: List<Change>, summary: String) = schedules.reviseProposalByUser(id, changes, summary)
    fun updateSettings(settings: AppSettings) = schedules.updateSettings(settings)
    fun exportBackup() = schedules.exportBackup()
    fun restoreBackup(text: String) = mutate { schedules.restoreBackupByUser(text) }

    fun configureAi(config: AiConfiguration, key: String) {
        val active = aiProfiles.active()
        saveAiProfile(active?.id, active?.name ?: "默认接口", config, key)
    }
    fun aiProfiles(): List<AiProfile> = aiProfiles.profiles()
    fun activeAiProfileId(): String? = aiProfiles.active()?.id
    fun saveAiProfile(id: String?, name: String, config: AiConfiguration, key: String): String = aiProfiles.save(id, name, config, key)
    fun selectAiProfile(id: String) = aiProfiles.select(id)
    fun deleteAiProfile(id: String) = aiProfiles.delete(id)
    fun aiKeyForEndpoint(endpoint: String): String {
        val active = aiProfiles.active() ?: return ""
        return if (AiEndpoints.chat(endpoint) == AiEndpoints.chat(active.config.endpoint)) active.key else ""
    }
    fun aiConfiguration(): AiConfiguration? = aiProfiles.active()?.config
    private fun aiClient(modelId: String? = null): AiClient {
        // Capture endpoint and its key together; a concurrent provider switch must never mix credentials.
        val active = requireNotNull(aiProfiles.active()) { "请先配置AI接口" }
        val config = if (modelId == null) active.config else {
            val selected = requireNotNull(active.config.models.find { it.id == modelId }) { "所选模型已从接口配置中移除" }
            AiConfiguration(active.config.endpoint, selected.id, selected.supportsImages)
        }
        return AiClient(config, { active.key }, diagnostics = { report ->
            val file = context.cacheDir.resolve("ai-diagnostic.jsonl")
            synchronized(ScheduleRuntime::class.java) {
                if (file.length() > 65536) file.writeText("", Charsets.UTF_8)
                file.appendText(report + "\n", Charsets.UTF_8)
            }
        })
    }
    fun agent(cancelled: () -> Boolean = { false }, modelId: String? = null, onSummarySaved: (String) -> Unit = {}) = ScheduleAgent(schedules, aiClient(modelId), StudyTools { name, args ->
        when (name) {
            "school_courses" -> scheduleJson.encodeToJsonElement(listCourses())
            "lesson_sources" -> lessonSources(args.text("itemId"))
            "school_schedule" -> scheduleJson.encodeToJsonElement(currentLectures(args.text("from"), args.text("until")))
            "sync_courses" -> {
                val proposal = requestCourseSync()
                buildJsonObject { if (proposal == null) put("message", "课表无变化") else { put("proposalId", proposal.id); put("summary", proposal.summary) } }
            }
            "course_materials" -> scheduleJson.encodeToJsonElement(listCourseMaterialsByUser(args.text("courseId")))
            "transcript" -> {
                val offset = args["offset"]?.jsonPrimitive?.int ?: 0
                require(offset >= 0)
                val segments = fetchTranscriptByUser(args.text("lectureId"))
                buildJsonObject { put("total", segments.size); put("offset", offset); put("segments", scheduleJson.encodeToJsonElement(segments.drop(offset).take(100))) }
            }
            "summarize_sources" -> {
                val item = requireNotNull(snapshot().items.find { it.id == args.text("itemId") && it.kind == ItemKind.COURSE }) { "请先选择要总结的课程安排，标注实际课程和上课时间" }
                requireCurrentDate(java.time.Instant.ofEpochMilli(requireNotNull(item.startAt)).atZone(java.time.ZoneId.of(item.zoneId)).toLocalDate())
                val lecture = args["lectureId"]?.jsonPrimitive?.contentOrNull
                val course = args["courseId"]?.jsonPrimitive?.contentOrNull?.let { id -> requireNotNull(listCourses().find { it.courseId == id }) { "仅支持本学期课程" } }
                if (lecture != null) {
                    require(matchingLectures(item).any { it.subId == lecture }) { "该讲次与所选课程的时间、名称或教师不一致" }
                }
                if (course != null) require(matchesCourse(item, course) || library.read().lessonMaterials.any { it.itemId == item.id && it.courseId == course.courseId && it.material.uploadId == args["uploadId"]?.jsonPrimitive?.longOrNull }) { "课件课程与所选安排不一致，请先在当堂资料中选择课件确认关联" }
                val materials = if (args["uploadId"] == null) emptyList() else {
                    val material = listCourseMaterialsByUser(args.text("courseId")).single { it.uploadId == args.getValue("uploadId").jsonPrimitive.long }
                    val pages = args.getValue("pages").jsonArray.map { it.jsonPrimitive.int }
                    require(pages.size in 1..4) { "单次请选择1至4页" }
                    listOf(MaterialSelection(material, pages))
                }
                val summary = summarizeCourseByUser(StudyRequest(args.text("instruction"), listOfNotNull(lecture), materials, args["fromSeconds"]?.jsonPrimitive?.double ?: 0.0, args["untilSeconds"]?.jsonPrimitive?.doubleOrNull), cancelled)
                val saved = SavedSummary(courseName = item.title, lessonStart = item.startAt, lessonEnd = item.endAt,
                    lessonId = lecture, lmsCourseId = course?.courseId, sections = summary.sections)
                library.saveSummary(saved, materials.map { LessonMaterial(item.id, requireNotNull(course).courseId, course.name, it.material) })
                onSummarySaved(saved.id)
                buildJsonObject {
                    put("savedSummaryId", saved.id); put("courseName", saved.courseName)
                    put("lessonStart", java.time.Instant.ofEpochMilli(item.startAt!!).toString())
                    put("sectionCount", summary.sections.size)
                    put("sources", JsonArray(summary.sections.map { JsonPrimitive(it.source) }))
                    put("preview", summary.sections.joinToString("\n") { it.summary }.take(2500))
                    put("message", "全文已保存，聊天将显示打开总结按钮；此处为预览，不是全文。")
                }
            }
            else -> error("工具不存在")
        }
    })

    fun configureSchool(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "学校账号密码不能为空" }
        val diagnosticFile = context.cacheDir.resolve("school-auth-diagnostic.json")
        diagnosticFile.delete()
        ZjuClient(CredentialsProvider { SchoolCredentials(username, password) }, diagnostics = { report ->
            if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) diagnosticFile.writeText(report, Charsets.UTF_8)
        }).verifyCredentials()
        vault.put("school.account", JSONObject().put("username", username).put("password", password).toString())
        currentCourses = null
        knownLectures.clear()
        schoolSyncPreferences.edit().clear().apply()
        school.clearSessions()
    }
    fun schoolAccount(): SchoolCredentials? = vault.get("school.account")?.let {
        val account = JSONObject(it)
        SchoolCredentials(account.getString("username"), account.getString("password"))
    }
    fun clearSchoolAccount() { vault.put("school.account", null); schoolSyncPreferences.edit().clear().apply(); school.clearSessions(); currentCourses = null; knownLectures.clear() }
    @Synchronized fun listCourses(): List<SchoolCourse> {
        val term = AcademicSchedule.termOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))
        currentCourses?.takeIf { it.first == term.id }?.let { return it.second }
        return school.listCourses().filter { it.belongsTo(term) }.also { currentCourses = term.id to it }
    }
    private fun normalizedCourse(value: String) = value.replace("（", "(").replace("）", ")").replace(Regex("\\s+"), "")
    private fun matchesCourse(item: ScheduleItem, course: SchoolCourse): Boolean = normalizedCourse(item.title) == normalizedCourse(course.name) &&
        (item.teacher.isBlank() || course.teachers.any { it == item.teacher })
    fun coursesForLesson(itemId: String): List<SchoolCourse> {
        val item = requireNotNull(snapshot().items.find { it.id == itemId && it.kind == ItemKind.COURSE }) { "课程安排不存在" }
        return listCourses().filter { matchesCourse(item, it) }
    }
    fun linkLessonMaterial(itemId: String, courseId: String, material: CourseMaterial) {
        require(snapshot().items.any { it.id == itemId && it.kind == ItemKind.COURSE }) { "课程安排不存在" }
        val course = requireNotNull(listCourses().find { it.courseId == courseId }) { "仅支持本学期课程" }
        library.linkMaterial(LessonMaterial(itemId, courseId, course.name, material))
    }
    fun lecturesForLesson(itemId: String): List<SchoolLesson> {
        val item = requireNotNull(snapshot().items.find { it.id == itemId && it.kind == ItemKind.COURSE }) { "课程安排不存在" }
        return matchingLectures(item)
    }
    private fun matchingLectures(item: ScheduleItem): List<SchoolLesson> {
        val start = requireNotNull(item.startAt)
        val date = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.of(item.zoneId)).toLocalDate().toString()
        return currentLectures(date, date).filter { lesson ->
            normalizedCourse(item.title) == normalizedCourse(lesson.title) && lesson.startMillis < requireNotNull(item.endAt) && lesson.endMillis > start &&
                (item.teacher.isBlank() || item.teacher == lesson.teacher)
        }
    }
    private fun requireCurrentDate(date: java.time.LocalDate) {
        require(AcademicSchedule.termOn(date) == AcademicSchedule.termOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) { "仅提供本学期的课程资料与转录" }
    }
    private fun currentLectures(from: String, until: String): List<SchoolLesson> {
        requireCurrentDate(java.time.LocalDate.parse(from)); requireCurrentDate(java.time.LocalDate.parse(until))
        return school.fetchSchedule(from, until).also { lessons -> lessons.forEach { knownLectures[it.subId] = it } }
    }
    private fun lessonSources(id: String): JsonObject {
        val item = requireNotNull(snapshot().items.find { it.id == id && it.kind == ItemKind.COURSE }) { "请先从calendar选择课程itemId" }
        val lectures = matchingLectures(item)
        val courses = listCourses().filter { matchesCourse(item, it) }
        return buildJsonObject {
            put("item", AiDraftParser.itemContext(item)); put("lectures", scheduleJson.encodeToJsonElement(lectures)); put("lmsCourses", scheduleJson.encodeToJsonElement(courses))
            put("selectedMaterials", scheduleJson.encodeToJsonElement(library.read().lessonMaterials.filter { it.itemId == item.id }))
            put("matching", "名称、教师与上课时间核验；多个候选需用户确认。学在浙大课件只有课程和活动标题，需结合转录内容选择相关文件及页码，不代表全部属于当堂。")
        }
    }
    fun schoolSyncStatus(): String = schoolSyncPreferences.getString("status", "自动同步当前学期全部课程与考试")!!
    fun syncSchoolSchedule(force: Boolean = false): SchoolSyncResult = synchronized(schoolSyncLock) {
        require(hasSchoolAccount()) { "请先配置学校账号" }
        val now = System.currentTimeMillis()
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val interval = if (schoolSyncPreferences.getBoolean("success", false)) 6 * 3600000L else 15 * 60000L
        if (!force && schoolSyncPreferences.getLong("build", 0) == installedAt && now - schoolSyncPreferences.getLong("attempt", 0) in 0 until interval)
            return@synchronized SchoolSyncResult(snapshot(), schoolSyncStatus())
        schoolSyncPreferences.edit().putLong("attempt", now).putLong("build", installedAt).putBoolean("success", false).apply()
        val records = mutableListOf<ScheduleItem>()
        val warnings = mutableListOf<String>()
        val term = AcademicSchedule.termOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))
        var lessons = 0
        var exams = 0
        var pending = emptyList<String>()
        try {
            val calendar = AcademicCalendarStore(context).load(term)
            val fetched = school.fetchAcademicSchedule(term, calendar)
            records.addAll(fetched); lessons = fetched.size
        } catch (e: Exception) { warnings += "课表：${e.message ?: "同步失败"}" }
        try {
            val fetched = school.fetchExams()
            records.addAll(fetched.items); exams = fetched.items.size; pending = fetched.pending
        } catch (e: Exception) { warnings += "考试：${e.message ?: "同步失败"}" }
        val result = mutate { schedules.syncSchoolSchedule(records) }
        result.reminders.error?.let(warnings::add)
        val summary = buildList {
            add("${term.year} ${if (term.half == 1) "秋冬" else "春夏"} · $lessons 节课 · $exams 场考试")
            if (pending.isNotEmpty()) add("考试时间待公布：${pending.joinToString("、")}")
            addAll(warnings)
        }.joinToString("\n")
        schoolSyncPreferences.edit().putString("status", summary).putBoolean("success", warnings.isEmpty()).apply()
        SchoolSyncResult(result.state, summary)
    }
    fun requestCourseSync(): Proposal? {
        val revision = snapshot().revision
        val term = AcademicSchedule.termOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))
        val lessons = school.fetchAcademicSchedule(term, AcademicCalendarStore(context).load(term))
        return schedules.proposeSchoolRefresh(lessons + school.fetchExams().items, revision)
    }
    fun listCourseMaterialsByUser(lmsCourseId: String): List<CourseMaterial> {
        require(listCourses().any { it.courseId == lmsCourseId }) { "仅提供本学期的课程资料" }
        return school.listMaterials(lmsCourseId)
    }
    fun fetchTranscriptByUser(subId: String): List<TranscriptSegment> {
        if (knownLectures[subId] == null) {
            val item = snapshot().items.find { it.cmcLectureId == subId || (it.courseId?.startsWith("cmc:") == true && it.sourceKey == subId) }
            require(item != null) { "请先查询本学期上课日期，取得讲次编号" }
            matchingLectures(item)
        }
        val lecture = requireNotNull(knownLectures[subId]) { "未能核验本学期讲次" }
        requireCurrentDate(java.time.Instant.ofEpochMilli(lecture.startMillis).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())
        return school.fetchTranscript(subId)
    }
    fun hasSchoolAccount() = vault.get("school.account") != null
    fun downloadCourseMaterialByUser(material: CourseMaterial) = school.downloadMaterial(material)
    fun summarizeCourseByUser(request: StudyRequest, cancelled: () -> Boolean = { false }): StudySummary {
        if (request.materials.isNotEmpty()) require(aiConfiguration()?.canUseImages == true) { "PDF视觉总结需要已验证图片支持的模型" }
        return CourseStudyService(school, aiClient(), AndroidPdfPageReader(context)).fetchAndSummarizeByUser(request, cancelled)
    }

    private fun mutate(action: () -> ScheduleState): MutationResult = synchronized(lock) {
        val state = action()
        // Report an alarm failure separately: the user's edit has already committed successfully.
        val report = try { ReminderSyncReport(refreshReminders()) } catch (_: Exception) { ReminderSyncReport(error = "日程已保存，提醒同步失败；请检查通知权限后重试") }
        MutationResult(state, report)
    }
    fun refreshReminders(): Map<String, ScheduleResult> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val state = snapshot()
        val active = state.items.filter { it.status == ItemStatus.OPEN }.associateBy { it.id }
        val planned = TimeEngine.reminders(state.items, now, now + REMINDER_HORIZON_MS).map { planned ->
            Reminder(planned.id, planned.title, planned.triggerAt, itemId = planned.itemId.takeIf { active[it]?.kind != ItemKind.COURSE }, originItemId = planned.itemId, occurrenceStart = planned.occurrenceStart)
        }.associateBy { it.id }.toMutableMap()
        scheduler.pending().filter { it.snoozed && it.triggerAtMillis > now && it.originItemId in active && it.occurrenceStart !in active[it.originItemId]?.completedOccurrences.orEmpty() }.forEach { planned[it.id] = it }
        val result = scheduler.replaceAll(planned.values)
        scheduler.pruneDelivered(active.keys, active.mapValues { it.value.completedOccurrences })
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (jobs.getPendingJob(REFRESH_JOB_ID) == null) {
            val job = JobInfo.Builder(REFRESH_JOB_ID, ComponentName(context, ReminderRefreshJob::class.java))
                .setPeriodic(24 * 60 * 60 * 1000L).setPersisted(true).build()
            check(jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) { "提醒维护任务注册失败" }
        }
        result
    }
    companion object {
        private val lock = Any()
        private val schoolSyncLock = Any()
        private const val REMINDER_HORIZON_MS = 30L * 86400000
        private const val REFRESH_JOB_ID = 804173
    }
}

class ReminderRefreshJob : JobService() {
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        executor.execute {
            var retry = false
            try { if (!stopped) ScheduleRuntime(this).refreshReminders() } catch (_: Exception) { retry = true }
            if (!stopped) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}

class AndroidPdfPageReader(private val context: Context) : PdfPageReader {
    override fun readPages(bytes: ByteArray, pages: List<Int>): List<AiImage> {
        val file = java.io.File.createTempFile("course-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pages.all { it in 1..renderer.pageCount }) { "所选PDF页码越界" }
                    return pages.map { number -> renderer.openPage(number - 1).use { page ->
                        require(page.width > 0 && page.height > 0) { "PDF页面尺寸无效" }
                        val scale = minOf(1600.0 / page.width, 2200.0 / page.height)
                        val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val out = ByteArrayOutputStream()
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PDF图片编码失败" }
                            AiImage("image/png", out.toByteArray())
                        } finally { bitmap.recycle() }
                    } }
                }
            }
        } finally { file.delete() }
    }
}
