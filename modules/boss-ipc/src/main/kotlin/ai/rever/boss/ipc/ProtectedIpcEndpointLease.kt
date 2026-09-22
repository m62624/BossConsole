package ai.rever.boss.ipc

/** A local endpoint lease that survives the Cageforge launch handoff. */
interface ProtectedIpcEndpointLease : AutoCloseable {
    /** Makes the endpoint available to the native child after Cageforge has acquired its lease. */
    fun handoffToChild()
}
