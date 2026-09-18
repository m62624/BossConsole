package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.SandboxProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
) : Process(), AutoCloseable {
    private val delegate = sandbox.asJavaProcess()
    private val resourcesClosed = AtomicBoolean(false)

    init {
        delegate.onExit().whenComplete { _, _ -> closeNativeResources() }
    }

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int = delegate.waitFor()

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = delegate.waitFor(timeout, unit)

    override fun exitValue(): Int = delegate.exitValue()

    override fun destroy() = delegate.destroy()

    override fun destroyForcibly(): Process {
        delegate.destroyForcibly()
        return this
    }

    override fun isAlive(): Boolean = delegate.isAlive

    override fun pid(): Long = delegate.pid()

    override fun onExit(): CompletableFuture<Process> =
        delegate.onExit().thenApply { this }

    override fun supportsNormalTermination(): Boolean = delegate.supportsNormalTermination()

    override fun close() = closeNativeResources()

    private fun closeNativeResources() {
        if (resourcesClosed.compareAndSet(false, true)) {
            runCatching { delegate.close() }
            runCatching { runtime.close() }
        }
    }
}
