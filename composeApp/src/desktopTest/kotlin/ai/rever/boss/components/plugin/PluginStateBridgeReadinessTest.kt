package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.proto.PluginStateEnvelope
import ai.rever.boss.ipc.proto.PluginStateRequest
import ai.rever.boss.ipc.proto.PluginStateServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PluginStateBridgeReadinessTest {
    @Test
    fun `successful initial state RPC establishes readiness`() = runBlocking {
        val server = startServer(rejectRequests = false)
        val channel = channelFor(server)
        val bridge = PluginStateBridge("plugin", "instance", channel)
        try {
            bridge.start()
            bridge.awaitConnected(5_000)
        } finally {
            bridge.dispose()
            shutdown(channel, server)
        }
    }

    @Test
    fun `authentication failure never establishes readiness`() = runBlocking {
        val server = startServer(rejectRequests = true)
        val channel = channelFor(server)
        val bridge = PluginStateBridge("plugin", "instance", channel)
        try {
            bridge.start()
            assertFailsWith<TimeoutCancellationException> {
                bridge.awaitConnected(500)
            }
        } finally {
            bridge.dispose()
            shutdown(channel, server)
        }
    }

    private fun startServer(rejectRequests: Boolean): Server =
        ServerBuilder
            .forPort(0)
            .addService(
                object : PluginStateServiceGrpcKt.PluginStateServiceCoroutineImplBase() {
                    override suspend fun getCurrentState(request: PluginStateRequest): PluginStateEnvelope {
                        if (rejectRequests) {
                            throw Status.UNAUTHENTICATED.asRuntimeException()
                        }
                        return PluginStateEnvelope
                            .newBuilder()
                            .setPluginId(request.pluginId)
                            .setInstanceId(request.instanceId)
                            .build()
                    }
                },
            ).build()
            .start()

    private fun channelFor(server: Server): ManagedChannel =
        ManagedChannelBuilder
            .forAddress("127.0.0.1", server.port)
            .usePlaintext()
            .build()

    private fun shutdown(
        channel: ManagedChannel,
        server: Server,
    ) {
        channel.shutdownNow()
        server.shutdownNow()
    }
}
