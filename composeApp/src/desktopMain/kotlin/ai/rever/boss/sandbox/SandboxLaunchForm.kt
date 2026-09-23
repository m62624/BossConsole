package ai.rever.boss.sandbox

import kotlinx.serialization.json.Json
import java.nio.file.Path

/** Arguments are a JSON array, not a shell string; empty arguments and whitespace are preserved. */
internal data class SandboxLaunchForm(
    val project: String,
    val policy: String,
    val profile: String = "base",
    val executable: String = "",
    val arguments: String = "[]",
) {
    fun command(): SandboxCommand =
        SandboxCommand(
            Path.of(project),
            Path.of(policy),
            profile,
            listOf(executable) + Json.decodeFromString<List<String>>(arguments),
        )
}
