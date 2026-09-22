package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.services.KernelServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Windows-specific BOSS IPC setup used by the native Cageforge smoke. */
internal object CageforgeWindowsNativeSecuritySupport {
    fun runLocalIpcSmoke(
        workspace: Path,
        logs: Path,
        result: Path,
    ) {
        startServers().use { servers ->
            val config = createProcessConfig(workspace, servers, result)
            val managed = ProcessSpawner("native-security-ipc", logs.toFile()).spawn(config)
            try {
                assertTrue(managed.process.waitFor(10, TimeUnit.SECONDS), "Windows IPC probe did not exit")
                val output = awaitWindowsResult(result)
                assertTrue(output.contains("allowed-ipc-connected"), output)
                assertTrue(output.contains("blocked-ipc-denied"), output)
                assertEquals(0, managed.process.exitValue())
            } finally {
                terminateWindowsProcess(managed)
            }
        }
    }

    private fun startServers(): WindowsLocalIpcServers {
        val allowedAddress = IpcAddressResolver.resolveAddress("smoke-allowed", "native-security-ipc")
        val blockedAddress = IpcAddressResolver.resolveAddress("smoke-blocked", "native-security-ipc")
        val identity = IpcTlsIdentity.create()
        val tokens = ProcessTokenRegistry()
        val token = tokens.issue("native-security-ipc", ProcessAuthority.PROCESS)
        val service = KernelServiceImpl()
        val allowedServer = BossIpcServer(allowedAddress, tokens, identity).addService(service).start()
        return runCatching {
            val blockedServer = BossIpcServer(blockedAddress, tokens, identity).addService(service).start()
            WindowsLocalIpcServers(
                allowedAddress = allowedAddress,
                blockedAddress = blockedAddress,
                identity = identity,
                token = token,
                allowedServer = allowedServer,
                blockedServer = blockedServer,
            )
        }.getOrElse { error ->
            allowedServer.stop()
            throw error
        }
    }

    private fun createProcessConfig(
        workspace: Path,
        servers: WindowsLocalIpcServers,
        result: Path,
    ): ProcessConfig {
        val classpath = nativeSecurityClasspath()
        val javaExecutable = ProcessSpawner.findJavaExecutable()
        val readRoots =
            nativeSecurityReadRoots(classpath) +
                listOf(File(System.getProperty("java.home")), File(javaExecutable))
        return ProcessConfig(
            processId = "native-security-ipc",
            processType = ProcessType.APP,
            displayName = "Cageforge Windows named-pipe smoke",
            mainClass = CageforgeLocalIpcProbe::class.java.name,
            classpath = classpath,
            workDir = workspace.toFile(),
            environment =
                mapOf(
                    "BOSS_SMOKE_ALLOWED_ADDRESS" to servers.allowedAddress,
                    "BOSS_SMOKE_BLOCKED_ADDRESS" to servers.blockedAddress,
                    "BOSS_SMOKE_CERTIFICATE" to servers.identity.certificateBase64,
                    "BOSS_SMOKE_TOKEN" to servers.token,
                    "BOSS_SMOKE_RESULT" to result.toString(),
                ),
            cageforge =
                CageforgeProcessPolicy.workspace(
                    workspace.toFile(),
                    readRoots,
                    localIpcEndpoints =
                        listOf(servers.allowedAddress).map(::nativeLocalIpcEndpoint),
                    runtimeExecutableRoots =
                        listOf(
                            File(System.getProperty("java.home")).canonicalFile,
                        ),
                ),
        )
    }

    private data class WindowsLocalIpcServers(
        val allowedAddress: String,
        val blockedAddress: String,
        val identity: IpcTlsIdentity,
        val token: String,
        private val allowedServer: BossIpcServer,
        private val blockedServer: BossIpcServer,
    ) : AutoCloseable {
        override fun close() {
            blockedServer.stop()
            allowedServer.stop()
        }
    }

    private fun awaitWindowsResult(path: Path): String {
        repeat(200) {
            if (Files.isRegularFile(path)) return Files.readString(path)
            Thread.sleep(50)
        }
        return Files.readString(path)
    }

    private fun terminateWindowsProcess(managed: ManagedProcess) {
        managed.process.destroyForcibly()
        assertTrue(
            managed.process.waitFor(10, TimeUnit.SECONDS),
            "Windows IPC smoke process did not terminate during cleanup",
        )
        managed.closeNativeResources()
    }
}
