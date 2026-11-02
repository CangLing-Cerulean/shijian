package schedule.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import schedule.android.CredentialVault
import schedule.android.ScheduleRuntime
import java.io.File

class AiSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectSeveralModelsAndSaveBeforeBatchTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val previous = vault.get("ai.configuration")
        val previousProfiles = vault.get("ai.profiles")
        vault.put("ai.configuration", null); vault.put("ai.profiles", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("AI 接口").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("新建接口").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("新建接口").performClick()
                compose.onNodeWithText("名称").performTextInput("测试配置")
                compose.onNodeWithTag("ai_endpoint").performTextInput("https://ai-test.invalid/v1")
                compose.onNodeWithTag("ai_key").performTextInput("fixture-key")
                for (name in listOf("first", "second")) {
                    compose.onNodeWithTag("ai_manual_model").performScrollTo().performTextInput(name)
                    compose.onNodeWithText("添加模型").performScrollTo().performClick()
                }
                compose.onAllNodesWithText("尚未测试").assertCountEquals(2)
                compose.onNodeWithTag("ai_test").assertIsEnabled()
                compose.onNodeWithTag("ai_save").assertIsEnabled().performClick()
                compose.waitUntil(5000) { ScheduleRuntime(context).aiProfiles().size == 1 }
                val profile = ScheduleRuntime(context).aiProfiles().single()
                org.junit.Assert.assertEquals(listOf("first", "second"), profile.config.models.map { it.id })
                org.junit.Assert.assertEquals("fixture-key", profile.key)
                org.junit.Assert.assertFalse(profile.config.canUseImages)
            }
        } finally { vault.put("ai.profiles", previousProfiles); vault.put("ai.configuration", previous) }
    }

    @Test fun orderModelsAndRequireCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val previous = vault.get("ai.configuration")
        val previousProfiles = vault.get("ai.profiles")
        vault.put("ai.profiles", null)
        vault.put("ai.configuration", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("AI 接口").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("新建接口").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("新建接口").performClick()
                compose.onNodeWithTag("ai_endpoint").performTextInput("https://example.com/v1")
                compose.onNodeWithTag("ai_manual_model").performScrollTo().performTextInput("first")
                compose.onNodeWithText("添加模型").performScrollTo().performClick()
                compose.onNodeWithTag("ai_manual_model").performScrollTo().performTextInput("second")
                compose.onNodeWithText("添加模型").performScrollTo().performClick()
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.onNodeWithText("设为主模型").performScrollTo().performClick()
                compose.onNodeWithText("second · 主模型").assertExists()
                compose.onNodeWithTag("ai_save").assertIsNotEnabled()
                compose.onNodeWithTag("ai_test").assertIsNotEnabled()
                compose.onNodeWithText("second · 主模型").performScrollTo()
                compose.waitForIdle()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "ai-settings.png").outputStream().use { output -> screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                screenshot.recycle()
                compose.onNodeWithText("返回").performClick()
                compose.onNodeWithText("关闭").performClick()
            }
        } finally { vault.put("ai.profiles", previousProfiles); vault.put("ai.configuration", previous) }
    }
}
