package ai.rever.boss.ipc

/**
 * Materializes a native local endpoint before Cageforge preflight.
 *
 * Cageforge verifies and leases the DACL of an existing Windows pipe. The
 * returned handle remains open until Cageforge has acquired its own native
 * policy lease; the child or host server then owns the usable pipe instance.
 *
 * The input is the native endpoint value stored in the policy, not the
 * `pipe://`-prefixed BOSS transport address.
 */
fun prepareProtectedIpcEndpoint(address: String): AutoCloseable {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (!isWindows || !address.startsWith("\\\\.\\pipe\\")) return AutoCloseable {}
    return WindowsNamedPipeTransport.prepareForProtectedLaunch(
        WindowsNamedPipeAddress(address),
    )
}
