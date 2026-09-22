package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.launchpad.DevPluginArtifacts
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginSandboxRequestTest {
    @Test
    fun `reads bounded capability request from plugin manifest`() {
        val jar =
            manifestJar(
                """
            {
              "securityRequired": true,
              "sandbox": {
                "filesystem": [{"path": "${'$'}{workspace}/models", "access": "read"}],
                "network": ["https://api.example.test"],
                "localIpc": ["unix:///run/boss/plugin.sock"]
              }
            }
                """,
            )
        try {
            val request = PluginSandboxRequestReader.readFromJar(jar.path)

            assertEquals(
                listOf(PluginSandboxFilesystemRequest("${'$'}{workspace}/models", "read")),
                request.filesystem,
            )
            assertEquals(listOf("https://api.example.test"), request.network)
            assertEquals(listOf("unix:///run/boss/plugin.sock"), request.localIpc)
            assertEquals(3, request.capabilities().size)
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `rejects malformed and duplicate capability declarations`() {
        val jar =
            manifestJar(
                """
            {
              "securityRequired": true,
              "sandbox": {
                "filesystem": [
                  {"path": "/tmp/plugin-data", "access": "write"},
                  {"path": "/tmp/plugin-data", "access": "write"}
                ]
              }
            }
                """,
            )
        try {
            assertFailsWith<IllegalArgumentException> {
                PluginSandboxRequestReader.readFromJar(jar.path)
            }
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `rejects one filesystem path requested with conflicting access`() {
        val jar =
            manifestJar(
                """
            {
              "securityRequired": true,
              "sandbox": {
                "filesystem": [
                  {"path": "/tmp/plugin-data", "access": "read"},
                  {"path": "/tmp/plugin-data", "access": "write"}
                ]
              }
            }
                """,
            )
        try {
            assertFailsWith<IllegalArgumentException> {
                PluginSandboxRequestReader.readFromJar(jar.path)
            }
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `empty or absent sandbox request does not prompt`() {
        val emptyJar = manifestJar("{\"securityRequired\":true}")
        try {
            assertTrue(PluginSandboxRequestReader.readFromJar(emptyJar.path).isEmpty)
        } finally {
            emptyJar.delete()
        }
    }

    @Test
    fun `rejects an oversized plugin manifest before parsing capabilities`() {
        val oversizedManifest =
            """{"sandbox":{"network":["${"x".repeat(DevPluginArtifacts.MAX_MANIFEST_BYTES)}"]}}"""
        val jar = manifestJar(oversizedManifest)
        try {
            assertFailsWith<IllegalArgumentException> {
                PluginSandboxRequestReader.readFromJar(jar.path)
            }
        } finally {
            jar.delete()
        }
    }

    private fun manifestJar(manifest: String): File =
        File.createTempFile("plugin-sandbox-request-", ".jar").also { jar ->
            JarOutputStream(jar.outputStream()).use { output ->
                output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
                output.write(manifest.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
}
