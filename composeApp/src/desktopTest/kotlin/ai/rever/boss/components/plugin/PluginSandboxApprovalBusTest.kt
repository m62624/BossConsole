package ai.rever.boss.components.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSandboxApprovalBusTest {
    @Test
    fun `approval completes the protected launch request`() =
        runBlocking {
            val bus = PluginSandboxApprovalBus(defaultTimeoutMs = 1_000)
            val result =
                async {
                    bus.requestApproval(
                        pluginId = "com.example.protected",
                        displayName = "Protected plugin",
                        capabilities = listOf(PluginSandboxCapability("filesystem", "read /workspace")),
                    )
                }
            val request = bus.requests.first()

            assertTrue(bus.approve(request.id))
            assertEquals(PluginSandboxApprovalDecision.Approved, result.await())
            assertTrue(bus.pendingList.value.isEmpty())
        }

    @Test
    fun `denial and timeout fail closed`() =
        runBlocking {
            val bus = PluginSandboxApprovalBus(defaultTimeoutMs = 50)
            val denied =
                async {
                    bus.requestApproval(
                        pluginId = "com.example.denied",
                        displayName = "Denied plugin",
                        capabilities = listOf(PluginSandboxCapability("network", "https://example.test")),
                    )
                }
            val deniedRequest = bus.requests.first()
            assertTrue(bus.deny(deniedRequest.id))
            assertEquals(PluginSandboxApprovalDecision.Denied(), denied.await())

            val timedOut =
                async {
                    bus.requestApproval(
                        pluginId = "com.example.timeout",
                        displayName = "Timeout plugin",
                        capabilities = listOf(PluginSandboxCapability("filesystem", "/tmp")),
                    )
                }
            assertEquals(PluginSandboxApprovalDecision.Timeout, timedOut.await())
        }

    @Test
    fun `approval consumer clears the delivered request after operator denial`() =
        runBlocking {
            val bus = PluginSandboxApprovalBus(defaultTimeoutMs = 1_000)
            val delivered = CompletableDeferred<PluginSandboxApprovalRequest>()
            val cleared = CompletableDeferred<Unit>()
            val consumer =
                launch {
                    bus.consumeApprovals { request ->
                        if (request == null) {
                            cleared.complete(Unit)
                        } else {
                            delivered.complete(request)
                        }
                    }
                }
            val result =
                async {
                    bus.requestApproval(
                        pluginId = "com.example.consumer",
                        displayName = "Consumer plugin",
                        capabilities = listOf(PluginSandboxCapability("filesystem", "/workspace")),
                    )
                }

            val request = delivered.await()
            assertEquals(1, bus.pendingList.value.size)
            assertTrue(bus.deny(request.id))
            assertEquals(PluginSandboxApprovalDecision.Denied(), result.await())
            cleared.await()
            assertTrue(bus.pendingList.value.isEmpty())
            consumer.cancelAndJoin()
        }
}
