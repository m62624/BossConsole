package ai.rever.boss.sandbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxEscalationArgumentsTest {
    @Test
    fun `capabilities and separate arguments are parsed without a shell`() {
        val args =
            Json
                .parseToJsonElement(
                    """{"argv":["node","a b",""],"filesystem":[{"operation":"read","path":"/input"}],
                "network":["example.com:443"],"reason":"Read input"}""",
                ).jsonObject
        val request = sandboxEscalation(args)
        assertEquals(listOf("node", "a b", ""), request.argv)
        assertEquals(listOf("read" to "/input"), request.filesystem)
        assertEquals(listOf("example.com:443"), request.network)
    }

    @Test
    fun `malformed filesystem requests cannot carry approval`() {
        val args =
            Json
                .parseToJsonElement(
                    """{"argv":["node"],"filesystem":[{"operation":"read","path":"/input","approve":true}],
                "network":[],"reason":"Read input"}""",
                ).jsonObject
        assertFailsWith<IllegalArgumentException> { sandboxEscalation(args) }
    }
}
