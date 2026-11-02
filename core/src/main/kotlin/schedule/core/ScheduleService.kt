package schedule.core

import kotlinx.serialization.encodeToString
import java.time.Clock

class ScheduleService(private val store: StateStore, private val clock: Clock = Clock.systemUTC()) {
    fun snapshot() = store.read().also { validateState(it) }

    fun saveByUser(item: ScheduleItem): ScheduleState = store.update { state ->
        item.validate()
        state.copy(revision = state.revision + 1, items = state.items.filterNot { it.id == item.id } + item).also { validateState(it) }
    }
    fun deleteByUser(id: String): ScheduleState = store.update { state ->
        require(state.items.any { it.id == id }) { "日程不存在" }
        state.copy(revision = state.revision + 1, items = state.items.filterNot { it.id == id }).also { validateState(it) }
    }
    fun completeByUser(id: String, occurrenceStart: Long? = null, completed: Boolean = true): ScheduleState = store.update { state ->
        val item = requireNotNull(state.items.find { it.id == id }) { "任务不存在" }
        require(item.kind != ItemKind.COURSE) { "课程无需标记完成" }
        val changed = if (item.isRecurring) {
            val key = requireNotNull(occurrenceStart) { "请选择本次提醒" }
            val actual = item.exceptions[key]?.startAt ?: key
            require(TimeEngine.occurrences(item, actual, Math.addExact(actual, 1), true).any { it.originalStart == key }) { "该次提醒不存在" }
            item.copy(completedOccurrences = if (completed) item.completedOccurrences + key else item.completedOccurrences - key)
        } else item.copy(status = if (completed) ItemStatus.DONE else ItemStatus.OPEN)
        state.copy(revision = state.revision + 1, items = state.items.map { if (it.id == id) changed else it }).also(::validateState)
    }
    fun updateSettings(settings: AppSettings): ScheduleState = store.update { it.copy(settings = settings).also(::validateState) }

