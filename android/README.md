# Android 基础服务

无 UI 的 Android library，minSdk 26、compileSdk 36、Java 17，无额外依赖。宿主必须明确设置 targetSdk，并处理通知运行时授权与精确闹钟设置授权。

```kotlin
val vault = CredentialVault(context)
vault.put("account", account)
vault.put("password", password)
val passwordAgain = vault.get("password")
vault.put("password", null) // null 或空字符串删除

val scheduler = ReminderScheduler(context)
val permissions = scheduler.permissions()
val result = scheduler.schedule(Reminder("task-42", "线性代数作业", triggerAtMillis))
scheduler.cancel("task-42")
val resultsAfterGrant = scheduler.reschedule()
```

在工作线程调用持久化 API。API 面向单进程宿主；同进程实例共享锁。凭据名按 SHA-256 转换为存储槽位，值使用 Android Keystore AES-256/GCM、随机 IV 与槽位 AAD 加密；SharedPreferences 只保存密文与 IV。不记录秘密。密钥失效、认证失败和存储失败向调用者抛出异常，调用者应要求重新录入凭据，不可自动当作空密码。

相同提醒 ID 更新，不同 ID 使用独立 PendingIntent URI 和通知 tag，不依赖哈希 requestCode。更新和取消均清除旧通知。所有提醒是绝对 Unix 毫秒时间，时区变化不重算墙上时间。权限不足仍保存未来提醒，返回 EXACT_PERMISSION_REQUIRED 或 NOTIFICATIONS_DISABLED；permissions() 可同时查看两种权限。宿主应在授权或重新进入应用后调用 reschedule()。不降级成不精确闹钟。

系统启动、时间/时区变化、精确权限授予、应用更新触发重排；不重放已过期提醒。正常交付迟到超过 5 分钟也丢弃。receiver 均不导出，系统广播仍可送达，不接收其他应用的伪造广播。标题属于普通提醒元数据，存储在私有 SharedPreferences；秘密不得放入标题。通知支持完成当次事项和延后十分钟，重复事项用原始发生时间标识当次记录。备份完全禁用，并显式从云备份和设备迁移排除凭据、提醒文件；宿主合并 manifest 时必须保留这些排除规则。

平台服务验证情况见上级 README。SCHEDULED_EXACT 仅表示 AlarmManager 接受注册，无法保证系统实时交付；设备关闭、应用被强制停止或用户禁通知时无法显示提醒。真机重启、Doze及厂商后台限制仍须针对设备验证。

官方依据：[闹钟与精确权限](https://developer.android.com/develop/background-work/services/alarms)、[密码算法](https://developer.android.com/privacy-and-security/cryptography)、[备份安全](https://developer.android.com/privacy-and-security/risks/backup-best-practices)。
