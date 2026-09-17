package ai.rever.boss.components.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

/** JVM implementation of the host-owned marker preflight. */
internal actual object SecurityRequiredPlugin {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the marker without loading any plugin class. A missing marker means ordinary plugin
     * behavior; malformed or unreadable plugin artifacts are rejected by the normal manifest
     * validation path before they can be launched.
     */
    actual fun isMarked(jarPath: String): Boolean =
        runCatching {
            JarFile(File(jarPath)).use { jar ->
                val entry = jar.getJarEntry("META-INF/boss-plugin/plugin.json") ?: return@use false
                val root =
                    jar.getInputStream(entry).bufferedReader().use { json.parseToJsonElement(it.readText()) }
                val objectRoot = root.jsonObject
                objectRoot["securityRequired"]?.jsonPrimitive?.booleanOrNull == true ||
                    objectRoot["security"]
                        ?.jsonObject
                        ?.get("required")
                        ?.jsonPrimitive
                        ?.booleanOrNull == true
            }
        }.getOrDefault(false)
}
