package ai.rever.boss.process

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs inside Cageforge and starts the actual target as a protected descendant.
 * No shell is involved; everything after `--` is an argv element.
 */
object ProtectedChildBootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parse(args.toList())
        ProtectedEnvironmentChannel.writeReady(System.out)
        val environment = ProtectedEnvironmentChannel.readEnvironment(System.`in`)
        val process =
            ProcessBuilder(parsed.command)
                .directory(parsed.workingDirectory)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }.start()
        exitProcess(process.waitFor())
    }

    private data class Parsed(
        val workingDirectory: File,
        val command: List<String>,
    )

    private fun parse(args: List<String>): Parsed {
        val cwdIndex = args.indexOf("--cwd")
        val separator = args.indexOf("--")
        require(cwdIndex >= 0 && separator > cwdIndex + 1) { "Missing --cwd" }
        require(separator + 1 < args.size) { "Missing child argv" }
        val cwd = File(args[cwdIndex + 1])
        require(cwd.isAbsolute) { "Protected working directory must be absolute" }
        val normalizedCwd = cwd.normalize()
        require(normalizedCwd.isDirectory) { "Invalid protected working directory" }
        val command = args.subList(separator + 1, args.size)
        require(command.isNotEmpty()) { "Protected child argv must not be empty" }
        require(command.none { '\u0000' in it }) { "Protected child argv must not contain NUL" }
        require(File(command.first()).isAbsolute) { "Protected child executable must be absolute" }
        return Parsed(normalizedCwd, command)
    }
}
