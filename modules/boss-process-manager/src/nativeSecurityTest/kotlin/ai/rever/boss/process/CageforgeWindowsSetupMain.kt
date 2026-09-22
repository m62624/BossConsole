package ai.rever.boss.process

import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState
import java.nio.file.Files
import java.nio.file.Path

private const val TEARDOWN_COMMAND = "teardown"
private const val OWNED_MARKER_ENV = "CAGEFORGE_WINDOWS_SETUP_MARKER"

/**
 * Performs the explicit Windows Cageforge provisioning required by the native CI lane.
 *
 * Protected production launches intentionally never call [WindowsSetup.install], because that
 * operation may require UAC. This entry point is invoked only by the dedicated workflow, where
 * the provisioning and its cleanup are visible steps.
 */
fun main(args: Array<String>) {
    val marker = ownedMarker()
    if (args.singleOrNull() == TEARDOWN_COMMAND) {
        teardown(marker)
    } else {
        setup(marker)
    }
}

private fun setup(marker: Path) {
    check(WindowsSetup.isSupported()) { "Cageforge Windows setup is only available on Windows" }
    when (WindowsSetup.status()) {
        WindowsSetupState.READY -> {
            WindowsSetup.verify()
        }

        WindowsSetupState.MISSING,
        WindowsSetupState.STALE,
        -> {
            installAndRecord(marker)
        }
    }
}

private fun installAndRecord(marker: Path) {
    WindowsSetup.install()
    WindowsSetup.verify()
    marker.parent?.let(Files::createDirectories)
    Files.writeString(marker, "installed-by-this-run\n")
}

private fun teardown(marker: Path) {
    if (!Files.exists(marker)) return
    check(WindowsSetup.isSupported()) { "Cageforge Windows setup is only available on Windows" }
    WindowsSetup.uninstall()
    check(WindowsSetup.status() == WindowsSetupState.MISSING) {
        "Cageforge Windows setup was not removed"
    }
    Files.deleteIfExists(marker)
}

private fun ownedMarker(): Path {
    val value = System.getenv(OWNED_MARKER_ENV)
    require(!value.isNullOrBlank()) { "$OWNED_MARKER_ENV must identify the CI-owned setup marker" }
    return Path.of(value).toAbsolutePath().normalize()
}
