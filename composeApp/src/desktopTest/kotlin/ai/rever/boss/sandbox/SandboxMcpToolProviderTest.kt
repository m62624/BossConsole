package ai.rever.boss.sandbox

import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxMcpToolProviderTest {
    @Test
    fun `every mutating tool declares mutation and no tool grants approval`() =
        runBlocking {
            val subsystem = SandboxSubsystem()
            try {
                val tools = SandboxMcpToolProvider(subsystem.service, subsystem.requests) { true }.tools()
                assertEquals(
                    setOf("sandbox_output", "sandbox_sessions", "sandbox_request_status"),
                    tools.filter { it.readOnly }.map { it.name }.toSet(),
                )
                assertEquals(9, tools.size)
                assertTrue(tools.none { it.name.contains("approve") || it.name.contains("enable") })
                val list = tools.single { it.name == "sandbox_sessions" }.handler.call(McpToolArgs(emptyMap(), "{}"))
                assertEquals("[]", list.text)
                assertFalse(list.isError)
            } finally {
                subsystem.shutdown()
            }
        }

    @Test
    fun `unknown approval fields and shell strings fail without native preparation`() =
        runBlocking {
            val subsystem = SandboxSubsystem()
            try {
                val tools = SandboxMcpToolProvider(subsystem.service, subsystem.requests) { true }.tools()
                val start = tools.single { it.name == "sandbox_start" }.handler
                val invalid =
                    listOf(
                        """{"project":"/x","policy":"/x/p.toml","profile":"base","argv":"sh -c x","reason":"test"}""",
                        """
                        {"project":"/x","policy":"/x/p.toml","profile":"base","argv":["tool"],"reason":"test","approve":true}
                        """.trimIndent(),
                    )
                invalid.forEach { assertTrue(start.call(McpToolArgs(emptyMap(), it)).isError) }
                assertTrue(
                    subsystem.service.consent.requests.value
                        .isEmpty(),
                )
                assertTrue(
                    subsystem.service.sessions.value
                        .isEmpty(),
                )
            } finally {
                subsystem.shutdown()
            }
        }
}
