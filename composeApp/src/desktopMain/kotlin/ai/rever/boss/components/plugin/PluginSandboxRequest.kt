package ai.rever.boss.components.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.jar.JarFile

internal data class PluginSandboxFilesystemRequest(
    val path: String,
    val access: String,
)

internal data class PluginSandboxRequest(
    val filesystem: List<PluginSandboxFilesystemRequest>,
    val network: List<String>,
    val localIpc: List<String>,
) {
    val isEmpty: Boolean
        get() = filesystem.isEmpty() && network.isEmpty() && localIpc.isEmpty()

    fun capabilities(): List<PluginSandboxCapability> =
        buildList {
            filesystem.forEach { capability ->
                add(PluginSandboxCapability("filesystem", capability.path, capability.access))
            }
            network.forEach { endpoint ->
                add(PluginSandboxCapability("network", endpoint))
            }
            localIpc.forEach { endpoint ->
                add(PluginSandboxCapability("local IPC", endpoint))
            }
        }

    companion object {
        val EMPTY = PluginSandboxRequest(emptyList(), emptyList(), emptyList())
    }
}

internal object PluginSandboxRequestReader {
    private const val MAX_CAPABILITIES = 32
    private const val MAX_VALUE_LENGTH = 4_096
    private val json = Json { ignoreUnknownKeys = true }

    fun readFromJar(jarPath: String): PluginSandboxRequest =
        JarFile(File(jarPath)).use { jar ->
            val entry =
                jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                    ?: return PluginSandboxRequest.EMPTY
            val root =
                jar.getInputStream(entry).bufferedReader().use { reader ->
                    json.parseToJsonElement(reader.readText()).jsonObject
                }
            read(root)
        }

    private fun read(root: JsonObject): PluginSandboxRequest {
        val sandbox = root["sandbox"] ?: return PluginSandboxRequest.EMPTY
        require(sandbox is JsonObject) { "Plugin sandbox request must be an object" }
        val filesystem = readFilesystem(sandbox["filesystem"])
        val network = readStrings(sandbox["network"], "sandbox.network")
        val localIpc = readStrings(sandbox["localIpc"], "sandbox.localIpc")
        require(filesystem.map { it.path }.distinct().size == filesystem.size) {
            "sandbox.filesystem must not contain duplicate entries"
        }
        require(network.distinct().size == network.size) {
            "sandbox.network must not contain duplicate entries"
        }
        require(localIpc.distinct().size == localIpc.size) {
            "sandbox.localIpc must not contain duplicate entries"
        }
        require(filesystem.size + network.size + localIpc.size <= MAX_CAPABILITIES) {
            "Plugin sandbox request exceeds $MAX_CAPABILITIES capabilities"
        }
        return PluginSandboxRequest(filesystem, network, localIpc)
    }

    private fun readFilesystem(element: JsonElement?): List<PluginSandboxFilesystemRequest> {
        if (element == null) return emptyList()
        require(element is JsonArray) { "sandbox.filesystem must be an array" }
        return element.mapIndexed { index, value ->
            require(value is JsonObject) { "sandbox.filesystem[$index] must be an object" }
            val path = value.requiredString("path", "sandbox.filesystem[$index].path")
            val access = value.requiredString("access", "sandbox.filesystem[$index].access")
            require(access == "read" || access == "write") {
                "sandbox.filesystem[$index].access must be read or write"
            }
            PluginSandboxFilesystemRequest(path, access)
        }
    }

    private fun readStrings(
        element: JsonElement?,
        field: String,
    ): List<String> {
        if (element == null) return emptyList()
        require(element is JsonArray) { "$field must be an array" }
        return element.mapIndexed { index, value ->
            require(value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                "$field[$index] must be a string"
            }
            validateValue(value.content, "$field[$index]")
        }
    }

    private fun JsonObject.requiredString(
        name: String,
        field: String,
    ): String {
        val value = this[name]
        require(value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
            "$field must be a string"
        }
        return validateValue(value.content, field)
    }

    private fun validateValue(
        value: String,
        field: String,
    ): String {
        require(value.isNotBlank()) { "$field must not be blank" }
        require(value.length <= MAX_VALUE_LENGTH) { "$field exceeds $MAX_VALUE_LENGTH characters" }
        require('\u0000' !in value) { "$field must not contain NUL" }
        return value
    }
}
