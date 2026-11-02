package schedule.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class Reminder(
    val id: String,
    val title: String,
    val triggerAtMillis: Long,
    val itemId: String? = null,
    val originItemId: String? = itemId,
    val snoozed: Boolean = false,
    val occurrenceStart: Long? = null,
)
enum class ScheduleResult { SCHEDULED_EXACT, EXACT_PERMISSION_REQUIRED, NOTIFICATIONS_DISABLED, EXPIRED }
data class ReminderPermissions(val exactAlarms: Boolean, val notifications: Boolean)

/** Single-process service. Call from a worker thread; persistence uses synchronous commits. */
class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val preferences = this.context.getSharedPreferences("schedule_reminders", Context.MODE_PRIVATE)

    fun permissions(): ReminderPermissions {
        ensureChannel()
        val notificationPermission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return ReminderPermissions(
            Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
            notificationPermission && notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        )
    }

    /** Upserts persisted intent even if permissions are denied; retry with reschedule() after grant. */
    fun schedule(reminder: Reminder): ScheduleResult = synchronized(lock) {
        require(reminder.id.isNotBlank() && reminder.title.isNotBlank())
        val reminders = read().associateBy { it.id }.toMutableMap()
        val delivered = read("delivered").filterNot { it.id == reminder.id }
        if (reminder.triggerAtMillis <= System.currentTimeMillis()) {
            reminders.remove(reminder.id)
            writeState(reminders.values, delivered)
            cancelAlarm(reminder.id)
            notifications.cancel(reminder.id, NOTIFICATION_ID)
            return@synchronized ScheduleResult.EXPIRED
        }
        reminders[reminder.id] = reminder
        writeState(reminders.values, delivered)
        cancelAlarm(reminder.id)
        notifications.cancel(reminder.id, NOTIFICATION_ID)
        arm(reminder)
    }

    fun cancel(id: String) = synchronized(lock) {
        writeState(read().filterNot { it.id == id }, read("delivered").filterNot { it.id == id })
        cancelAlarm(id)
        notifications.cancel(id, NOTIFICATION_ID)
    }

    fun pending(): List<Reminder> = synchronized(lock) { read().sortedBy { it.triggerAtMillis } }

    /** Reconciles pending alarms without recreating registrations whose DTOs are unchanged. */
    fun replaceAll(reminders: Collection<Reminder>): Map<String, ScheduleResult> = synchronized(lock) {
        require(reminders.map { it.id }.distinct().size == reminders.size)
        reminders.forEach { require(it.id.isNotBlank() && it.title.isNotBlank()) }
        val previous = read().associateBy { it.id }
        val next = reminders.associateBy { it.id }
        previous.keys.filterNot { it in next }.forEach { cancel(it) }
        reminders.associate { reminder ->
            reminder.id to if (previous[reminder.id] != reminder) schedule(reminder) else {
                val status = permissions()
                when {
                    reminder.triggerAtMillis <= System.currentTimeMillis() -> { cancel(reminder.id); ScheduleResult.EXPIRED }
                    !status.notifications -> ScheduleResult.NOTIFICATIONS_DISABLED
                    !status.exactAlarms -> ScheduleResult.EXACT_PERMISSION_REQUIRED
                    registered[reminder.id] == reminder -> ScheduleResult.SCHEDULED_EXACT
                    else -> arm(reminder)
                }
            }
        }
    }

    internal fun performAction(id: String, expectedTime: Long, complete: Boolean) {
        // Do not hold the alarm lock while acquiring the runtime's schedule lock.
        val reminder = synchronized(lock) { read("delivered").find { it.id == id && it.triggerAtMillis == expectedTime } } ?: return
        if (complete) {
            val itemId = reminder.itemId ?: return
            ScheduleRuntime(context).completeTask(itemId, reminder.occurrenceStart)
            val ids = synchronized(lock) { (read() + read("delivered")).filter { it.itemId == itemId && it.occurrenceStart == reminder.occurrenceStart }.map { it.id }.distinct() }
            ids.forEach { cancel(it) }
        } else {
            val result = schedule(reminder.copy(triggerAtMillis = System.currentTimeMillis() + SNOOZE_MS, snoozed = true))
            check(result == ScheduleResult.SCHEDULED_EXACT) { "Reminder permission required" }
        }
    }

    fun pruneDelivered(activeItemIds: Set<String>, completedOccurrences: Map<String, Set<Long>> = emptyMap()) = synchronized(lock) {
        val all = read("delivered")
        val removed = all.filter { it.originItemId !in activeItemIds || it.occurrenceStart in completedOccurrences[it.originItemId].orEmpty() || it.triggerAtMillis < System.currentTimeMillis() - 7L * 86400000 }
        write(all - removed.toSet(), "delivered")
        removed.forEach { notifications.cancel(it.id, NOTIFICATION_ID) }
    }

    /** Absolute wall-clock times stay fixed on timezone changes. Expired entries are discarded. */
    fun reschedule(): Map<String, ScheduleResult> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val all = read()
        val future = all.filter { it.triggerAtMillis > now }
        write(future)
        all.forEach { cancelAlarm(it.id) }
        all.associate { it.id to if (it.triggerAtMillis <= now) ScheduleResult.EXPIRED else arm(it) }
    }

    internal fun deliver(id: String, expectedTime: Long) = synchronized(lock) {
        val all = read()
        val reminder = all.find { it.id == id && it.triggerAtMillis == expectedTime } ?: return@synchronized
        val now = System.currentTimeMillis()
        if (now < reminder.triggerAtMillis) return@synchronized
        // Do not release a backlog after reboot, clock changes or prolonged device shutdown.
        if (now - reminder.triggerAtMillis > MAX_LATENESS_MS || !permissions().notifications) {
            write(all.filterNot { it.id == id })
            return@synchronized
        }
        writeState(all.filterNot { it.id == id }, read("delivered").filterNot { it.id == id } + reminder)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "10分钟后提醒", actionIntent(reminder, ACTION_SNOOZE)).build())
        if (reminder.itemId != null) {
            builder.addAction(Notification.Action.Builder(null, "完成", actionIntent(reminder, ACTION_COMPLETE)).build())
        }
        val notification = builder.build()
        try {
            notifications.notify(id, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between checking and posting. No secret/title is logged.
        }
    }

    private fun arm(reminder: Reminder): ScheduleResult {
        val status = permissions()
        if (!status.notifications) return ScheduleResult.NOTIFICATIONS_DISABLED
        if (!status.exactAlarms) return ScheduleResult.EXACT_PERMISSION_REQUIRED
        return try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, intent(reminder))
            registered[reminder.id] = reminder
            ScheduleResult.SCHEDULED_EXACT
        } catch (_: SecurityException) {
            ScheduleResult.EXACT_PERMISSION_REQUIRED
        }
    }

    private fun intent(reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context, 0, baseIntent(reminder.id).putExtra("time", reminder.triggerAtMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun baseIntent(id: String) = Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION_DELIVER)
        .setData(Uri.Builder().scheme("schedule-reminder").authority(context.packageName).appendPath(id).build())

    private fun actionIntent(reminder: Reminder, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderActionReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("schedule-reminder-action").authority(context.packageName)
                .appendPath(reminder.id).appendPath(reminder.triggerAtMillis.toString()).build()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelAlarm(id: String) {
        registered.remove(id)
        PendingIntent.getBroadcast(context, 0, baseIntent(id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { alarms.cancel(it); it.cancel() }
    }

    private fun ensureChannel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun read(key: String = "items"): List<Reminder> {
        val json = JSONArray(preferences.getString(key, "[]"))
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            Reminder(item.getString("id"), item.getString("title"), item.getLong("time"),
                if (item.isNull("itemId")) null else item.getString("itemId"),
                if (item.isNull("originItemId")) null else item.getString("originItemId"),
                item.getBoolean("snoozed"), if (item.isNull("occurrenceStart")) null else item.getLong("occurrenceStart"))
        }
    }

    private fun write(reminders: Collection<Reminder>, key: String = "items") {
        check(preferences.edit().putString(key, encode(reminders)).commit()) { "Reminder persistence failed" }
    }

    private fun writeState(pending: Collection<Reminder>, delivered: Collection<Reminder>) {
        check(preferences.edit().putString("items", encode(pending))
            .putString("delivered", encode(delivered)).commit()) { "Reminder persistence failed" }
    }

    private fun encode(reminders: Collection<Reminder>): String {
        val json = JSONArray()
        reminders.forEach {
            json.put(JSONObject().put("id", it.id).put("title", it.title).put("time", it.triggerAtMillis)
                .put("itemId", it.itemId ?: JSONObject.NULL).put("originItemId", it.originItemId ?: JSONObject.NULL)
                .put("snoozed", it.snoozed).put("occurrenceStart", it.occurrenceStart ?: JSONObject.NULL))
        }
        return json.toString()
    }

    internal companion object {
        val lock = Any()
        val registered = mutableMapOf<String, Reminder>()
        const val ACTION_DELIVER = "schedule.android.DELIVER"
        const val ACTION_COMPLETE = "schedule.android.COMPLETE"
        const val ACTION_SNOOZE = "schedule.android.SNOOZE"
        const val SNOOZE_MS = 10 * 60 * 1000L
        const val CHANNEL = "schedule.reminders"
        const val NOTIFICATION_ID = 1
        const val MAX_LATENESS_MS = 5 * 60 * 1000L
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_DELIVER) return
        val uri = intent.data ?: return
        if (uri.scheme != "schedule-reminder" || uri.authority != context.packageName) return
        val id = uri.lastPathSegment ?: return
        runReminderWork(context) { ReminderScheduler(context).deliver(id, intent.getLongExtra("time", -1)) }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            runReminderWork(context) {
                ScheduleRuntime(context).refreshReminders()
                ReminderScheduler(context).reschedule()
            }
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ReminderScheduler.ACTION_COMPLETE, ReminderScheduler.ACTION_SNOOZE)) return
        val uri = intent.data ?: return
        if (uri.scheme != "schedule-reminder-action" || uri.authority != context.packageName) return
        val segments = uri.pathSegments
        if (segments.size != 2) return
        val time = segments[1].toLongOrNull() ?: return
        runReminderWork(context) {
            ReminderScheduler(context).performAction(segments[0], time, intent.action == ReminderScheduler.ACTION_COMPLETE)
        }
    }
}

private val reminderExecutor = Executors.newSingleThreadExecutor()

private fun BroadcastReceiver.runReminderWork(context: Context, work: () -> Unit) {
    val result = goAsync()
    reminderExecutor.execute {
        try {
            work()
        } catch (_: Exception) {
            // Fixed text avoids exposing task titles, credentials, or exception payloads.
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                manager.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_DEFAULT))
                manager.notify("schedule.error", 1, Notification.Builder(context, ReminderScheduler.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("提醒操作失败")
                    .setContentText("请打开应用检查权限并重试")
                    .build())
            } catch (_: SecurityException) {
                // The OS cannot display the failure when notification permission is denied.
            }
        } finally {
            result.finish()
        }
    }
}
