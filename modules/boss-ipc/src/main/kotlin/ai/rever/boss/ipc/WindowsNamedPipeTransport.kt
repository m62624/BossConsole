package ai.rever.boss.ipc

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.EventLoop
import io.netty.channel.ServerChannel
import io.netty.channel.oio.AbstractOioMessageChannel
import io.netty.channel.oio.OioByteStreamChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A typed SocketAddress for the Windows `\\.\pipe\` namespace. */
internal class WindowsNamedPipeAddress(
    val name: String,
) : SocketAddress() {
    init {
        require(name.startsWith("\\\\.\\pipe\\")) {
            "Windows named pipe must use the \\\\.\\pipe\\ namespace"
        }
        require(name.length > "\\\\.\\pipe\\".length && '\u0000' !in name) {
            "Windows named pipe must have a non-empty name without NUL"
        }
    }

    override fun toString(): String = name
}

/**
 * Netty transport for the Windows named-pipe endpoints used by protected BOSS
 * processes. It is deliberately OIO: Windows named-pipe handles are blocking
 * kernel objects, and the OIO event loop gives gRPC a real byte-stream channel
 * without introducing a TCP relay or a second broker process.
 */
internal object WindowsNamedPipeTransport {
    fun newClientChannelFactory(): io.netty.channel.ChannelFactory<out Channel> =
        io.netty.channel.ChannelFactory { WindowsNamedPipeChannel() }

    fun newServerChannelFactory(): io.netty.channel.ChannelFactory<out ServerChannel> =
        io.netty.channel.ChannelFactory { WindowsNamedPipeServerChannel() }

    fun newEventLoopGroup() =
        io.netty.channel.oio
            .OioEventLoopGroup()
}

private const val NAMED_PIPE_CLIENT_BIND_ERROR = "A named-pipe client cannot bind"

private const val NAMED_PIPE_SERVER_CONNECT_ERROR = "A named-pipe server cannot connect"

private const val NAMED_PIPE_SERVER_WRITE_ERROR = "A named-pipe server cannot write"

private fun rejectClientBind(): Nothing = throw UnsupportedOperationException(NAMED_PIPE_CLIENT_BIND_ERROR)

private fun rejectServerConnect(): Nothing = throw UnsupportedOperationException(NAMED_PIPE_SERVER_CONNECT_ERROR)

private fun rejectServerWrite(): Nothing = throw UnsupportedOperationException(NAMED_PIPE_SERVER_WRITE_ERROR)

private class WindowsNamedPipeChannel : OioByteStreamChannel {
    private val config = DefaultChannelConfig(this)
    private var connection: NamedPipeConnection? = null
    private var address: WindowsNamedPipeAddress? = null

    constructor() : super(null)

    constructor(
        parent: Channel,
        connection: NamedPipeConnection,
        address: WindowsNamedPipeAddress,
    ) : super(parent) {
        this.connection = connection
        this.address = address
        activate(connection.inputStream(), connection.outputStream())
    }

    override fun config(): ChannelConfig = config

    override fun isOpen(): Boolean = connection?.isClosed != true

    override fun isInputShutdown(): Boolean = connection?.isClosed == true

    override fun shutdownInput(): ChannelFuture = close()

    override fun localAddress0(): SocketAddress? = address

    override fun remoteAddress0(): SocketAddress? = address

    override fun doBind(localAddress: SocketAddress): Unit = rejectClientBind()

    override fun doConnect(
        remoteAddress: SocketAddress,
        localAddress: SocketAddress?,
    ) {
        check(remoteAddress is WindowsNamedPipeAddress) {
            "Windows named-pipe channel requires WindowsNamedPipeAddress"
        }
        val opened = NamedPipeConnection.openClient(remoteAddress.name)
        connection = opened
        address = remoteAddress
        activate(opened.inputStream(), opened.outputStream())
    }

    override fun doDisconnect() {
        doClose()
    }
}

private abstract class UnsupportedNamedPipeServerChannel :
    AbstractOioMessageChannel(null),
    ServerChannel {
    override fun doConnect(
        remoteAddress: SocketAddress,
        localAddress: SocketAddress?,
    ): Unit = rejectServerConnect()

    override fun doDisconnect() {
        doClose()
    }

    override fun remoteAddress0(): SocketAddress? = null

    override fun doWrite(buffer: ChannelOutboundBuffer): Unit = rejectServerWrite()
}

private class WindowsNamedPipeServerChannel : UnsupportedNamedPipeServerChannel() {
    private val config = DefaultChannelConfig(this)
    private val acceptExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "boss-ipc-windows-pipe-accept").apply { isDaemon = true }
        }
    private val acceptPending = AtomicBoolean(false)
    private var address: WindowsNamedPipeAddress? = null

    @Volatile private var pendingConnection: NamedPipeConnection? = null

    @Volatile private var open = true

    override fun config(): ChannelConfig = config

    override fun metadata(): ChannelMetadata = ChannelMetadata(false)

    override fun isOpen(): Boolean = open

    override fun isActive(): Boolean = open && address != null

    override fun localAddress0(): SocketAddress? = address

    override fun doBind(localAddress: SocketAddress) {
        check(localAddress is WindowsNamedPipeAddress) {
            "Windows named-pipe server requires WindowsNamedPipeAddress"
        }
        check(address == null) { "Windows named-pipe server is already bound" }
        address = localAddress
    }

    override fun doBeginRead() {
        val boundAddress = address ?: return
        if (!open || !acceptPending.compareAndSet(false, true)) return
        acceptExecutor.execute {
            try {
                val connection = NamedPipeConnection.createServer(boundAddress.name)
                pendingConnection = connection
                connection.accept()
                eventLoop().execute {
                    pendingConnection = null
                    acceptPending.set(false)
                    if (open) {
                        pipeline().fireChannelRead(WindowsNamedPipeChannel(this, connection, boundAddress))
                        pipeline().fireChannelReadComplete()
                        if (config.isAutoRead) read()
                    } else {
                        connection.close()
                    }
                }
            } catch (error: IOException) {
                reportAcceptFailure(error)
            } catch (error: IllegalArgumentException) {
                reportAcceptFailure(error)
            }
        }
    }

    private fun reportAcceptFailure(error: Exception) {
        pendingConnection?.close()
        pendingConnection = null
        eventLoop().execute {
            acceptPending.set(false)
            if (open) pipeline().fireExceptionCaught(error)
        }
    }

    override fun doReadMessages(readBuf: MutableList<Any>): Int = 0

    override fun doClose() {
        open = false
        pendingConnection?.close()
        pendingConnection = null
        acceptExecutor.shutdownNow()
    }
}

private class NamedPipeConnection private constructor(
    private val handle: WinNT.HANDLE,
) {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun accept() {
        val connected = Kernel32.INSTANCE.ConnectNamedPipe(handle, null)
        if (!connected && Kernel32.INSTANCE.GetLastError() != WinError.ERROR_PIPE_CONNECTED) {
            throw win32Failure("ConnectNamedPipe")
        }
    }

    fun inputStream(): InputStream =
        object : InputStream() {
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
            }

            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                checkBounds(bytes.size, offset, length)
                return when {
                    length == 0 -> 0
                    isClosed -> -1
                    else -> readFromPipe(bytes, offset, length)
                }
            }

            private fun readFromPipe(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val received = IntByReference()
                val target =
                    if (offset == 0 && length == bytes.size) {
                        bytes
                    } else {
                        ByteArray(length)
                    }
                val success = Kernel32.INSTANCE.ReadFile(handle, target, length, received, null)
                if (!success) {
                    val error = Kernel32.INSTANCE.GetLastError()
                    if (error == WinError.ERROR_BROKEN_PIPE || error == WinError.ERROR_NO_DATA) {
                        return -1
                    }
                    throw win32Failure("ReadFile")
                }
                val count = received.value
                if (target !== bytes && count > 0) {
                    System.arraycopy(target, 0, bytes, offset, count)
                }
                return count
            }

            override fun close() = this@NamedPipeConnection.close()
        }

    fun outputStream(): OutputStream =
        object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                checkBounds(bytes.size, offset, length)
                var written = 0
                while (written < length) {
                    if (isClosed) throw IOException("Windows named pipe is closed")
                    val chunk = bytes.copyOfRange(offset + written, offset + length)
                    val sent = IntByReference()
                    if (!Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.size, sent, null)) {
                        throw win32Failure("WriteFile")
                    }
                    if (sent.value <= 0) throw IOException("WriteFile wrote no bytes")
                    written += sent.value
                }
            }

            override fun close() = this@NamedPipeConnection.close()
        }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            Kernel32.INSTANCE.DisconnectNamedPipe(handle)
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    companion object {
        fun createServer(name: String): NamedPipeConnection {
            val handle =
                Kernel32.INSTANCE.CreateNamedPipe(
                    name,
                    WinBase.PIPE_ACCESS_DUPLEX,
                    WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT or
                        WinBase.PIPE_REJECT_REMOTE_CLIENTS,
                    1,
                    64 * 1024,
                    64 * 1024,
                    0,
                    null,
                )
            requireValid(handle, "CreateNamedPipe")
            return NamedPipeConnection(handle)
        }

        fun openClient(name: String): NamedPipeConnection {
            if (!Kernel32.INSTANCE.WaitNamedPipe(name, 5_000)) throw win32Failure("WaitNamedPipe")
            val handle =
                Kernel32.INSTANCE.CreateFile(
                    name,
                    WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                    0,
                    null,
                    WinNT.OPEN_EXISTING,
                    WinNT.FILE_ATTRIBUTE_NORMAL,
                    null,
                )
            requireValid(handle, "CreateFile")
            return NamedPipeConnection(handle)
        }

        private fun requireValid(
            handle: WinNT.HANDLE?,
            operation: String,
        ) {
            require(
                handle != null &&
                    handle.pointer != null &&
                    handle.pointer != WinBase.INVALID_HANDLE_VALUE.pointer,
            ) {
                win32Failure(operation)
            }
        }

        private fun checkBounds(
            size: Int,
            offset: Int,
            length: Int,
        ) {
            require(offset >= 0 && length >= 0 && offset <= size - length) {
                "Invalid byte-array range"
            }
        }

        private fun win32Failure(operation: String): IOException =
            IOException("$operation failed with Windows error ${Kernel32.INSTANCE.GetLastError()}")
    }
}
