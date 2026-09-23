package ai.rever.boss.platform

import ai.rever.boss.sandbox.SandboxCommandHost
import ai.rever.boss.sandbox.SandboxConsentDialog
import ai.rever.boss.sandbox.SandboxManagerDialog
import ai.rever.boss.sandbox.SandboxWindowModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
internal actual fun SandboxSessionDialogs(
    windowId: String,
    projectDirectory: String,
    showManager: Boolean,
    onDismiss: () -> Unit,
) {
    val host = SandboxCommandHost
    val service = host.service
    val scope = rememberCoroutineScope()
    val sessions by service.sessions.collectAsState()
    val requests by service.consent.requests.collectAsState()
    val reviewWindow by host.reviewWindow.collectAsState()
    val model = remember(projectDirectory) { SandboxWindowModel(projectDirectory) }
    DisposableEffect(windowId) {
        host.attach(windowId)
        onDispose { host.detach(windowId) }
    }
    val review = requests.firstOrNull()?.takeIf { reviewWindow == windowId }
    if (review != null) {
        SandboxConsentDialog(review) { service.consent.decide(review.id, it) }
    } else if (showManager) {
        SandboxManagerDialog(
            model.form,
            { model.form = it },
            model.busy,
            model.message,
            sessions,
            onStart = { scope.launch { model.start(windowId) } },
            onAction = { action -> scope.launch { model.perform(action) } },
            onRemove = { service.remove(it) },
            onRevoke = { service.consent.revoke() },
            onDismiss = onDismiss,
        )
    }
}
