package ai.rever.boss.sandbox

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A reviewable immutable command/policy snapshot. Preparing this value does not launch anything. */
class SandboxSessionPlan internal constructor(
    internal val snapshot: SandboxPolicySnapshot,
    internal val permissionDigest: String,
    val permissionsJson: String,
) {
    val projectDirectory: Path get() = snapshot.projectDirectory
    val policyFile: Path get() = snapshot.policyFile
    val profile: String get() = snapshot.profile
    val argv: List<String> get() = snapshot.argv
    val approvalDigest: String get() = snapshot.digest
    private val consumed = AtomicBoolean()

    internal fun review(reason: String): SandboxPermissionReview =
        SandboxPermissionReview(approvalDigest, projectDirectory.toString(), argv, permissionsJson, reason, false)

    internal fun claim(approvedDigest: String) {
        require(approvedDigest == approvalDigest) { "Approval does not match the command and policy shown" }
        check(consumed.compareAndSet(false, true)) { "This session plan has already been used; prepare a new session" }
    }
}
