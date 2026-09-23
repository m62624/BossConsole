package ai.rever.boss.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SandboxLaunchRequestsTest {
    private val command = SandboxCommand(Path.of("project"), Path.of("policy"), "base", listOf("tool"))

    @Test(timeout = 10000)
    fun `submission returns before approval and completed tickets require explicit forgetting`(): Unit =
        runBlocking {
            val answer = CompletableDeferred<Unit>()
            val requests =
                SandboxLaunchRequests({ _, _ ->
                    answer.await()
                    null
                }, capacity = 1)
            try {
                val id = requests.submit(command, "Test")
                assertEquals(SandboxLaunchState.PENDING, requests.status(id).state)
                assertFailsWith<IllegalStateException> { requests.forget(id) }
                assertFailsWith<IllegalStateException> { requests.submit(command, "Over capacity") }
                answer.complete(Unit)
                awaitState(requests, id, SandboxLaunchState.DENIED)
                // Completion publication can precede the coroutine's final instruction.
                withTimeout(5000) {
                    while (sandboxOperationResult { requests.forget(id) }.isFailure) delay(1)
                }
                assertFailsWith<IllegalArgumentException> { requests.status(id) }
            } finally {
                requests.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `asynchronous native failure remains observable with its original cause`() =
        runBlocking {
            val failure = IOException("Native failure")
            val requests = SandboxLaunchRequests({ _, _ -> throw failure })
            try {
                val id = requests.submit(command, "Test")
                awaitState(requests, id, SandboxLaunchState.FAILED)
                assertSame(failure, requests.status(id).failure)
            } finally {
                requests.shutdown()
            }
        }

    @Test(timeout = 10000)
    fun `shutdown cancels pending reviews and rejects new requests`(): Unit =
        runBlocking {
            val requests =
                SandboxLaunchRequests({ _, _ ->
                    CompletableDeferred<Unit>().await()
                    null
                })
            val id = requests.submit(command, "Test")
            requests.shutdown()
            assertEquals(SandboxLaunchState.CANCELLED, requests.status(id).state)
            assertFailsWith<IllegalStateException> { requests.submit(command, "After shutdown") }
        }

    private suspend fun awaitState(
        requests: SandboxLaunchRequests,
        id: String,
        state: SandboxLaunchState,
    ) {
        withTimeout(5000) { while (requests.status(id).state != state) delay(1) }
    }
}
