package ai.rever.boss.components.plugin

import ai.rever.boss.process.CageforgeLocalIpcEndpoint
import java.io.File

/**
 * Converts a BOSS IPC address into the endpoint kind supported by Cageforge policy.
 *
 * Windows uses the native named-pipe transport for protected launches. Explicit TCP loopback
 * addresses remain legacy/test-only and are rejected here rather than widening the Cageforge
 * policy to unrestricted loopback networking.
 */
internal fun parseProtectedLocalIpcEndpoint(address: String): CageforgeLocalIpcEndpoint =
    when {
        address.startsWith("unix://") -> {
            val path = File(address.removePrefix("unix://"))
            require(path.isAbsolute) {
                "Protected Unix-socket IPC path must be absolute: ${path.path}"
            }
            CageforgeLocalIpcEndpoint.UnixSocket(path.canonicalFile.path)
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
