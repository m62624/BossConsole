package ai.rever.boss.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native capability consent, independent of tool-wide MCP routing permission.
 * Only the host GUI calls decide/revoke. No approval or trust is written to disk.
 */
class SandboxConsentQueue(
    private val timeoutMs: Long = 120_000,
    private val capacity: Int = 8,
) : AutoCloseable {
    private class Pending(
        val request: SandboxConsentRequest,
        val answer: CompletableDeferred<SandboxConsentChoice> = CompletableDeferred(),
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val trusted = mutableSetOf<String>()
    private val mutableRequests = MutableStateFlow<List<SandboxConsentRequest>>(emptyList())
    val requests: StateFlow<List<SandboxConsentRequest>> = mutableRequests.asStateFlow()
    private var revision = 0L
    private var closed = false

    init {
        require(timeoutMs > 0 && capacity > 0)
    }

    suspend fun request(review: SandboxPermissionReview): SandboxConsentPermit? {
        currentCoroutineContext().ensureActive()
        val item: Pending
        val requestedRevision: Long
        synchronized(lock) {
            check(!closed) { "Sandbox consent is closed" }
            if (review.approvalDigest in trusted) return SandboxConsentPermit(lock, review.approvalDigest, revision)
            check(pending.size < capacity) { "Sandbox approval queue is full" }
            requestedRevision = revision
            item = Pending(SandboxConsentRequest(review))
            pending[item.request.id] = item
            publish()
        }
        return try {
            val choice = withTimeoutOrNull(timeoutMs) { item.answer.await() }
            val approved = choice == SandboxConsentChoice.ONCE || choice == SandboxConsentChoice.UNTIL_APP_CLOSES
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || revision != requestedRevision || !approved) {
                    null
                } else {
                    if (choice == SandboxConsentChoice.UNTIL_APP_CLOSES) {
                        check(trusted.size < MAX_TRUSTED_REVIEWS) { "Revoke session approvals before adding more" }
                        trusted.add(review.approvalDigest)
                    }
                    SandboxConsentPermit(lock, review.approvalDigest, revision)
                }
            }
        } finally {
            synchronized(lock) {
                pending.remove(item.request.id)
                publish()
            }
        }
    }

    /** A stale dialog or a double click cannot decide a different request. */
    fun decide(
        requestId: String,
        choice: SandboxConsentChoice,
    ): Boolean =
        synchronized(lock) {
            val accepted = pending[requestId]?.answer?.complete(choice) ?: false
            publish()
            accepted
        }

    fun consume(
        permit: SandboxConsentPermit,
        review: SandboxPermissionReview,
    ) {
        synchronized(lock) {
            check(!closed && permit.issuer === lock && permit.revision == revision) {
                "Sandbox approval has been revoked"
            }
            require(permit.digest == review.approvalDigest) { "Sandbox approval does not match this request" }
            check(permit.consumed.compareAndSet(false, true)) { "Sandbox approval has already been used" }
        }
    }

    fun revoke() {
        synchronized(lock) { invalidate() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            invalidate()
        }
    }

    private fun invalidate() {
        revision++
        trusted.clear()
        pending.values.forEach { it.answer.complete(SandboxConsentChoice.DENY) }
        pending.clear()
        publish()
    }

    private fun publish() {
        mutableRequests.value = pending.values.filterNot { it.answer.isCompleted }.map { it.request }
    }

    companion object {
        private const val MAX_TRUSTED_REVIEWS = 256
    }
}
