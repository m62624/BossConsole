package ai.rever.boss.process

internal fun nativeLocalIpcEndpoint(address: String): CageforgeLocalIpcEndpoint =
    when {
        address.startsWith("unix://") -> {
            CageforgeLocalIpcEndpoint.UnixSocket(address.removePrefix("unix://"))
        }

        address.startsWith("pipe://") -> {
            CageforgeLocalIpcEndpoint.WindowsNamedPipe(address.removePrefix("pipe://"))
        }

        else -> {
            error("Unsupported native smoke IPC address: $address")
        }
    }
