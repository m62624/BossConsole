package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/** Representative BOSS-side native smoke; the complete backend suite remains in Cageforge CI. */
class CageforgeNativeSecuritySmokeTest {
    @Test
    fun nativeProcessRegistersThroughAuthenticatedLocalIpc() {
        val workspace = Files.createTempDirectory("boss-cageforge-auth-")
        val logs = Files.createTempDirectory("boss-cageforge-auth-logs-")
        val kernelAddress = IpcAddressResolver.kernelAddress()
        val processId = "native-security-auth"
        val processAddress = IpcAddressResolver.resolveAddress("plugin", processId)
        var server: BossIpcServer? = null
        var managed: ManagedProcess? = null
        try {
            if (isWindows()) {
                assertThrowsUnsupportedLocalIpc(workspace, workspace.resolve("unsupported.sock"))
                return
            }

            val kernel = startAuthenticatedKernel(kernelAddress)
            server = kernel.server
            val config = authenticatedIpcConfig(workspace, processId, kernelAddress, processAddress)

            managed =
                ProcessSpawner(
                    kernelAddress,
                    logs.toFile(),
                    tokenRegistry = kernel.tokens,
                    kernelIdentity = kernel.identity,
                ).spawn(config)
            assertTrue(
                managed.process.waitFor(10, TimeUnit.MILLISECONDS).not(),
                "authenticated child exited before readiness",
            )
            awaitAuthenticatedRegistration(
                kernel.service,
                managed.process,
                logs,
                workspace.resolve("auth-diag.txt"),
            )
            val client = checkNotNull(managed.ipcClient) { "Cageforge child has no authenticated IPC client" }
            runBlocking {
                assertTrue(client.waitForReady(10_000), "authenticated child service did not become ready")
                val state =
                    StateServiceGrpcKt
                        .StateServiceCoroutineStub(client.channel)
                        .getState(StateKey.newBuilder().setKey("ready").build())
                assertEquals("ready", state.key)
            }
        } finally {
            managed?.let { terminateAfterTest(it.process) }
            server?.stop()
            logs.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeProcessCanReachOnlyAnExplicitlyAllowedLocalIpcEndpoint() {
        val workspace = Files.createTempDirectory("boss-cageforge-ipc-")
        val logs = Files.createTempDirectory("boss-cageforge-ipc-logs-")
        val socketRoot = Files.createTempDirectory("boss-cageforge-ipc-sockets-")
        val allowedSocket = socketRoot.resolve("allowed.sock")
        val blockedSocket = socketRoot.resolve("blocked.sock")
        val result = workspace.resolve("ipc-result.txt")
        var allowedServer: ServerSocketChannel? = null
        var blockedServer: ServerSocketChannel? = null
        var managed: ManagedProcess? = null
        try {
            if (isWindows()) {
                assertThrowsUnsupportedLocalIpc(workspace, allowedSocket)
                return
            }

            allowedServer = unixServer(allowedSocket)
            blockedServer = unixServer(blockedSocket)
            val config = createLocalIpcConfig(workspace, socketRoot, allowedSocket, blockedSocket, result)

            managed = ProcessSpawner("native-security-ipc", logs.toFile()).spawn(config)
            assertTrue(managed.process.waitFor(10, TimeUnit.SECONDS), "local IPC probe did not exit")
            val output = awaitFileText(result)
            assertTrue(output.contains("allowed-ipc-connected"), output)
            assertTrue(output.contains("blocked-ipc-denied"), output)
            assertEquals(0, managed.process.exitValue())
            assertEquals('a'.code.toByte(), readSocketByte(allowedServer))
        } finally {
            managed?.let { terminateAfterTest(it.process) }
            allowedServer?.close()
            blockedServer?.close()
            logs.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            socketRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeProcessEnforcesWorkspaceNetworkAndDescendantPolicy() {
        val workspace = Files.createTempDirectory("boss-cageforge-smoke-")
        val logs = Files.createTempDirectory("boss-cageforge-logs-")
        val outside = Files.createTempDirectory("boss-cageforge-outside-").resolve("must-not-exist.txt")
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        try {
            val classpath = System.getProperty("java.class.path")
            val config = createConfig(workspace, outside, server, classpath)
            var managed: ManagedProcess? = null
            try {
                managed = ProcessSpawner("native-security-test", logs.toFile()).spawn(config)
                assertSmokeResult(managed.process, workspace, outside, server)
            } finally {
                managed?.let { terminateAfterTest(it.process) }
            }
        } finally {
            server.close()
            logs.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            outside.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeProcessKillDoesNotLeaveADescendant() {
        val workspace = Files.createTempDirectory("boss-cageforge-kill-")
        val logs = Files.createTempDirectory("boss-cageforge-kill-logs-")
        val ready = workspace.resolve("kill-ready.txt")
        val lateMarker = workspace.resolve("late-descendant-write.txt")
        var managed: ManagedProcess? = null
        try {
            val classpath = System.getProperty("java.class.path")
            val javaExecutable = ProcessSpawner.findJavaExecutable()
            val readRoots =
                classpathRoots(classpath) +
                    listOf(File(System.getProperty("java.home")), File(javaExecutable))
            val config =
                ProcessConfig(
                    processId = "native-security-kill",
                    processType = ProcessType.APP,
                    displayName = "Cageforge native kill smoke",
                    mainClass = CageforgeNativeKillProbe::class.java.name,
                    classpath = classpath,
                    workDir = workspace.toFile(),
                    environment =
                        mapOf(
                            "BOSS_SMOKE_JAVA" to javaExecutable,
                            "BOSS_SMOKE_CLASSPATH" to classpath,
                            "BOSS_SMOKE_KILL_READY" to ready.toString(),
                            "BOSS_SMOKE_LATE_MARKER" to lateMarker.toString(),
                        ),
                    cageforge = CageforgeProcessPolicy.workspace(workspace.toFile(), readRoots),
                )

            managed = ProcessSpawner("native-security-kill", logs.toFile()).spawn(config)
            awaitFile(ready)
            managed.destroyForcibly()

            assertTrue(managed.process.waitFor(10, TimeUnit.SECONDS), "parent process did not exit")
            Thread.sleep(2_000)
            assertTrue(
                !Files.exists(lateMarker),
                "Cageforge kill must terminate descendants before they perform late work",
            )
        } finally {
            managed?.let { terminateAfterTest(it.process) }
            logs.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    private fun createConfig(
        workspace: Path,
        outside: Path,
        server: ServerSocket,
        classpath: String,
    ): ProcessConfig {
        val javaExecutable = ProcessSpawner.findJavaExecutable()
        val childEnvironment =
            mapOf(
                "BOSS_SMOKE_VALUE" to "explicit-channel-value",
                "BOSS_SMOKE_OUTSIDE" to outside.toString(),
                "BOSS_SMOKE_PORT" to server.localPort.toString(),
                "BOSS_SMOKE_JAVA" to javaExecutable,
                "BOSS_SMOKE_CLASSPATH" to classpath,
                "BOSS_SMOKE_GRANDCHILD" to workspace.resolve("grandchild-created.txt").toString(),
            )
        val readRoots =
            classpathRoots(classpath) +
                listOf(File(System.getProperty("java.home")), File(javaExecutable))
        return ProcessConfig(
            processId = "native-security-smoke",
            processType = ProcessType.APP,
            displayName = "Cageforge native security smoke",
            mainClass = CageforgeNativeProbe::class.java.name,
            classpath = classpath,
            workDir = workspace.toFile(),
            environment = childEnvironment,
            cageforge = CageforgeProcessPolicy.workspace(workspace.toFile(), readRoots),
        )
    }

    private fun assertSmokeResult(
        process: Process,
        workspace: Path,
        outside: Path,
        server: ServerSocket,
    ) {
        val output =
            process.inputStream
                .bufferedReader()
                .readLines()
                .joinToString("\n")
        assertTrue(output.contains("probe-ready"), output)
        assertTrue(output.contains("explicit-channel-value"), output)
        assertTrue(output.contains("outside-denied"), output)
        assertTrue(output.contains("network-denied"), output)
        assertTrue(output.contains("grandchild-ready"), output)
        assertTrue(Files.exists(workspace.resolve("child-created.txt")))
        assertTrue(Files.exists(workspace.resolve("grandchild-created.txt")))
        assertEquals(0, process.waitFor())
        assertTrue(!Files.exists(outside), "probe must not create files outside its workspace")
        server.soTimeout = 100
        val networkReached = runCatching { server.accept().use { true } }.getOrDefault(false)
        assertTrue(!networkReached, "probe must not reach a loopback listener")
    }

    private fun classpathRoots(classpath: String): List<File> =
        classpath
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)

    private fun awaitFile(path: Path) {
        repeat(200) {
            if (Files.isRegularFile(path)) return
            Thread.sleep(50)
        }
        assertTrue(Files.isRegularFile(path), "native kill probe did not become ready: $path")
    }

    private fun terminateAfterTest(process: Process) {
        process.destroyForcibly()
        assertTrue(
            process.waitFor(10, TimeUnit.SECONDS),
            "native smoke process did not terminate during cleanup",
        )
    }
}

private fun awaitAuthenticatedRegistration(
    service: KernelServiceImpl,
    process: Process,
    logs: Path,
    diagnosticPath: Path,
) {
    repeat(200) {
        if (service.registeredCount == 1) return
        if (!process.isAlive) return@repeat
        Thread.sleep(50)
    }
    if (service.registeredCount != 1) {
        Thread.sleep(250)
        val stdout = readDiagnosticLog(logs, "stdout")
        val stderr = readDiagnosticLog(logs, "stderr")
        val childDiagnostic =
            runCatching { Files.readString(diagnosticPath).takeLast(8_000) }
                .getOrDefault("<unavailable: $diagnosticPath>")
        error(
            "authenticated child registration did not complete: alive=${process.isAlive}, " +
                "exit=${runCatching { process.exitValue() }.getOrDefault("running")}\n" +
                "stdout=$stdout\nstderr=$stderr\nchild=$childDiagnostic",
        )
    }
}

private fun readDiagnosticLog(
    root: Path,
    streamName: String,
): String =
    runCatching {
        Files
            .walk(root)
            .use { paths ->
                val files = mutableListOf<Path>()
                paths.forEach { path ->
                    if (
                        path.fileName.toString().startsWith("$streamName.") ||
                        path.fileName.toString() == "$streamName.log"
                    ) {
                        files.add(path)
                    }
                }
                val contents = files.sorted().joinToString("\n") { Files.readString(it) }
                contents.takeLast(8_000)
            }
    }.getOrDefault("<unavailable: $root/$streamName>")

private data class AuthenticatedKernel(
    val server: BossIpcServer,
    val service: KernelServiceImpl,
    val tokens: ProcessTokenRegistry,
    val identity: IpcTlsIdentity,
)

private fun startAuthenticatedKernel(address: String): AuthenticatedKernel {
    val tokens = ProcessTokenRegistry()
    val identity = IpcTlsIdentity.create()
    val service = KernelServiceImpl()
    val server = BossIpcServer(address, tokens, identity).addService(service).start()
    return AuthenticatedKernel(server, service, tokens, identity)
}

private fun authenticatedIpcConfig(
    workspace: Path,
    processId: String,
    kernelAddress: String,
    processAddress: String,
): ProcessConfig {
    val classpath = System.getProperty("java.class.path")
    val javaExecutable = ProcessSpawner.findJavaExecutable()
    val readRoots =
        classpath
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File) +
            listOf(
                File(System.getProperty("java.home")),
                File(System.getProperty("java.home"), "conf").canonicalFile,
                File(System.getProperty("java.home"), "conf/security").canonicalFile,
                File(javaExecutable),
            )
    return ProcessConfig(
        processId = processId,
        processType = ProcessType.PLUGIN,
        displayName = "Cageforge authenticated IPC smoke",
        mainClass = CageforgeAuthenticatedIpcProbe::class.java.name,
        classpath = classpath,
        workDir = workspace.toFile(),
        jvmArgs = listOf("-Dio.netty.native.workdir=${workspace.toAbsolutePath()}"),
        environment = mapOf("BOSS_SMOKE_AUTH_DIAG" to workspace.resolve("auth-diag.txt").toString()),
        cageforge =
            CageforgeProcessPolicy.workspace(
                workspace.toFile(),
                readRoots,
                localIpcPaths =
                    listOf(
                        kernelAddress.removePrefix("unix://"),
                        processAddress.removePrefix("unix://"),
                    ),
            ),
    )
}

private fun createLocalIpcConfig(
    workspace: Path,
    socketRoot: Path,
    allowedSocket: Path,
    blockedSocket: Path,
    result: Path,
): ProcessConfig {
    val classpath = System.getProperty("java.class.path")
    val javaExecutable = ProcessSpawner.findJavaExecutable()
    val readRoots =
        classpath
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File) +
            listOf(
                File(System.getProperty("java.home")),
                File(System.getProperty("java.home"), "conf").canonicalFile,
                File(System.getProperty("java.home"), "conf/security").canonicalFile,
                File(javaExecutable),
                socketRoot.toFile(),
            )
    return ProcessConfig(
        processId = "native-security-ipc",
        processType = ProcessType.APP,
        displayName = "Cageforge local IPC smoke",
        mainClass = CageforgeLocalIpcProbe::class.java.name,
        classpath = classpath,
        workDir = workspace.toFile(),
        environment =
            mapOf(
                "BOSS_SMOKE_ALLOWED_SOCKET" to allowedSocket.toString(),
                "BOSS_SMOKE_BLOCKED_SOCKET" to blockedSocket.toString(),
                "BOSS_SMOKE_RESULT" to result.toString(),
            ),
        cageforge =
            CageforgeProcessPolicy.workspace(
                workspace.toFile(),
                readRoots,
                localIpcPaths = listOf(allowedSocket.toString()),
            ),
    )
}

private fun unixServer(path: Path): ServerSocketChannel =
    ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
        it.bind(UnixDomainSocketAddress.of(path))
    }

private fun readSocketByte(server: ServerSocketChannel?): Byte {
    requireNotNull(server) { "local IPC server was not created" }
    server.configureBlocking(true)
    return server.accept().use { client ->
        val buffer = ByteBuffer.allocate(1)
        while (buffer.hasRemaining()) check(client.read(buffer) >= 0) { "local IPC client closed early" }
        buffer.array().single()
    }
}

private fun assertThrowsUnsupportedLocalIpc(
    workspace: Path,
    endpoint: Path,
) {
    val error =
        runCatching {
            CageforgeProcessPolicy.workspace(
                workspace.toFile(),
                localIpcPaths = listOf(endpoint.toString()),
            )
        }.exceptionOrNull()
    assertTrue(
        error is IllegalArgumentException && error.message.orEmpty().contains("unsupported"),
        "Windows protected local IPC must fail closed: $error",
    )
}

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun awaitFileText(path: Path): String {
    repeat(200) {
        if (Files.isRegularFile(path)) return Files.readString(path)
        Thread.sleep(50)
    }
    return Files.readString(path)
}

/** Child JVM used only by the native smoke. */
object CageforgeNativeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = File(System.getProperty("user.dir"))
        val marker = workspace.toPath().resolve("child-created.txt")
        marker.parent?.createDirectories()
        Files.writeString(marker, "created")
        println("probe-ready")
        println(System.getenv("BOSS_SMOKE_VALUE"))

        val outside = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_OUTSIDE")))
        runCatching { Files.writeString(outside, "forbidden") }
            .onSuccess { println("outside-created") }
            .onFailure { println("outside-denied") }

        val port = requireNotNull(System.getenv("BOSS_SMOKE_PORT")).toInt()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            }
        }.onSuccess { println("network-allowed") }
            .onFailure { println("network-denied") }

        val grandchild =
            ProcessBuilder(
                requireNotNull(System.getenv("BOSS_SMOKE_JAVA")),
                "-cp",
                requireNotNull(System.getenv("BOSS_SMOKE_CLASSPATH")),
                CageforgeNativeGrandchild::class.java.name,
            ).inheritIO().start()
        check(grandchild.waitFor(10, TimeUnit.SECONDS)) { "grandchild did not exit" }
        check(grandchild.exitValue() == 0) { "grandchild exited ${grandchild.exitValue()}" }
    }
}

