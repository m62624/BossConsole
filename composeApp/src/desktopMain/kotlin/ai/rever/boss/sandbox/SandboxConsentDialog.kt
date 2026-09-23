package ai.rever.boss.sandbox

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun SandboxConsentDialog(
    request: SandboxConsentRequest,
    onDecide: (SandboxConsentChoice) -> Unit,
) {
    val review = request.review
    BossDialog(onDismissRequest = { onDecide(SandboxConsentChoice.DENY) }) {
        Surface(color = BossTheme.colors.panel, contentColor = BossTheme.colors.textPrimary) {
            Column(Modifier.width(680.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Approve sandbox permissions?")
                Text("No command has been launched for this request. Review the exact command and native policy below.")
                SelectionContainer {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("Project: ${review.projectDirectory}")
                        Text(
                            "Command (executable + argv): ${Json.encodeToString(review.argv)}",
                            fontFamily = FontFamily.Monospace,
                        )
                        Text("Reason: ${review.reason}")
                        Text(review.permissionsJson, fontFamily = FontFamily.Monospace)
                    }
                }
                if (review.requiresRestart) {
                    Text("Additional permissions apply only to this new command. The requesting agent stays unchanged.")
                }
                Text("Until BOSS closes remembers only this exact command and policy. Restarting BOSS asks again.")
                SandboxConsentButtons(request.id, onDecide)
            }
        }
    }
}

/** Arming is keyed by request identity, so a double click cannot authorize the next request. */
@Composable
internal fun SandboxConsentButtons(
    requestId: String,
    onDecide: (SandboxConsentChoice) -> Unit,
) {
    key(requestId) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onDecide(SandboxConsentChoice.DENY) }) { Text("Deny") }
            TextButton(
                enabled = armed,
                onClick = { if (armed) onDecide(SandboxConsentChoice.UNTIL_APP_CLOSES) },
            ) { Text("Until BOSS closes") }
            TextButton(enabled = armed, onClick = { if (armed) onDecide(SandboxConsentChoice.ONCE) }) {
                Text("Approve once")
            }
        }
    }
}
