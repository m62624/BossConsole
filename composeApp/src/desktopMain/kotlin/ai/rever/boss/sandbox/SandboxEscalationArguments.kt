package ai.rever.boss.sandbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun sandboxEscalation(args: JsonObject): SandboxEscalation {
    val filesystem = args["filesystem"]
    require(filesystem is JsonArray) { "filesystem must be an array of operation/path objects" }
    val files =
        filesystem.map {
            require(it is JsonObject && it.keys == setOf("operation", "path")) { "Invalid filesystem capability" }
            it.sandboxString("operation") to it.sandboxString("path")
        }
    return SandboxEscalation(args.stringArray("argv"), files, args.stringArray("network"), args.sandboxString("reason"))
}

private fun JsonObject.stringArray(name: String): List<String> {
    val value = this[name]
    require(value is JsonArray) { "$name must be an array" }
    return value.map {
        require(it is JsonPrimitive && it.isString) { "$name entries must be strings" }
        it.content
    }
}
