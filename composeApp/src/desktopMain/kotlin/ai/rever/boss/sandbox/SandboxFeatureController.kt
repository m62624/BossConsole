package ai.rever.boss.sandbox

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No settings persistence and no eager runtime: every BOSS process starts with sandboxing disabled. */
internal class SandboxFeatureController(
    private val register: (SandboxSubsystem) -> Unit,
    private val unregister: () -> Unit,
) {
    private val lifecycle = Mutex()
    private val mutableActive = MutableStateFlow<SandboxSubsystem?>(null)
    private val mutableStopping = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()
    val stopping = mutableStopping.asStateFlow()
    private var closed = false

    suspend fun enable() {
        lifecycle.withLock {
            check(!closed) { "BOSS is shutting down" }
            if (active.value == null) {
                val subsystem = SandboxSubsystem()
                var transferred = false
                try {
                    register(subsystem)
                    mutableActive.value = subsystem
                    transferred = true
                } finally {
                    if (!transferred) {
                        try {
                            unregister()
                        } finally {
                            subsystem.shutdown()
                        }
                    }
                }
            }
        }
    }

    suspend fun disable() =
        withContext(NonCancellable) {
            lifecycle.withLock { disableCurrent() }
        }

    suspend fun shutdown() =
        withContext(NonCancellable) {
            lifecycle.withLock {
                closed = true
                disableCurrent()
            }
        }

    private suspend fun disableCurrent() {
        active.value?.let { subsystem ->
            mutableStopping.value = true
            mutableActive.value = null
            try {
                try {
                    unregister()
                } finally {
                    subsystem.shutdown()
                }
            } finally {
                mutableStopping.value = false
            }
        }
    }
}

internal class SandboxSubsystem {
    val service = SandboxSessionService()
    val requests = SandboxLaunchRequests(service)

    suspend fun shutdown() {
        try {
            service.shutdown()
        } finally {
            requests.shutdown()
        }
    }
}
