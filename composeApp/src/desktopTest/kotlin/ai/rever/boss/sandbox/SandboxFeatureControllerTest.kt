package ai.rever.boss.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class SandboxFeatureControllerTest {
    @Test
    fun `default is off and only explicit enable registers tools with a fresh nonpersistent subsystem`() =
        runBlocking {
            var registered = 0
            var unregistered = 0
            val feature = SandboxFeatureController({ registered++ }, { unregistered++ })
            assertNull(feature.active.value)
            assertEquals(0, registered)
            feature.enable()
            feature.enable()
            val first = requireNotNull(feature.active.value)
            assertEquals(1, registered)
            feature.disable()
            assertNull(feature.active.value)
            assertEquals(1, unregistered)
            val command = SandboxCommand(Path.of("project"), Path.of("policy"), "base", listOf("tool"))
            assertFailsWith<IllegalStateException> { first.service.start(command, "Stale UI") }
            assertFailsWith<IllegalStateException> { first.requests.submit(command, "Stale MCP tool") }
            feature.enable()
            assertNotSame(first, feature.active.value)
            feature.shutdown()
            assertEquals(2, unregistered)
            assertFailsWith<IllegalStateException> { feature.enable() }
            val nextRun = SandboxFeatureController({ error("Not enabled") }, {})
            assertNull(nextRun.active.value)
            nextRun.shutdown()
        }
}
