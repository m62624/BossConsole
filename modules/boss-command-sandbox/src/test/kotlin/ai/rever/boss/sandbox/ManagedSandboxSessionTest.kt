package ai.rever.boss.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedSandboxSessionTest {
    @Test(timeout = 20000)
    fun `both pipes drain beyond retention capacity without blocking the child`() =
        runBlocking {
            val (session, closed) = start("flood")
            try {
                val output = session.awaitCompletion()
                assertNull(output.failure)
                assertEquals(0, output.exitCode)
                assertTrue(closed.get())
                assertEquals(SandboxOutputTail.MAX_CHARACTERS, output.stdout.text.length)
                assertEquals(SandboxOutputTail.MAX_CHARACTERS, output.stderr.text.length)
                assertTrue(output.stdout.text.endsWith("STDOUT_END"))
                assertTrue(output.stderr.text.endsWith("STDERR_END"))
                assertTrue(output.stdout.discardedCharacters > 0)
                assertTrue(output.stderr.discardedCharacters > 0)
            } finally {
                session.stop()
            }
        }

    @Test(timeout = 20000)
    fun `input preserves Unicode and EOF and oversized input is rejected`() =
        runBlocking {
            val (session, _) = start("echo")
            try {
                assertFailsWith<IllegalArgumentException> {
                    session.sendInput("x".repeat(ManagedSandboxSession.MAX_INPUT_BYTES + 1))
                }
                session.sendInput("Привет\nλ\n")
                session.closeInput()
                val output = session.awaitCompletion()
                assertNull(output.failure)
                assertEquals("Привет\nλ\n", output.stdout.text)
            } finally {
                session.stop()
            }
        }

    @Test(timeout = 20000)
    fun `stop waits for boundary cleanup and can be repeated`() =
        runBlocking {
            val (session, closed) = start("wait")
            session.stop()
            session.stop()
            assertTrue(closed.get())
            assertFalse(session.output.value.running)
        }

    private fun start(mode: String): Pair<ManagedSandboxSession, AtomicBoolean> {
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", javaName).toString()
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("boss.sandbox.test.classpath"),
                SessionIoProbe::class.java.name,
                mode,
            ).start()
        val closed = AtomicBoolean()
        val owner =
            AutoCloseable {
                process.destroyForcibly()
                process.waitFor()
                closed.set(true)
            }
        return ManagedSandboxSession(process, owner) to closed
    }
}
