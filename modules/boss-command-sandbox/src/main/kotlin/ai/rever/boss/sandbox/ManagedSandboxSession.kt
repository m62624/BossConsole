package ai.rever.boss.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Concurrently drains both pipes; stopping always targets the native process boundary. */
class ManagedSandboxSession internal constructor(
    private val process: Process,
    private val boundary: AutoCloseable,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputMutex = Mutex()
    private val boundaryLock = Any()
    private var boundaryClosed = false
    private val completed = CompletableDeferred<Unit>()
    private val mutableOutput = MutableStateFlow(SandboxSessionOutput())
    val output: StateFlow<SandboxSessionOutput> = mutableOutput.asStateFlow()

    init {
        scope.launch {
            val result = runCatching { runProcess() }
            mutableOutput.update {
                it.copy(running = false, exitCode = result.getOrNull(), failure = result.exceptionOrNull())
            }
            completed.complete(Unit)
            scope.cancel()
        }
    }

    suspend fun awaitCompletion(): SandboxSessionOutput {
        completed.await()
        return output.value
    }

    suspend fun sendInput(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INPUT_BYTES) { "Input exceeds 16 KiB" }
        withInputLock {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
    }

    suspend fun closeInput() = withInputLock { process.outputStream.close() }

    /** Waits for confirmed cleanup, rather than reporting success after merely requesting a stop. */
    suspend fun stop() {
        withContext(NonCancellable + Dispatchers.IO) {
            terminate()
            completed.await()
        }
    }

    private suspend fun withInputLock(action: () -> Unit) {
        check(inputMutex.tryLock()) { "Session input is busy" }
        try {
            check(output.value.running) { "Session has finished" }
            withContext(Dispatchers.IO) { action() }
        } finally {
            inputMutex.unlock()
        }
    }

    private suspend fun runProcess(): Int =
        coroutineScope {
            // Close inside the scope body: native close releases blocking readers before
            // coroutineScope waits for its children, including on wait/read failure.
            AutoCloseable(::closeBoundary).use {
                val stdout = async { pump(process.inputStream, false) }
                val stderr = async { pump(process.errorStream, true) }
                val code = process.waitFor()
                // End detached descendants too; they can otherwise keep output pipes open.
                terminate()
                stdout.await()
                stderr.await()
                code
            }
        }

    private fun pump(
        stream: InputStream,
        stderr: Boolean,
    ) {
        runCatching {
            stream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                var count = reader.read(buffer)
                while (count >= 0) {
                    val text = String(buffer, 0, count)
                    mutableOutput.update {
                        if (stderr) {
                            it.copy(stderr = it.stderr.append(text))
                        } else {
                            it.copy(stdout = it.stdout.append(text))
                        }
                    }
                    count = reader.read(buffer)
                }
            }
        }.onFailure { terminate() }.getOrThrow()
    }

    private fun terminate() {
        synchronized(boundaryLock) {
            if (!boundaryClosed) process.destroyForcibly()
        }
    }

    private fun closeBoundary() {
        synchronized(boundaryLock) {
            try {
                boundary.close()
            } finally {
                boundaryClosed = true
            }
        }
    }

    companion object {
        const val MAX_INPUT_BYTES = 16 * 1024
    }
}
