package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.HealthContract
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.services.KernelServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsNamedPipeIpcTest {
    @Test
    fun `windows uses a named pipe and other platforms reject that address`() =
        runBlocking {
            val address = "pipe://\\\\.\\pipe\\boss-ipc-transport-test"
            if (!isWindows()) {
                assertFailsWith<UnsupportedOperationException> { IpcAddressResolver.parseAddress(address) }
                return@runBlocking
            }

            val registry = ProcessTokenRegistry()
            val identity = IpcTlsIdentity.create()
            val server = BossIpcServer(address, registry, identity).addService(KernelServiceImpl()).start()
            val token = registry.issue("pipe-test", ProcessAuthority.PROCESS)
            val client =
                BossIpcClient(
                    address,
                    IpcClientCredentials(identity.certificateBase64, token),
                )
            try {
                assertTrue(client.waitForReady(10_000), "named-pipe gRPC channel did not become ready")
                val response =
                    KernelServiceGrpcKt.KernelServiceCoroutineStub(client.channel).registerProcess(
                        RegisterProcessRequest
                            .newBuilder()
                            .setManifest(
                                ProcessManifest
                                    .newBuilder()
                                    .setProcessId("pipe-test")
                                    .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                                    .setDisplayName("Named pipe transport test")
                                    .setVersion("1.0.0")
                                    .setMainClass("test.NamedPipe")
                                    .setHealthContract(
                                        HealthContract
                                            .newBuilder()
                                            .setHeartbeatIntervalMs(5_000)
                                            .setStartupTimeoutMs(10_000)
                                            .build(),
                                    ).build(),
                            ).setIpcAddress(address)
                            .build(),
                    )
                assertTrue(response.success)
                assertEquals("pipe-test", response.assignedProcessId)
            } finally {
                client.shutdown()
                server.stop()
            }
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
}
