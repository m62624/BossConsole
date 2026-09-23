package ai.rever.boss.sandbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One owner for every BOSS window and CLI caller. Never persists grants or launches an ordinary process. */
internal object SandboxCommandHost {
    val service = SandboxSessionService()
    private val windows = linkedSetOf<String>()
    private val mutableReviewWindow = MutableStateFlow<String?>(null)
    val reviewWindow = mutableReviewWindow.asStateFlow()

    fun attach(windowId: String) =
        synchronized(windows) {
            windows.add(windowId)
            if (mutableReviewWindow.value == null) mutableReviewWindow.value = windowId
        }

    fun detach(windowId: String) =
        synchronized(windows) {
            windows.remove(windowId)
            if (mutableReviewWindow.value == windowId) mutableReviewWindow.value = windows.firstOrNull()
        }

    fun reviewIn(windowId: String) =
        synchronized(windows) {
            check(windowId in windows)
            mutableReviewWindow.value = windowId
        }
}
