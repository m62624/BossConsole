package ai.rever.boss.process

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * One-shot, process-owned credentials channel for the Cageforge bootstrap.
 *
 * The channel is the stdin pipe returned by Cageforge, not a filesystem socket.
 * That keeps the protocol portable and prevents a restricted filesystem policy
 * from hiding the channel from the child. The bootstrap emits a fixed readiness
 * marker on stdout first; only then does the host send the environment frame.
 */
internal object ProtectedEnvironmentChannel {
    private val READY = "BOSS-CAGEFORGE-BOOTSTRAP-READY\n".toByteArray(StandardCharsets.US_ASCII)
    private val MAGIC = "BOSS-CAGEFORGE-ENV-V2".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_ENTRIES = 256
    private const val MAX_VALUE_BYTES = 1024 * 1024
    private const val END = 0x424f5353

    fun send(
        bootstrapStdout: InputStream,
        bootstrapStdin: OutputStream,
        environment: Map<String, String>,
        timeoutMs: Long,
    ) {
        val ready =
            CompletableFuture.runAsync {
                val actual = ByteArray(READY.size)
                readFully(bootstrapStdout, actual)
                check(actual.contentEquals(READY)) { "Invalid Cageforge bootstrap readiness" }
            }
        runCatching { ready.get(timeoutMs, TimeUnit.MILLISECONDS) }.onFailure { error ->
            ready.cancel(true)
            throw IllegalStateException("Cageforge bootstrap did not authenticate", error)
        }

        // Do not close this stream: the actual child inherits the same stdin
        // after the bootstrap consumes this frame.
        val output = DataOutputStream(bootstrapStdin)
        output.writeInt(MAGIC.size)
        output.write(MAGIC)
        val entries = environment.toSortedMap()
        require(entries.size <= MAX_ENTRIES) { "Protected environment has too many entries" }
        output.writeInt(entries.size)
        entries.forEach { (key, value) ->
            require(key.matches(ENVIRONMENT_NAME)) { "Invalid environment name" }
            writeBytes(output, key.toByteArray(StandardCharsets.UTF_8))
            writeBytes(output, value.toByteArray(StandardCharsets.UTF_8))
        }
        output.writeInt(END)
        output.flush()
    }

    fun readEnvironment(input: InputStream): Map<String, String> {
        // Do not close stdin: the target child inherits the descriptor after
        // the frame is consumed and may continue reading from it.
        val source = DataInputStream(input)
        val magicSize = source.readInt()
        require(magicSize == MAGIC.size) { "Invalid Cageforge environment header" }
        val magic = ByteArray(magicSize)
        source.readFully(magic)
        check(magic.contentEquals(MAGIC)) { "Invalid Cageforge environment handshake" }
        val count = source.readInt()
        require(count in 0..MAX_ENTRIES) { "Invalid protected environment size" }
        val result = linkedMapOf<String, String>()
        repeat(count) {
            val key = String(readBytes(source), StandardCharsets.UTF_8)
            val value = String(readBytes(source), StandardCharsets.UTF_8)
            require(key.matches(ENVIRONMENT_NAME)) { "Invalid environment name" }
            result[key] = value
        }
        check(source.readInt() == END) { "Invalid Cageforge environment terminator" }
        return result
    }

    fun writeReady(output: OutputStream) {
        output.write(READY)
        output.flush()
    }

    private fun writeBytes(
        output: DataOutputStream,
        bytes: ByteArray,
    ) {
        require(bytes.size <= MAX_VALUE_BYTES) { "Protected environment value is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_VALUE_BYTES) { "Invalid protected environment field size" }
        return ByteArray(size).also(input::readFully)
    }

    private fun readFully(
        input: InputStream,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) error("Cageforge bootstrap closed before authentication")
            offset += count
        }
    }

    private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
