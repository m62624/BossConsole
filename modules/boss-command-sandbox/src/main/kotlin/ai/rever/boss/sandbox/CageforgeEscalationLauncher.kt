package ai.rever.boss.sandbox

import ai.cageforge.Cageforge
import ai.cageforge.PermissionApprover
import ai.cageforge.PermissionEscalationRequest
import ai.cageforge.RuntimeContext
import ai.cageforge.WindowsSetup
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A command-specific runtime has no old process to replace; the requesting agent stays confined. */
internal class CageforgeEscalationLauncher {
    fun prepare(
        base: SandboxSessionPlan,
        additional: SandboxEscalation,
    ): SandboxEscalationPlan {
        check(!WindowsSetup.isSupported()) {
            "Concurrent additional-permission commands are unavailable on Windows with Cageforge Java 0.7.1: " +
                "its shared filesystem read authority can expose the command's grant to the running agent"
        }
        val snapshot = base.snapshot.forCommand(additional.argv)
        val context = RuntimeContext(snapshot.projectDirectory)
        Cageforge.checkToml(snapshot.toml, SandboxPolicySnapshot.LAUNCH_PROFILE, context)
        return Cageforge
            .permissionRequest(
                snapshot.toml,
                SandboxPolicySnapshot.LAUNCH_PROFILE,
                context,
                toolId = "boss-command-session",
                configDigest = snapshot.digest,
            ).use { request ->
                // This grant constructs a host-private runtime only. No process may launch until
                // the service consumes human approval for the expanded request and exact argv.
                PermissionApprover().approve(request).use { grant ->
                    val runtime =
                        Cageforge.fromToml(
                            snapshot.toml,
                            SandboxPolicySnapshot.LAUNCH_PROFILE,
                            context,
                            grant,
                            request,
                        )
                    prepareOwned(runtime, snapshot, additional)
                }
            }
    }

    private fun prepareOwned(
        runtime: Cageforge,
        snapshot: SandboxPolicySnapshot,
        additional: SandboxEscalation,
    ): SandboxEscalationPlan {
        var transferred = false
        val pendingRuntime = AutoCloseable { if (!transferred) runtime.close() }
        return pendingRuntime.use {
            val escalation = runtime.requestEscalation(additional.filesystem, additional.network, additional.reason)
            val pendingRequest = AutoCloseable { if (!transferred) escalation.close() }
            pendingRequest.use {
                NativeEscalationPlan(runtime, escalation, snapshot, additional.reason).also { transferred = true }
            }
        }
    }
}

private class NativeEscalationPlan(
    private val runtime: Cageforge,
    private val escalation: PermissionEscalationRequest,
    snapshot: SandboxPolicySnapshot,
    reason: String,
) : SandboxEscalationPlan {
    private val consumed = AtomicBoolean()
    private var transferred = false
    override val review =
        SandboxPermissionReview(
            MessageDigest
                .getInstance("SHA-256")
                .digest("${snapshot.digest}\u0000${escalation.json}".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            snapshot.projectDirectory.toString(),
            snapshot.argv,
            escalation.json,
            reason,
            true,
        )

    override fun launch(): ManagedSandboxSession {
        check(consumed.compareAndSet(false, true)) { "Escalation plan was already used" }
        return PermissionApprover().approveEscalation(escalation).use { grant ->
            val session = SandboxSession(runtime.launchEscalated(escalation, grant).asJavaProcess(), runtime)
            try {
                session.manage().also { transferred = true }
            } finally {
                if (!transferred) session.close()
            }
        }
    }

    override fun close() {
        try {
            escalation.close()
        } finally {
            if (!transferred) runtime.close()
        }
    }
}
