package ai.rever.boss.sandbox

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/** Captures policy bytes and command together before the permission request is shown. */
internal class SandboxPolicySnapshot private constructor(
    val projectDirectory: Path,
    val policyFile: Path,
    val profile: String,
    val argv: List<String>,
    val toml: String,
) {
    val digest: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$projectDirectory\u0000$policyFile\u0000$toml".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val LAUNCH_PROFILE = "boss-command-session"
        internal const val MAX_POLICY_BYTES = 1024 * 1024
        private const val MAX_ARGUMENT_BYTES = 128 * 1024

        fun read(command: SandboxCommand): SandboxPolicySnapshot {
            require(command.projectDirectory.isAbsolute) { "Project directory must be absolute" }
            require(command.policyFile.isAbsolute) { "Policy file must be absolute" }
            val project = command.projectDirectory.toRealPath()
            require(Files.isDirectory(project)) { "Project directory does not exist" }
            val policy = command.policyFile.toRealPath()
            require(Files.isRegularFile(policy)) { "Policy must be a regular file" }
            require(command.profile.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "Invalid profile name" }
            require(command.profile != LAUNCH_PROFILE) { "$LAUNCH_PROFILE is reserved for the host" }
            val argv = command.argv.toList()
            require(argv.isNotEmpty() && argv.first().isNotBlank()) { "An executable is required" }
            require(argv.none { '\u0000' in it }) { "Command arguments must not contain NUL" }
            require(argv.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1 } <= MAX_ARGUMENT_BYTES) {
                "Command arguments exceed 128 KiB"
            }
            val bytes = Files.newInputStream(policy).use { it.readNBytes(MAX_POLICY_BYTES + 1) }
            require(bytes.size <= MAX_POLICY_BYTES) { "Policy exceeds 1 MiB" }
            val source =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            require(source.isNotBlank()) { "Policy must not be empty" }
            return SandboxPolicySnapshot(
                project,
                policy,
                command.profile,
                Collections.unmodifiableList(argv),
                compose(source, command.profile, argv, project),
            )
        }

        // Cageforge owns inheritance, canonical rule replacement and OS overlays. The final
        // child only binds this session's command and pipes; it never reimplements policy merge.
        private fun compose(
            source: String,
            profile: String,
            argv: List<String>,
            project: Path,
        ): String =
            buildString {
                appendLine(source)
                appendLine()
                appendLine("[profiles.$LAUNCH_PROFILE]")
                appendLine("inherits = [${tomlString(profile)}]")
                appendLine("[profiles.$LAUNCH_PROFILE.approval]")
                appendLine("mode = \"preflight\"")
                appendLine("persistence = \"session\"")
                appendLine("[profiles.$LAUNCH_PROFILE.command]")
                appendLine("program = ${tomlString(argv.first())}")
                appendLine("args = [${argv.drop(1).joinToString(", ", transform = ::tomlString)}]")
                appendLine("working_directory = ${tomlString(project.toString())}")
                appendLine("[profiles.$LAUNCH_PROFILE.command.stdio]")
                appendLine("stdin = \"pipe\"")
                appendLine("stdout = \"pipe\"")
                appendLine("stderr = \"pipe\"")
            }
    }
}

internal fun tomlString(value: String): String =
    buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f', '\u007f' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }
