package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.IpcTransport
import ai.rever.boss.ipc.IpcVersion
import ai.rever.boss.kernel.KernelBootstrap
import ai.rever.boss.kernel.ReapAdmissionException
import ai.rever.boss.kernel.discardReapedSpawn
import ai.rever.boss.kernel.reapSpawnGate
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessSpawner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Implementation of [OutOfProcessPluginSpawner] that uses [ProcessSpawner]
 * to launch plugin child JVM processes.
 *
 * Each spawned plugin:
 * 1. Runs the generic `PluginProcessMain` entry point
 * 2. Reads its plugin JAR from `BOSS_PLUGIN_CLASSPATH`
 * 3. Connects back to the kernel via `BOSS_KERNEL_IPC_ADDR`
 * 4. Registers with the kernel's process registry
 * 5. Starts its gRPC server for UI streaming and state sync
 *
 * The spawner tracks all managed processes and provides connection info
 * (gRPC channel) for [PluginStateBridge] and remote UI components.
 */
class OutOfProcessPluginSpawnerImpl(
    private val processSpawner: ProcessSpawner,
    private val windowId: String = "",
    private val projectPath: String = "",
) : OutOfProcessPluginSpawner {
    private val logger = LoggerFactory.getLogger(OutOfProcessPluginSpawnerImpl::class.java)
    private val sessionRegistry = PluginSessionRegistry()

    /**
     * Classpath for the plugin runtime fat JAR.
     * Resolved from BOSS_PLUGIN_RUNTIME_JAR env var or default location.
     */
    private val runtimeClasspath: String by lazy {
        resolveRuntimeClasspath()
    }

    override suspend fun spawn(
        manifest: PluginManifest,
        jarPath: String,
        securityRequired: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            var session: PluginProcessSession? = null
            var managedProcess: ManagedProcess? = null
            var reservationHeld = false
            try {
                val spawnGeneration = reapSpawnGate.generation()
                val pluginId = manifest.pluginId
                sessionRegistry.requireAvailable(pluginId)
                reservationHeld = true
                validateRuntime(runtimeClasspath)
                val config =
                    buildProcessConfig(
                        ProtectedPluginProcessConfig(
                            manifest = manifest,
                            jarPath = jarPath,
                            securityRequired = securityRequired,
                            runtimeClasspath = runtimeClasspath,
                            windowId = windowId,
                            projectPath = projectPath,
                        ),
                    )
                val spawnedProcess = spawnProcess(spawnGeneration, config)
                managedProcess = spawnedProcess
                val newSession = sessionRegistry.newSession(pluginId, config, spawnedProcess)
                session = newSession
                sessionRegistry.register(newSession)
                waitForReady(pluginId, newSession, config.startupTimeoutMs)
                attachAuthenticatedBridge(newSession)

                logger.info("Out-of-process plugin ready: id={}, pid={}", pluginId, managedProcess.pid)

                Result.success(Unit)
            } catch (e: ReapAdmissionException) {
                logger.warn("Refusing plugin startup after a reap: {}", manifest.pluginId)
                Result.failure(e)
            } catch (e: CancellationException) {
                cleanupFailedSpawn(session, managedProcess)
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Failed to spawn out-of-process plugin: manifest={}",
                    manifest.pluginId,
                    e,
                )
                // A waitForReady timeout leaves a child that started but never registered -
                // still alive, and no longer referenced by anything that would kill it. Reap
                // it here rather than let a failed spawn become another orphan.
                cleanupFailedSpawn(session, managedProcess)
                Result.failure(e)
            } finally {
                if (reservationHeld) sessionRegistry.releaseReservation(manifest.pluginId)
            }
        }

    private fun spawnProcess(
        spawnGeneration: Long,
        config: ProcessConfig,
    ): ManagedProcess =
        reapSpawnGate.spawn(
            spawnGeneration,
            createChild = { processSpawner.spawn(config) },
            discardChild = { discardReapedSpawn(it, kernelRegistry()) },
        )

    private suspend fun attachAuthenticatedBridge(session: PluginProcessSession) {
        check(sessionRegistry.isCurrent(session)) {
            "Plugin session was replaced before authenticated bridge setup: ${session.pluginId}"
        }
        val channel = checkNotNull(session.process.ipcClient) { "Managed plugin lacks authenticated IPC" }.channel
        val bridge =
            PluginStateBridge(
                pluginId = session.pluginId,
                instanceId = session.config.processId,
                channel = channel,
            )
        session.attachResources(channel, bridge)
        var ready = false
        try {
            bridge.start()
            bridge.awaitConnected(session.config.startupTimeoutMs)
            check(sessionRegistry.isCurrent(session)) {
                "Plugin session was replaced before authenticated bridge readiness: ${session.pluginId}"
            }
            session.markMcpReady(channel, bridge)
            session.markRunning()
            ready = true
        } finally {
            if (!ready) {
                runCatching { bridge.dispose() }
                runCatching { channel.shutdownNow() }
            }
        }
    }

    /**
     * Tear down everything [spawn] may have created for a plugin whose startup failed.
     */
    private fun cleanupFailedSpawn(
        session: PluginProcessSession?,
        managedProcess: ManagedProcess?,
    ) {
        if (session == null) {
            cleanupUnregisteredProcess(managedProcess, kernelRegistry(), logger)
            return
        }
        val resources = session.beginTermination() ?: return
        val descendants =
            ai.rever.boss.kernel
                .processDescendants(session.process.process)
        runCatching { resources.bridge?.dispose() }
        runCatching { resources.channel?.shutdownNow() }
        runCatching { session.process.destroyForcibly() }
        if (!finishSessionCleanup(session, descendants)) {
            logger.error(
                "Failed to confirm cleanup of protected plugin process: id={}, pid={}",
                session.pluginId,
                session.process.pid,
            )
        }
    }

    private fun finishSessionCleanup(
        session: PluginProcessSession,
        descendants: List<ProcessHandle>,
    ): Boolean {
        // Kill first, then complete the session cleanup. Ownership remains registered until the
        // session has reached STOPPED; removing it earlier would expose a partially-cleaned
        // process to a concurrent shutdown or replacement.
        ai.rever.boss.kernel
            .killProcessDescendants(descendants)
        val terminated = !session.process.isAlive || awaitForcedExit(session.process)
        if (!terminated) return false

        session.markTerminated()
        session.markCleaned()
        kernelRegistry()?.unregisterIfSame(session.config.processId, session.process)
        sessionRegistry.removeIfCurrent(session)
        return true
    }

    override suspend fun terminate(pluginId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val session = sessionRegistry.current(pluginId)
            if (session == null) {
                logger.warn("No managed process found for plugin: {}", pluginId)
                return@withContext Result.success(Unit)
            }
            val resources =
                session.beginTermination()
                    ?: return@withContext Result.success(Unit)
            val process = session.process
            val descendants =
                ai.rever.boss.kernel
                    .processDescendants(process.process)
            var failure: Throwable? = null
            try {
                // Dispose state bridge
                resources.bridge?.dispose()

                // Shutdown gRPC channel with timeout
                resources.channel?.let { channel ->
                    channel.shutdown()
                    if (!channel.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn("gRPC channel shutdown timeout for {}, forcing", pluginId)
                        channel.shutdownNow()
                    }
                }

                // Destroy process
                logger.info("Terminating plugin process: id={}, pid={}", pluginId, process.pid)
                process.destroy()

                // Wait for graceful shutdown, then force kill
                val exited = process.process.waitFor(5, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    logger.warn("Force-killed plugin process after shutdown timeout: id={}", pluginId)
                }
            } catch (e: CancellationException) {
                // Termination already owns cleanup: cancellation must not orphan this subtree.
                runCatching { process.destroyForcibly() }
                throw e
            } catch (e: Exception) {
                // Force kill if graceful shutdown failed
                runCatching { process.destroyForcibly() }
                failure = e
                logger.warn("Force-killed plugin process: id={}", pluginId, e)
            } finally {
                if (!finishSessionCleanup(session, descendants)) {
                    failure =
                        failure
                            ?: IllegalStateException(
                                "Failed to confirm cleanup of protected plugin process: $pluginId",
                            )
                }
            }
            if (failure == null) Result.success(Unit) else Result.failure(requireNotNull(failure))
        }

    /**
     * Get the state bridge for a plugin.
     */
    fun getStateBridge(pluginId: String): PluginStateBridge? = sessionRegistry.current(pluginId)?.bridge

    /**
     * Get the managed process for a plugin.
     */
    fun getManagedProcess(pluginId: String): ManagedProcess? = sessionRegistry.current(pluginId)?.process

    /**
     * Check if a plugin process is alive.
     */
    fun isAlive(pluginId: String): Boolean = sessionRegistry.current(pluginId)?.process?.isAlive == true

    /**
     * Wait for the child process to become ready (registered with kernel).
     */
    private suspend fun waitForReady(
        pluginId: String,
        session: PluginProcessSession,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) {
            val process = session.process
            val processId = session.config.processId
            val registry = kernelRegistry()

            while (true) {
                check(sessionRegistry.isCurrent(session)) {
                    "Plugin session was replaced during startup: $pluginId"
                }
                if (!process.isAlive) {
                    throw IllegalStateException(
                        "Plugin process died during startup: $pluginId (exit=${process.process.exitValue()})",
                    )
                }
                // Check kernel's process registry (populated by gRPC registration from child)
                if (registry?.getManifest(processId) != null) {
                    break
                }
                delay(100)
            }
        }
    }
}

