package ai.rever.boss.ipc

import com.sun.jna.Library
import com.sun.jna.Native
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
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
    private const val SERVER_READY_TIMEOUT_MS = 5_000L

    private val serverReadiness = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    fun newClientChannelFactory(): io.netty.channel.ChannelFactory<out Channel> =
        io.netty.channel.ChannelFactory { WindowsNamedPipeChannel() }

    fun newServerChannelFactory(): io.netty.channel.ChannelFactory<out ServerChannel> =
        io.netty.channel.ChannelFactory { WindowsNamedPipeServerChannel() }

    fun prepareServer(address: WindowsNamedPipeAddress) {
        serverReadiness.computeIfAbsent(address.name) { CompletableFuture() }
    }

    fun prepareForProtectedLaunch(address: WindowsNamedPipeAddress): AutoCloseable =
        NamedPipeConnection.createServer(address.name).let { connection ->
            AutoCloseable { connection.close() }
        }

    fun awaitServerReady(address: WindowsNamedPipeAddress) {
        try {
            serverReadiness
                .computeIfAbsent(address.name) { CompletableFuture() }
                .get(SERVER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw IllegalStateException("Windows named-pipe server failed to bind", error)
        } catch (error: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("Windows named-pipe server did not become ready", error)
        }
    }

    fun markServerReady(name: String) {
        serverReadiness[name]?.complete(Unit)
    }

    fun markServerFailed(
        name: String,
        error: Throwable,
    ) {
        serverReadiness[name]?.completeExceptionally(error)
    }

    fun forgetServer(address: WindowsNamedPipeAddress) {
        serverReadiness.remove(address.name)
    }

    fun newEventLoopGroup() =
        // Netty's OioEventLoopGroup is thread-per-channel and intentionally throws from
        // next(); gRPC asks its transport group for next() while building every server and
        // client. The channels retain their blocking stream implementation, while a regular
        // event-loop group supplies the executor contract required by gRPC.
        io.netty.channel.DefaultEventLoopGroup(
            0,
            ThreadFactory { runnable ->
                Thread(runnable, "boss-ipc-windows-pipe-event-loop").apply {
                    isDaemon = true
                }
            },
        )
}

private const val NAMED_PIPE_CLIENT_BIND_ERROR = "A named-pipe client cannot bind"

private const val NAMED_PIPE_SERVER_CONNECT_ERROR = "A named-pipe server cannot connect"

private const val NAMED_PIPE_SERVER_WRITE_ERROR = "A named-pipe server cannot write"

private const val NAMED_PIPE_ACCEPT_POLL_MS = 10L

/** JNA declaration whose method name must match the exported Win32 symbol exactly. */
@Suppress("FunctionName")
private interface WindowsKernel32Cancellation : Library {
    fun CancelSynchronousIo(thread: WinNT.HANDLE?): Boolean
}

private val windowsKernel32Cancellation: WindowsKernel32Cancellation by lazy {
    Native.load("kernel32", WindowsKernel32Cancellation::class.java)
}

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

    override fun isCompatible(eventLoop: EventLoop): Boolean = true

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
    override fun isCompatible(eventLoop: EventLoop): Boolean = true

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

    @Volatile private var acceptThreadHandle: WinNT.HANDLE? = null

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
        try {
            pendingConnection = NamedPipeConnection.createServer(localAddress.name)
        } catch (error: IllegalArgumentException) {
            WindowsNamedPipeTransport.markServerFailed(localAddress.name, error)
            throw error
        }
    }

    override fun doBeginRead() {
        val boundAddress = address ?: return
        if (!open || !acceptPending.compareAndSet(false, true)) return
        acceptExecutor.execute {
            val threadHandle =
                Kernel32.INSTANCE.OpenThread(
                    WinNT.THREAD_TERMINATE,
                    false,
                    Kernel32.INSTANCE.GetCurrentThreadId(),
                )
            acceptThreadHandle = threadHandle
            try {
                if (!open) return@execute
                val connection =
                    pendingConnection
                        ?: NamedPipeConnection.createServer(boundAddress.name).also {
                            pendingConnection = it
                        }
                if (!open) {
                    connection.close()
                    return@execute
                }
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
            } finally {
                acceptThreadHandle = null
                threadHandle?.let(Kernel32.INSTANCE::CloseHandle)
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
        address?.let {
            WindowsNamedPipeTransport.markServerFailed(
                it.name,
                IOException("Windows named-pipe server closed"),
            )
        }
        acceptThreadHandle?.let { windowsKernel32Cancellation.CancelSynchronousIo(it) }
        pendingConnection?.close()
        pendingConnection = null
        acceptExecutor.shutdownNow()
    }
}

private class NamedPipeConnection private constructor(
    private val name: String,
    private val handle: WinNT.HANDLE,
) {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    private fun availableBytes(): Int =
        if (isClosed) {
            0
        } else {
            val available = IntByReference()
            if (Kernel32.INSTANCE.PeekNamedPipe(handle, null, 0, null, available, null)) {
                available.value
            } else {
                val error = Kernel32.INSTANCE.GetLastError()
                if (error == WinError.ERROR_BROKEN_PIPE || error == WinError.ERROR_NO_DATA) {
                    0
                } else {
                    throw win32Failure("PeekNamedPipe")
                }
            }
        }

    fun accept() {
        while (!isClosed && !tryAccept()) {
            Thread.sleep(NAMED_PIPE_ACCEPT_POLL_MS)
        }
    }

    private fun tryAccept(): Boolean {
        if (Kernel32.INSTANCE.ConnectNamedPipe(handle, null)) {
            switchToBlockingMode()
            return true
        }
        return when (Kernel32.INSTANCE.GetLastError()) {
            WinError.ERROR_PIPE_CONNECTED -> {
                switchToBlockingMode()
                true
            }

            WinError.ERROR_NO_DATA -> {
                true
            }

            WinError.ERROR_PIPE_LISTENING -> {
                // Cageforge opens a client handle to inspect and lease the ACL. A nonblocking
                // named pipe is already in the listening state at this point, so that handoff
                // does not race the server's first ConnectNamedPipe call.
                WindowsNamedPipeTransport.markServerReady(name)
                false
            }

            else -> {
                throw win32Failure("ConnectNamedPipe")
            }
        }
    }

    private fun switchToBlockingMode() {
        val mode = IntByReference(WinBase.PIPE_WAIT)
        check(Kernel32.INSTANCE.SetNamedPipeHandleState(handle, mode, null, null)) {
            win32Failure("SetNamedPipeHandleState")
        }
    }

    fun inputStream(): InputStream =
        object : InputStream() {
            override fun available(): Int = availableBytes()

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
                    WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_NOWAIT or
                        WinBase.PIPE_REJECT_REMOTE_CLIENTS,
                    WinBase.PIPE_UNLIMITED_INSTANCES,
                    64 * 1024,
                    64 * 1024,
                    0,
                    null,
                )
            requireValid(handle, "CreateNamedPipe")
            return NamedPipeConnection(name, handle)
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
            return NamedPipeConnection(name, handle)
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
