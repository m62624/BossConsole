package ai.rever.boss.components.plugin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginSandboxCapabilityValidatorTest {
    @Test
    fun `workspace placeholders are resolved before approval`() {
        val workspace = Files.createTempDirectory("protected-plugin-workspace-").toFile()
        val models = workspace.resolve("models").apply { mkdirs() }
        try {
            val validated =
                validatePluginSandboxRequest(
                    request =
                        PluginSandboxRequest(
                            filesystem =
                                listOf(
                                    PluginSandboxFilesystemRequest("${'$'}{workspace}/models", "read"),
                                ),
                            network = emptyList(),
                            localIpc = emptyList(),
                        ),
                    securityRequired = true,
                    workspace = workspace,
                    protectedRoots = ProtectedRoots(emptyList(), emptyList()),
                    hostEndpoints = emptyList(),
                )

            assertEquals(
                models.canonicalPath,
                validated.request
                    .filesystem
                    .single()
                    .path,
            )
            assertEquals(
                models.canonicalFile,
                validated.requestedReadOnlyRoots.single(),
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `new writable workspace paths remain inside the host ceiling`() {
        val workspace = Files.createTempDirectory("protected-plugin-workspace-").toFile()
        try {
            val validated =
                validatePluginSandboxRequest(
                    request =
                        PluginSandboxRequest(
                            filesystem =
                                listOf(
                                    PluginSandboxFilesystemRequest(
                                        "${'$'}{workspace}/output/result.json",
                                        "write",
                                    ),
                                ),
                            network = emptyList(),
                            localIpc = emptyList(),
                        ),
                    securityRequired = true,
                    workspace = workspace,
                    protectedRoots = ProtectedRoots(emptyList(), emptyList()),
                    hostEndpoints = emptyList(),
                )

            assertEquals(
                workspace.resolve("output/result.json").canonicalPath,
                validated.request
                    .filesystem
                    .single()
                    .path,
            )
            assertEquals(emptyList(), validated.requestedReadOnlyRoots)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `filesystem requests outside the host ceiling fail before approval`() {
        val workspace = Files.createTempDirectory("protected-plugin-workspace-").toFile()
        val outside = Files.createTempFile("protected-plugin-outside-", ".txt").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                validatePluginSandboxRequest(
                    request =
                        PluginSandboxRequest(
                            filesystem = listOf(PluginSandboxFilesystemRequest(outside.path, "read")),
                            network = emptyList(),
                            localIpc = emptyList(),
                        ),
                    securityRequired = true,
                    workspace = workspace,
                    protectedRoots = ProtectedRoots(emptyList(), emptyList()),
                    hostEndpoints = emptyList(),
                )
            }
        } finally {
            outside.delete()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `network requests fail closed against the host ceiling`() {
        val workspace = Files.createTempDirectory("protected-plugin-workspace-").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                validatePluginSandboxRequest(
                    request = PluginSandboxRequest(emptyList(), listOf("https://example.test"), emptyList()),
                    securityRequired = true,
                    workspace = workspace,
                    protectedRoots = ProtectedRoots(emptyList(), emptyList()),
                    hostEndpoints = emptyList(),
                )
            }
        } finally {
            workspace.deleteRecursively()
        }
    }
}