    /** AI is allowed to write proposals, never the active schedule. Estimates come only from the user. */
    fun propose(basedOnRevision: Long, changes: List<Change>, summary: String): Proposal {
        val proposal = Proposal(basedOnRevision = basedOnRevision, changes = changes, summary = summary, createdAt = clock.millis())
        store.update { state ->
            if (state.revision != basedOnRevision) throw StaleProposalException()
            applyChanges(state, changes, fromAi = true)
            state.copy(proposals = state.proposals + proposal).also(::validateState)
        }
        return proposal
    }
    fun review(id: String): ProposalReview {
        val state = snapshot()
        val proposal = requireNotNull(state.proposals.find { it.id == id }) { "提议不存在" }
        val warnings = mutableListOf<String>()
        if (proposal.basedOnRevision != state.revision) warnings += "该提议已过期，需要重新生成"
        val changes = proposal.changes.map { change -> ReviewChange(state.items.find { it.id == change.itemId }, change.replacement, change.reason) }
        if (proposal.basedOnRevision == state.revision) {
            val candidate = applyChanges(state, proposal.changes, fromAi = proposal.origin == ProposalOrigin.AI)
            val changedIds = proposal.changes.map { it.itemId }.toSet()
            warnings += TimeEngine.conflicts(candidate.items, clock.millis(), clock.millis() + 366L * 86400000, changedIds)
            candidate.items.filter { it.id in changedIds && it.kind == ItemKind.TASK && it.status == ItemStatus.OPEN && it.startAt != null && it.estimatedMinutes == null }
                .forEach { warnings += "${it.title}未填写预计耗时，无法确定结束时间和完整冲突范围" }
            candidate.items.filter { it.id in changedIds && it.startAt != null && it.deadlineAt != null && it.estimatedMinutes != null }
                .filter { it.startAt!! + it.estimatedMinutes!! * 60000L > it.deadlineAt!! }
                .forEach { warnings += "${it.title}按你填写的耗时将超过截止时间" }
        }
        return ProposalReview(proposal, changes, warnings.distinct())
    }
    fun approve(id: String): ScheduleState = store.update { state ->
        val proposal = requireNotNull(state.proposals.find { it.id == id }) { "提议不存在" }
        require(proposal.status == ProposalStatus.PENDING) { "该提议已处理" }
        if (proposal.basedOnRevision != state.revision) throw StaleProposalException()
        val next = applyChanges(state, proposal.changes, fromAi = proposal.origin == ProposalOrigin.AI)
        next.copy(revision = state.revision + 1, proposals = state.proposals.map { if (it.id == id) it.copy(status = ProposalStatus.APPROVED) else it })
    }
    fun reject(id: String): ScheduleState = store.update { state ->
        val proposal = requireNotNull(state.proposals.find { it.id == id }) { "提议不存在" }
        require(proposal.status == ProposalStatus.PENDING) { "该提议已处理" }
        state.copy(proposals = state.proposals.map { if (it.id == id) it.copy(status = ProposalStatus.REJECTED) else it })
    }
    /** Editing a draft does not apply it. The revised draft is checked against the current calendar. */
    fun reviseProposalByUser(id: String, changes: List<Change>, summary: String): Proposal {
        var revised: Proposal? = null
        store.update { state ->
            val old = requireNotNull(state.proposals.find { it.id == id }) { "提议不存在" }
            require(old.status == ProposalStatus.PENDING) { "该提议已处理" }
            // Estimates remain a user edit to the task itself, avoiding ambiguity about their source.
            applyChanges(state, changes, fromAi = old.origin == ProposalOrigin.AI)
            revised = old.copy(basedOnRevision = state.revision, changes = changes, summary = summary)
            state.copy(proposals = state.proposals.map { if (it.id == id) revised!! else it })
        }
        return revised!!
    }
    fun exportBackup(): String = scheduleJson.encodeToString(snapshot().copy(proposals = emptyList()))
    /** User-enabled school sync is atomic and keeps fields edited locally since the last sync. */
    fun syncSchoolSchedule(records: List<ScheduleItem>): ScheduleState = store.update { state ->
        require(records.map { it.id }.distinct().size == records.size) { "学校安排编号重复" }
        val items = state.items.associateBy { it.id }.toMutableMap()
        for (remote in records) {
            require(remote.sourceKey != null && remote.id == "zju:${remote.sourceKey}" &&
                (remote.kind == ItemKind.COURSE || (remote.kind == ItemKind.EVENT && remote.sourceKey.startsWith("exam:")))) { "学校来源编号无效" }
            remote.validate()
            val old = items[remote.id]
            require(old == null || old.sourceKey == remote.sourceKey) { "学校编号与本地数据冲突" }
            val base = old?.schoolBaseline
            val keepLocalTime = old != null && (old.startAt != base?.startAt || old.endAt != base?.endAt)
            items[remote.id] = if (old == null) remote.copy(schoolBaseline = remote.baseline()) else old.copy(
                title = if (old.title == base?.title) remote.title else old.title,
                startAt = if (keepLocalTime) old.startAt else remote.startAt,
                endAt = if (keepLocalTime) old.endAt else remote.endAt,
                location = if (old.location == base?.location) remote.location else old.location,
                teacher = if (old.teacher == base?.teacher) remote.teacher else old.teacher,
                notes = if (old.notes == base?.notes) remote.notes else old.notes,
                schoolBaseline = remote.baseline(),
            )
        }
        val updated = items.values.toList()
        val proposals = state.proposals.filterNot { it.origin == ProposalOrigin.SCHOOL }
        if (updated == state.items) state.copy(proposals = proposals) else state.copy(revision = state.revision + 1, items = updated, proposals = proposals).also(::validateState)
    }
    /** School refresh is reviewable too. Missing lessons are not presumed cancelled by an incomplete feed. */
    fun proposeSchoolRefresh(lessons: List<ScheduleItem>, basedOnRevision: Long): Proposal? {
        require(lessons.map { it.id }.distinct().size == lessons.size) { "学校讲次编号重复" }
        var proposal: Proposal? = null
        store.update { state ->
            if (state.revision != basedOnRevision) throw StaleProposalException()
            val changes = lessons.mapNotNull { remote ->
                require(remote.sourceKey != null && remote.id == "zju:${remote.sourceKey}" &&
                    (remote.kind == ItemKind.COURSE || (remote.kind == ItemKind.EVENT && remote.sourceKey.startsWith("exam:")))) { "学校来源编号无效" }
                remote.validate()
                val old = state.items.find { it.id == remote.id }
                require(old == null || old.sourceKey == remote.sourceKey) { "学校编号与本地数据冲突" }
                val base = old?.schoolBaseline
                val keepLocalTime = old != null && (old.startAt != base?.startAt || old.endAt != base?.endAt)
                val merged = if (old == null) remote.copy(schoolBaseline = remote.baseline()) else old.copy(
                    title = if (old.title == base?.title) remote.title else old.title,
                    startAt = if (keepLocalTime) old.startAt else remote.startAt,
                    endAt = if (keepLocalTime) old.endAt else remote.endAt,
                    location = if (old.location == base?.location) remote.location else old.location,
                    teacher = if (old.teacher == base?.teacher) remote.teacher else old.teacher,
                    schoolBaseline = remote.baseline(),
                )
                if (merged == old) null else Change(remote.id, merged, if (old == null) "学校课表新增讲次" else "学校课表与当前安排不同，请确认时间及地点")
            }
            if (changes.isEmpty()) state else {
                require(changes.size <= 10000) { "学校安排数量超过限制" }
                proposal = Proposal(basedOnRevision = state.revision, changes = changes, summary = "课表与考试同步：${changes.size}项变更", createdAt = clock.millis(), origin = ProposalOrigin.SCHOOL)
                applyChanges(state, changes, fromAi = false)
                state.copy(proposals = state.proposals + proposal!!).also(::validateState)
            }
        }
        return proposal
    }
    fun restoreBackupByUser(text: String): ScheduleState {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "备份过大" }
        val backup = scheduleJson.decodeFromString<ScheduleState>(text).also(::validateState)
        return store.update { current -> backup.copy(revision = current.revision + 1, proposals = emptyList()) }
    }
    private fun applyChanges(state: ScheduleState, changes: List<Change>, fromAi: Boolean): ScheduleState {
        require(changes.isNotEmpty() && changes.size <= (if (fromAi) 100 else 10000) && changes.map { it.itemId }.distinct().size == changes.size) { "提议为空、重复或过大" }
        val items = state.items.associateBy { it.id }.toMutableMap()
        if (fromAi) {
            val deletesTask = changes.any { it.replacement == null && items[it.itemId]?.kind == ItemKind.TASK }
            val createsEvent = changes.any { it.replacement?.kind == ItemKind.EVENT && it.itemId !in items }
            require(!(deletesTask && createsEvent)) { "AI不能以删除任务并新建活动的方式补造任务时长；请分别明确处理" }
        }
        changes.forEach { change ->
            require(change.reason.isNotBlank()) { "每项调整需要说明原因" }
            val old = items[change.itemId]
            val new = change.replacement
            require(new != null || old != null) { "删除目标不存在" }
            require(new == null || new.id == change.itemId) { "调整编号不一致" }
            if (fromAi) {
                require(new == null || ((new.kind != ItemKind.COURSE || old?.kind == ItemKind.COURSE) && new.sourceKey == old?.sourceKey && new.schoolBaseline == old?.schoolBaseline && new.cmcLectureId == old?.cmcLectureId)) { "AI不能创建学校课程或修改来源" }
                require(new == null || new.estimatedMinutes == old?.estimatedMinutes) { "预计耗时只能由用户填写" }
                require(new == null || new.status == (old?.status ?: ItemStatus.OPEN)) { "完成状态由用户确认" }
                require(new == null || new.completedOccurrences == (old?.completedOccurrences ?: emptySet<Long>())) { "完成状态由用户确认" }
                require(old == null || new == null || old.kind == new.kind) { "AI不能改变已有事项类型以补造时长" }
            }
            if (new == null) items.remove(change.itemId) else { new.validate(); items[change.itemId] = new }
        }
        return state.copy(items = items.values.toList()).also(::validateState)
    }
}
