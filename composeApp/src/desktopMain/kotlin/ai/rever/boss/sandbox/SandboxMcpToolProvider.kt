package ai.rever.boss.sandbox

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider

/** Operator-facing host tools. Agent-scoped endpoints must not expose this global session catalogue. */
internal class SandboxMcpToolProvider(
    service: SandboxSessionService,
    requests: SandboxLaunchRequests,
    canReview: () -> Boolean,
) : McpToolProvider {
    override val providerId = "boss-command-sandbox"
    private val operations = SandboxMcpOperations(service, requests, canReview)

    override fun tools(): List<McpToolDefinition> = launchTools() + sessionTools()

    private fun launchTools(): List<McpToolDefinition> =
        listOf(
            sandboxMcpTool(
                "sandbox_start",
                "Request a root command session. Returns a ticket, not approval; review native permissions in BOSS.",
                SandboxMcpFields(listOf("project", "policy", "profile", "argv", "reason")),
                false,
            ) { operations.start(it) },
            sandboxMcpTool(
                "sandbox_request_permissions",
                "Request extra rights for one command in a new sandbox. Only the human in BOSS can approve.",
                SandboxMcpFields(listOf("session_id", "argv", "filesystem", "network", "reason")),
                false,
            ) { operations.requestPermissions(it) },
            sandboxMcpTool(
                "sandbox_request_status",
                "Poll a launch ticket; STARTED includes session_id. No polling call launches a process.",
                SandboxMcpFields(listOf("request_id")),
                true,
            ) { operations.requestStatus(it) },
            sandboxMcpTool(
                "sandbox_forget_request",
                "Forget a completed ticket to release request capacity; does not remove its session.",
                SandboxMcpFields(listOf("request_id")),
                false,
            ) { operations.forget(it) },
            sandboxMcpTool(
                "sandbox_sessions",
                "List operator-managed sandbox sessions, including GUI launches.",
                SandboxMcpFields(emptyList()),
                true,
            ) { operations.sessions() },
        )

    private fun sessionTools(): List<McpToolDefinition> =
        listOf(
            sandboxMcpTool(
                "sandbox_output",
                "Read bounded stdout/stderr tails, exit status and omitted-character counts.",
                SandboxMcpFields(listOf("session_id")),
                true,
            ) { operations.output(it) },
            sandboxMcpTool(
                "sandbox_input",
                "Write exact UTF-8 text to stdin (at most 16 KiB) and/or close stdin. Does not add a newline.",
                SandboxMcpFields(listOf("session_id"), optional = listOf("text", "close_stdin")),
                false,
            ) { operations.input(it) },
            sandboxMcpTool(
                "sandbox_stop",
                "Stop the root command and all its descendants; wait for native boundary cleanup.",
                SandboxMcpFields(listOf("session_id")),
                false,
            ) { operations.stop(it) },
            sandboxMcpTool(
                "sandbox_remove",
                "Remove retained output for a finished session. Running sessions must be stopped first.",
                SandboxMcpFields(listOf("session_id")),
                false,
            ) { operations.remove(it) },
        )
}
