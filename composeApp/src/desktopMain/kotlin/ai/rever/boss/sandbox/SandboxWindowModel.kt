package ai.rever.boss.sandbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path

/** Window-local form/error state; process ownership remains with the application service. */
internal class SandboxWindowModel(
    projectDirectory: String,
) {
    var form by mutableStateOf(
        SandboxLaunchForm(projectDirectory, Path.of(projectDirectory, "cageforge.toml").toString()),
    )
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    suspend fun start(windowId: String) {
        if (busy) return
        busy = true
        val captured = form
        try {
            SandboxCommandHost.reviewIn(windowId)
            val result =
                sandboxOperationResult {
                    SandboxCommandHost.service.start(captured.command(), "Run from BOSS GUI")
                }
            message =
                result.fold(
                    {
                        if (it == null) "Denied or expired. No command was launched." else "Sandbox session started."
                    },
                    { it.message ?: it.javaClass.simpleName },
                )
        } finally {
            busy = false
        }
    }

    suspend fun perform(action: suspend () -> Unit) {
        message = sandboxOperationResult(action).exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }
}
