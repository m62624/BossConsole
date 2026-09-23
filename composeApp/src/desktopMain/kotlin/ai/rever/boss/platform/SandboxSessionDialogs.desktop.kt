package ai.rever.boss.platform

import ai.rever.boss.sandbox.SandboxCommandHost
import ai.rever.boss.sandbox.SandboxConsentDialog
import ai.rever.boss.sandbox.SandboxDisabledDialog
import ai.rever.boss.sandbox.SandboxManagerDialog
import ai.rever.boss.sandbox.SandboxSubsystem
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
    val scope = rememberCoroutineScope()
    val subsystem by host.feature.active.collectAsState()
    val stopping by host.feature.stopping.collectAsState()
    val model = remember(projectDirectory, subsystem) { SandboxWindowModel(projectDirectory) }
    DisposableEffect(windowId) {
        host.attach(windowId)
        onDispose { host.detach(windowId) }
    }
    val active = subsystem
    if (active != null) {
        ActiveSandboxDialogs(active, model, windowId, showManager, onDismiss)
    } else if (showManager) {
        SandboxDisabledDialog(
            stopping,
            model.message,
            onEnable = { scope.launch { model.perform { host.feature.enable() } } },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ActiveSandboxDialogs(
    subsystem: SandboxSubsystem,
    model: SandboxWindowModel,
    windowId: String,
    showManager: Boolean,
    onDismiss: () -> Unit,
) {
    val host = SandboxCommandHost
    val service = subsystem.service
    val scope = rememberCoroutineScope()
    val sessions by service.sessions.collectAsState()
    val requests by service.consent.requests.collectAsState()
    val reviewWindow by host.reviewWindow.collectAsState()
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
            onStart = { scope.launch { model.start(windowId, service) } },
            onAction = { action -> scope.launch { model.perform(action) } },
            onRemove = { service.remove(it) },
            onRevoke = { service.consent.revoke() },
            onDismiss = onDismiss,
            onDisable = { scope.launch { model.perform { host.feature.disable() } } },
        )
    }
}
