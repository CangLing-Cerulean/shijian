package schedule.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import schedule.android.ReminderScheduler
import schedule.core.*
import java.time.*

@Composable fun SettingsScreen(model: AppModel, onCourses: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf("") }
    var permissionGuide by rememberSaveable { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(ReminderScheduler(context).permissions()) }
    var backup by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissions = ReminderScheduler(context).permissions() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.launchWork { withContext(Dispatchers.IO) { val text = model.runtime.exportBackup(); requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) } }; model.notice = "备份已导出" }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.launchWork { backup = withContext(Dispatchers.IO) { val text = requireNotNull(context.contentResolver.openInputStream(uri)).readLimited(MAX_BACKUP_BYTES).toString(Charsets.UTF_8); validateState(scheduleJson.decodeFromString<ScheduleState>(text)); text } }
    }
    if (permissionGuide) {
        BackHandler { permissionGuide = false }
        PermissionGuideScreen(onBack = { permissionGuide = false }, onChanged = { model.refresh() })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        PageTitle("设置")
        SectionLabel("外观")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemePreference.SYSTEM to "跟随系统", ThemePreference.LIGHT to "浅色", ThemePreference.DARK to "深色").forEach { (value, label) -> FilterChip(selected = model.state?.settings?.theme == value, onClick = { model.theme(value) }, label = { Text(label) }) }
        }
        SectionLabel("连接")
        SettingsRow("AI 接口", model.aiConfig?.model ?: "未配置") { dialog = "ai" }
        SettingsRow("学校账号", if (model.schoolConfigured) "已配置" else "未配置") { dialog = "school" }
        SettingsRow("课表与考试", if (model.schoolSyncing) "正在同步…" else model.schoolSyncStatus) { model.syncSchool(force = true) }
        SettingsRow("资料", "课程资料与本地下载", onCourses)
        SectionLabel("提醒")
        SettingsRow("权限引导", if (permissions.notifications && permissions.exactAlarms) "通知与准时提醒已开启" else "完善通知与准时提醒权限") { permissionGuide = true }
        SectionLabel("数据")
        SettingsRow("导出备份", "日程与设置") { export.launch("日程-${LocalDate.now()}.json") }
        SettingsRow("恢复备份", "替换当前日程") { restore.launch(arrayOf("application/json", "text/plain")) }
        Spacer(Modifier.height(100.dp))
    }
    if (dialog == "ai") AiSettingsDialog(model, { dialog = "" })
    if (dialog == "school") SchoolSettingsDialog(model, { dialog = "" })
    backup?.let { content -> AlertDialog(onDismissRequest = { backup = null }, title = { Text("恢复这份备份？") }, text = { Text("将以备份中的${scheduleJson.decodeFromString<ScheduleState>(content).items.size}项安排替换当前日程。账号和API密钥保持不变。") },
        confirmButton = { TextButton(onClick = { model.mutation({ model.runtime.restoreBackup(content) }) { backup = null } }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { backup = null }) { Text("取消") } }) }
}
@Composable private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }; Glyph(R.drawable.ic_chevron_right, null) }
    }
}
