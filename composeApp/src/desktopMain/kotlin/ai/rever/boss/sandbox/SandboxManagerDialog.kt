package ai.rever.boss.sandbox

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun SandboxManagerDialog(
    form: SandboxLaunchForm,
    onChange: (SandboxLaunchForm) -> Unit,
    busy: Boolean,
    message: String?,
    sessions: List<SandboxSessionEntry>,
    onStart: () -> Unit,
    onAction: (suspend () -> Unit) -> Unit,
    onRemove: suspend (String) -> Unit,
    onRevoke: () -> Unit,
    onDismiss: () -> Unit,
) {
    BossDialog(onDismissRequest = onDismiss) {
        Surface(color = BossTheme.colors.panel, contentColor = BossTheme.colors.textPrimary) {
            Column(
                Modifier
                    .width(720.dp)
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Sandbox command sessions")
                Text(
                    "Explicit opt-in. The root executable and its descendants share one Cageforge policy. " +
                        "Ordinary terminals are unchanged.",
                )
                Text("Uses stdin/stdout pipes, not an interactive terminal. Windows requires Cageforge setup first.")
                SandboxLaunchFields(form, onChange, !busy)
                Row {
                    TextButton(onClick = onStart, enabled = !busy) {
                        Text(if (busy) "Waiting for approval..." else "Review and run")
                    }
                    TextButton(onClick = onRevoke) { Text("Revoke remembered approvals") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                message?.let { Text(it) }
                sessions.forEach { entry ->
                    key(entry.id) {
                        Divider()
                        SandboxSessionCard(entry, onAction) { onRemove(entry.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxLaunchFields(
    form: SandboxLaunchForm,
    onChange: (SandboxLaunchForm) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(form.project, {
        onChange(form.copy(project = it))
    }, label = { Text("Project directory (absolute)") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.policy, {
        onChange(form.copy(policy = it))
    }, label = { Text("Policy TOML file (absolute)") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.profile, {
        onChange(form.copy(profile = it))
    }, label = { Text("TOML profile") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.executable, {
        onChange(form.copy(executable = it))
    }, label = { Text("Executable (not a shell command)") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = form.arguments,
        onValueChange = { onChange(form.copy(arguments = it)) },
        label = { Text("Arguments as JSON array, e.g. [\"--version\"]") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SandboxSessionCard(
    entry: SandboxSessionEntry,
    onAction: (suspend () -> Unit) -> Unit,
    onRemove: suspend () -> Unit,
) {
    val output by entry.session.output.collectAsState()
    var input by remember { mutableStateOf("") }
    Text("${entry.review.argv.first()} - ${if (output.running) "running" else "finished (${output.exitCode})"}")
    Text("Project: ${entry.review.projectDirectory}")
    SelectionContainer {
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(
                "stdout (${output.stdout.discardedCharacters} earlier characters omitted):\n${output.stdout.text}",
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "stderr (${output.stderr.discardedCharacters} earlier characters omitted):\n${output.stderr.text}",
                fontFamily = FontFamily.Monospace,
            )
            output.failure?.let { Text("Session failed: ${it.message}") }
        }
    }
    if (output.running) {
        OutlinedTextField(
            input,
            { input = it },
            label = { Text("Send a line to stdin") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = {
                val captured = input
                onAction {
                    entry.session.sendInput("$captured\n")
                    if (input == captured) input = ""
                }
            }) { Text("Send line") }
            TextButton(onClick = { onAction { entry.session.closeInput() } }) { Text("Close stdin") }
            TextButton(onClick = { onAction { entry.session.stop() } }) { Text("Stop session and descendants") }
        }
    } else {
        TextButton(onClick = { onAction(onRemove) }) { Text("Remove finished session") }
    }
}
