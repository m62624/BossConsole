package ai.rever.boss.components.plugin

/** The host-owned isolation decision made before any plugin class is loaded. */
internal enum class SecurityRequirement {
    OPTIONAL,
    REQUIRED,
}

/** Host-owned marker read before any plugin class is loaded. */
internal expect object SecurityRequiredPlugin {
    /**
     * Reads the marker without loading plugin code.
     *
     * A missing marker is a successful [SecurityRequirement.OPTIONAL] result. Malformed or
     * unreadable metadata is a failure and must not fall through to an ordinary plugin load.
     */
    fun readRequirement(jarPath: String): Result<SecurityRequirement>
}
