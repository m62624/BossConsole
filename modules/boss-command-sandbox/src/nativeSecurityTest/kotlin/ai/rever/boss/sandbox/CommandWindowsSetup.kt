package ai.rever.boss.sandbox

import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState
import java.nio.file.Files
import java.nio.file.Path

/** Explicit provisioning for disposable CI runners, never called by a command launch. */
object CommandWindowsSetup {
    @JvmStatic
    fun main(args: Array<String>) {
        check(WindowsSetup.isSupported()) { "This provisioning entry point is Windows-only" }
        val marker = Path.of(checkNotNull(System.getenv("BOSS_CAGEFORGE_SETUP_MARKER")))
        when (args.single()) {
            "install" -> {
                check(WindowsSetup.status() == WindowsSetupState.MISSING) { "Refusing to replace an existing setup" }
                // Retain ownership on an interrupted/partial installation so teardown can reconcile it.
                Files.createFile(marker)
                WindowsSetup.install()
                WindowsSetup.verify()
            }

            "uninstall" -> {
                if (Files.exists(marker)) {
                    WindowsSetup.uninstall()
                    Files.delete(marker)
                }
            }

            else -> {
                error("Expected install or uninstall")
            }
        }
    }
}
