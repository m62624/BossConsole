package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSandboxUnloadAwaitTest {
    @Test
    fun `uninstall awaits sandbox teardown before returning`() = verifyRemoval(cancelCaller = false)

    @Test
    fun `caller cancellation cannot abandon destructive cleanup`() = verifyRemoval(cancelCaller = true)

    @Test
    fun `cancellation waiting for cleanup lock refreshes surviving panels`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val lock =
                manager.javaClass
                    .getDeclaredField("mutex")
                    .apply { isAccessible = true }
                    .get(manager) as Mutex
            val previousRefresh = DynamicPluginManager.pluginPanelsRefresh
            var refreshed = false
            DynamicPluginManager.pluginPanelsRefresh = { id, _ -> if (id == "waiting-plugin") refreshed = true }
            lock.lock()
            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin("waiting-plugin") }
            try {
                assertFalse(uninstall.isCompleted)
                uninstall.cancel()
                withTimeout(5_000) { uninstall.join() }
                assertTrue(refreshed, "Cancellation must compensate panel detachment before lock acquisition")
            } finally {
                lock.unlock()
                DynamicPluginManager.pluginPanelsRefresh = previousRefresh
                manager.disposeWindow()
                sandboxManager.dispose()
            }
        }

    @Test
    fun `cancellation during UI disposal cannot proceed into destructive cleanup`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val previousTeardown = DynamicPluginManager.pluginTabsTeardown
            val started = CompletableDeferred<Unit>()
            val disposal = CompletableDeferred<Unit>()
            var proceeded = false
            DynamicPluginManager.pluginTabsTeardown = {
                started.complete(Unit)
                disposal.await()
            }
            val teardown =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.teardownPluginTabsForUnload("disposal-plugin")
                    proceeded = true
                }
            try {
                withTimeout(5_000) { started.await() }
                teardown.cancel()
                withTimeout(5_000) { teardown.join() }
                assertFalse(proceeded, "Cancelled disposal must propagate before acquiring even an uncontended lock")
            } finally {
                DynamicPluginManager.pluginTabsTeardown = previousTeardown
                manager.disposeWindow()
                sandboxManager.dispose()
            }
        }

    @Test
    fun `cancellation during protected termination propagates to the caller`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val terminationStarted = CompletableDeferred<Unit>()
            val terminationReleased = CompletableDeferred<Unit>()
            val spawner =
                object : OutOfProcessPluginSpawner {
                    override suspend fun spawn(
                        manifest: PluginManifest,
                        jarPath: String,
                        securityRequired: Boolean,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun terminate(pluginId: String): Result<Unit> {
                        terminationStarted.complete(Unit)
                        terminationReleased.await()
                        return Result.success(Unit)
                    }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                    outOfProcessSpawner = spawner,
                )
            val id = "com.example.security-required"
            val info =
                DynamicPluginInfo(
                    manifest =
                        PluginManifest(
                            pluginId = id,
                            displayName = "Protected plugin",
                            version = "1.0.0",
                            apiVersion = "1.0",
                            mainClass = "example.Plugin",
                            type = PluginType.PANEL,
                        ),
                    jarPath = "/unused.jar",
                    state = PluginState.LOADED,
                    loadedAt = 0L,
                    enabled = true,
                )
            manager.javaClass
                .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
                .apply {
                    isAccessible = true
                    invoke(manager, id, info)
                }
            manager.javaClass
                .getDeclaredField("securityRequiredPluginIds")
                .apply { isAccessible = true }
                .let { field ->
                    val ids = checkNotNull(field.get(manager))
                    ids.javaClass.getMethod("add", Any::class.java).invoke(ids, id)
                }

            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin(id, force = true) }
            try {
                withTimeout(5_000) { terminationStarted.await() }
                uninstall.cancel()
                withTimeout(5_000) { uninstall.join() }
                assertTrue(uninstall.isCancelled)
            } finally {
                terminationReleased.complete(Unit)
                uninstall.cancel()
                withTimeout(5_000) { uninstall.join() }
                manager.disposeWindow()
                sandboxManager.dispose()
            }
        }

    @Test
    fun `cancellation during protected spawn propagates to the caller`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val spawnStarted = CompletableDeferred<Unit>()
            val spawnReleased = CompletableDeferred<Unit>()
            val spawner =
                object : OutOfProcessPluginSpawner {
                    override suspend fun spawn(
                        manifest: PluginManifest,
                        jarPath: String,
                        securityRequired: Boolean,
                    ): Result<Unit> {
                        spawnStarted.complete(Unit)
                        spawnReleased.await()
                        return Result.success(Unit)
                    }

                    override suspend fun terminate(pluginId: String): Result<Unit> = Result.success(Unit)
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                    outOfProcessSpawner = spawner,
                )
            withTempDir { tempDir ->
                val jar =
                    PluginJarTestFixtures.writeJar(
                        tempDir,
                        "security-required-plugin.jar",
                        "com.example.security-required",
                        "1.0.0",
                        securityRequired = true,
                    )
                val install = async(start = CoroutineStart.UNDISPATCHED) { manager.installPlugin(jar.absolutePath) }
                try {
                    withTimeout(5_000) { spawnStarted.await() }
                    install.cancel()
                    withTimeout(5_000) { install.join() }
                    assertTrue(install.isCancelled)
                } finally {
                    spawnReleased.complete(Unit)
                    install.cancel()
                    withTimeout(5_000) { install.join() }
                    manager.disposeWindow()
                    sandboxManager.dispose()
                }
            }
        }

    @Test
    fun `protected enable and disable use the process spawner`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            var spawnCount = 0
            var terminationCount = 0
            var protectedSpawn = false
            val spawner =
                object : OutOfProcessPluginSpawner {
                    override suspend fun spawn(
                        manifest: PluginManifest,
                        jarPath: String,
                        securityRequired: Boolean,
                    ): Result<Unit> {
                        spawnCount++
                        protectedSpawn = securityRequired
                        return Result.success(Unit)
                    }

                    override suspend fun terminate(pluginId: String): Result<Unit> {
                        terminationCount++
                        return Result.success(Unit)
                    }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("Protected plugins must not create a host context") },
                    outOfProcessSpawner = spawner,
                )
            withTempDir { tempDir ->
                val jar =
                    PluginJarTestFixtures.writeJar(
                        tempDir,
                        "security-required-plugin.jar",
                        "com.example.security-required",
                        "1.0.0",
                        securityRequired = true,
                    )

                val installed = manager.installPlugin(jar.absolutePath, enabled = false).getOrThrow()
                assertFalse(installed.enabled)
                assertFalse(installed.state == PluginState.LOADED)
                assertTrue(spawnCount == 0, "Disabled protected install must not spawn a child")

                assertTrue(manager.enablePlugin(installed.manifest.pluginId).isSuccess)
                assertTrue(protectedSpawn)
                assertTrue(spawnCount == 1)
                assertTrue(manager.getPluginInfo(installed.manifest.pluginId)?.state == PluginState.LOADED)

                assertTrue(manager.disablePlugin(installed.manifest.pluginId).isSuccess)
                assertTrue(terminationCount == 1)
                assertTrue(manager.getPluginInfo(installed.manifest.pluginId)?.state == PluginState.DISABLED)
                assertTrue(File(jar.absolutePath).isFile)
            }
            manager.disposeWindow()
            sandboxManager.dispose()
        }

    @Test
    fun `protected install fails closed without a protected spawner`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("Protected plugins must not create a host context") },
                )
            withTempDir { tempDir ->
                val jar =
                    PluginJarTestFixtures.writeJar(
                        tempDir,
                        "security-required-plugin.jar",
                        "com.example.security-required",
                        "1.0.0",
                        securityRequired = true,
                    )

                val result = manager.installPlugin(jar.absolutePath)

                assertTrue(result.isFailure)
                assertFalse(manager.getPluginInfo("com.example.security-required") != null)
            }
            manager.disposeWindow()
            sandboxManager.dispose()
        }

    @Test
    fun `protected disable preserves ownership when termination fails`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            var failTermination = true
            val spawner =
                object : OutOfProcessPluginSpawner {
                    override suspend fun spawn(
                        manifest: PluginManifest,
                        jarPath: String,
                        securityRequired: Boolean,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun terminate(pluginId: String): Result<Unit> =
                        if (failTermination) {
                            Result.failure(IllegalStateException("termination refused"))
                        } else {
                            Result.success(Unit)
                        }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("Protected plugins must not create a host context") },
                    outOfProcessSpawner = spawner,
                )
            withTempDir { tempDir ->
                val jar =
                    PluginJarTestFixtures.writeJar(
                        tempDir,
                        "security-required-plugin.jar",
                        "com.example.security-required",
                        "1.0.0",
                        securityRequired = true,
                    )
                val installed = manager.installPlugin(jar.absolutePath).getOrThrow()
                val failedDisable = manager.disablePlugin(installed.manifest.pluginId)
                assertTrue(failedDisable.isFailure)
                assertTrue(manager.getPluginInfo(installed.manifest.pluginId)?.state == PluginState.LOADED)
                assertTrue(manager.getPluginInfo(installed.manifest.pluginId)?.enabled == true)

                failTermination = false
                assertTrue(manager.disablePlugin(installed.manifest.pluginId).isSuccess)
            }
            manager.disposeWindow()
            sandboxManager.dispose()
        }

    @Test
    fun `protected uninstall terminates before removing ownership state`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            var terminationCount = 0
            var stateWasOwnedDuringTermination = false
            lateinit var manager: DynamicPluginManager
            val spawner =
                object : OutOfProcessPluginSpawner {
                    override suspend fun spawn(
                        manifest: PluginManifest,
                        jarPath: String,
                        securityRequired: Boolean,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun terminate(pluginId: String): Result<Unit> {
                        terminationCount++
                        stateWasOwnedDuringTermination = manager.getPluginInfo(pluginId) != null
                        return Result.success(Unit)
                    }
                }
            manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("Protected plugins must not create a host context") },
                    outOfProcessSpawner = spawner,
                )
            withTempDir { tempDir ->
                val jar =
                    PluginJarTestFixtures.writeJar(
                        tempDir,
                        "security-required-plugin.jar",
                        "com.example.security-required",
                        "1.0.0",
                        securityRequired = true,
                    )
                val installed = manager.installPlugin(jar.absolutePath).getOrThrow()

                assertTrue(manager.uninstallPlugin(installed.manifest.pluginId, force = true).isSuccess)
                assertTrue(stateWasOwnedDuringTermination)
                assertTrue(terminationCount == 1)
                assertFalse(manager.getPluginInfo(installed.manifest.pluginId) != null)
            }
            manager.disposeWindow()
            sandboxManager.dispose()
        }

    private fun verifyRemoval(cancelCaller: Boolean) =
        runBlocking {
            val real = PluginSandboxManagerImpl()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sandboxManager =
                object : PluginSandboxManager by real {
                    override suspend fun removeSandbox(pluginId: String) {
                        started.complete(Unit)
                        release.await()
                        real.removeSandbox(pluginId)
                    }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val id = "com.example.await-removal"
            val info = unloadedInfo(id)
            // Reproduce the existing unload-with-state-only path without loading a real plugin JAR.
            manager.javaClass
                .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
                .apply {
                    isAccessible = true
                    invoke(manager, id, info)
                }
            PluginCrashRegistry.markIncompatible(id)
            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin(id, force = true) }
            try {
                withTimeout(5_000) { started.await() }
                assertFalse(uninstall.isCompleted, "uninstall must await its sandbox removal")
                if (cancelCaller) uninstall.cancel()
                release.complete(Unit)
                if (cancelCaller) {
                    withTimeout(5_000) { uninstall.join() }
                    assertTrue(uninstall.isCancelled)
                } else {
                    assertTrue(withTimeout(5_000) { uninstall.await() }.isSuccess)
                }
                assertFalse(manager.isInstalled(id))
                assertFalse(PluginCrashRegistry.isIncompatible(id))
            } finally {
                release.complete(Unit)
                uninstall.join()
                manager.disposeWindow()
                real.dispose()
                PluginCrashRegistry.clearIncompatible(id)
            }
        }

    private fun unloadedInfo(id: String): DynamicPluginInfo =
        DynamicPluginInfo(
            manifest =
                PluginManifest(
                    pluginId = id,
                    displayName = "Await removal",
                    version = "1.0.0",
                    apiVersion = "1.0",
                    mainClass = "example.Plugin",
                    type = PluginType.PANEL,
                ),
            jarPath = "/unused.jar",
            state = PluginState.ERROR,
            loadedAt = 0L,
            enabled = false,
        )
}
