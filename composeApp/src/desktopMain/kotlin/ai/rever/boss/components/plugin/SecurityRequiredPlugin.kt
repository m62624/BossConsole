package ai.rever.boss.components.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

/** JVM implementation of the host-owned marker preflight. */
internal actual object SecurityRequiredPlugin {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the marker without loading any plugin class. */
    actual fun readRequirement(jarPath: String): Result<SecurityRequirement> =
        runCatching {
            JarFile(File(jarPath)).use { jar ->
                val entry =
                    jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                        ?: return@use SecurityRequirement.OPTIONAL
                val root =
                    jar.getInputStream(entry).bufferedReader().use { json.parseToJsonElement(it.readText()) }
                val objectRoot = root.jsonObject
                val topLevel = readBoolean(objectRoot, "securityRequired")
                val nested =
                    objectRoot["security"]?.let { security ->
                        require(security is JsonObject) { "Plugin security marker must be an object" }
                        readBoolean(security, "required")
                    }
                require(objectRoot["sandbox"] == null || topLevel == true || nested == true) {
                    "Plugin sandbox capabilities require securityRequired=true"
                }
                require(topLevel == null || nested == null || topLevel == nested) {
                    "Plugin security markers disagree"
                }
                when {
                    topLevel == true || nested == true -> SecurityRequirement.REQUIRED
                    else -> SecurityRequirement.OPTIONAL
                }
            }
        }

    private fun readBoolean(
        root: JsonObject,
        name: String,
    ): Boolean? {
        val value = root[name] ?: return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString && primitive.booleanOrNull != null) {
            "Plugin security marker '$name' must be a boolean"
        }
        return primitive.booleanOrNull
    }
}
