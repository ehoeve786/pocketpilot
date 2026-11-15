package app.pocketpilot.capability.shizuku.adb

import app.pocketpilot.capability.shizuku.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.ADB_AUTH_TOKEN
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_AUTH
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_CLSE
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_CNXN
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_MAXDATA
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_OKAY
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_OPEN
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_STLS
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_STLS_VERSION
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_VERSION
import app.pocketpilot.capability.shizuku.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * A minimal adb client: connects to adbd on this phone (TLS for wireless debugging, RSA auth for TCP mode) and
 * runs services such as `shell:` one at a time.
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
) : Closeable {
    private val socket = Socket()
    private var tls: SSLSocket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    /** Connects and authenticates; throws [AdbAuthException] when adbd does not accept this key. */
    fun connect() {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        input = DataInputStream(socket.getInputStream())
        output = socket.getOutputStream()

        write(AdbMessage.of(A_CNXN, A_VERSION, A_MAXDATA, "host::"))
        var message = read()
        if (message.command == A_STLS) {
            write(AdbMessage(A_STLS, A_STLS_VERSION, 0))
            val secure = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            try {
                secure.startHandshake()
            } catch (e: IOException) {
                throw AdbAuthException("Wireless debugging refused PocketPilot's key; pair again", e)
            }
            tls = secure
            input = DataInputStream(secure.inputStream)
            output = secure.outputStream
            message = read()
        } else if (message.command == A_AUTH && message.arg0 == ADB_AUTH_TOKEN) {
            write(AdbMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data)))
            message = read()
            if (message.command == A_AUTH) {
                // Not a known key: offering it shows "Allow debugging?" on the phone.
                write(AdbMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey))
                socket.soTimeout = PROMPT_TIMEOUT_MS
                message = read()
                socket.soTimeout = READ_TIMEOUT_MS
            }
        }
        if (message.command != A_CNXN) throw AdbAuthException("adb did not accept PocketPilot's key")
    }

    /** Opens [service] (such as `shell:id`) and hands each chunk of output to [onOutput] until adbd closes it. */
    fun run(
        service: String,
        onOutput: (ByteArray) -> Unit = {},
    ) {
        val localId = 1
        write(AdbMessage.of(A_OPEN, localId, 0, service))
        var message = read()
        if (message.command == A_CLSE) return
        if (message.command != A_OKAY) throw IOException("adb did not open $service")
        while (true) {
            message = read()
            val remoteId = message.arg0
            when (message.command) {
                A_WRTE -> {
                    if (message.data.isNotEmpty()) onOutput(message.data)
                    write(AdbMessage(A_OKAY, localId, remoteId))
                }

                A_CLSE -> {
                    write(AdbMessage(A_CLSE, localId, remoteId))
                    return
                }

                A_OKAY -> {
                    continue
                }

                else -> {
                    throw IOException("Unexpected adb packet ${message.command}")
                }
            }
        }
    }

    private fun write(message: AdbMessage) {
        output.write(message.toByteArray())
        output.flush()
    }

    private fun read(): AdbMessage = AdbMessage.readFrom(input)

    override fun close() {
        runCatching { tls?.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 30_000
        const val PROMPT_TIMEOUT_MS = 60_000
    }
}

/** adbd refused this key: wireless debugging is not paired with PocketPilot, or the prompt was declined. */
class AdbAuthException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
