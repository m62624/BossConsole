package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.RuntimeContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CageforgeProcessPolicyTest {
    @Test
    fun `workspace preset uses Cageforge selectors and host approved roots`() {
        val workspace = Files.createTempDirectory("cageforge-policy-workspace-")
        val runtimeRoot = Files.createTempDirectory("cageforge-policy-runtime-")
        try {
            val policy =
                CageforgeProcessPolicy.workspace(
                    workspace.toFile(),
                    listOf(runtimeRoot.toFile()),
                )

            assertTrue("target = \"minimal\"" in policy.toml)
            assertTrue("target = \"workspace-root\"" in policy.toml)
            assertTrue("target = \"absolute\"" in policy.toml)
            val escapedRuntimeRoot = runtimeRoot.toString().replace("\\", "\\\\").replace("\"", "\\\"")
            assertTrue(escapedRuntimeRoot in policy.toml)
            assertTrue("/dev/.cageforge-runtime" !in policy.toml)

            Cageforge.checkToml(
                policy.toml,
                policy.profileName,
                RuntimeContext(currentDirectory = workspace.toAbsolutePath()),
            )
        } finally {
            runtimeRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing host approved root fails closed`() {
        val workspace = Files.createTempDirectory("cageforge-policy-workspace-")
        try {
            assertFailsWith<IllegalArgumentException> {
                CageforgeProcessPolicy.workspace(
                    workspace.toFile(),
                    listOf(File(workspace.toFile(), "missing-runtime-root")),
                )
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relative workspace and read roots are rejected before normalization`() {
        assertFailsWith<IllegalArgumentException> {
            CageforgeProcessPolicy.workspace(File("relative-workspace"))
        }

        val workspace = Files.createTempDirectory("cageforge-policy-workspace-")
        try {
            assertFailsWith<IllegalArgumentException> {
                CageforgeProcessPolicy.workspace(workspace.toFile(), listOf(File("relative-runtime")))
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `policy ceiling rejects a workspace outside the approved root`() {
        val parent = Files.createTempDirectory("cageforge-ceiling-")
        val approved = Files.createDirectories(parent.resolve("approved"))
        val nested = Files.createDirectories(approved.resolve("nested"))
        val outside = Files.createDirectories(parent.resolve("outside"))
        try {
            val ceiling = CageforgePolicyCeiling.forWorkspace(approved.toFile())

            assertTrue(ceiling.policyFor(nested.toFile()).toml.contains("workspace-root"))
            assertFailsWith<IllegalArgumentException> {
                ceiling.policyFor(outside.toFile())
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bootstrap environment protocol is portable and does not require a path`() {
        val ready = "BOSS-CAGEFORGE-BOOTSTRAP-READY\n".toByteArray(Charsets.US_ASCII)
        val encoded = ByteArrayOutputStream()
        ProtectedEnvironmentChannel.send(
            ByteArrayInputStream(ready),
            encoded,
            mapOf("BOSS_TOKEN" to "not-written-to-toml", "BOSS_EMPTY" to ""),
            timeoutMs = 1_000,
        )

        val decoded = ProtectedEnvironmentChannel.readEnvironment(ByteArrayInputStream(encoded.toByteArray()))
        assertEquals(
            mapOf("BOSS_EMPTY" to "", "BOSS_TOKEN" to "not-written-to-toml"),
            decoded,
        )
    }
}