private fun cleanupUnregisteredProcess(
    process: ManagedProcess?,
    registry: ProcessRegistry?,
    logger: Logger,
) {
    if (process == null) return
    val descendants =
        ai.rever.boss.kernel
            .processDescendants(process.process)
    runCatching { process.ipcClient?.shutdown(timeoutMs = 0) }
    runCatching { process.destroyForcibly() }
    ai.rever.boss.kernel
        .killProcessDescendants(descendants)
    val terminated =
        !process.isAlive ||
            runCatching { process.process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    if (terminated) {
        registry?.unregisterIfSame(process.config.processId, process)
    } else {
        logger.error(
            "Failed to confirm cleanup of unregistered protected plugin process: id={}, pid={}",
            process.config.processId,
            process.pid,
        )
    }
}

/** Read the current runtime on every spawn; replacing a JAR must invalidate the previous decision. */
private fun validateRuntime(runtimeClasspath: String) {
    IpcTransport.requireCompatibleRuntime(File(runtimeClasspath).toPath())
    val manifest = PluginManifestReader.readFromJar(runtimeClasspath)
    when (val compatibility = IpcVersion.isCompatible(manifest.minIpcVersion)) {
        is IpcVersion.CompatResult.Compatible -> Unit
        is IpcVersion.CompatResult.UnknownRuntime -> error("The microkernel runtime must declare minIpcVersion")
        is IpcVersion.CompatResult.Incompatible -> error(compatibility.reason)
    }
}

/**
 * Kernel-side process ID for a plugin.
 *
 * The registry is process-wide, so window-owned spawners include [windowId]. This prevents the
 * same plugin in two windows from evicting the other live process. A blank window ID preserves
 * the legacy identity for non-window and test contexts.
 */
internal fun pluginProcessId(
    windowId: String,
    pluginId: String,
): String =
    if (windowId.isBlank()) {
        "plugin-$pluginId"
    } else {
        // IPC uses this as a socket filename. Full window UUIDs plus manifest IDs exceed
        // Unix socket path limits, so keep the identity bounded without ambiguous separators.
        val identity = "${windowId.length}:$windowId$pluginId"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        "plugin-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

/**
 * The kernel's process registry, or null when the kernel is not up.
 *
 * Resolved per call rather than cached, for symmetry with the other lookups. In practice it cannot
 * be null here: `DefaultPlugin` obtains this spawner's [ProcessSpawner] *from*
 * `KernelBootstrap.instance`, so the instance and its registry both exist before this class does.
 */
private fun kernelRegistry(): ProcessRegistry? = KernelBootstrap.instance?.processRegistry

internal fun classpathRoots(classpath: String): List<File> {
    val roots = mutableListOf<File>()
    for (rawEntry in classpath.split(File.pathSeparator)) {
        if (rawEntry.isBlank()) continue
        val entry = File(rawEntry)
        require(entry.isAbsolute) {
            "Protected launch classpath entry must be absolute: ${entry.path}"
        }
        val normalizedEntry = entry.normalize()
        require(normalizedEntry.exists()) {
            "Protected launch classpath entry does not exist: ${normalizedEntry.path}"
        }
        roots += normalizedEntry
    }
    return roots
}

private fun awaitForcedExit(process: ai.rever.boss.process.ManagedProcess?): Boolean {
    // SIGKILL is asynchronous. Preserve a still-live handle after this bounded wait.
    return process?.process?.let {
        runCatching { it.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
    } ?: true
}
