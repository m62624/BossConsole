package ai.rever.boss.components.plugin

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityRequiredPluginTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing marker keeps ordinary plugin behavior`() {
        val jar = writeJar("{}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertEquals(SecurityRequirement.OPTIONAL, result.getOrThrow())
    }

    @Test
    fun `explicit marker requires protected launch`() {
        val jar = writeJar("{\"securityRequired\":true}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertEquals(SecurityRequirement.REQUIRED, result.getOrThrow())
    }

    @Test
    fun `nested marker requires protected launch`() {
        val jar = writeJar("{\"security\":{\"required\":true}}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertEquals(SecurityRequirement.REQUIRED, result.getOrThrow())
    }

    @Test
    fun `conflicting marker fields fail closed`() {
        val jar = writeJar("{\"securityRequired\":true,\"security\":{\"required\":false}}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `malformed marker fails closed`() {
        val jar = writeJar("{\"securityRequired\":\"true\"}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `sandbox capabilities require the protected launch marker`() {
        val jar = writeJar("{\"sandbox\":{\"network\":[\"https://example.test\"]}}")

        val result = SecurityRequiredPlugin.readRequirement(jar.toString())

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    private fun writeJar(manifest: String): Path {
        val jar = tempDir.resolve("plugin.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            output.write(manifest.toByteArray())
            output.closeEntry()
        }
        return jar
    }
}
