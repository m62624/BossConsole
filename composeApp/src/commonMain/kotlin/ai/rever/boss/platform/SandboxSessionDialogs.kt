package ai.rever.boss.platform

import androidx.compose.runtime.Composable

/** Desktop-owned native sessions; ordinary terminal launch remains unchanged. */
@Composable
internal expect fun SandboxSessionDialogs(
    windowId: String,
    projectDirectory: String,
    showManager: Boolean,
    onDismiss: () -> Unit,
)
