package ai.rever.boss.sandbox

import ai.cageforge.Cageforge
import ai.cageforge.CageforgeConfigurationException
import ai.cageforge.PermissionApprover
import ai.cageforge.RuntimeContext
import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
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

    @Before
    fun ensureWindowsSetup() {
        if (WindowsSetup.isSupported() && WindowsSetup.status() == WindowsSetupState.MISSING) {
            WindowsSetup.install()
        }
    }

    @After
    fun restoreWindowsSetupBeforeTemporaryCleanup() {
        if (WindowsSetup.isSupported() && WindowsSetup.status() == WindowsSetupState.READY) {
            WindowsSetup.uninstall()
        }
    }

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
        val approvedFile = Files.writeString(outside.resolve("approved"), "approved-input")
        assertEquals("host-secret", Files.readString(outside.resolve("secret")))
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { server.accept().close() }
            val arguments =
                listOf(
                    "root",
                    project.toString(),
                    outside.toString(),
                    server.localPort.toString(),
                    approvedFile.toString(),
                )
            val command = command(project, arguments, listOf(approvedFile))
            CommandNativeTestRunner.stage("preparing root policy")
            val plan = launcher.prepare(command)
            assertFalse(
                plan.permissionsJson.contains(outside.resolve("secret").toString()),
                "The host permission request must not grant the denied secret: ${plan.permissionsJson}",
            )
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
        assertEquals("allowed", Files.readString(project.resolve("root-allowed")))
        assertEquals("allowed", Files.readString(project.resolve("descendant-allowed")))
        assertFalse(Files.exists(outside.resolve("root-escape")))
        assertFalse(Files.exists(outside.resolve("descendant-escape")))
        assertEquals("approved-input", Files.readString(approvedFile))
    }

    @Test(timeout = 90000)
    fun closingSessionTerminatesRunningDescendant() {
        val project = temporary.newFolder("tree").toPath()
        Files.createDirectory(project.resolve(".git"))
        CommandNativeTestRunner.stage("preparing descendant termination policy")
        val heartbeat = project.resolve("heartbeat")
        runBlocking {
            val service = SandboxSessionService()
            try {
                val start =
                    async {
                        service.start(
                            command(project, listOf("tree", project.toString())),
                            "Native descendant cleanup test",
                        )
                    }
                val review =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                assertTrue(service.sessions.value.isEmpty(), "Review must precede native launch")
                assertFalse(Files.exists(heartbeat), "No process may run before approval")
                CommandNativeTestRunner.stage("approving and launching heartbeat boundary")
                service.consent.decide(review.id, SandboxConsentChoice.ONCE)
                val entry = requireNotNull(start.await())
                CommandNativeTestRunner.stage("waiting for descendant heartbeat")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while ((!Files.exists(heartbeat) || Files.size(heartbeat) < 2) && System.nanoTime() < deadline) {
                    Thread.sleep(25)
                }
                assertTrue(Files.exists(heartbeat) && Files.size(heartbeat) >= 2, "Descendant did not start")
                CommandNativeTestRunner.stage("closing heartbeat boundary through app service")
                service.shutdown()
                assertFalse(entry.session.output.value.running)
            } finally {
                service.shutdown()
            }
        }
        val stoppedSize = Files.size(heartbeat)
        Thread.sleep(300)
        assertEquals(stoppedSize, Files.size(heartbeat), "Descendant survived boundary termination")
    }

    @Test(timeout = 90000)
    fun cageforgeEscalationLaunchesOnlyAfterAnExplicitAdditionalGrant() {
        val project = temporary.newFolder("escalation-project").toPath()
        Files.createDirectory(project.resolve(".git"))
        val privateDirectory = temporary.newFolder("escalation-private").toPath()
        val approvedFile = Files.writeString(privateDirectory.resolve("approved-input"), "approved")
        assertEquals("approved", Files.readString(approvedFile))
        val command = command(project, listOf("baseline", approvedFile.toString()))
        val toml = escalationPolicy(command, project)
        val context = RuntimeContext(project)
        Cageforge.checkToml(toml, "escalation-test", context)
        val baseRequest =
            Cageforge.permissionRequest(
                toml,
                "escalation-test",
                context,
                toolId = "boss-command-session",
            )
        val baseGrant = PermissionApprover().approve(baseRequest)

        Cageforge
            .fromToml(
                toml,
                "escalation-test",
                context,
                baseGrant,
                baseRequest,
            ).use { runtime ->
                runtime.launchProcess().use { firstLaunch ->
                    assertEquals(0, waitForExit(firstLaunch))
                }

                runtime
                    .requestEscalation(
                        listOf("read" to approvedFile.toString()),
                        emptyList(),
                        "Read the explicitly approved input file",
                    ).use { escalation ->
                        assertEquals(listOf("read" to approvedFile.toString()), escalation.filesystem)
                        assertTrue(escalation.network.isEmpty())
                        val escalationGrant = PermissionApprover().approveEscalation(escalation)
                        val argv =
                            listOf(
                                executable.toString(),
                                "-cp",
                                probeClasspath,
                                CommandSecurityProbe::class.java.name,
                                "escalated",
                                approvedFile.toString(),
                            )
                        runtime.launchEscalated(escalation, escalationGrant, argv).use { escalated ->
                            val output = requireNotNull(escalated.stdout).bufferedReader().readText()
                            assertEquals(0, escalated.waitFor().exitCode)
                            assertTrue(output.contains("ESCALATION_OK:approved"), output)
                        }
                    }
            }
        assertEquals("approved", Files.readString(approvedFile))
    }

    @Test
    fun invalidInheritanceFailsBeforeLaunch() {
        val project = temporary.newFolder("invalid").toPath()
        val command = command(project, listOf("root"))
        Files.writeString(command.policyFile, "[profiles.cli]\ninherits = [\"missing\"]\n")
        assertFailsWith<CageforgeConfigurationException> { launcher.prepare(command) }
        assertFalse(Files.exists(project.resolve("root-allowed")))
    }

    @Test(timeout = 120000)
    fun serviceEscalationRequiresConsentAndKeepsParentRunning() =
        runBlocking {
            val project = temporary.newFolder("service-escalation").toPath()
            Files.createDirectory(project.resolve(".git"))
            val privateDirectory = temporary.newFolder("service-private").toPath()
            val approvedFile = Files.writeString(privateDirectory.resolve("approved-input"), "approved")
            val parentCommand = command(project, listOf("guardian", project.toString(), approvedFile.toString()))
            val service = SandboxSessionService()
            try {
                val start = async { service.start(parentCommand, "Start requesting agent") }
                val initial =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                service.consent.decide(initial.id, SandboxConsentChoice.ONCE)
                val parent = requireNotNull(start.await())
                assertParentConfined(parent)
                val additional =
                    SandboxEscalation(
                        parentCommand.argv.dropLast(3) + listOf("escalated-held", approvedFile.toString()),
                        listOf("read" to approvedFile.toString()),
                        emptyList(),
                        "Read approved input for one command",
                    )
                val denied = async { service.startEscalated(parent.id, additional) }
                val denial =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                service.consent.decide(denial.id, SandboxConsentChoice.DENY)
                assertEquals(null, denied.await())
                assertEquals(1, service.sessions.value.size)
                val launch = async { service.startEscalated(parent.id, additional) }
                val review =
                    service.consent.requests
                        .first { it.isNotEmpty() }
                        .single()
                assertEquals(additional.argv, review.review.argv)
                assertTrue(review.review.permissionsJson.contains("approved-input"))
                service.consent.decide(review.id, SandboxConsentChoice.ONCE)
                val elevated = requireNotNull(launch.await())
                withTimeout(20000) {
                    elevated.session.output.first { it.stdout.text.contains("ESCALATION_OK:approved") || !it.running }
                }
                assertParentConfined(parent)
                elevated.session.closeInput()
                val output = elevated.session.awaitCompletion()
                assertEquals(0, output.exitCode, output.stderr.text)
                assertTrue(output.stdout.text.contains("ESCALATION_OK:approved"), output.stdout.text)
                assertTrue(parent.session.output.value.running, "Additional command must not restart the agent")
                assertParentConfined(parent)
            } finally {
                service.shutdown()
            }
        }

    private suspend fun assertParentConfined(parent: SandboxSessionEntry) {
        val before = parent.session.output.value.stdout.text
        parent.session.sendInput("check\n")
        val output =
            withTimeout(20000) {
                parent.session.output.first { it.stdout.text != before || !it.running }
            }
        assertTrue(output.running, output.stderr.text)
        assertTrue(
            output.stdout.text
                .removePrefix(before)
                .contains("PARENT_DENIED"),
            output.stdout.text,
        )
    }

    private fun command(
        project: Path,
        arguments: List<String>,
        additionalReadPaths: List<Path> = emptyList(),
    ): SandboxCommand {
        val classpathRoots = probeClasspath.split(File.pathSeparator).map { Path.of(it).toRealPath() }
        val runtimeRoots =
            if (File.separatorChar == '\\') {
                val bin = javaHome.resolve("bin")
                val binLibraries =
                    Files.list(bin).use { paths ->
                        paths.filter { it.fileName.toString().endsWith(".dll", ignoreCase = true) }.toList()
                    }
                binLibraries +
                    listOf(
                        bin.resolve("server/jvm.dll"),
                        javaHome.resolve("lib/jvm.cfg"),
                        javaHome.resolve("lib/modules"),
                    ).filter(Files::isRegularFile)
            } else {
                listOf(javaHome)
            }
        val roots = (classpathRoots + runtimeRoots + additionalReadPaths).distinct()
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

    private fun quote(path: Path): String = quoteText(path.toString())
}

private fun waitForExit(process: Process): Int {
    if (!process.waitFor(30, TimeUnit.SECONDS)) return -1
    return process.exitValue()
}

private fun escalationPolicy(
    command: SandboxCommand,
    project: Path,
): String {
    val policy = Files.readString(command.policyFile)
    val args = command.argv.drop(1).joinToString(", ", transform = ::quoteText)
    return policy +
        """

        [profiles.escalation-test]
        inherits = ["${command.profile}"]
        [profiles.escalation-test.approval]
        mode = "preflight-and-on-demand"
        persistence = "session"
        [profiles.escalation-test.command]
        program = ${quoteText(command.argv.first())}
        args = [$args]
        working_directory = ${quoteText(project.toString())}
        [profiles.escalation-test.command.stdio]
        stdin = "pipe"
        stdout = "pipe"
        stderr = "pipe"
        """.trimIndent()
}

private fun quoteText(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
