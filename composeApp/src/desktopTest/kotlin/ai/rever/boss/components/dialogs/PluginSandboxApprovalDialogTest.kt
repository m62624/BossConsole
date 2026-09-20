package ai.rever.boss.components.dialogs

import ai.rever.boss.components.plugin.PluginSandboxApprovalRequest
import ai.rever.boss.components.plugin.PluginSandboxCapability
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PluginSandboxApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `approve and deny are explicit operator decisions`() {
        var approved = 0
        var denied = 0
        val request =
            PluginSandboxApprovalRequest(
                pluginId = "com.example.protected",
                displayName = "Protected plugin",
                capabilities =
                    listOf(
                        PluginSandboxCapability("filesystem", "${'$'}{workspace}/models", "read"),
                    ),
                timeoutMs = 1_000,
            )

        rule.setContent {
            PluginSandboxApprovalDialog(
                request = request,
                onApprove = { approved++ },
                onDeny = { denied++ },
            )
        }
        rule.onNodeWithText("Protected plugin").assertExists()
        rule.onNodeWithText("Approve and launch").performClick()
        rule.onNodeWithText("Deny").performClick()

        rule.runOnIdle {
            assertEquals(1, approved)
            assertEquals(1, denied)
        }
    }
}
