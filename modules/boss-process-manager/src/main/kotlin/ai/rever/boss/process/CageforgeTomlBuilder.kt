package ai.rever.boss.process

import java.io.File

internal const val CAGEFORGE_PROFILE_NAME = "boss-protected"

internal data class CageforgeLocalIpcPolicy(
    val parentRoots: List<File>,
    val unixSocketPaths: List<String>,
    val namedPipeNames: List<String>,
)

/** Builds the single BOSS-owned Cageforge profile used by protected launches. */
internal object CageforgeTomlBuilder {
    fun build(
        root: File,
        readOnlyRoots: List<File>,
        localIpc: CageforgeLocalIpcPolicy,
        runtimeExecutableRoots: List<File> = emptyList(),
    ): String {
        val filesystemRules = filesystemRules(readOnlyRoots, localIpc.parentRoots)
        val allFilesystemRules =
            buildList {
                add("  { target = \"minimal\", access = \"read\" }")
                addAll(filesystemRules)
                add("  { target = \"workspace-root\", access = \"write\" }")
            }.joinToString(",\n")
        val localIpcToml = renderLocalIpcPolicy(localIpc.unixSocketPaths, localIpc.namedPipeNames)
        return buildString {
            appendLine("default_profile = \"$CAGEFORGE_PROFILE_NAME\"")
            appendLine()
            appendLine("[profiles.$CAGEFORGE_PROFILE_NAME]")
            appendLine("description = \"BOSS protected child process\"")
            appendLine()
            appendLine("[profiles.$CAGEFORGE_PROFILE_NAME.workspace_roots]")
            appendLine("${tomlString(root.path)} = true")
            appendLine()
            appendLine("[profiles.$CAGEFORGE_PROFILE_NAME.filesystem]")
            appendLine("mode = \"restricted\"")
            appendLine("glob_scan_max_depth = 8")
            appendLine("additional_protected_paths = [\".git\", \".env\", \".ssh\"]")
            appendLine("rules = [")
            appendLine(allFilesystemRules)
            appendLine("]")
            appendLine()
            appendLine(networkPolicy())
            appendLine()
            appendLine(localIpcToml)
            appendLine()
            appendLine(runtimePolicy(runtimeExecutableRoots))
            appendLine()
            appendLine("[profiles.$CAGEFORGE_PROFILE_NAME.command]")
            appendLine("program = \"java\"")
            appendLine("working_directory = \".\"")
            appendLine()
            appendLine("[profiles.$CAGEFORGE_PROFILE_NAME.command.stdio]")
            appendLine("stdin = \"pipe\"")
            appendLine("stdout = \"pipe\"")
            appendLine("stderr = \"pipe\"")
        }
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

    private fun networkPolicy(): String =
        listOf(
            "[profiles.$CAGEFORGE_PROFILE_NAME.network]",
            "mode = \"disabled\"",
        ).joinToString("\n")

    private fun renderLocalIpcPolicy(
        unixSocketPaths: List<String>,
        namedPipeNames: List<String>,
    ): String {
        if (unixSocketPaths.isEmpty() && namedPipeNames.isEmpty()) return ""
        return when (val platform = CageforgePlatform.current()) {
            CageforgePlatform.LINUX,
            CageforgePlatform.MACOS,
            -> {
                require(namedPipeNames.isEmpty()) {
                    "Windows named-pipe endpoints cannot be used on $platform"
                }
                val sockets = unixSocketPaths.joinToString(", ") { tomlString(it) }
                "[profiles.$CAGEFORGE_PROFILE_NAME.platforms.${platform.tomlName}.local_ipc]\n" +
                    "unix_sockets = [$sockets]"
            }

            CageforgePlatform.WINDOWS -> {
                require(unixSocketPaths.isEmpty()) {
                    "Unix-socket endpoints cannot be used on Windows"
                }
                val pipes = namedPipeNames.joinToString(", ") { tomlString(it) }
                "[profiles.$CAGEFORGE_PROFILE_NAME.platforms.windows.local_ipc]\n" +
                    "named_pipes = [$pipes]"
            }
        }
    }

    private fun runtimePolicy(runtimeExecutableRoots: List<File>): String {
        if (runtimeExecutableRoots.isEmpty()) return ""
        return when (val platform = CageforgePlatform.current()) {
            CageforgePlatform.MACOS -> {
                "[profiles.$CAGEFORGE_PROFILE_NAME.platforms.${platform.tomlName}.runtime]\n" +
                    "executable_roots = [" +
                    runtimeExecutableRoots.joinToString(", ") { tomlString(it.path) } +
                    "]"
            }

            CageforgePlatform.LINUX,
            CageforgePlatform.WINDOWS,
            -> {
                ""
            }
        }
    }

    private fun tomlString(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}

internal enum class CageforgePlatform(
    val tomlName: String,
) {
    LINUX("linux"),
    MACOS("macos"),
    WINDOWS("windows"),
    ;

    companion object {
        fun current(): CageforgePlatform {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("windows") -> WINDOWS
                osName.contains("mac") -> MACOS
                osName.contains("linux") -> LINUX
                else -> error("Cageforge protected local IPC is unsupported on $osName")
            }
        }
    }
}
