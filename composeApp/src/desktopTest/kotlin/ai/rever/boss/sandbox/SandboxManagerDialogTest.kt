package ai.rever.boss.sandbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SandboxManagerDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `opening or editing the form never launches and a pending review prevents duplicate submission`() {
        val form = mutableStateOf(SandboxLaunchForm("project", "policy.toml"))
        val busy = mutableStateOf(false)
        val submitted = mutableListOf<List<String>>()
        rule.setContent {
            SandboxManagerDialog(
                form = form.value,
                onChange = { form.value = it },
                busy = busy.value,
                message = null,
                sessions = emptyList(),
                onStart = {
                    submitted.add(form.value.command().argv)
                    busy.value = true
                },
                onAction = {},
                onRemove = {},
                onRevoke = {},
                onDismiss = {},
                onDisable = {},
            )
        }
        rule.onNodeWithText("Executable (not a shell command)").performScrollTo().performTextReplacement("node")
        rule
            .onNodeWithText("Arguments as JSON array, e.g. [\"--version\"]")
            .performScrollTo()
            .performTextReplacement("[\"a b.js\",\"\"]")
        rule.runOnIdle { assertEquals(emptyList(), submitted) }
        rule.onNodeWithText("Review and run").performScrollTo().performClick()
        rule.onNodeWithText("Waiting for approval...").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(listOf("node", "a b.js", "")), submitted) }
    }
}
