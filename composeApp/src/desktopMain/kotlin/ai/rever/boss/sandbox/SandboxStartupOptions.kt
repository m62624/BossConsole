package ai.rever.boss.sandbox

/** Startup-only opt-in. Never consume an argument belonging to a command or executable. */
internal class SandboxStartupOptions private constructor(
    val enabled: Boolean,
    val arguments: Array<String>,
) {
    companion object {
        fun parse(args: Array<String>): SandboxStartupOptions {
            val enabled = args.firstOrNull() == "--sandbox"
            require(!enabled || args.size == 1) { "Use boss --sandbox alone to enable sandbox sessions at startup" }
            return SandboxStartupOptions(enabled, if (enabled) emptyArray() else args)
        }
    }
}
