package ai.rever.boss.sandbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SandboxConsentDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `next request rearms both approval scopes but denial is immediate`() {
        val requestId = mutableStateOf("first")
        val decisions = mutableListOf<SandboxConsentChoice>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SandboxConsentButtons(requestId.value) { decisions.add(it) }
        }
        rule.onNodeWithText("Approve once").assertIsNotEnabled()
        rule.onNodeWithText("Until BOSS closes").assertIsNotEnabled()
        rule.onNodeWithText("Deny").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Approve once").assertIsEnabled().performClick()
        rule.runOnIdle { requestId.value = "second" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Approve once").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Until BOSS closes").assertIsNotEnabled().performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Until BOSS closes").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(SandboxConsentChoice.DENY, SandboxConsentChoice.ONCE, SandboxConsentChoice.UNTIL_APP_CLOSES),
                decisions,
            )
        }
    }
}
