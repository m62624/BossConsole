package ai.rever.boss.sandbox

/** Owns preparation resources until approval transfers the new native process to the service. */
internal interface SandboxEscalationPlan : AutoCloseable {
    val review: SandboxPermissionReview

    fun launch(): ManagedSandboxSession
}
