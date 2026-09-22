package ai.rever.boss.components.plugin

import ai.rever.boss.process.CageforgeLocalIpcEndpoint
import ai.rever.boss.process.CageforgePolicyCeiling
import ai.rever.boss.process.CageforgeProcessPolicy
import java.io.File

internal fun buildCageforgePolicy(
    securityRequired: Boolean,
    workDir: File,
    protectedRoots: ProtectedRoots,
    localIpcEndpoints: List<CageforgeLocalIpcEndpoint>,
    requestedReadOnlyRoots: List<File>,
): CageforgeProcessPolicy? =
    if (securityRequired) {
        CageforgePolicyCeiling
            .forWorkspace(
                workDir,
                protectedRoots.readOnlyRoots,
                runtimeExecutableRoots = protectedRoots.executableRoots,
            ).policyFor(
                workDir,
                localIpcEndpoints = localIpcEndpoints,
                additionalReadOnlyRoots = requestedReadOnlyRoots,
            )
    } else {
        null
    }
