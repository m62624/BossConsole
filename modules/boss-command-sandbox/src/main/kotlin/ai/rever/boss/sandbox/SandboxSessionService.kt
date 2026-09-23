package ai.rever.boss.sandbox

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned sessions. GUI and CLI requests must pass through the same consent boundary. */
class SandboxSessionService internal constructor(
    val consent: SandboxConsentQueue,
    private val backend: SandboxSessionBackend,
) {
    constructor() : this(SandboxConsentQueue(), NativeSandboxSessionBackend())

    // Serialize acquisition with shutdown: no native launch can escape application ownership.
    private val lifecycle = Mutex()
    private val closed = AtomicBoolean()
    private val mutableSessions = MutableStateFlow<List<SandboxSessionEntry>>(emptyList())
    val sessions: StateFlow<List<SandboxSessionEntry>> = mutableSessions.asStateFlow()

    /** Returns null for denial/timeout. Preparing and showing the review never starts a process. */
    suspend fun start(
        command: SandboxCommand,
        reason: String,
    ): SandboxSessionEntry? {
        require(reason.isNotBlank() && reason.length <= MAX_REASON_CHARACTERS) {
            "Provide a short reason for this command"
        }
        val captured = command.copy(argv = command.argv.toList())
        return lifecycle.withLock {
            check(!closed.get()) { "Sandbox sessions are closed" }
            check(sessions.value.size < MAX_SESSIONS) { "Remove a finished sandbox session before starting another" }
            val plan = withContext(Dispatchers.IO) { backend.prepare(captured) }
            val review = plan.review(reason)
            consent.request(review)?.let { permit ->
                consent.consume(permit, review)
                acquire(plan, review) { backend.launch(plan) }
            }
        }
    }

    /** Run a new command with reviewed additional rights, leaving the original agent unchanged. */
    suspend fun startEscalated(
        parentId: String,
        additional: SandboxEscalation,
    ): SandboxSessionEntry? =
        lifecycle.withLock {
            check(!closed.get()) { "Sandbox sessions are closed" }
            val parent = requireNotNull(sessions.value.singleOrNull { it.id == parentId }) { "Unknown parent session" }
            check(parent.session.output.value.running) { "The requesting session has stopped" }
            check(sessions.value.size < MAX_SESSIONS) { "Remove a finished sandbox session before starting another" }
            val prepared =
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { backend.prepareEscalation(parent.plan, additional) }
                }
            prepared.use {
                currentCoroutineContext().ensureActive()
                consent.request(it.review)?.let { permit ->
                    consent.consume(permit, it.review)
                    check(parent.session.output.value.running) { "The requesting session has stopped" }
                    acquire(parent.plan, it.review, it::launch)
                }
            }
        }

    /** Finished output stays available until explicitly removed; running sessions cannot be forgotten. */
    suspend fun remove(sessionId: String) {
        lifecycle.withLock {
            val entry = requireNotNull(sessions.value.singleOrNull { it.id == sessionId }) { "Unknown sandbox session" }
            check(!entry.session.output.value.running) { "Stop the sandbox session before removing it" }
            mutableSessions.value = sessions.value.filterNot { it.id == sessionId }
        }
    }

    /** Revokes pending/remembered consent immediately, then waits for every owned native boundary. */
    suspend fun shutdown() {
        closed.set(true)
        consent.close()
        withContext(NonCancellable) {
            lifecycle.withLock {
                supervisorScope {
                    sessions.value
                        .map { entry ->
                            // Enter every non-cancellable stop before awaiting any failure.
                            async(start = CoroutineStart.UNDISPATCHED) { entry.session.stop() }
                        }.awaitAll()
                }
            }
        }
    }

    private suspend fun acquire(
        plan: SandboxSessionPlan,
        review: SandboxPermissionReview,
        launch: () -> ManagedSandboxSession,
    ): SandboxSessionEntry {
        // JNI acquisition is synchronous. Cancellation must not discard a successfully acquired owner.
        val session = withContext(NonCancellable) { withContext(Dispatchers.IO) { launch() } }
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Sandbox sessions are closed" }
            val entry = SandboxSessionEntry(review, session, plan)
            mutableSessions.value = sessions.value + entry
            transferred = true
            return entry
        } finally {
            if (!transferred) session.stop()
        }
    }

    companion object {
        const val MAX_SESSIONS = 8
        const val MAX_REASON_CHARACTERS = 2048
    }
}

class SandboxSessionEntry internal constructor(
    val review: SandboxPermissionReview,
    val session: ManagedSandboxSession,
    internal val plan: SandboxSessionPlan,
) {
    val id: String = UUID.randomUUID().toString()
}

internal interface SandboxSessionBackend {
    fun prepare(command: SandboxCommand): SandboxSessionPlan

    fun launch(plan: SandboxSessionPlan): ManagedSandboxSession

    fun prepareEscalation(
        base: SandboxSessionPlan,
        additional: SandboxEscalation,
    ): SandboxEscalationPlan = CageforgeEscalationLauncher().prepare(base, additional)
}

private class NativeSandboxSessionBackend : SandboxSessionBackend {
    private val launcher = CageforgeSessionLauncher()

    override fun prepare(command: SandboxCommand): SandboxSessionPlan = launcher.prepare(command)

    override fun launch(plan: SandboxSessionPlan): ManagedSandboxSession =
        launcher
            .launch(plan, plan.approvalDigest)
            .manage()
}
