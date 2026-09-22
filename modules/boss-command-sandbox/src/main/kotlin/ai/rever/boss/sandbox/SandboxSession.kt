package ai.rever.boss.sandbox

import ai.cageforge.CageforgeProcess
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a root process, its native descendant boundary, and the runtime that created them. */
class SandboxSession internal constructor(
    private val child: CageforgeProcess,
    private val runtime: AutoCloseable,
) : AutoCloseable {
    val process: Process get() = child
    private val closed = AtomicBoolean()

    /** Terminates the complete native boundary, including descendants, before releasing the runtime. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runtime.use { child.close() }
        }
    }
}
