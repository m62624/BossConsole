package ai.rever.boss.sandbox

/** A bounded tail, with an explicit count of characters no longer retained. */
data class SandboxOutputTail(
    val text: String = "",
    val discardedCharacters: Long = 0,
) {
    internal fun append(chunk: String): SandboxOutputTail {
        val combined = text + chunk
        val discarded = (combined.length - MAX_CHARACTERS).coerceAtLeast(0)
        return SandboxOutputTail(combined.drop(discarded), discardedCharacters + discarded)
    }

    companion object {
        const val MAX_CHARACTERS = 65_536
    }
}

data class SandboxSessionOutput(
    val stdout: SandboxOutputTail = SandboxOutputTail(),
    val stderr: SandboxOutputTail = SandboxOutputTail(),
    val running: Boolean = true,
    val exitCode: Int? = null,
    val failure: Throwable? = null,
)
