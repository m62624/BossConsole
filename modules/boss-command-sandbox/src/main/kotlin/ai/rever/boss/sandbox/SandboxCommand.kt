package ai.rever.boss.sandbox

import java.nio.file.Path

/** One root executable and its arguments, never an implicitly interpreted shell command. */
data class SandboxCommand(
    val projectDirectory: Path,
    val policyFile: Path,
    val profile: String,
    val argv: List<String>,
)
