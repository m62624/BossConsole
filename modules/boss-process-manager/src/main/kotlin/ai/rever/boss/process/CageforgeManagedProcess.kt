package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.SandboxProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Bridges Cageforge's native Java process facade to the existing BOSS process registry.
 *
 * The child is created and controlled by Cageforge's native backend. This adapter only owns the
 * Cageforge runtime lifetime; process waiting, streams, liveness, and termination are delegated
 * to the official [java.lang.Process] implementation supplied by Cageforge.
 */
internal class CageforgeManagedProcess(
    sandbox: SandboxProcess,
    private val runtime: Cageforge,
) : Process() {
    private val delegate = sandbox.asJavaProcess()

    @Volatile private var completedExitCode: Int? = null

    init {
        delegate.onExit().whenComplete { _, _ ->
            completedExitCode = runCatching { delegate.exitValue() }.getOrNull()
            runCatching { delegate.close() }
            runCatching { runtime.close() }
        }
    }

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int = completedExitCode ?: delegate.waitFor()

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = completedExitCode != null || delegate.waitFor(timeout, unit)

    override fun exitValue(): Int = completedExitCode ?: delegate.exitValue()

    override fun destroy() {
        if (isAlive) delegate.destroy()
    }

    override fun destroyForcibly(): Process {
        if (isAlive) delegate.destroyForcibly()
        return this
    }

    override fun pid(): Long = delegate.pid()

    override fun onExit(): CompletableFuture<Process> =
        if (completedExitCode != null) {
            CompletableFuture.completedFuture(this)
        } else {
            delegate.onExit().thenApply { this }
        }
}
