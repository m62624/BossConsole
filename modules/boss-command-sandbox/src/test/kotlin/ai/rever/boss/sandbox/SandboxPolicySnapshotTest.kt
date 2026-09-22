package ai.rever.boss.sandbox

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SandboxPolicySnapshotTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun command(argv: List<String> = listOf("node", "script.js")): SandboxCommand {
        val project = temporary.newFolder().toPath()
        val policy = project.resolve("cageforge.toml")
        Files.writeString(policy, "[profiles.node]\nworkspace_roots = { \".\" = true }\n")
        return SandboxCommand(project, policy, "node", argv)
    }

    @Test
    fun `preparation freezes both the file and caller owned arguments`() {
        val arguments = mutableListOf("node", "original.js")
        val command = command(arguments)
        val snapshot = SandboxPolicySnapshot.read(command)
        val original = snapshot.toml
        arguments[1] = "replacement.js"
        Files.writeString(command.policyFile, "[profiles.node]\nnetwork.mode = \"enabled\"\n")
        assertEquals(listOf("node", "original.js"), snapshot.argv)
        assertEquals(original, snapshot.toml)
        assertFailsWith<UnsupportedOperationException> { (snapshot.argv as MutableList)[0] = "other" }
        assertNotEquals(snapshot.digest, SandboxPolicySnapshot.read(command).digest)
    }

    @Test
    fun `approval identifies arguments and project as well as policy`() {
        val command = command()
        val original = SandboxPolicySnapshot.read(command)
        val otherArguments = SandboxPolicySnapshot.read(command.copy(argv = listOf("node", "other.js")))
        val otherProject = SandboxPolicySnapshot.read(command.copy(projectDirectory = temporary.newFolder().toPath()))
        assertNotEquals(original.digest, otherArguments.digest)
        assertNotEquals(original.digest, otherProject.digest)
        assertEquals(original.digest, SandboxPolicySnapshot.read(command).digest)
    }

    @Test
    fun `explicit empty arguments are retained and control characters cannot inject tables`() {
        val arguments = listOf("node", "", "\n[profiles.evil]\n", "C:\\tools\\cli", "\"")
        val snapshot = SandboxPolicySnapshot.read(command(arguments))
        val encoded = "args = [\"\", \"\\u000a[profiles.evil]\\u000a\", \"C:\\\\tools\\\\cli\", \"\\\"\"]"
        assertTrue(snapshot.toml.contains(encoded))
        assertEquals("\"\\u0009\\u007f\"", tomlString("\t\u007f"))
    }

    @Test
    fun `a missing policy and invalid commands fail before any native work`() {
        val command = command()
        assertFailsWith<IllegalArgumentException> { SandboxPolicySnapshot.read(command.copy(argv = emptyList())) }
        assertFailsWith<IllegalArgumentException> { SandboxPolicySnapshot.read(command.copy(argv = listOf(" "))) }
        assertFailsWith<IllegalArgumentException> {
            SandboxPolicySnapshot.read(command.copy(argv = listOf("node", "a\u0000b")))
        }
        assertFailsWith<IllegalArgumentException> { SandboxPolicySnapshot.read(command.copy(profile = "x\n")) }
        assertFailsWith<IllegalArgumentException> {
            SandboxPolicySnapshot.read(
                command.copy(profile = SandboxPolicySnapshot.LAUNCH_PROFILE),
            )
        }
        Files.delete(command.policyFile)
        assertFailsWith<java.nio.file.NoSuchFileException> { SandboxPolicySnapshot.read(command) }
    }

    @Test
    fun `policy reads and command size are bounded`() {
        val command = command()
        Files.write(command.policyFile, ByteArray(SandboxPolicySnapshot.MAX_POLICY_BYTES + 1) { 32 })
        assertFailsWith<IllegalArgumentException> { SandboxPolicySnapshot.read(command) }
        assertFailsWith<IllegalArgumentException> {
            SandboxPolicySnapshot.read(command.copy(argv = listOf("node", "x".repeat(128 * 1024))))
        }
    }

    @Test
    fun `invalid UTF-8 policy is rejected instead of silently rewritten`() {
        val command = command()
        Files.write(command.policyFile, byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<CharacterCodingException> { SandboxPolicySnapshot.read(command) }
    }

    @Test
    fun `wrong approval cannot launch or consume a plan and successful claim cannot be replayed`() {
        val snapshot = SandboxPolicySnapshot.read(command())
        val plan = SandboxSessionPlan(snapshot, "native-permissions", "{}")
        assertFailsWith<IllegalArgumentException> { CageforgeSessionLauncher().launch(plan, "wrong") }
        plan.claim(plan.approvalDigest)
        assertFailsWith<IllegalStateException> { plan.claim(plan.approvalDigest) }
    }
}
