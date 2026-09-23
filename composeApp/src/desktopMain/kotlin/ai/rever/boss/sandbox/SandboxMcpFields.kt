package ai.rever.boss.sandbox

internal class SandboxMcpFields(
    val required: List<String>,
    optional: List<String> = emptyList(),
) {
    val all = required + optional
}
