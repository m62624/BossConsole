package ai.rever.boss.components.plugin

import ai.rever.boss.process.CageforgeLocalIpcEndpoint

/**
 * Converts a BOSS IPC address into the endpoint kind supported by Cageforge policy.
 *
 * Windows keeps TCP for the ordinary legacy IPC transport today. A protected launch cannot
 * accept that address: it must wait for BOSS to provide a real named-pipe transport instead of
 * widening the Cageforge policy to loopback networking.
 */
internal fun parseProtectedLocalIpcEndpoint(address: String): CageforgeLocalIpcEndpoint =
    when {
        address.startsWith("unix://") -> {
            CageforgeLocalIpcEndpoint.UnixSocket(address.removePrefix("unix://"))
        }

        address.startsWith("pipe://") -> {
            CageforgeLocalIpcEndpoint.WindowsNamedPipe(address.removePrefix("pipe://"))
        }

        address.startsWith("tcp://") -> {
            error(
                "Protected Cageforge IPC requires a native local transport; " +
                    "TCP loopback is not an allowed security fallback: $address",
            )
        }

        else -> {
            error("Unsupported protected IPC address: $address")
        }
    }
