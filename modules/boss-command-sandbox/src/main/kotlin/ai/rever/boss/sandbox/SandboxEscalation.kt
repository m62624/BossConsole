package ai.rever.boss.sandbox

import java.util.Collections

/** Additional capabilities for one explicit command, never a mutation of the requesting agent. */
class SandboxEscalation(
    argv: List<String>,
    filesystem: List<Pair<String, String>>,
    network: List<String>,
    val reason: String,
) {
    val argv: List<String> = Collections.unmodifiableList(argv.toList())
    val filesystem: List<Pair<String, String>> = Collections.unmodifiableList(filesystem.toList())
    val network: List<String> = Collections.unmodifiableList(network.toList())

    init {
        require(reason.isNotBlank() && reason.length <= SandboxSessionService.MAX_REASON_CHARACTERS)
        require(filesystem.size + network.size in 1..64) { "Request between one and 64 additional capabilities" }
        require(
            filesystem.all { (operation, path) ->
                operation.length <= 32 && path.length <= 32768 && '\u0000' !in path
            },
        ) { "Invalid filesystem capability" }
        require(network.all { it.length <= 4096 && '\u0000' !in it }) { "Invalid network capability" }
    }
}