object CageforgeNativeGrandchild {
    @JvmStatic
    fun main(args: Array<String>) {
        val marker = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_GRANDCHILD")))
        Files.writeString(marker, "created")
        println("grandchild-ready")
    }
}

/** Child JVM used by the authenticated Cageforge IPC smoke. */
object CageforgeAuthenticatedIpcProbe {
    @JvmStatic
    fun main(args: Array<String>) =
        runCatching {
            runBlocking {
                val bootstrap = ChildProcessBootstrap()
                val manifest =
                    ProcessManifest
                        .newBuilder()
                        .setProcessId(bootstrap.processId)
                        .setDisplayName("Authenticated Cageforge IPC probe")
                        .setProcessType(ai.rever.boss.ipc.proto.ProcessType.PROCESS_TYPE_PLUGIN)
                        .setVersion("1.0.0")
                        .build()
                val connection = bootstrap.connect(manifest)
                try {
                    connection.processServer.addService(StateServiceImpl())
                    connection.startServer().awaitTermination()
                } finally {
                    connection.shutdown()
                }
            }
        }.onFailure { error ->
            System.getenv("BOSS_SMOKE_AUTH_DIAG")?.let { diagnostic ->
                Files.writeString(Path.of(diagnostic), error.stackTraceToString())
            }
        }.getOrThrow()
}

object CageforgeLocalIpcProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val allowed = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_ALLOWED_SOCKET")))
        val blocked = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_BLOCKED_SOCKET")))
        val result = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_RESULT")))
        val statuses =
            buildList {
                runCatching { connectAndWrite(allowed, 'a') }
                    .onSuccess { add("allowed-ipc-connected") }
                    .onFailure { add("allowed-ipc-denied: ${it::class.simpleName}: ${it.message}") }
                runCatching { connectAndWrite(blocked, 'b') }
                    .onSuccess { add("blocked-ipc-allowed") }
                    .onFailure { add("blocked-ipc-denied") }
            }
        Files.writeString(result, statuses.joinToString("\n"))
    }

    private fun connectAndWrite(
        path: Path,
        value: Char,
    ) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            channel.write(ByteBuffer.wrap(byteArrayOf(value.code.toByte())))
        }
    }
}

/** Parent used only by the native kill smoke. */
object CageforgeNativeKillProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val javaExecutable = requireNotNull(System.getenv("BOSS_SMOKE_JAVA"))
        val classpath = requireNotNull(System.getenv("BOSS_SMOKE_CLASSPATH"))
        val lateMarker = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_LATE_MARKER")))
        val ready = Path.of(requireNotNull(System.getenv("BOSS_SMOKE_KILL_READY")))
        ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            CageforgeNativeSleeper::class.java.name,
            lateMarker.toString(),
        ).inheritIO().start()
        Files.writeString(ready, "ready")
        Thread.sleep(Long.MAX_VALUE)
    }
}

object CageforgeNativeSleeper {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(1_000)
        Files.writeString(Path.of(args.single()), "late-descendant-write")
    }
}
