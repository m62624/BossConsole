package ai.rever.boss.process

import java.io.File

/** A host-owned local IPC endpoint that can be lowered into the Cageforge policy. */
sealed interface CageforgeLocalIpcEndpoint {
    val value: String

    data class UnixSocket(
        override val value: String,
    ) : CageforgeLocalIpcEndpoint

    data class WindowsNamedPipe(
        override val value: String,
    ) : CageforgeLocalIpcEndpoint
}

/**
 * Immutable policy input for a protected process launch.
 *
 * The TOML contains policy only. Runtime credentials are delivered through the
 * one-shot local bootstrap channel and are never interpolated into this value.
 */
data class CageforgeProcessPolicy(
    val toml: String,
    val profileName: String? = null,
) {
    init {
        require(toml.isNotBlank()) { "Cageforge TOML must not be blank" }
    }

    companion object {
        /**
         * Safe default for an agent workspace: write access is limited to the
         * validated workspace, protected metadata is read-only, and direct
         * networking is disabled.
         */
        fun workspace(
            workspace: File,
            readOnlyRoots: Iterable<File> = emptyList(),
            localIpcPaths: Iterable<String> = emptyList(),
            localIpcEndpoints: Iterable<CageforgeLocalIpcEndpoint> = emptyList(),
        ): CageforgeProcessPolicy {
            require(workspace.isAbsolute) { "Cageforge workspace must be absolute" }
            val root = workspace.canonicalFile
            require(root.isDirectory) { "Cageforge workspace must be an existing directory" }

            val additionalRoots =
                readOnlyRoots
                    .map {
                        require(it.isAbsolute) { "Cageforge read-only root must be absolute" }
                        it.canonicalFile
                    }.onEach {
                        require(it.exists()) { "Cageforge read-only root does not exist: ${it.path}" }
                    }.filterNot { it == root }
                    .distinctBy { it.path }

            val endpoints =
                localIpcPaths
                    .map(CageforgeLocalIpcEndpoint::UnixSocket)
                    .toList() + localIpcEndpoints.toList()
            val distinctEndpoints = endpoints.distinct()
            require(distinctEndpoints.size == endpoints.size) {
                "Cageforge local IPC endpoints must be unique"
            }
            val unixSocketPaths =
                distinctEndpoints
                    .filterIsInstance<CageforgeLocalIpcEndpoint.UnixSocket>()
                    .map { validatedUnixSocketPath(it.value) }
            val namedPipeNames =
                distinctEndpoints
                    .filterIsInstance<CageforgeLocalIpcEndpoint.WindowsNamedPipe>()
                    .map(::validatedWindowsNamedPipe)
            validateEndpointPlatform(unixSocketPaths, namedPipeNames)
            val ipcParentRoots = unixSocketPaths.map(::localIpcParent).distinctBy { it.path }
            val effectiveReadOnlyRoots = additionalRoots.filterNot { it in ipcParentRoots }
            val filesystemRules = filesystemRules(effectiveReadOnlyRoots, ipcParentRoots)

            return CageforgeProcessPolicy(
                renderWorkspacePolicy(root, filesystemRules, unixSocketPaths, namedPipeNames),
                "boss-protected",
            )
        }

        private fun filesystemRules(
            readOnlyRoots: List<File>,
            ipcParentRoots: List<File>,
        ): List<String> =
            buildList {
                addAll(
                    ipcParentRoots.map { path ->
                        "  { target = \"absolute\", path = ${tomlString(path.path)}, access = \"write\" }"
                    },
                )
                addAll(
                    readOnlyRoots.map { path ->
                        "  { target = \"absolute\", path = ${tomlString(path.path)}, access = \"read\" }"
                    },
                )
            }

        private fun renderWorkspacePolicy(
            root: File,
            filesystemRules: List<String>,
            unixSocketPaths: List<String>,
            namedPipeNames: List<String>,
        ): String {
            val rootText = tomlString(root.path)
            val allFilesystemRules =
                buildList {
                    add("  { target = \"minimal\", access = \"read\" }")
                    addAll(filesystemRules)
                    add("  { target = \"workspace-root\", access = \"write\" }")
                }.joinToString(",\n")
            val networkPolicy = renderNetworkPolicy()
            val localIpcPolicy = renderLocalIpcPolicy(unixSocketPaths, namedPipeNames)
            return buildString {
                appendLine("default_profile = \"boss-protected\"")
                appendLine()
                appendLine("[profiles.boss-protected]")
                appendLine("description = \"BOSS protected child process\"")
                appendLine()
                appendLine("[profiles.boss-protected.workspace_roots]")
                appendLine("$rootText = true")
                appendLine()
                appendLine("[profiles.boss-protected.filesystem]")
                appendLine("mode = \"restricted\"")
                appendLine("glob_scan_max_depth = 8")
                appendLine("additional_protected_paths = [\".git\", \".env\", \".ssh\"]")
                appendLine("rules = [")
                appendLine(allFilesystemRules)
                appendLine("]")
                appendLine()
                appendLine(networkPolicy)
                appendLine()
                appendLine(localIpcPolicy)
                appendLine()
                appendLine("[profiles.boss-protected.command]")
                appendLine("program = \"java\"")
                appendLine("working_directory = \".\"")
                appendLine()
                appendLine("[profiles.boss-protected.command.stdio]")
                appendLine("stdin = \"pipe\"")
                appendLine("stdout = \"pipe\"")
                appendLine("stderr = \"pipe\"")
            }
        }

        private fun renderNetworkPolicy(): String =
            listOf(
                "[profiles.boss-protected.network]",
                "mode = \"disabled\"",
            ).joinToString("\n")

        private fun renderLocalIpcPolicy(
            unixSocketPaths: List<String>,
            namedPipeNames: List<String>,
        ): String {
            if (unixSocketPaths.isEmpty() && namedPipeNames.isEmpty()) return ""
            val platform = currentPlatform()
            return when (platform) {
                Platform.LINUX,
                Platform.MACOS,
                -> {
                    require(namedPipeNames.isEmpty()) {
                        "Windows named-pipe endpoints cannot be used on $platform"
                    }
                    val sockets = unixSocketPaths.joinToString(", ") { tomlString(it) }
                    val platformName = platform.tomlName
                    "[profiles.boss-protected.platforms.$platformName.local_ipc]\n" +
                        "unix_sockets = [$sockets]"
                }

                Platform.WINDOWS -> {
                    require(unixSocketPaths.isEmpty()) {
                        "Unix-socket endpoints cannot be used on Windows"
                    }
                    val pipes = namedPipeNames.joinToString(", ") { tomlString(it) }
                    "[profiles.boss-protected.platforms.windows.local_ipc]\n" +
                        "named_pipes = [$pipes]"
                }
            }
        }

        private fun tomlString(value: String): String {
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            return "\"$escaped\""
        }

        private fun validatedUnixSocketPath(value: String): String {
            require(value.isNotBlank() && '\u0000' !in value) {
                "Cageforge Unix-socket path must not be blank or contain NUL"
            }
            val path = File(value)
            require(path.isAbsolute) { "Cageforge Unix-socket path must be absolute: ${path.path}" }
            return path.canonicalFile.path
        }

        private fun validatedWindowsNamedPipe(endpoint: CageforgeLocalIpcEndpoint.WindowsNamedPipe): String {
            val value = endpoint.value
            require(value.startsWith("\\\\.\\pipe\\")) {
                "Cageforge Windows named pipe must use the \\\\.\\pipe\\ namespace"
            }
            require(value.isNotBlank() && '\u0000' !in value) {
                "Cageforge Windows named pipe must not be blank or contain NUL"
            }
            return value
        }

        private fun validateEndpointPlatform(
            unixSocketPaths: List<String>,
            namedPipeNames: List<String>,
        ) {
            when (currentPlatform()) {
                Platform.LINUX,
                Platform.MACOS,
                -> {
                    require(namedPipeNames.isEmpty()) {
                        "Windows named-pipe endpoints are unsupported on this platform"
                    }
                }

                Platform.WINDOWS -> {
                    require(unixSocketPaths.isEmpty()) {
                        "Unix-socket endpoints are unsupported on Windows"
                    }
                }
            }
        }

        private fun localIpcParent(path: String): File {
            val parent =
                File(path).parentFile?.canonicalFile
                    ?: error("Cageforge local IPC path must have a parent directory: $path")
            require(parent.isDirectory) {
                "Cageforge local IPC parent must be an existing directory: ${parent.path}"
            }
            return parent
        }
    }
}

