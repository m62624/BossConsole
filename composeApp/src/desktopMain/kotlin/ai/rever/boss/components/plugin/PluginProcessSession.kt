package ai.rever.boss.components.plugin

import ai.rever.boss.kernel.ReapAdmissionException
import ai.rever.boss.kernel.isReaping
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import io.grpc.ManagedChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Owns the active session and generation for each protected plugin identity. */
internal class PluginSessionRegistry {
    private val sessions = ConcurrentHashMap<String, PluginProcessSession>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val reservations = ConcurrentHashMap.newKeySet<String>()

    fun requireAvailable(pluginId: String) {
        if (isReaping()) throw ReapAdmissionException()
        check(sessions[pluginId] == null) {
            "Refusing to replace active plugin session: $pluginId"
        }
        check(reservations.add(pluginId)) {
            "Refusing concurrent startup for plugin: $pluginId"
        }
    }

    fun releaseReservation(pluginId: String) {
        reservations.remove(pluginId)
    }

    fun newSession(
        pluginId: String,
        config: ProcessConfig,
        process: ManagedProcess,
    ): PluginProcessSession =
        PluginProcessSession(
            pluginId = pluginId,
            sessionId = UUID.randomUUID().toString(),
            generation = generations.computeIfAbsent(pluginId) { AtomicLong() }.incrementAndGet(),
            config = config,
            process = process,
        )

    fun register(session: PluginProcessSession) {
        session.markNativeSpawned()
        check(sessions.putIfAbsent(session.pluginId, session) == null) {
            "Refusing to replace active plugin session: ${session.pluginId}"
        }
        releaseReservation(session.pluginId)
    }

    fun current(pluginId: String): PluginProcessSession? = sessions[pluginId]

    fun isCurrent(session: PluginProcessSession): Boolean = sessions[session.pluginId] === session

    fun removeIfCurrent(session: PluginProcessSession): Boolean = sessions.remove(session.pluginId, session)
}

/**
 * Owns every resource belonging to one protected plugin launch.
 *
 * A new restart gets a new session ID and generation. Cleanup must retain the exact session
 * reference so a late callback from an older generation cannot remove or close a replacement.
 */
internal class PluginProcessSession(
    val pluginId: String,
    val sessionId: String,
    val generation: Long,
    val config: ProcessConfig,
    val process: ManagedProcess,
) {
    private val lock = ReentrantLock()

    @Volatile var state: PluginProcessSessionState = PluginProcessSessionState.STARTING
        private set

    @Volatile var channel: ManagedChannel? = null
        private set

    @Volatile var bridge: PluginStateBridge? = null
        private set

    fun markNativeSpawned() =
        lock.withLock {
            check(state == PluginProcessSessionState.STARTING) {
                "Plugin session $sessionId cannot enter NATIVE_SPAWNED from $state"
            }
            state = PluginProcessSessionState.NATIVE_SPAWNED
        }

    fun markMcpReady(
        channel: ManagedChannel,
        bridge: PluginStateBridge,
    ) = lock.withLock {
        check(state == PluginProcessSessionState.NATIVE_SPAWNED) {
            "Plugin session $sessionId cannot become MCP_READY from $state"
        }
        this.channel = channel
        this.bridge = bridge
        state = PluginProcessSessionState.MCP_READY
    }

    fun markRunning() =
        lock.withLock {
            check(state == PluginProcessSessionState.MCP_READY) {
                "Plugin session $sessionId cannot become RUNNING from $state"
            }
            state = PluginProcessSessionState.RUNNING
        }

    fun beginTermination(): SessionResources? =
        lock.withLock {
            when (state) {
                PluginProcessSessionState.STOPPING,
                PluginProcessSessionState.TERMINATED,
                PluginProcessSessionState.CLEANED,
                PluginProcessSessionState.STOPPED,
                -> return null

                else -> Unit
            }
            state = PluginProcessSessionState.STOPPING
            SessionResources(channel, bridge)
        }

    fun markTerminated() =
        lock.withLock {
            check(state == PluginProcessSessionState.STOPPING) {
                "Plugin session $sessionId cannot become TERMINATED from $state"
            }
            check(!process.isAlive) {
                "Plugin session $sessionId cannot be terminated while its process is alive"
            }
            state = PluginProcessSessionState.TERMINATED
        }

    fun markCleaned() =
        lock.withLock {
            check(state == PluginProcessSessionState.TERMINATED) {
                "Plugin session $sessionId cannot be cleaned from $state"
            }
            state = PluginProcessSessionState.CLEANED
            state = PluginProcessSessionState.STOPPED
        }
}

internal enum class PluginProcessSessionState {
    STARTING,
    NATIVE_SPAWNED,
    MCP_READY,
    RUNNING,
    STOPPING,
    TERMINATED,
    CLEANED,
    STOPPED,
}

internal data class SessionResources(
    val channel: ManagedChannel?,
    val bridge: PluginStateBridge?,
)
