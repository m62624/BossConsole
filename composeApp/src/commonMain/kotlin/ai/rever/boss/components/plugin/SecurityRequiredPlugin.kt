package ai.rever.boss.components.plugin

/** Host-owned marker read before any plugin class is loaded. */
internal expect object SecurityRequiredPlugin {
    /** Returns true only for an explicit security-required marker in a plugin artifact. */
    fun isMarked(jarPath: String): Boolean
}
