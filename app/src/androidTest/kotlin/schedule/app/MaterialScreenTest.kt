package schedule.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import schedule.android.CredentialVault
import schedule.android.MaterialStore
import schedule.school.CourseMaterial
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class MaterialScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineDownloadsRemainAfterReadingAndDeleteLocally() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = CredentialVault(context)
        val account = vault.get("school.account")
        val store = MaterialStore(context)
        val previousIds = store.list().map { it.id }.toSet()
        val unique = UUID.randomUUID().toString()
        val name = "离线资料验证-$unique.pdf"
        val courseName = "000 离线测试课程"
        val activityTitle = "极限与连续（测试活动）"
        val bytes = ByteArrayOutputStream().use { output ->
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Offline material fixture", 24f, 60f, Paint().apply { textSize = 16f })
                document.finishPage(page)
                document.writeTo(output)
            } finally { document.close() }
            output.toByteArray()
        }
        val saved = store.save("test-$unique", courseName,
            CourseMaterial("activity-$unique", 1L, null, name, bytes.size.toLong(), activityTitle), bytes)
        val untouched = File(context.cacheDir, "material-sentinel-$unique").apply { writeText("keep", Charsets.UTF_8) }
        try {
            vault.put("school.account", null)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("资料").performScrollTo().performClick()
                compose.onNodeWithText("已下载").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("$courseName · $activityTitle").assertIsDisplayed()

                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(requireNotNull(context.getExternalFilesDir(null)), "material-downloads.png").outputStream().use { output ->
                        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally { screenshot.recycle() }

                clickFixtureAction(name, "阅读")
                compose.waitUntil(5000) { compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithText(name).assertIsDisplayed()
                assertTrue(store.file(saved).isFile)
                assertArrayEquals(bytes, store.file(saved).readBytes())

                clickFixtureAction(name, "删除")
                compose.waitUntil(5000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
                assertFalse(store.file(saved).exists())
                assertEquals(previousIds, store.list().map { it.id }.toSet())
                assertEquals("keep", untouched.readText(Charsets.UTF_8))
            }
        } finally {
            try {
                if (store.list().any { it.id == saved.id }) store.delete(saved)
                untouched.delete()
            } finally { vault.put("school.account", account) }
        }
    }

    /** Locate the action row immediately below this fixture, preserving other stored downloads. */
    private fun clickFixtureAction(name: String, action: String) {
        compose.onNodeWithText(name).performScrollTo()
        val titleBottom = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.bottom
        val actions = compose.onAllNodesWithText(action)
        val index = actions.fetchSemanticsNodes().withIndex()
            .filter { it.value.boundsInRoot.top >= titleBottom }
            .minByOrNull { it.value.boundsInRoot.top }?.index
            ?: error("Missing fixture action: $action")
        actions[index].performClick()
    }
}
