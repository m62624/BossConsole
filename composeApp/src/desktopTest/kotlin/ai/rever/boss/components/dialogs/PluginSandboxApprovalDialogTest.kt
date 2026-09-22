package ai.rever.boss.components.dialogs

import ai.rever.boss.components.plugin.PluginSandboxApprovalRequest
import ai.rever.boss.components.plugin.PluginSandboxCapability
import androidx.compose.runtime.Composable
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
    fun `approve is an explicit operator decision`() {
        var approved = 0
        var denied = 0

        rule.setContent { approvalDialog(onApprove = { approved++ }, onDeny = { denied++ }) }
        rule.onNodeWithText("Protected plugin").assertExists()
        rule.onNodeWithText("Approve and launch").performClick()

        rule.runOnIdle {
            assertEquals(1, approved)
            assertEquals(0, denied)
        }
    }

    @Test
    fun `deny is an explicit operator decision`() {
        var approved = 0
        var denied = 0

        rule.setContent { approvalDialog(onApprove = { approved++ }, onDeny = { denied++ }) }
        rule.onNodeWithText("Protected plugin").assertExists()
        rule.onNodeWithText("Deny").performClick()

        rule.runOnIdle {
            assertEquals(0, approved)
            assertEquals(1, denied)
        }
    }

    @Composable
    private fun approvalDialog(
        onApprove: () -> Unit,
        onDeny: () -> Unit,
    ) {
        PluginSandboxApprovalDialog(
            request =
                PluginSandboxApprovalRequest(
                    pluginId = "com.example.protected",
                    displayName = "Protected plugin",
                    capabilities =
                        listOf(
                            PluginSandboxCapability("filesystem", "${'$'}{workspace}/models", "read"),
                        ),
                    timeoutMs = 1_000,
                ),
            onApprove = onApprove,
            onDeny = onDeny,
        )
    }
}
