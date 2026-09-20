package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginSandboxApprovalCoordinatorTest {
    @Test
    fun `denial fails the protected launch approval boundary`() =
        runBlocking {
            supervisorScope {
                val bus = PluginSandboxApprovalBus(defaultTimeoutMs = 1_000)
                val approval =
                    async {
                        requestSandboxApproval(
                            manifest =
                                PluginManifest(
                                    pluginId = "com.example.protected",
                                    displayName = "Protected plugin",
                                    version = "1.0.0",
                                    apiVersion = "1.0.0",
                                    mainClass = "example.Plugin",
                                ),
                            pluginId = "com.example.protected",
                            sandboxRequest =
                                PluginSandboxRequest(
                                    filesystem =
                                        listOf(
                                            PluginSandboxFilesystemRequest("/workspace/models", "read"),
                                        ),
                                    network = emptyList(),
                                    localIpc = emptyList(),
                                ),
                            approvalBus = bus,
                        )
                    }

                val request = bus.requests.first()
                assertTrue(bus.deny(request.id))
                val failure = assertFailsWith<IllegalStateException> { approval.await() }
                assertTrue(failure.message.orEmpty().contains("denied"))
            }
        }
}