private enum class Platform(
    val tomlName: String,
) {
    LINUX("linux"),
    MACOS("macos"),
    WINDOWS("windows"),
}

private fun currentPlatform(): Platform {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("windows") -> Platform.WINDOWS
        osName.contains("mac") -> Platform.MACOS
        osName.contains("linux") -> Platform.LINUX
        else -> error("Cageforge protected local IPC is unsupported on $osName")
    }
}

/**
 * Host-owned immutable ceiling for a protected launch.
 *
 * A plugin may request a workspace below [allowedWorkspaceRoot], but it cannot move the policy
 * outside that root or add a network/filesystem capability. The returned policy is constructed
 * by this class rather than by plugin-provided TOML.
 */
class CageforgePolicyCeiling private constructor(
    val allowedWorkspaceRoot: File,
    private val readOnlyRoots: List<File>,
) {
    init {
        require(allowedWorkspaceRoot.isAbsolute) {
            "Cageforge policy ceiling root must be absolute"
        }
        require(allowedWorkspaceRoot.isDirectory) {
            "Cageforge policy ceiling root must be an existing directory"
        }
        require(readOnlyRoots.all { it.isAbsolute && it.exists() }) {
            "Cageforge policy ceiling contains an invalid read-only root"
        }
    }

    /** Materialize the only policy permitted by this host-owned ceiling. */
    fun policyFor(
        workspace: File,
        localIpcPaths: Iterable<String> = emptyList(),
        localIpcEndpoints: Iterable<CageforgeLocalIpcEndpoint> = emptyList(),
    ): CageforgeProcessPolicy {
        require(workspace.isAbsolute) { "Cageforge requested workspace must be absolute" }
        val requestedRoot = workspace.canonicalFile
        require(requestedRoot.isDirectory) {
            "Cageforge requested workspace must be an existing directory"
        }
        require(requestedRoot.toPath().startsWith(allowedWorkspaceRoot.toPath())) {
            "Cageforge requested workspace is outside the host policy ceiling"
        }
        return CageforgeProcessPolicy.workspace(
            requestedRoot,
            readOnlyRoots,
            localIpcPaths,
            localIpcEndpoints,
        )
    }

    companion object {
        fun forWorkspace(
            allowedWorkspaceRoot: File,
            readOnlyRoots: Iterable<File> = emptyList(),
        ): CageforgePolicyCeiling {
            require(allowedWorkspaceRoot.isAbsolute) {
                "Cageforge policy ceiling root must be absolute"
            }
            val canonicalRoot = allowedWorkspaceRoot.canonicalFile
            require(canonicalRoot.isDirectory) {
                "Cageforge policy ceiling root must be an existing directory"
            }
            val canonicalReadOnlyRoots =
                readOnlyRoots
                    .map {
                        require(it.isAbsolute) { "Cageforge read-only root must be absolute" }
                        it.canonicalFile
                    }.toList()
            return CageforgePolicyCeiling(canonicalRoot, canonicalReadOnlyRoots)
        }
    }
}
