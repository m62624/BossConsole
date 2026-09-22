package ai.rever.boss.components.plugin

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A capability shown to the operator before a protected plugin is launched. */
data class PluginSandboxCapability(
    val kind: String,
    val value: String,
    val access: String? = null,
) {
    val displayValue: String
        get() = listOfNotNull(kind, access, value).joinToString(" ")
}

/** The operator's decision on a protected plugin capability request. */
sealed interface PluginSandboxApprovalDecision {
    data object Approved : PluginSandboxApprovalDecision

    data class Denied(
        val reason: String = "Operator rejected protected plugin capabilities",
    ) : PluginSandboxApprovalDecision

    data object Timeout : PluginSandboxApprovalDecision

    data object QueueFull : PluginSandboxApprovalDecision
}

/** A pending request owned by one protected plugin launch. */
data class PluginSandboxApprovalRequest(
    val pluginId: String,
    val displayName: String,
    val capabilities: List<PluginSandboxCapability>,
    val timeoutMs: Long,
    val id: String = UUID.randomUUID().toString(),
    internal val deferred: CompletableDeferred<PluginSandboxApprovalDecision> = CompletableDeferred(),
)

/** Host-owned approval queue for Cageforge capabilities, separate from MCP tool approval. */
class PluginSandboxApprovalBus(
    private val defaultTimeoutMs: Long = 45_000L,
    private val maxPendingRequests: Int = 4,
) {
    private val logger = BossLogger.forComponent("PluginSandboxApprovalBus")
    private val lock = Any()
    private val requestsChannel = Channel<PluginSandboxApprovalRequest>(maxPendingRequests)
    private val activeRequests = ConcurrentHashMap<String, PluginSandboxApprovalRequest>()
    private val _pendingList = MutableStateFlow<List<PluginSandboxApprovalRequest>>(emptyList())

    val requests: Flow<PluginSandboxApprovalRequest> = requestsChannel.receiveAsFlow()
    val pendingList: StateFlow<List<PluginSandboxApprovalRequest>> = _pendingList.asStateFlow()

    suspend fun requestApproval(
        pluginId: String,
        displayName: String,
        capabilities: List<PluginSandboxCapability>,
    ): PluginSandboxApprovalDecision {
        val request =
            PluginSandboxApprovalRequest(
                pluginId = pluginId,
                displayName = displayName,
                capabilities = capabilities,
                timeoutMs = defaultTimeoutMs,
            )
        if (!enqueue(request)) return PluginSandboxApprovalDecision.QueueFull
        return awaitDecision(request)
    }

    private fun enqueue(request: PluginSandboxApprovalRequest): Boolean {
        val registered =
            synchronized(lock) {
                val accepted = _pendingList.value.size < maxPendingRequests
                if (accepted) {
                    activeRequests[request.id] = request
                    _pendingList.update { it + request }
                }
                accepted
            }
        if (!registered) {
            logger.warn(
                LogCategory.SYSTEM,
                "Protected plugin approval queue is full",
                mapOf("pluginId" to request.pluginId),
            )
        }
        val sent = registered && requestsChannel.trySend(request).isSuccess
        if (registered && !sent) remove(request)
        return sent
    }

    private suspend fun awaitDecision(request: PluginSandboxApprovalRequest): PluginSandboxApprovalDecision =
        try {
            withTimeoutOrNull(request.timeoutMs) { request.deferred.await() }
                ?: PluginSandboxApprovalDecision.Timeout.also { request.deferred.complete(it) }
        } finally {
            request.deferred.complete(PluginSandboxApprovalDecision.Denied("Approval request expired"))
            remove(request)
        }

    fun approve(requestId: String): Boolean = complete(requestId, PluginSandboxApprovalDecision.Approved)

    fun deny(
        requestId: String,
        reason: String = "Operator rejected protected plugin capabilities",
    ): Boolean = complete(requestId, PluginSandboxApprovalDecision.Denied(reason))

    private fun complete(
        requestId: String,
        decision: PluginSandboxApprovalDecision,
    ): Boolean {
        val request = activeRequests[requestId] ?: return false
        return request.deferred.complete(decision)
    }

    private fun remove(request: PluginSandboxApprovalRequest) {
        synchronized(lock) {
            activeRequests.remove(request.id)
            _pendingList.update { it.filterNot { pending -> pending.id == request.id } }
        }
    }
}

object PluginSandboxApprovalRegistry {
    val bus = PluginSandboxApprovalBus()
}

suspend fun PluginSandboxApprovalBus.consumeApprovals(show: (PluginSandboxApprovalRequest?) -> Unit) {
    requests.collect { request ->
        if (!request.deferred.isCompleted) {
            try {
                show(request)
                request.deferred.await()
            } finally {
                request.deferred.complete(PluginSandboxApprovalDecision.Denied("Approval window closed"))
                show(null)
            }
        }
    }
}
