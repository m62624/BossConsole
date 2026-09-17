package ai.rever.boss.process

import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState

/**
 * Ensures the platform-owned Cageforge prerequisites are ready before a protected spawn.
 *
 * Windows provisioning is persistent and owner-scoped. It may request UAC the first time, so it
 * must not be repeated for every child; a verified READY state is reused for later launches. Any
 * provisioning or verification error is allowed to escape and therefore prevents an unsandboxed
 * fallback.
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
                WindowsSetupState.READY -> WindowsSetup.verify()
                WindowsSetupState.MISSING,
                WindowsSetupState.STALE,
                -> {
                    WindowsSetup.install()
                    WindowsSetup.verify()
                }
            }
        }
    }
}
