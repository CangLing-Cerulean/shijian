package schedule.app

import android.app.NotificationManager
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import schedule.android.*
import schedule.ai.*
import schedule.core.ItemStatus
import schedule.core.ScheduleItem
import java.io.ByteArrayOutputStream

class PlatformServicesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun aiModelsPersistAndKeysStayBoundToEndpoint() {
        val vault = CredentialVault(context)
        val previous = vault.get("ai.configuration")
        val previousProfiles = vault.get("ai.profiles")
        vault.put("ai.profiles", null)
        try {
            val runtime = ScheduleRuntime(context)
            val config = AiConfiguration("https://example.com/v1/chat/completions", "primary", false, listOf(AiModel("vision", true)))
            runtime.configureAi(config, "fixture-key")
            assertEquals(config, ScheduleRuntime(context).aiConfiguration())
            assertEquals("fixture-key", runtime.aiKeyForEndpoint("https://example.com/v1"))
            assertEquals("", runtime.aiKeyForEndpoint("https://other.example/v1"))
            runtime.configureAi(config.copy(model = "new-primary"), "")
            assertEquals("fixture-key", runtime.aiKeyForEndpoint(config.endpoint))
            assertThrows(IllegalArgumentException::class.java) { runtime.configureAi(config.copy(endpoint = "https://other.example/v1/chat/completions"), "") }
            assertEquals("new-primary", runtime.aiConfiguration()?.model)
        } finally { vault.put("ai.profiles", previousProfiles); vault.put("ai.configuration", previous) }
    }
    @Test fun multipleAiProfilesKeepSeparateKeysAcrossRestarts() {
        val vault = CredentialVault(context)
        val previous = vault.get("ai.configuration")
        val previousProfiles = vault.get("ai.profiles")
        vault.put("ai.configuration", null); vault.put("ai.profiles", null)
        try {
            val runtime = ScheduleRuntime(context)
            val first = runtime.saveAiProfile(null, "One", AiConfiguration("https://one.example/v1", "one"), "first-secret")
            val second = runtime.saveAiProfile(null, "Two", AiConfiguration("https://two.example/v1", "two"), "second-secret")
            val reopened = ScheduleRuntime(context)
            assertEquals(2, reopened.aiProfiles().size)
            assertEquals(second, reopened.activeAiProfileId())
            reopened.selectAiProfile(first)
            assertEquals("first-secret", reopened.aiKeyForEndpoint("https://one.example/v1"))
            assertEquals("", reopened.aiKeyForEndpoint("https://two.example/v1"))
            assertFalse(context.getSharedPreferences("schedule_vault", 0).all.values.any { it.toString().contains("first-secret") || it.toString().contains("second-secret") })
            reopened.deleteAiProfile(first)
            assertEquals(second, reopened.activeAiProfileId())
            assertEquals("second-secret", reopened.aiKeyForEndpoint("https://two.example/v1"))
        } finally { vault.put("ai.profiles", previousProfiles); vault.put("ai.configuration", previous) }
    }
    @Test fun keystoreRoundTripAndPdfPageRendering() {
        val vault = CredentialVault(context)
        val secret = "local-emulator-fixture-secret"
        vault.put("platform-test", secret)
        assertEquals(secret, vault.get("platform-test"))
        assertFalse(context.getSharedPreferences("schedule_vault", 0).all.values.any { it.toString().contains(secret) })
        vault.put("platform-test", null); assertNull(vault.get("platform-test"))
        val bytes = ByteArrayOutputStream()
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
            page.canvas.drawText("PDF rendering test", 15f, 40f, android.graphics.Paint())
            document.finishPage(page); document.writeTo(bytes)
        } finally { document.close() }
        val reader = AndroidPdfPageReader(context)
        val image = reader.readPages(bytes.toByteArray(), listOf(1)).single()
        assertEquals("image/png", image.mimeType); assertTrue(image.bytes.size > 100)
        assertThrows(IllegalArgumentException::class.java) { reader.readPages(bytes.toByteArray(), listOf(2)) }
    }
    @Test fun exactAlarmNotificationSnoozeAndComplete() {
        val scheduler = ReminderScheduler(context)
        assertTrue("Test emulator needs notification permission", scheduler.permissions().notifications)
        assertTrue("Test emulator needs exact alarm permission", scheduler.permissions().exactAlarms)
        val runtime = ScheduleRuntime(context)
        val task = ScheduleItem("platform-task", "平台提醒测试", deadlineAt = System.currentTimeMillis() + 3600000)
        runtime.saveItem(task)
        val id = "platform-reminder"
        val manager = context.getSystemService(NotificationManager::class.java)
        fun awaitCondition(condition: () -> Boolean) {
            val limit = System.currentTimeMillis() + 12000
            while (!condition() && System.currentTimeMillis() < limit) Thread.sleep(100)
            assertTrue(condition())
        }
        try {
            assertEquals(ScheduleResult.SCHEDULED_EXACT, scheduler.schedule(Reminder(id, task.title, System.currentTimeMillis() + 1500, itemId = task.id)))
            awaitCondition { manager.activeNotifications.any { it.tag == id } }
            manager.activeNotifications.single { it.tag == id }.notification.actions[0].actionIntent.send()
            awaitCondition { scheduler.pending().any { it.id == id && it.snoozed && it.triggerAtMillis > System.currentTimeMillis() + 9 * 60000 } }
            scheduler.schedule(Reminder(id, task.title, System.currentTimeMillis() + 1500, itemId = task.id))
            awaitCondition { manager.activeNotifications.any { it.tag == id } }
            manager.activeNotifications.single { it.tag == id }.notification.actions[1].actionIntent.send()
            awaitCondition { runtime.snapshot().items.single { it.id == task.id }.status == ItemStatus.DONE }
            awaitCondition { manager.activeNotifications.none { it.tag == id } }
        } finally { scheduler.cancel(id); runtime.deleteItem(task.id) }
    }
}
