package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest

internal fun readSandboxRequest(
    jarPath: String,
    securityRequired: Boolean,
): PluginSandboxRequest =
    if (securityRequired) {
        PluginSandboxRequestReader.readFromJar(jarPath)
    } else {
        PluginSandboxRequest.EMPTY
    }

internal suspend fun requestSandboxApproval(
    manifest: PluginManifest,
    pluginId: String,
    sandboxRequest: PluginSandboxRequest,
) {
    if (sandboxRequest.isEmpty) return
    when (
        val decision =
            PluginSandboxApprovalRegistry.bus.requestApproval(
                pluginId = pluginId,
                displayName = manifest.displayName,
                capabilities = sandboxRequest.capabilities(),
            )
    ) {
        PluginSandboxApprovalDecision.Approved -> {
            return
        }

        is PluginSandboxApprovalDecision.Denied -> {
            error("Protected plugin capability request denied: ${decision.reason}")
        }

        PluginSandboxApprovalDecision.Timeout -> {
            error("Protected plugin capability request timed out")
        }

        PluginSandboxApprovalDecision.QueueFull -> {
            error("Protected plugin capability approval queue is full")
        }
    }
}
