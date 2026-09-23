package ai.rever.boss.sandbox

import ai.cageforge.Cageforge
import ai.cageforge.PermissionApprover
import ai.cageforge.PermissionRequest
import ai.cageforge.RuntimeContext
import ai.cageforge.WindowsSetup
import ai.cageforge.WindowsSetupState

/** The only native launch boundary for opt-in command sessions. No unsandboxed fallback exists. */
class CageforgeSessionLauncher {
    fun prepare(command: SandboxCommand): SandboxSessionPlan {
        val snapshot = SandboxPolicySnapshot.read(command)
        // checkToml also initializes JNI; permissionRequest expects it loaded.
        Cageforge.checkToml(
            snapshot.toml,
            SandboxPolicySnapshot.LAUNCH_PROFILE,
            RuntimeContext(snapshot.projectDirectory),
        )
        return permissionRequest(snapshot).use { request ->
            SandboxSessionPlan(snapshot, request.digest, request.json)
        }
    }

    /** Call only after the operator approves this exact plan, through the GUI or explicit CLI action. */
    fun launch(
        plan: SandboxSessionPlan,
        approvedDigest: String,
    ): SandboxSession {
        plan.claim(approvedDigest)
        ensurePlatformReady()
        val snapshot = plan.snapshot
        return permissionRequest(snapshot).use { request ->
            check(request.digest == plan.permissionDigest) { "Permissions changed since review; prepare a new session" }
            PermissionApprover().approve(request).use { grant ->
                val runtime =
                    Cageforge.fromToml(
                        snapshot.toml,
                        SandboxPolicySnapshot.LAUNCH_PROFILE,
                        RuntimeContext(snapshot.projectDirectory),
                        grant,
                        request,
                    )
                launchOwned(runtime)
            }
        }
    }

    private fun launchOwned(runtime: Cageforge): SandboxSession {
        var transferred = false
        // use preserves a launch failure even if cleanup also fails. After a successful
        // launch, the session owns the runtime and closes it after the process boundary.
        val pendingRuntime = AutoCloseable { if (!transferred) runtime.close() }
        return pendingRuntime.use {
            SandboxSession(runtime.launchProcess(), runtime).also { transferred = true }
        }
    }

    private fun permissionRequest(snapshot: SandboxPolicySnapshot): PermissionRequest =
        Cageforge.permissionRequest(
            snapshot.toml,
            SandboxPolicySnapshot.LAUNCH_PROFILE,
            RuntimeContext(snapshot.projectDirectory),
            toolId = "boss-command-session",
            configDigest = snapshot.digest,
        )

    private fun ensurePlatformReady() {
        if (WindowsSetup.isSupported()) {
            check(WindowsSetup.status() == WindowsSetupState.READY) {
                "Cageforge Windows setup is not ready. Run explicit setup before starting a sandboxed session."
            }
            WindowsSetup.verify()
        }
    }
}
