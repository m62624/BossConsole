package ai.rever.boss.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxSessionServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test(timeout = 10000)
    fun `denial never crosses the native launch boundary`() =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val start = async { service.start(command(), "Run the selected tool") }
                decide(service, SandboxConsentChoice.DENY)
                assertNull(start.await())
                assertEquals(0, backend.launches)
                assertTrue(service.sessions.value.isEmpty())
            } finally {
                service.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `approved session is retained and shutdown awaits boundary cleanup`(): Unit =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val start = async { service.start(command(), "Run the selected tool") }
                decide(service, SandboxConsentChoice.ONCE)
                val entry = requireNotNull(start.await())
                assertEquals(listOf(entry), service.sessions.value)
                assertFailsWith<IllegalStateException> { service.remove(entry.id) }
                service.shutdown()
                service.shutdown()
                assertTrue(backend.closed.get())
                assertFalse(entry.session.output.value.running)
                service.remove(entry.id)
                assertTrue(service.sessions.value.isEmpty())
                assertFailsWith<IllegalStateException> { service.start(command(), "No launch after shutdown") }
            } finally {
                service.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `cancellation during synchronous launch cannot discard the acquired process`() =
        runBlocking {
            val backend = TestBackend(blockLaunch = true)
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val start = async { service.start(command(), "Cancellation test") }
                decide(service, SandboxConsentChoice.ONCE)
                withTimeout(5000) { backend.entered.await() }
                start.cancel()
                backend.release.countDown()
                start.join()
                assertTrue(backend.closed.get())
                assertTrue(service.sessions.value.isEmpty())
            } finally {
                backend.release.countDown()
                service.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `closing the app denies pending consent without launching`() =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            val start = async { service.start(command(), "Pending launch") }
            withTimeout(5000) { service.consent.requests.first { it.isNotEmpty() } }
            service.shutdown()
            assertNull(start.await())
            assertEquals(0, backend.launches)
            assertTrue(
                service.consent.requests.value
                    .isEmpty(),
            )
        }

    @Test(timeout = 10000)
    fun `cancelling a pending review removes it and does not launch`() =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val start = async { service.start(command(), "Pending launch") }
                withTimeout(5000) { service.consent.requests.first { it.isNotEmpty() } }
                start.cancelAndJoin()
                assertTrue(
                    service.consent.requests.value
                        .isEmpty(),
                )
                assertEquals(0, backend.launches)
            } finally {
                service.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `native failure propagates without a fallback or a retained session`() =
        runBlocking {
            val backend = TestBackend(failLaunch = true)
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val start =
                    async {
                        assertFailsWith<IOException> { service.start(command(), "Launch failure test") }
                    }
                decide(service, SandboxConsentChoice.ONCE)
                assertEquals("Native launch failed", start.await().message)
                assertEquals(1, backend.launches)
                assertTrue(service.sessions.value.isEmpty())
            } finally {
                service.shutdown()
            }
        }

    private fun command(): SandboxCommand {
        val project = temporary.newFolder().toPath()
        val policy = project.resolve("cageforge.toml")
        Files.writeString(policy, "[profiles.tool]\n")
        return SandboxCommand(project, policy, "tool", listOf("tool", "arg with spaces"))
    }

    @Test(timeout = 10000)
    fun `additional command denial and cancellation release preparation without launching`() =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val initial = async { service.start(command(), "Agent") }
                decide(service, SandboxConsentChoice.ONCE)
                val parent = requireNotNull(initial.await())
                val request =
                    SandboxEscalation(
                        listOf("other", "exact arg"),
                        listOf("read" to "/input"),
                        emptyList(),
                        "Read",
                    )
                val denied = async { service.startEscalated(parent.id, request) }
                val review =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                assertEquals(request.argv, review.review.argv)
                service.consent.decide(review.id, SandboxConsentChoice.DENY)
                assertNull(denied.await())
                assertEquals(1, backend.launches)
                assertEquals(1, backend.preparationsClosed)
                val cancelled = async { service.startEscalated(parent.id, request) }
                service.consent.requests.first { it.isNotEmpty() }
                cancelled.cancelAndJoin()
                assertEquals(2, backend.preparationsClosed)
                assertEquals(1, backend.launches)
                assertTrue(parent.session.output.value.running)
            } finally {
                service.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `additional command launches only after its own approval and preserves parent`() =
        runBlocking {
            val backend = TestBackend()
            val service = SandboxSessionService(SandboxConsentQueue(), backend)
            try {
                val initial = async { service.start(command(), "Agent") }
                decide(service, SandboxConsentChoice.ONCE)
                val parent = requireNotNull(initial.await())
                val request = SandboxEscalation(listOf("other"), listOf("read" to "/input"), emptyList(), "Read")
                val pending = async { service.startEscalated(parent.id, request) }
                val review =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                assertEquals(1, backend.launches)
                service.consent.decide(review.id, SandboxConsentChoice.ONCE)
                val child = requireNotNull(pending.await())
                assertEquals(request.argv, child.review.argv)
                assertEquals(2, backend.launches)
                assertTrue(parent.session.output.value.running)
                assertEquals(1, backend.preparationsClosed)
            } finally {
                service.shutdown()
            }
        }

    private suspend fun decide(
        service: SandboxSessionService,
        choice: SandboxConsentChoice,
    ) {
        val request =
            withTimeout(5000) {
                service.consent.requests
                    .first { it.isNotEmpty() }
                    .single()
            }
        assertEquals(listOf("tool", "arg with spaces"), request.review.argv)
        service.consent.decide(request.id, choice)
    }

    private class TestBackend(
        private val blockLaunch: Boolean = false,
        private val failLaunch: Boolean = false,
    ) : SandboxSessionBackend {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        var launches = 0
        var preparationsClosed = 0

        override fun prepareEscalation(
            base: SandboxSessionPlan,
            additional: SandboxEscalation,
        ): SandboxEscalationPlan =
            object : SandboxEscalationPlan {
                override val review =
                    SandboxSessionPlan(
                        base.snapshot.forCommand(additional.argv),
                        "expanded-digest",
                        "{\"expanded\":true}",
                    ).review(additional.reason)

                override fun launch() = this@TestBackend.launch(base)

                override fun close() {
                    preparationsClosed++
                }
            }

        override fun prepare(command: SandboxCommand): SandboxSessionPlan =
            SandboxSessionPlan(SandboxPolicySnapshot.read(command), "native-digest", "{}")

        override fun launch(plan: SandboxSessionPlan): ManagedSandboxSession {
            launches++
            entered.complete(Unit)
            if (blockLaunch) check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (failLaunch) throw IOException("Native launch failed")
            val process = WaitingProcess()
            return ManagedSandboxSession(
                process,
                AutoCloseable {
                    process.destroy()
                    closed.set(true)
                },
            )
        }
    }

    private class WaitingProcess : Process() {
        private val done = CountDownLatch(1)

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            done.await()
            return 0
        }

        override fun exitValue(): Int {
            check(done.count == 0L)
            return 0
        }

        override fun destroy() {
            done.countDown()
        }
    }
}
