package ai.rever.boss.sandbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxConsentQueueTest {
    @Test(timeout = 5000)
    fun `once approval is single use and repeats prompt`() =
        runBlocking<Unit> {
            SandboxConsentQueue().use { queue ->
                val review = review("first")
                val answer = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review) }
                val id =
                    queue.requests.value
                        .single()
                        .id
                assertTrue(queue.decide(id, SandboxConsentChoice.ONCE))
                assertFalse(queue.decide(id, SandboxConsentChoice.ONCE))
                val permit = assertNotNull(answer.await())
                assertFailsWith<IllegalArgumentException> { queue.consume(permit, review("changed")) }
                queue.consume(permit, review)
                assertFailsWith<IllegalStateException> { queue.consume(permit, review) }
                val again = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review) }
                assertEquals(1, queue.requests.value.size)
                assertFalse(queue.decide(id, SandboxConsentChoice.ONCE))
                again.cancelAndJoin()
                assertTrue(queue.requests.value.isEmpty())
            }
        }

    @Test(timeout = 5000)
    fun `app lifetime trusts only exact review and never another queue`() =
        runBlocking<Unit> {
            SandboxConsentQueue().use { queue ->
                val review = review("first")
                val answer = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review) }
                queue.decide(
                    queue.requests.value
                        .single()
                        .id,
                    SandboxConsentChoice.UNTIL_APP_CLOSES,
                )
                queue.consume(assertNotNull(answer.await()), review)
                val reused = assertNotNull(queue.request(review))
                assertTrue(queue.requests.value.isEmpty())
                val changed = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("other")) }
                assertEquals(1, queue.requests.value.size)
                changed.cancelAndJoin()
                SandboxConsentQueue().use { nextApp ->
                    assertFailsWith<IllegalStateException> { nextApp.consume(reused, review) }
                    val afterRestart = async(start = CoroutineStart.UNDISPATCHED) { nextApp.request(review) }
                    assertEquals(1, nextApp.requests.value.size)
                    afterRestart.cancelAndJoin()
                }
                queue.revoke()
                assertFailsWith<IllegalStateException> { queue.consume(reused, review) }
            }
        }

    @Test(timeout = 5000)
    fun `revocation beats already answered prompt before authorization returns`() =
        runBlocking<Unit> {
            SandboxConsentQueue().use { queue ->
                val answer = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("first")) }
                queue.decide(
                    queue.requests.value
                        .single()
                        .id,
                    SandboxConsentChoice.UNTIL_APP_CLOSES,
                )
                queue.revoke()
                assertNull(answer.await())
                assertTrue(queue.requests.value.isEmpty())
            }
        }

    @Test(timeout = 5000)
    fun `timeout deny overflow cancellation and shutdown withhold authorization`() =
        runBlocking<Unit> {
            SandboxConsentQueue(timeoutMs = 20, capacity = 1).use { queue ->
                val answer = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("first")) }
                assertFailsWith<IllegalStateException> { queue.request(review("overflow")) }
                assertNull(answer.await())
                assertTrue(queue.requests.value.isEmpty())
                val denied = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("denied")) }
                queue.decide(
                    queue.requests.value
                        .single()
                        .id,
                    SandboxConsentChoice.DENY,
                )
                assertNull(denied.await())
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("cancelled")) }
                queue.decide(
                    queue.requests.value
                        .single()
                        .id,
                    SandboxConsentChoice.UNTIL_APP_CLOSES,
                )
                cancelled.cancelAndJoin()
                val repeated = async(start = CoroutineStart.UNDISPATCHED) { queue.request(review("cancelled")) }
                assertEquals(1, queue.requests.value.size)
                queue.close()
                assertNull(repeated.await())
                assertFailsWith<IllegalStateException> { queue.request(review("closed")) }
            }
        }

    private fun review(digest: String) =
        SandboxPermissionReview(
            approvalDigest = digest,
            projectDirectory = "/project",
            argv = listOf("tool", "argument"),
            permissionsJson = "{}",
            reason = "Read input",
            requiresRestart = true,
        )
}
