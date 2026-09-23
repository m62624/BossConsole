package ai.rever.boss.sandbox

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxStartupOptionsTest {
    @Test
    fun defaultIsOffAndExplicitStartupIsEnabled() {
        assertFalse(SandboxStartupOptions.parse(emptyArray()).enabled)
        val enabled = SandboxStartupOptions.parse(arrayOf("--sandbox"))
        assertTrue(enabled.enabled)
        assertTrue(enabled.arguments.isEmpty())
    }

    @Test
    fun commandArgumentsAreNotConsumed() {
        val args = arrayOf("mcp", "invoke", "example", "--sandbox")
        val options = SandboxStartupOptions.parse(args)
        assertFalse(options.enabled)
        assertContentEquals(args, options.arguments)
    }

    @Test
    fun startupFlagCannotSilentlyEnableHeadlessCommands() {
        assertFailsWith<IllegalArgumentException> {
            SandboxStartupOptions.parse(arrayOf("--sandbox", "mcp", "list"))
        }
    }
}
