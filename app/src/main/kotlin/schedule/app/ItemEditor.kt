package schedule.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import schedule.core.*
import java.time.*

@Composable fun ItemEditor(original: ScheduleItem, busy: Boolean, onBack: () -> Unit, onSave: (ScheduleItem) -> Unit, onError: (String) -> Unit, allowEstimate: Boolean = true) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var kind by rememberSaveable(original.id) { mutableStateOf(original.kind) }
    var start by rememberSaveable(original.id) { mutableStateOf(original.startAt) }
    var end by rememberSaveable(original.id) { mutableStateOf(original.endAt) }
    var deadline by rememberSaveable(original.id) { mutableStateOf(original.deadlineAt) }
    var times by rememberSaveable(original.id) { mutableStateOf(original.dailyTimes.joinToString(", ")) }
    var location by rememberSaveable(original.id) { mutableStateOf(original.location) }
    var teacher by rememberSaveable(original.id) { mutableStateOf(original.teacher) }
    var notes by rememberSaveable(original.id) { mutableStateOf(visibleNotes(original)) }
    var estimate by rememberSaveable(original.id) { mutableStateOf(original.estimatedMinutes?.toString().orEmpty()) }
    var reminders by rememberSaveable(original.id) { mutableStateOf(original.reminders.filter { it.anchor != TimeAnchor.END }.joinToString(", ") { it.minutesBefore.toString() }) }
    var endReminders by rememberSaveable(original.id) { mutableStateOf(original.reminders.filter { it.anchor == TimeAnchor.END }.joinToString(", ") { it.minutesBefore.toString() }) }
    var frequency by rememberSaveable(original.id) { mutableStateOf(original.repeat?.frequency?.name.orEmpty()) }
    var interval by rememberSaveable(original.id) { mutableStateOf(original.repeat?.interval?.toString() ?: "1") }
    var weekdays by rememberSaveable(original.id) { mutableStateOf(original.repeat?.weekdays?.sorted().orEmpty()) }
    var repeatUntil by rememberSaveable(original.id) { mutableStateOf(original.repeat?.until) }
    val task = kind == ItemKind.TASK; val daily = kind == ItemKind.DAILY; val reminderOnly = kind == ItemKind.REMINDER
    fun changeMode(value: ItemKind) {
        if (kind == value) return
        val previous = kind
        val at = start ?: deadline ?: System.currentTimeMillis()
        when (value) {
            ItemKind.TASK -> { deadline = deadline ?: end ?: at; end = null; if (previous == ItemKind.REMINDER) start = null }
            ItemKind.REMINDER -> { start = deadline ?: at; end = null; deadline = null; estimate = "" }
            ItemKind.EVENT -> { start = at; end = end?.takeIf { it > at } ?: (at + 3600000L); deadline = null; estimate = "" }
            ItemKind.DAILY -> {
                start = Instant.ofEpochMilli(at).atZone(ZoneId.of(original.zoneId)).toLocalDate().atStartOfDay(ZoneId.of(original.zoneId)).toInstant().toEpochMilli()
                end = null; deadline = null; estimate = ""; frequency = ""; repeatUntil = null
                if (times.isBlank()) times = "08:00, 21:00"
            }
            ItemKind.COURSE -> error("课程类型不可手动创建")
        }
        if (previous == ItemKind.DAILY && value != ItemKind.DAILY) frequency = "DAILY"
        kind = value
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageTitle(if (original.title.isBlank()) "新建事项" else "编辑", onBack)
        OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(14.dp))
        if (original.kind != ItemKind.COURSE && original.sourceKey == null && allowEstimate) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(ItemKind.REMINDER to "提醒时间", ItemKind.TASK to "截止时间", ItemKind.EVENT to "起止时间") +
                    if (original.kind == ItemKind.DAILY) listOf(ItemKind.DAILY to "每日多次") else emptyList()).forEach { (value, label) ->
                    FilterChip(selected = kind == value, onClick = { changeMode(value) }, label = { Text(label) })
                }
            }
        }
        if (task) {
            DateTimeField("截止时间", deadline, original.zoneId, { deadline = it })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(start != null, { start = if (it) System.currentTimeMillis() else null }); Text("计划开始时间") }
            if (start != null) DateTimeField("开始", start, original.zoneId, { start = it })
            if (allowEstimate) OutlinedTextField(estimate, { estimate = it }, label = { Text("预计耗时（分钟，选填）") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true)
        } else if (reminderOnly) {
            DateTimeField("提醒时间", start, original.zoneId, { start = it })
        } else if (daily) {
            DateTimeField("开始日期", start, original.zoneId, { start = it }, dateOnly = true)
            OutlinedTextField(times, { times = it }, label = { Text("每日时刻") }, placeholder = { Text("08:00, 21:00") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        } else {
            DateTimeField("开始", start, original.zoneId, { start = it })
            Spacer(Modifier.height(12.dp)); DateTimeField("结束", end, original.zoneId, { end = it })
        }
        if (!daily) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "不重复", "DAILY" to "每天", "WEEKLY" to "每周", "MONTHLY" to "每月", "YEARLY" to "每年").forEach { (value, label) ->
                    FilterChip(selected = frequency == value, onClick = { frequency = value }, label = { Text(label) })
                }
            }
            if (frequency.isNotEmpty()) {
                OutlinedTextField(interval, { interval = it }, label = { Text("重复间隔") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (frequency == "WEEKLY") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, day ->
                        FilterChip(selected = index + 1 in weekdays, onClick = { weekdays = if (index + 1 in weekdays) weekdays - (index + 1) else weekdays + (index + 1) }, label = { Text(day) })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(repeatUntil != null, { repeatUntil = if (it) (start ?: deadline ?: System.currentTimeMillis()) else null }); Text("限定结束日期") }
                repeatUntil?.let { DateTimeField("重复至", it, original.zoneId, { value -> repeatUntil = Instant.ofEpochMilli(value).atZone(ZoneId.of(original.zoneId)).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of(original.zoneId)).toInstant().toEpochMilli() - 1 }, dateOnly = true) }
            }
        }
        OutlinedTextField(location, { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true)
        if (original.kind == ItemKind.COURSE) OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 3)
        OutlinedTextField(reminders, { reminders = it }, label = { Text(if (kind == ItemKind.EVENT || kind == ItemKind.COURSE) "开始前提醒（分钟）" else "提前提醒（分钟）") }, supportingText = { Text("0 为准时，多次用逗号分隔；留空关闭") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        if (kind == ItemKind.EVENT || kind == ItemKind.COURSE) {
            OutlinedTextField(endReminders, { endReminders = it }, label = { Text("结束前提醒（分钟）") }, supportingText = { Text("0 为结束时提醒，多次用逗号分隔；留空关闭") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        Button(enabled = !busy, onClick = {
            try {
                val anchor = if (task && deadline != null) TimeAnchor.DEADLINE else TimeAnchor.START
                val minuteValues = reminders.replace('，', ',').split(',').map(String::trim).filter(String::isNotEmpty).map(String::toInt)
                val endMinuteValues = if (kind == ItemKind.EVENT || kind == ItemKind.COURSE) endReminders.replace('，', ',').split(',').map(String::trim).filter(String::isNotEmpty).map(String::toInt) else emptyList()
                val dailyValues = if (daily) times.replace('，', ',').split(',').map(String::trim).filter(String::isNotEmpty).map { LocalTime.parse(it).toString() }.sorted() else emptyList()
                val rule = frequency.takeIf { it.isNotEmpty() && !daily }?.let { RepeatRule(RepeatFrequency.valueOf(it), interval.toInt(), repeatUntil, if (it == "WEEKLY") weekdays.toSet() else emptySet()) }
                val recurring = daily || rule != null
                val saved = original.copy(title = title.trim(), kind = kind, startAt = start, endAt = end, deadlineAt = deadline, notes = notes, location = location, teacher = teacher,
                    dailyTimes = if (daily) dailyValues else emptyList(), estimatedMinutes = if (task) estimate.takeIf(String::isNotBlank)?.toInt() else null,
                    reminders = minuteValues.map { Reminder(anchor, it) } + endMinuteValues.map { Reminder(TimeAnchor.END, it) },
                    repeat = rule, status = if (recurring) ItemStatus.OPEN else original.status,
                    completedOccurrences = if (recurring && kind == original.kind) original.completedOccurrences else emptySet(),
                    exceptions = if (rule == original.repeat && kind == original.kind) original.exceptions else emptyMap())
                saved.validate(); onSave(saved)
            } catch (e: Exception) { onError(e.message ?: "请检查时间和数字格式") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) { Text("保存") }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable fun DateTimeField(label: String, value: Long?, zoneId: String, onValue: (Long) -> Unit, dateOnly: Boolean = false) {
    val context = LocalContext.current
    val zone = ZoneId.of(zoneId)
    fun pick() {
        val old = Instant.ofEpochMilli(value ?: System.currentTimeMillis()).atZone(zone)
        DatePickerDialog(context, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (dateOnly) onValue(date.atStartOfDay(zone).toInstant().toEpochMilli())
            else TimePickerDialog(context, { _, hour, minute -> onValue(date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()) }, old.hour, old.minute, true).show()
        }, old.year, old.monthValue - 1, old.dayOfMonth).show()
    }
    OutlinedTextField(value = if (dateOnly) dateTimeText(value, zoneId).take(10) else dateTimeText(value, zoneId), onValueChange = {}, readOnly = true,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { GlyphButton(R.drawable.ic_calendar_days, "选择$label", ::pick) })
}

@Composable fun ItemDetails(occurrence: Occurrence, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onCourses: () -> Unit, onSaveNotes: (String) -> Unit, onAiNotes: () -> Unit) {
    val item = occurrence.item
    var deleting by remember { mutableStateOf(false) }
    var editingNotes by remember { mutableStateOf(false) }
    var noteDraft by remember(item.id, item.notes) { mutableStateOf(visibleNotes(item)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        PageTitle("详情", onBack) { GlyphButton(R.drawable.ic_pencil, "编辑", onEdit) }
        Text(item.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        if (item.kind == ItemKind.DAILY) DetailLine("每日", item.dailyTimes.joinToString("、"))
        else {
            occurrence.startAt?.let { DetailLine(if (item.kind == ItemKind.REMINDER) "提醒" else "开始", dateTimeText(it, item.zoneId)) }
            occurrence.endAt?.let { DetailLine("结束", dateTimeText(it, item.zoneId)) }
            occurrence.deadlineAt?.let { DetailLine("截止", dateTimeText(it, item.zoneId)) }
        }
        if (item.location.isNotBlank()) DetailLine("地点", item.location)
        item.repeat?.let { DetailLine("重复", repeatText(it)) }
        if (item.teacher.isNotBlank()) DetailLine("教师", item.teacher)
        item.estimatedMinutes?.let { DetailLine("预计耗时", "$it 分钟") }
        if (item.reminders.isNotEmpty()) DetailLine("提醒", item.reminders.joinToString("、") {
            val anchor = when (it.anchor) { TimeAnchor.START -> if (item.kind == ItemKind.REMINDER || item.kind == ItemKind.DAILY) "提醒时刻" else "开始"; TimeAnchor.END -> "结束"; TimeAnchor.DEADLINE -> "截止" }
            if (it.minutesBefore == 0) "${anchor}时" else "${anchor}前${it.minutesBefore}分钟"
        })
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("备注", style = MaterialTheme.typography.titleSmall)
            Row { TextButton(onClick = { editingNotes = true }) { Text(if (visibleNotes(item).isBlank()) "添加" else "编辑") }; TextButton(onClick = onAiNotes) { Text("AI填写") } }
        }
        if (visibleNotes(item).isNotBlank()) Text(visibleNotes(item), style = MaterialTheme.typography.bodyLarge)
        if (item.kind == ItemKind.COURSE) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCourses, modifier = Modifier.fillMaxWidth()) { Text("资料") }
        }
        TextButton(onClick = { deleting = true }, modifier = Modifier.padding(top = 24.dp)) { Text("删除事项", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(100.dp))
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这项安排？") }, text = { Text(item.title) }, confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    if (editingNotes) AlertDialog(onDismissRequest = { editingNotes = false }, title = { Text("备注") }, text = { OutlinedTextField(noteDraft, { noteDraft = it }, modifier = Modifier.fillMaxWidth(), minLines = 4) },
        confirmButton = { TextButton(onClick = { onSaveNotes(noteDraft); editingNotes = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editingNotes = false; noteDraft = visibleNotes(item) }) { Text("取消") } })
}
fun visibleNotes(item: ScheduleItem): String = if (item.kind == ItemKind.COURSE) item.notes.lineSequence().filterNot { it.startsWith("来源：") || it.startsWith("教学班：") }.joinToString("\n").trim() else item.notes
fun repeatText(rule: RepeatRule): String = buildList {
    add("每${rule.interval}${when(rule.frequency) { RepeatFrequency.DAILY -> "天"; RepeatFrequency.WEEKLY -> "周"; RepeatFrequency.MONTHLY -> "月"; RepeatFrequency.YEARLY -> "年" }}")
    if (rule.weekdays.isNotEmpty()) add(rule.weekdays.sorted().joinToString("、") { "周" + listOf("一", "二", "三", "四", "五", "六", "日")[it - 1] })
    rule.until?.let { add("至 ${dateTimeText(it, "Asia/Shanghai")}") }
}.joinToString(" · ")
@Composable fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(label, Modifier.width(80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f)) }
}
