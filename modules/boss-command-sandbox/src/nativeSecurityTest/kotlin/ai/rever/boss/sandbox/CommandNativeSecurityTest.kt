package ai.rever.boss.sandbox

import ai.cageforge.CageforgeConfigurationException
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
    private val probeClasspath =
        System.getProperty("boss.sandbox.probe.classpath", System.getProperty("java.class.path"))

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
            val plan = launcher.prepare(command)
            // A disk edit cannot replace the already reviewed command/policy snapshot.
            Files.writeString(command.policyFile, "malformed replacement")
            launcher.launch(plan, plan.approvalDigest).use { session ->
                session.process.outputStream.close()
                assertTrue(session.process.waitFor(30, TimeUnit.SECONDS), "Native probe timed out")
                val output =
                    session.process.inputStream
                        .bufferedReader()
                        .readText()
                val errors =
                    session.process.errorStream
                        .bufferedReader()
                        .readText()
                assertEquals(0, session.process.exitValue(), errors)
                assertTrue(output.contains("SECURITY_OK:root"), output)
                assertTrue(output.contains("SECURITY_OK:descendant"), output)
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
        val plan = launcher.prepare(command(project, listOf("tree", project.toString())))
        val heartbeat = project.resolve("heartbeat")
        launcher.launch(plan, plan.approvalDigest).use {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while ((!Files.exists(heartbeat) || Files.size(heartbeat) < 2) && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(Files.exists(heartbeat) && Files.size(heartbeat) >= 2, "Descendant did not start")
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
        val roots = (probeClasspath.split(File.pathSeparator).map { Path.of(it).toRealPath() } + javaHome).distinct()
        val rules = roots.joinToString(",\n") { "{ target = \"absolute\", path = ${quote(it)}, access = \"read\" }" }
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
            [profiles.base.platforms.macos.runtime]
            executable_roots = [${quote(javaHome)}]
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
