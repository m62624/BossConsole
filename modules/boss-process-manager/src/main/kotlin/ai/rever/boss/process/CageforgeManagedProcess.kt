package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.ProcessResult
import ai.cageforge.SandboxProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture

/**
 * Bridges Cageforge's process handle to the existing BOSS process registry.
 *
 * The adapter implements the required process streams and lifecycle operations. The default
 * [Process] implementations provide timeout polling, forced termination and liveness checks in
 * terms of the primitives below, so we do not duplicate that logic here.
 */
internal class CageforgeManagedProcess(
    private val sandbox: SandboxProcess,
    private val runtime: Cageforge,
) : Process() {
    private val completion = CompletableFuture<Process>()

    @Volatile private var completedExitCode: Int? = null
    private val stdout = requireNotNull(sandbox.stdout) { "Cageforge stdout must be configured as pipe" }
    private val stderr = requireNotNull(sandbox.stderr) { "Cageforge stderr must be configured as pipe" }
    private val stdin = requireNotNull(sandbox.stdin) { "Cageforge stdin must be configured as pipe" }

    init {
        sandbox.waitForAsync().whenComplete { result, error ->
            if (error == null) {
                completedExitCode = resultToExitCode(result)
                completion.complete(this)
            } else {
                completion.completeExceptionally(error)
            }
            runCatching { sandbox.close() }
            runCatching { runtime.close() }
        }
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        completedExitCode?.let { return it }
        return waitForNativeProcess()
    }

    private fun waitForNativeProcess(): Int =
        runCatching {
            resultToExitCode(sandbox.waitFor()).also { completedExitCode = it }
        }.getOrElse { error ->
            completion.join()
            completedExitCode ?: throw error
        }

    override fun exitValue(): Int =
        tryWaitForExit()
            ?: throw IllegalThreadStateException("Cageforge process is still running")

    private fun tryWaitForExit(): Int? {
        completedExitCode?.let { return it }
        val exitCode = runCatching { sandbox.tryWait()?.let(::resultToExitCode) }.getOrNull()
        if (exitCode != null) completedExitCode = exitCode
        return exitCode
    }

    override fun destroy() {
        if (!completion.isDone) runCatching { sandbox.kill() }
    }

    override fun pid(): Long = sandbox.id.toLong()

    override fun supportsNormalTermination(): Boolean = false
}

private fun resultToExitCode(result: ProcessResult): Int = result.exitCode ?: -1
