package ai.rever.boss.process

import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState

/**
 * Ensures the platform-owned Cageforge prerequisites are ready before a protected spawn.
 *
 * Windows provisioning is persistent and owner-scoped, but it is deliberately not performed from
 * the child-process launch path: installation may request UAC and must be an explicit host action.
 * A protected launch only verifies the already-installed state. Any missing, stale, or invalid
 * setup is allowed to escape and therefore prevents an unsandboxed fallback.
 */
internal object CageforgePlatformSetup {
    private val windowsSetupLock = Any()

    fun ensureReady() {
        if (!WindowsSetup.isSupported()) {
            // Linux and macOS use their own native backends; Windows provisioning is not needed.
            return
        }

        synchronized(windowsSetupLock) {
            when (WindowsSetup.status()) {
                WindowsSetupState.READY -> {
                    WindowsSetup.verify()
                }

                WindowsSetupState.MISSING -> {
                    error(
                        "Cageforge Windows setup is missing; " +
                            "install it explicitly before protected launch",
                    )
                }

                WindowsSetupState.STALE -> {
                    error(
                        "Cageforge Windows setup is stale; " +
                            "reconcile it explicitly before protected launch",
                    )
                }
            }
        }
    }
}
