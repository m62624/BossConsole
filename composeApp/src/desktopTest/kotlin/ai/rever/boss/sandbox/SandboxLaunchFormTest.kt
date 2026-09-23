package ai.rever.boss.sandbox

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxLaunchFormTest {
    @Test
    fun `arguments are exact array entries and never parsed as shell syntax`() {
        val form =
            SandboxLaunchForm(
                "project",
                "policy.toml",
                executable = "tool",
                arguments = "[\"\",\"a b\",\"$(echo x)\"]",
            )
        assertEquals(listOf("tool", "", "a b", "$(echo x)"), form.command().argv)
    }

    @Test
    fun `shell strings and non string array entries are rejected`() {
        val form = SandboxLaunchForm("project", "policy.toml", executable = "tool")
        assertFailsWith<IllegalArgumentException> { form.copy(arguments = "--flag value").command() }
        assertFailsWith<IllegalArgumentException> { form.copy(arguments = "[{}]").command() }
    }
}
