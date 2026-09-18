package ai.rever.boss.process

import ai.cageforge.Cageforge
import ai.cageforge.CageforgeProcess
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
 * The method count is the required [Process] interoperability surface, not additional lifecycle
 * responsibilities in this adapter.
 */
@Suppress("TooManyFunctions")
internal class CageforgeManagedProcess(
    sandbox: SandboxProcess,
    private val runtime: Cageforge,
) : Process() {
    private val delegate: CageforgeProcess = sandbox.asJavaProcess()
    private val processState = CageforgeProcessState(this, delegate, runtime)

    init {
        processState.observeExit()
    }

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int = processState.waitFor()

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = processState.waitFor(timeout, unit)

    override fun exitValue(): Int = processState.exitValue()

    override fun destroy() = processState.destroy()

    override fun destroyForcibly(): Process = processState.destroyForcibly()

    override fun pid(): Long = processState.pid

    override fun isAlive(): Boolean = processState.isAlive()

    override fun onExit(): CompletableFuture<Process> = processState.completion
}

private class CageforgeProcessState(
    private val owner: Process,
    private val delegate: CageforgeProcess,
    private val runtime: Cageforge,
) {
    private val lock = Any()
    val completion = CompletableFuture<Process>()
    val pid = delegate.pid()
    private var completedExitCode: Int? = null
    private var nativeResourcesClosed = false

    fun observeExit() {
        delegate.onExit().whenComplete { _, failure ->
            synchronized(lock) {
                if (failure != null) {
                    completion.completeExceptionally(failure)
                } else {
                    runCatching { delegate.exitValue() }.getOrNull()?.let(::complete)
                }
                closeNativeResources()
            }
        }
    }

    fun waitFor(): Int =
        synchronized(lock) {
            completedExitCode ?: run {
                val exitCode = delegate.waitFor()
                complete(exitCode)
                exitCode
            }
        }

    fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean =
        synchronized(lock) {
            if (completedExitCode != null) {
                true
            } else if (!delegate.waitFor(timeout, unit)) {
                false
            } else {
                complete(delegate.exitValue())
                true
            }
        }

    fun exitValue(): Int =
        synchronized(lock) {
            completedExitCode ?: delegate.exitValue().also(::complete)
        }

    fun destroy() {
        synchronized(lock) {
            if (completedExitCode == null) {
                delegate.destroy()
            }
        }
    }

    fun destroyForcibly(): Process =
        synchronized(lock) {
            if (completedExitCode == null) {
                delegate.destroyForcibly()
            }
            owner
        }

    fun isAlive(): Boolean =
        synchronized(lock) {
            completedExitCode == null && delegate.isAlive
        }

    private fun complete(exitCode: Int) {
        completedExitCode = exitCode
        completion.complete(owner)
        closeNativeResources()
    }

    private fun closeNativeResources() {
        if (nativeResourcesClosed) {
            return
        }
        nativeResourcesClosed = true
        runCatching { delegate.close() }
        runCatching { runtime.close() }
    }
}
