package ai.rever.boss.process

import java.io.File

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

            fun tomlString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

            val rootText = tomlString(root.path)
            val runtimeRules =
                additionalRoots.joinToString(",\n") { path ->
                    "  { target = \"absolute\", path = ${tomlString(path.path)}, access = \"read\" }"
                }
            val runtimeRulesText = if (runtimeRules.isEmpty()) "" else "$runtimeRules,\n"
            val toml =
                """
                default_profile = "boss-protected"

                [profiles.boss-protected]
                description = "BOSS protected child process"

                [profiles.boss-protected.workspace_roots]
                $rootText = true

                [profiles.boss-protected.filesystem]
                mode = "restricted"
                glob_scan_max_depth = 8
                additional_protected_paths = [".git", ".env", ".ssh"]
                rules = [
                  { target = "minimal", access = "read" },
                $runtimeRulesText  { target = "workspace-root", access = "write" },
                ]

                [profiles.boss-protected.network]
                mode = "disabled"

                [profiles.boss-protected.command]
                program = "java"
                working_directory = "."

                [profiles.boss-protected.command.stdio]
                stdin = "pipe"
                stdout = "pipe"
                stderr = "pipe"
                """.trimIndent() + "\n"

            return CageforgeProcessPolicy(toml, "boss-protected")
        }
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
    fun policyFor(workspace: File): CageforgeProcessPolicy {
        require(workspace.isAbsolute) { "Cageforge requested workspace must be absolute" }
        val requestedRoot = workspace.canonicalFile
        require(requestedRoot.isDirectory) {
            "Cageforge requested workspace must be an existing directory"
        }
        require(requestedRoot.toPath().startsWith(allowedWorkspaceRoot.toPath())) {
            "Cageforge requested workspace is outside the host policy ceiling"
        }
        return CageforgeProcessPolicy.workspace(requestedRoot, readOnlyRoots)
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
