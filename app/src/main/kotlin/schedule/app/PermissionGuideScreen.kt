package schedule.app

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import schedule.android.ReminderScheduler

@Composable
fun PermissionGuideScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permissions by remember { mutableStateOf(ReminderScheduler(context).permissions()) }
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            permissions = ReminderScheduler(context).permissions()
            unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
            onChanged()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) {
        try { context.startActivity(intent); error = null }
        catch (_: android.content.ActivityNotFoundException) { error = "系统未提供此入口，请在系统设置中搜索“时笺”。" }
        catch (_: SecurityException) { error = "系统限制了此入口，请在系统设置中搜索“时笺”。" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        PageTitle("权限引导", onBack)
        PermissionStep("通知提醒", if (permissions.notifications) "已开启" else "未开启",
            "允许时笺发送通知，并开启“日程提醒”通知类别。锁屏显示和声音可在通知设置中调整。", "通知设置") {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        PermissionStep("准时提醒", if (permissions.exactAlarms) "已开启" else "未开启",
            "允许在设定的时刻触发提醒。", if (Build.VERSION.SDK_INT >= 31) "闹钟与提醒设置" else "系统已支持", Build.VERSION.SDK_INT >= 31) {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }
        PermissionStep("电池优化", if (unrestricted) "已豁免" else "未豁免",
            "可将时笺设为不优化，减少后台提醒受省电策略影响。", "电池优化设置") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        PermissionStep("自启动与后台运行", "需手动检查",
            "在应用信息中检查自启动与省电策略。小米／澎湃系统可允许自启动，并将省电策略设为无限制；入口名称以手机系统为准。", "应用信息") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun PermissionStep(title: String, state: String, description: String, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(top = 6.dp)) { Text(action) }
        }
    }
}
