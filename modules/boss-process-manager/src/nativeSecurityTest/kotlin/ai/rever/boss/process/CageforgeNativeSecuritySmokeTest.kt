package ai.rever.boss.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/** Representative BOSS-side native smoke; the complete backend suite remains in Cageforge CI. */
class CageforgeNativeSecuritySmokeTest {
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
                managed?.destroyForcibly()
                managed?.process?.onExit()?.join()
            }
        } finally {
            server.close()
            logs.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            outside.parent.toFile().deleteRecursively()
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
