package ai.rever.boss.sandbox

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The host registry's tool policy remains in force, in addition to native capability consent. */
internal fun sandboxMcpTool(
    name: String,
    description: String,
    fields: SandboxMcpFields,
    readOnly: Boolean,
    action: suspend (JsonObject) -> JsonElement,
): McpToolDefinition =
    McpToolDefinition(
        name = name,
        description = description,
        inputSchema = sandboxMcpSchema(fields.all, fields.required),
        readOnly = readOnly,
        handler =
            McpToolHandler { args ->
                sandboxOperationResult {
                    val input = Json.parseToJsonElement(args.raw).jsonObject
                    require(input.keys.all { it in fields.all }) { "Unknown sandbox tool argument" }
                    require(fields.required.all { it in input }) { "Missing required sandbox tool argument" }
                    action(input)
                }.fold(
                    { McpToolResult(it.toString()) },
                    { McpToolResult(it.message ?: it.javaClass.simpleName, isError = true) },
                )
            },
    )

private fun sandboxMcpSchema(
    fields: List<String>,
    required: List<String>,
): String =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put(
            "properties",
            buildJsonObject {
                fields.forEach { field ->
                    put(
                        field,
                        buildJsonObject {
                            val type =
                                when (field) {
                                    "argv", "filesystem", "network" -> "array"
                                    "close_stdin" -> "boolean"
                                    else -> "string"
                                }
                            put("type", type)
                            if (field == "argv" || field == "network") {
                                put("items", buildJsonObject { put("type", "string") })
                            }
                            if (field == "filesystem") {
                                put(
                                    "items",
                                    Json.parseToJsonElement(
                                        sandboxMcpSchema(
                                            listOf("operation", "path"),
                                            listOf("operation", "path"),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            },
        )
    }.toString()

internal fun JsonObject.sandboxString(name: String): String {
    val value = this[name]
    require(value is JsonPrimitive && value.isString) { "$name must be a string" }
    return value.content
}
