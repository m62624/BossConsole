package ai.rever.boss.sandbox

import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Host-created review data. Agents may request capabilities, but cannot create an approval. */
class SandboxPermissionReview internal constructor(
    val approvalDigest: String,
    val projectDirectory: String,
    argv: List<String>,
    val permissionsJson: String,
    val reason: String,
    val requiresRestart: Boolean,
) {
    val argv: List<String> = Collections.unmodifiableList(argv.toList())
}

class SandboxConsentRequest internal constructor(
    val review: SandboxPermissionReview,
) {
    val id: String = UUID.randomUUID().toString()
}

enum class SandboxConsentChoice {
    ONCE,
    UNTIL_APP_CLOSES,
    DENY,
}

/** A single-use authorization, rechecked immediately before native launch. */
class SandboxConsentPermit internal constructor(
    internal val issuer: Any,
    internal val digest: String,
    internal val revision: Long,
) {
    internal val consumed = AtomicBoolean()
}
