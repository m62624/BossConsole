package ai.rever.boss.sandbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal class SandboxMcpOperations(
    private val service: SandboxSessionService,
    private val requests: SandboxLaunchRequests,
    private val canReview: () -> Boolean,
) {
    fun start(args: JsonObject): JsonElement {
        check(canReview()) { "Open a BOSS window to review sandbox permissions before requesting a launch" }
        val arguments = args["argv"]
        require(arguments is JsonArray && arguments.isNotEmpty()) { "argv must include an executable" }
        val argv =
            arguments.map {
                require(it is JsonPrimitive && it.isString) { "Every argv entry must be a string" }
                it.content
            }
        val command =
            SandboxCommand(
                Path.of(args.sandboxString("project")),
                Path.of(args.sandboxString("policy")),
                args.sandboxString("profile"),
                argv,
            )
        val id = requests.submit(command, args.sandboxString("reason"))
        return buildJsonObject {
            put("request_id", id)
            put("state", "PENDING")
            put("next", "Review permissions in BOSS; poll sandbox_request_status with request_id")
        }
    }

    fun requestStatus(args: JsonObject): JsonElement {
        val status = requests.status(args.sandboxString("request_id"))
        return buildJsonObject {
            put("request_id", status.id)
            put("state", status.state.name)
            status.sessionId?.let { put("session_id", it) }
            status.failure?.let { put("error", it.message ?: it.javaClass.simpleName) }
        }
    }

    fun requestPermissions(args: JsonObject): JsonElement {
        check(canReview()) { "Open a BOSS window to review additional permissions" }
        val request = sandboxEscalation(args)
        val id = requests.submitEscalation(args.sandboxString("session_id"), request)
        return buildJsonObject {
            put("request_id", id)
            put("state", "PENDING")
            put("next", "Review additional permissions in BOSS; poll sandbox_request_status with request_id")
        }
    }

    fun forget(args: JsonObject): JsonElement {
        requests.forget(args.sandboxString("request_id"))
        return buildJsonObject { put("forgotten", true) }
    }

    fun sessions(): JsonElement =
        JsonArray(
            service.sessions.value.map { entry ->
                buildJsonObject {
                    put("session_id", entry.id)
                    put("project", entry.review.projectDirectory)
                    put("argv", JsonArray(entry.review.argv.map(::JsonPrimitive)))
                    put("running", entry.session.output.value.running)
                }
            },
        )

    fun output(args: JsonObject): JsonElement = outputJson(entry(args).session.output.value)

    suspend fun input(args: JsonObject): JsonElement {
        val eof = args["close_stdin"]
        require(eof == null || (eof is JsonPrimitive && !eof.isString && eof.booleanOrNull != null)) {
            "close_stdin must be a boolean"
        }
        val close = eof?.booleanOrNull == true
        require("text" in args || close) { "Provide text or set close_stdin to true" }
        val session = entry(args).session
        if ("text" in args) session.sendInput(args.sandboxString("text"))
        if (close) session.closeInput()
        return buildJsonObject { put("accepted", true) }
    }

    suspend fun stop(args: JsonObject): JsonElement {
        val session = entry(args).session
        session.stop()
        return outputJson(session.output.value)
    }

    suspend fun remove(args: JsonObject): JsonElement {
        service.remove(args.sandboxString("session_id"))
        return buildJsonObject { put("removed", true) }
    }

    private fun entry(args: JsonObject): SandboxSessionEntry {
        val id = args.sandboxString("session_id")
        return requireNotNull(service.sessions.value.singleOrNull { it.id == id }) { "Unknown sandbox session" }
    }
}

private fun outputJson(output: SandboxSessionOutput): JsonObject =
    buildJsonObject {
        put("running", output.running)
        output.exitCode?.let { put("exit_code", it) }
        put("stdout", output.stdout.text)
        put("stderr", output.stderr.text)
        put("stdout_discarded_characters", output.stdout.discardedCharacters)
        put("stderr_discarded_characters", output.stderr.discardedCharacters)
        output.failure?.let { put("error", it.message ?: it.javaClass.simpleName) }
    }
