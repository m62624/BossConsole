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
            runtimeExecutableRoots: Iterable<File> = emptyList(),
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

            val executableRoots =
                runtimeExecutableRoots
                    .map {
                        require(it.isAbsolute) {
                            "Cageforge runtime executable root must be absolute: ${it.path}"
                        }
                        it.canonicalFile
                    }.onEach {
                        require(it.isDirectory) {
                            "Cageforge runtime executable root does not exist: ${it.path}"
                        }
                    }.distinctBy { it.path }

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
            val effectiveReadOnlyRoots = effectiveReadOnlyRoots(additionalRoots, ipcParentRoots)

            return CageforgeProcessPolicy(
                CageforgeTomlBuilder.build(
                    root = root,
                    readOnlyRoots = effectiveReadOnlyRoots,
                    ipcParentRoots = ipcParentRoots,
                    platformPolicy =
                        CageforgeTomlPlatformPolicy(
                            unixSocketPaths = unixSocketPaths,
                            namedPipeNames = namedPipeNames,
                            runtimeExecutableRoots = executableRoots,
                        ),
                ),
                CAGEFORGE_PROFILE_NAME,
            )
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
            when (CageforgePlatform.current()) {
                CageforgePlatform.LINUX,
                CageforgePlatform.MACOS,
                -> {
                    require(namedPipeNames.isEmpty()) {
                        "Windows named-pipe endpoints are unsupported on this platform"
                    }
                }

                CageforgePlatform.WINDOWS -> {
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

private fun macOsRuntimeReadOnlyRoots(): List<File> {
    if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) return emptyList()
    return buildList {
        File("/System/Cryptexes/OS").canonicalFile.takeIf { it.isDirectory }?.let(::add)
        val hostHome = System.getenv("HOME") ?: System.getProperty("user.home")
        File(hostHome, ".CFUserTextEncoding")
            .canonicalFile
            .takeIf { it.isFile }
            ?.let(::add)
    }
}

private fun effectiveReadOnlyRoots(
    additionalRoots: List<File>,
    ipcParentRoots: List<File>,
): List<File> =
    (additionalRoots + macOsRuntimeReadOnlyRoots())
        .filterNot { it in ipcParentRoots }
        .distinctBy { it.path }

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
    private val runtimeExecutableRoots: List<File>,
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
        require(runtimeExecutableRoots.all { it.isAbsolute && it.isDirectory }) {
            "Cageforge policy ceiling contains an invalid runtime executable root"
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
            runtimeExecutableRoots = this.runtimeExecutableRoots,
        )
    }

    companion object {
        fun forWorkspace(
            allowedWorkspaceRoot: File,
            readOnlyRoots: Iterable<File> = emptyList(),
            runtimeExecutableRoots: Iterable<File> = emptyList(),
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
            val canonicalExecutableRoots =
                runtimeExecutableRoots
                    .map {
                        require(it.isAbsolute) {
                            "Cageforge runtime executable root must be absolute: ${it.path}"
                        }
                        it.canonicalFile
                    }.onEach {
                        require(it.isDirectory) {
                            "Cageforge runtime executable root does not exist: ${it.path}"
                        }
                    }.distinctBy { it.path }
            return CageforgePolicyCeiling(
                canonicalRoot,
                canonicalReadOnlyRoots,
                canonicalExecutableRoots,
            )
        }
    }
}
