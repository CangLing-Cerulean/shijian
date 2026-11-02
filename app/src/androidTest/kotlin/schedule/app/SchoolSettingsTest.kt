package schedule.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import schedule.android.CredentialVault

class SchoolSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedAccountReturnsWithoutLoginOnOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val previous = vault.get("school.account")
        val fixture = JSONObject().put("username", "fixture-student").put("password", "fixture-secret").toString()
        vault.put("school.account", fixture)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("学校账号").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("fixture-student").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("school_username").assertTextContains("fixture-student")
                compose.onNodeWithTag("school_password").assertExists()
                compose.onNodeWithContentDescription("显示密码").performClick()
                compose.onNodeWithTag("school_password").assertTextContains("fixture-secret")
                compose.onNodeWithContentDescription("隐藏密码").performClick()
                compose.onNodeWithContentDescription("显示密码").assertExists()
                compose.onNodeWithTag("school_verify").assertIsEnabled()
                compose.onNodeWithText("取消").performClick()
                assertEquals(fixture, vault.get("school.account"))
            }
        } finally { vault.put("school.account", previous) }
    }
}
