package ai.rever.boss.sandbox

import ai.cageforge.CageforgeConfigurationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandNativeSecurityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val launcher = CageforgeSessionLauncher()
    private val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
    private val executable = javaHome.resolve("bin/" + if (File.separatorChar == '\\') "java.exe" else "java")

    // The pure-Java child needs only its classes, not the host's JUnit/Kotlin/JNI jars.
    private val probeClasspath =
        Path
            .of(
                CommandSecurityProbe::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).toRealPath()
            .toString()

    @Test(timeout = 90000)
    fun rootAndDescendantsEnforceFilesystemAndNetworkPolicy() {
        val project = temporary.newFolder("project").toPath()
        Files.createDirectory(project.resolve(".git"))
        val outside = temporary.newFolder("private").toPath()
        Files.writeString(outside.resolve("secret"), "host-secret")
        assertEquals("host-secret", Files.readString(outside.resolve("secret")))
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { server.accept().close() }
            val arguments = listOf("root", project.toString(), outside.toString(), server.localPort.toString())
            val command = command(project, arguments)
            CommandNativeTestRunner.stage("preparing root policy")
            val plan = launcher.prepare(command)
            // A disk edit cannot replace the already reviewed command/policy snapshot.
            Files.writeString(command.policyFile, "malformed replacement")
            runBlocking {
                CommandNativeTestRunner.stage("launching root boundary")
                val session = launcher.launch(plan, plan.approvalDigest).manage()
                try {
                    CommandNativeTestRunner.stage("waiting for root and descendant enforcement probes")
                    session.closeInput()
                    val output = session.awaitCompletion()
                    assertEquals(null, output.failure, output.failure?.stackTraceToString())
                    assertEquals(0, output.exitCode, output.stderr.text)
                    assertTrue(output.stdout.text.contains("SECURITY_OK:root"), output.stdout.text)
                    assertTrue(output.stdout.text.contains("SECURITY_OK:descendant"), output.stdout.text)
                } finally {
                    CommandNativeTestRunner.stage("closing root boundary")
                    session.stop()
                }
            }
        }
        assertTrue(Files.exists(project.resolve("root-allowed")))
        assertTrue(Files.exists(project.resolve("descendant-allowed")))
        assertFalse(Files.exists(outside.resolve("root-escape")))
        assertFalse(Files.exists(outside.resolve("descendant-escape")))
    }

    @Test(timeout = 90000)
    fun closingSessionTerminatesRunningDescendant() {
        val project = temporary.newFolder("tree").toPath()
        Files.createDirectory(project.resolve(".git"))
        CommandNativeTestRunner.stage("preparing descendant termination policy")
        val plan = launcher.prepare(command(project, listOf("tree", project.toString())))
        val heartbeat = project.resolve("heartbeat")
        CommandNativeTestRunner.stage("launching heartbeat boundary")
        launcher.launch(plan, plan.approvalDigest).use {
            CommandNativeTestRunner.stage("waiting for descendant heartbeat")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while ((!Files.exists(heartbeat) || Files.size(heartbeat) < 2) && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(Files.exists(heartbeat) && Files.size(heartbeat) >= 2, "Descendant did not start")
            CommandNativeTestRunner.stage("closing heartbeat boundary")
        }
        val stoppedSize = Files.size(heartbeat)
        Thread.sleep(300)
        assertEquals(stoppedSize, Files.size(heartbeat), "Descendant survived boundary termination")
    }

    @Test
    fun invalidInheritanceFailsBeforeLaunch() {
        val project = temporary.newFolder("invalid").toPath()
        val command = command(project, listOf("root"))
        Files.writeString(command.policyFile, "[profiles.cli]\ninherits = [\"missing\"]\n")
        assertFailsWith<CageforgeConfigurationException> { launcher.prepare(command) }
        assertFalse(Files.exists(project.resolve("root-allowed")))
    }

    private fun command(
        project: Path,
        arguments: List<String>,
    ): SandboxCommand {
        val classpathRoots = probeClasspath.split(File.pathSeparator).map { Path.of(it).toRealPath() }
        val roots = (classpathRoots + listOf(javaHome)).distinct()
        val rules = roots.joinToString(",\n") { "{ target = \"absolute\", path = ${quote(it)}, access = \"read\" }" }
        // Platform overlays are validated using their target's path syntax, even on another OS.
        val macosRuntime =
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                "[profiles.base.platforms.macos.runtime]\nexecutable_roots = [${quote(javaHome)}]"
            } else {
                ""
            }
        val policy = project.resolve("cageforge.toml")
        Files.writeString(
            policy,
            """
            [profiles.base]
            workspace_roots = { "." = true }
            [profiles.base.filesystem]
            mode = "restricted"
            rules = [
              { target = "minimal", access = "read" },
              { target = "workspace-root", access = "write" },
              $rules
            ]
            [profiles.base.network]
            mode = "disabled"
            [profiles.base.command]
            program = ${quote(executable)}
            [profiles.base.command.environment]
            inherit = "core"
            set = { BOSS_SANDBOX_VALUE = "parent" }
            $macosRuntime
            [profiles.cli]
            inherits = ["base"]
            [profiles.cli.command.environment]
            set = { BOSS_SANDBOX_VALUE = "child" }
            """.trimIndent(),
        )
        val prefix = listOf(executable.toString(), "-cp", probeClasspath, CommandSecurityProbe::class.java.name)
        val argv = prefix + arguments
        return SandboxCommand(project, policy, "cli", argv)
    }

    private fun quote(path: Path): String = "\"${path.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
