package ai.rever.boss.components.plugin

import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessType
import io.grpc.ManagedChannelBuilder
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginProcessSessionTest {
    @Test
    fun `registry rejects replacement while the current session is active`() {
        val registry = PluginSessionRegistry()
        val first = registry.newSession("plugin", config("first"), managedProcess("first"))
        registry.register(first)

        assertFailsWith<IllegalStateException> {
            registry.requireAvailable("plugin")
        }
    }

    @Test
    fun `registry reserves a plugin while native startup is in progress`() {
        val registry = PluginSessionRegistry()

        registry.requireAvailable("plugin")
        try {
            assertFailsWith<IllegalStateException> {
                registry.requireAvailable("plugin")
            }
        } finally {
            registry.releaseReservation("plugin")
        }

        registry.requireAvailable("plugin")
        registry.releaseReservation("plugin")
    }

    @Test
    fun `late cleanup from an older generation cannot remove a replacement`() {
        val registry = PluginSessionRegistry()
        val firstProcess = TestProcess()
        val first = registry.newSession("plugin", config("first"), managedProcess("first", firstProcess))
        registry.register(first)

        first.beginTermination()
        firstProcess.alive = false
        first.markTerminated()
        first.markCleaned()
        assertTrue(registry.removeIfCurrent(first))

        val second = registry.newSession("plugin", config("second"), managedProcess("second"))
        registry.register(second)

        assertEquals(first.generation + 1, second.generation)
        assertFalse(registry.removeIfCurrent(first))
        assertSame(second, registry.current("plugin"))
    }

    @Test
    fun `session cannot be marked stopped while its native process is alive`() {
        val process = TestProcess()
        val session = PluginSessionRegistry().newSession("plugin", config("plugin"), managedProcess("plugin", process))

        session.markNativeSpawned()
        session.beginTermination()

        assertFailsWith<IllegalStateException> {
            session.markTerminated()
        }
        assertEquals(PluginProcessSessionState.STOPPING, session.state)
    }

    @Test
    fun `startup resources are owned before authenticated readiness`() {
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", 1).usePlaintext().build()
        val bridge = PluginStateBridge("plugin", "instance", channel)
        val session = PluginSessionRegistry().newSession("plugin", config("plugin"), managedProcess("plugin"))

        try {
            session.markNativeSpawned()
            session.attachResources(channel, bridge)

            val resources = session.beginTermination()

            assertSame(channel, resources?.channel)
            assertSame(bridge, resources?.bridge)
        } finally {
            bridge.dispose()
            channel.shutdownNow()
        }
    }

    private fun config(id: String) =
        ProcessConfig(
            processId = id,
            processType = ProcessType.PLUGIN,
            displayName = id,
            mainClass = "Main",
        )

    private fun managedProcess(
        id: String,
        process: TestProcess = TestProcess(),
    ) = ManagedProcess(
        config = config(id),
        process = process,
        ipcAddress = "test://$id",
    )

    private class TestProcess : Process() {
        var alive = true

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun destroy() {
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }
}
