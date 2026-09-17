package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.ProcessResult
import ai.cageforge.SandboxProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bridges Cageforge's process handle to the existing BOSS process registry.
 *
 * The adapter implements the required process streams and lifecycle operations. Timed waits use
 * Cageforge's completion future directly; forced termination and liveness checks remain expressed
 * through the standard [Process] contract.
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
        return runCatching {
            resultToExitCode(sandbox.waitFor()).also { completedExitCode = it }
        }.getOrElse { error ->
            completion.join()
            completedExitCode ?: throw error
        }
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        require(timeout >= 0) { "timeout must not be negative" }
        return runCatching { completion.get(timeout, unit) }.fold(
            onSuccess = { true },
            onFailure = { error ->
                when (error) {
                    is TimeoutException -> {
                        false
                    }

                    is ExecutionException -> {
                        throw IllegalStateException(
                            "Cageforge process wait failed",
                            error.cause,
                        )
                    }

                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        throw error
                    }

                    else -> {
                        throw error
                    }
                }
            },
        )
    }

    override fun exitValue(): Int =
        completedExitCode
            ?: runCatching { sandbox.tryWait()?.let(::resultToExitCode) }
                .getOrNull()
                ?.also { completedExitCode = it }
            ?: throw IllegalThreadStateException("Cageforge process is still running")

    override fun destroy() {
        if (!completion.isDone) runCatching { sandbox.kill() }
    }

    override fun pid(): Long = sandbox.id.toLong()

    override fun supportsNormalTermination(): Boolean = false
}

private fun resultToExitCode(result: ProcessResult): Int = result.exitCode ?: -1
