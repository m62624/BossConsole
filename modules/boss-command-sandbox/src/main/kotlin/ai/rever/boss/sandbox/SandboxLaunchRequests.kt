package ai.rever.boss.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.util.UUID

/** Detached, bounded launch tickets for transports whose timeout is shorter than human review. */
class SandboxLaunchRequests internal constructor(
    private val start: suspend (SandboxCommand, String) -> SandboxSessionEntry?,
    private val capacity: Int = 8,
    private val escalate: (suspend (String, SandboxEscalation) -> SandboxSessionEntry?)? = null,
) {
    constructor(service: SandboxSessionService) : this(service::start, escalate = service::startEscalated)

    private class Pending(
        var status: SandboxLaunchStatus,
        val job: Deferred<Unit>,
    )

    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private var closed = false

    init {
        require(capacity > 0)
    }

    /** Submission is not approval. The app owns the request even if the transport disconnects. */
    fun submit(
        command: SandboxCommand,
        reason: String,
    ): String {
        val captured = command.copy(argv = command.argv.toList())
        require(reason.isNotBlank() && reason.length <= SandboxSessionService.MAX_REASON_CHARACTERS)
        return enqueue { start(captured, reason) }
    }

    fun submitEscalation(
        parentId: String,
        additional: SandboxEscalation,
    ): String {
        val launch = checkNotNull(escalate) { "Escalation is unavailable" }
        return enqueue { launch(parentId, additional) }
    }

    private fun enqueue(launch: suspend () -> SandboxSessionEntry?): String =
        synchronized(lock) {
            check(!closed) { "Sandbox launch requests are closed" }
            check(pending.size < capacity) { "Forget a completed sandbox launch request before submitting another" }
            val id = UUID.randomUUID().toString()
            val job =
                scope.async(start = CoroutineStart.LAZY) {
                    val entry = launch()
                    synchronized(lock) {
                        pending.getValue(id).status =
                            SandboxLaunchStatus(
                                id,
                                if (entry == null) SandboxLaunchState.DENIED else SandboxLaunchState.STARTED,
                                entry?.id,
                            )
                    }
                }
            pending[id] = Pending(SandboxLaunchStatus(id, SandboxLaunchState.PENDING), job)
            job.invokeOnCompletion { failure -> recordFailure(id, failure) }
            job.start()
            id
        }

    fun status(requestId: String): SandboxLaunchStatus =
        synchronized(lock) {
            requireNotNull(pending[requestId]) { "Unknown sandbox launch request" }.status
        }

    /** Forgetting a ticket never stops or removes the session it started. */
    fun forget(requestId: String) {
        synchronized(lock) {
            val request = requireNotNull(pending[requestId]) { "Unknown sandbox launch request" }
            check(request.job.isCompleted) { "The sandbox launch request is still pending" }
            pending.remove(requestId)
        }
    }

    suspend fun shutdown() {
        synchronized(lock) { closed = true }
        withContext(NonCancellable) { owner.cancelAndJoin() }
    }

    private fun recordFailure(
        id: String,
        failure: Throwable?,
    ) {
        if (failure == null) return
        synchronized(lock) {
            pending[id]?.let { request ->
                // Once a session was published, the service owns it even if cancellation arrives
                // at the last instruction of this coroutine. Do not hide that session's id.
                if (request.status.state == SandboxLaunchState.PENDING) {
                    val state =
                        if (failure is CancellationException) {
                            SandboxLaunchState.CANCELLED
                        } else {
                            SandboxLaunchState.FAILED
                        }
                    request.status = SandboxLaunchStatus(id, state, failure = failure)
                }
            }
        }
    }
}

enum class SandboxLaunchState { PENDING, STARTED, DENIED, FAILED, CANCELLED }

data class SandboxLaunchStatus(
    val id: String,
    val state: SandboxLaunchState,
    val sessionId: String? = null,
    val failure: Throwable? = null,
)
