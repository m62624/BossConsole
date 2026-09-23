package ai.rever.boss.sandbox

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SandboxDisabledDialog(
    stopping: Boolean,
    message: String?,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        Surface(color = BossTheme.colors.panel, contentColor = BossTheme.colors.textPrimary) {
            Column(Modifier.width(560.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (stopping) "Stopping sandbox sessions..." else "Sandbox command sessions are disabled")
                Text("Enable this subsystem for this BOSS run. Its MCP tools are unavailable while disabled.")
                Text("Ordinary launches stay unchanged. Every sandbox launch is still an explicit choice.")
                Text("Only a sandbox session's root process and descendants are isolated, not BOSS or external agents.")
                Text("Restarting BOSS disables the subsystem and forgets its permission approvals.")
                message?.let { Text(it) }
                Row {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    TextButton(onClick = onEnable, enabled = !stopping) { Text("Enable sandbox command sessions") }
                }
            }
        }
    }
}
