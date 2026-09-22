package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

object CageforgeLocalIpcProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (isWindows()) {
            runWindowsProbe()
            return
        }
        val allowed = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_ALLOWED_SOCKET")))
        val blocked = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_BLOCKED_SOCKET")))
        val result = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_RESULT")))
        val statuses = mutableListOf<String>()
        runCatching { connectAndWrite(allowed, 'a') }
            .onSuccess {
                statuses += "allowed-ipc-connected"
                Files.writeString(result, statuses.joinToString("\n"))
            }.onFailure {
                statuses += "allowed-ipc-denied: ${it::class.simpleName}: ${it.message}"
                Files.writeString(result, statuses.joinToString("\n"))
            }
        runCatching { connectAndWrite(blocked, 'b') }
            .onSuccess { statuses += "blocked-ipc-allowed" }
            .onFailure { statuses += "blocked-ipc-denied" }
        Files.writeString(result, statuses.joinToString("\n"))
    }

    private fun runWindowsProbe() =
        runBlocking {
            val allowed = requireNotNull(System.getenv("BOSS_SMOKE_ALLOWED_ADDRESS"))
            val blocked = requireNotNull(System.getenv("BOSS_SMOKE_BLOCKED_ADDRESS"))
            val credentials =
                IpcClientCredentials(
                    requireNotNull(System.getenv("BOSS_SMOKE_CERTIFICATE")),
                    requireNotNull(System.getenv("BOSS_SMOKE_TOKEN")),
                )
            val result = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_RESULT")))
            val statuses = mutableListOf<String>()
            val allowedStatus =
                runCatching { connectToAllowedEndpoint(allowed, credentials) }
                    .fold(
                        onSuccess = { "allowed-ipc-connected" },
                        onFailure = { error ->
                            "allowed-ipc-denied: ${error::class.simpleName}: ${error.message}"
                        },
                    )
            statuses += allowedStatus

            val blockedClient = BossIpcClient(blocked, credentials)
            try {
                val blockedReady = blockedClient.waitForReady(1_000)
                check(!blockedReady) { "blocked named pipe became ready" }
                statuses += "blocked-ipc-denied"
            } finally {
                blockedClient.shutdown()
            }
            Files.writeString(result, statuses.joinToString("\n"))
        }

    private suspend fun connectToAllowedEndpoint(
        address: String,
        credentials: IpcClientCredentials,
    ) {
        val client = BossIpcClient(address, credentials)
        try {
            check(client.waitForReady(5_000)) { "allowed named pipe did not become ready" }
            val response =
                KernelServiceGrpcKt
                    .KernelServiceCoroutineStub(client.channel)
                    .registerProcess(
                        RegisterProcessRequest
                            .newBuilder()
                            .setManifest(
                                ProcessManifest
                                    .newBuilder()
                                    .setProcessId("native-security-ipc")
                                    .setProcessType(ProcessType.PROCESS_TYPE_APP)
                                    .setDisplayName("Windows local IPC probe")
                                    .setVersion("1.0.0")
                                    .setMainClass("CageforgeLocalIpcProbe")
                                    .build(),
                            ).setIpcAddress(address)
                            .build(),
                    )
            check(response.success) { "allowed named pipe registration failed" }
        } finally {
            client.shutdown()
        }
    }

    private fun connectAndWrite(
        path: Path,
        value: Char,
    ) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                Selector.open().use { selector ->
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    check(selector.select(2_000) > 0 && channel.finishConnect()) {
                        "IPC connection timed out: $path"
                    }
                }
            }
            channel.configureBlocking(true)
            channel.write(ByteBuffer.wrap(byteArrayOf(value.code.toByte())))
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
