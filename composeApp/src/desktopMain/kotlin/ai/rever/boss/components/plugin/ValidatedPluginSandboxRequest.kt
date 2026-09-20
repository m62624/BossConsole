package ai.rever.boss.components.plugin

import ai.rever.boss.process.CageforgeLocalIpcEndpoint
import java.io.File

internal data class ValidatedPluginSandboxRequest(
    val request: PluginSandboxRequest,
    val requestedReadOnlyRoots: List<File>,
)

internal fun validatePluginSandboxRequest(
    request: PluginSandboxRequest,
    securityRequired: Boolean,
    workspace: File,
    protectedRoots: ProtectedRoots,
    hostEndpoints: List<CageforgeLocalIpcEndpoint>,
): ValidatedPluginSandboxRequest {
    if (!securityRequired) {
        require(request.isEmpty) {
            "Only security-required plugins may declare sandbox capabilities"
        }
        return ValidatedPluginSandboxRequest(PluginSandboxRequest.EMPTY, emptyList())
    }

    validatePluginLocalIpcRequests(request, hostEndpoints)
    require(request.network.isEmpty()) {
        "Protected plugin requested network access outside the host policy ceiling"
    }
    val (filesystem, readOnlyRoots) = validatePluginFilesystemRequests(request, workspace, protectedRoots)
    val localIpc = request.localIpc.map(::parseProtectedLocalIpcEndpoint).map(::manifestValue)
    return ValidatedPluginSandboxRequest(
        request = request.copy(filesystem = filesystem, localIpc = localIpc),
        requestedReadOnlyRoots = readOnlyRoots,
    )
}

private fun validatePluginFilesystemRequests(
    request: PluginSandboxRequest,
    workspace: File,
    protectedRoots: ProtectedRoots,
): Pair<List<PluginSandboxFilesystemRequest>, List<File>> {
    val readOnlyRoots = mutableListOf<File>()
    val filesystem =
        request.filesystem
            .map { capability ->
                val path = resolvePluginRequestedPath(capability.path, workspace)
                val withinWorkspace = path.toPath().startsWith(workspace.toPath())
                val withinRuntime = protectedRoots.readOnlyRoots.any { path.toPath().startsWith(it.toPath()) }
                when (capability.access) {
                    "read" -> {
                        require(path.isFile || path.isDirectory) {
                            "Protected plugin requested a missing filesystem path: ${path.path}"
                        }
                        require(withinWorkspace || withinRuntime) {
                            "Protected plugin requested a filesystem path outside the host policy ceiling: ${path.path}"
                        }
                        readOnlyRoots += path
                    }

                    "write" -> {
                        require(withinWorkspace) {
                            "Protected plugin requested a writable path outside the workspace: ${path.path}"
                        }
                    }

                    else -> {
                        error("Unsupported protected filesystem access: ${capability.access}")
                    }
                }
                PluginSandboxFilesystemRequest(path.path, capability.access)
            }.distinctBy { it.path to it.access }
    return filesystem to readOnlyRoots.distinctBy { it.path }
}

private fun manifestValue(endpoint: CageforgeLocalIpcEndpoint): String =
    when (endpoint) {
        is CageforgeLocalIpcEndpoint.UnixSocket -> "unix://${endpoint.value}"
        is CageforgeLocalIpcEndpoint.WindowsNamedPipe -> "pipe://${endpoint.value}"
    }

internal fun validatePluginLocalIpcRequests(
    request: PluginSandboxRequest,
    hostEndpoints: List<CageforgeLocalIpcEndpoint>,
) {
    val requested = request.localIpc.map(::parseProtectedLocalIpcEndpoint)
    require(hostEndpoints.containsAll(requested)) {
        "Protected plugin requested a local IPC endpoint outside the host policy ceiling"
    }
}

private fun resolvePluginRequestedPath(
    declaration: String,
    workspace: File,
): File {
    val path =
        when {
            declaration == "\${workspace}" -> workspace
            declaration.startsWith("\${workspace}/") -> File(workspace, declaration.removePrefix("\${workspace}/"))
            declaration.startsWith("\${workspace}\\") -> File(workspace, declaration.removePrefix("\${workspace}\\"))
            else -> File(declaration)
        }
    require(path.isAbsolute) {
        "Protected plugin filesystem request must be absolute or use \${workspace}: $declaration"
    }
    return path.canonicalFile
}
