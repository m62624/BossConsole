package ai.rever.boss.ipc

/**
 * Materializes a native local endpoint before Cageforge preflight.
 *
 * Cageforge verifies and leases the DACL of an existing Windows pipe. The
 * returned lease remains open until the native child and Cageforge have both
 * terminated; closing the server side immediately after launch breaks the
 * native ACL lease on Windows.
 *
 * The input is the native endpoint value stored in the policy, not the
 * `pipe://`-prefixed BOSS transport address.
 */
fun prepareProtectedIpcEndpoint(address: String): ProtectedIpcEndpointLease {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (!isWindows || !address.startsWith("\\\\.\\pipe\\")) {
        return NoOpProtectedIpcEndpointLease
    }
    return WindowsNamedPipeTransport.prepareForProtectedLaunch(
        WindowsNamedPipeAddress(address),
    )
}

private object NoOpProtectedIpcEndpointLease : ProtectedIpcEndpointLease {
    override fun handoffToChild() = Unit

    override fun close() = Unit
}
