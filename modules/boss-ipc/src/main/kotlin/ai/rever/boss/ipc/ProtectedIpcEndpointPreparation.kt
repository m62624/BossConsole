package ai.rever.boss.ipc

/**
 * Materializes a native local endpoint before Cageforge preflight.
 *
 * Cageforge verifies and leases the DACL of an existing Windows pipe. The
 * returned handle remains open until the native launch has been cleaned up;
 * the child server creates its own instance of the same named pipe.
 */
fun prepareProtectedIpcEndpoint(address: String): AutoCloseable {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (!isWindows || !address.startsWith("pipe://")) return AutoCloseable {}
    return WindowsNamedPipeTransport.prepareForProtectedLaunch(
        WindowsNamedPipeAddress(address.removePrefix("pipe://")),
    )
}
