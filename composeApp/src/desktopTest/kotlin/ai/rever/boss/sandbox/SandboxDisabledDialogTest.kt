package ai.rever.boss.sandbox

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SandboxDisabledDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `opening disabled subsystem does not enable it`() {
        var enabled = 0
        rule.setContent { SandboxDisabledDialog(false, null, { enabled++ }, {}) }
        rule.runOnIdle { assertEquals(0, enabled) }
        rule.onNodeWithText("Enable sandbox command sessions").performClick()
        rule.runOnIdle { assertEquals(1, enabled) }
    }

    @Test
    fun `cannot reenable while old native sessions are stopping`() {
        rule.setContent { SandboxDisabledDialog(true, null, { error("Must not enable") }, {}) }
        rule.onNodeWithText("Enable sandbox command sessions").assertIsNotEnabled().performClick()
    }
}
