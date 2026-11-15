package app.pocketpilot.capability.shizuku.adb

import android.annotation.SuppressLint
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * Pairs PocketPilot's [AdbKey] with wireless debugging using the six-digit code from "Pair device with pairing
 * code" (adb's pairing_connection: TLS, then SPAKE2 over the code and the TLS keying material, then the keys).
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val code: String,
    private val key: AdbKey,
) : Closeable {
    private val socket = Socket()
    private var tls: SSLSocket? = null
    private var context: PairingContext? = null

    /** Pairs; throws [AdbWrongCodeException] when the code does not match. */
    fun pair() {
        socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = TIMEOUT_MS
        val secure = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        tls = secure
        secure.startHandshake()
        val input = DataInputStream(secure.inputStream)
        val output = secure.outputStream

        val password = code.toByteArray() + exportKeyingMaterial(secure)
        val pairing = PairingContext.create(password) ?: throw IOException("Could not start pairing")
        context = pairing

        writePacket(output, SPAKE2_MSG, pairing.message)
        val theirs = readPacket(input, SPAKE2_MSG)
        if (!pairing.initCipher(theirs)) throw AdbWrongCodeException()

        // Our peer info: type 0 (RSA public key), then the key, zero-padded to the fixed size.
        val info = ByteBuffer.allocate(PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        info.put(PEER_RSA_PUB_KEY)
        info.put(key.adbPublicKey, 0, minOf(key.adbPublicKey.size, PEER_INFO_SIZE - 1))
        val sealed = pairing.encrypt(info.array()) ?: throw IOException("Could not encrypt pairing data")
        writePacket(output, PEER_INFO, sealed)

        val reply = readPacket(input, PEER_INFO)
        val opened = pairing.decrypt(reply) ?: throw AdbWrongCodeException()
        if (opened.size != PEER_INFO_SIZE) throw IOException("Unexpected pairing reply")
    }

    private fun writePacket(
        output: OutputStream,
        type: Byte,
        payload: ByteArray,
    ) {
        val header =
            ByteBuffer
                .allocate(HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(VERSION)
                .put(type)
                .putInt(payload.size)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    private fun readPacket(
        input: DataInputStream,
        type: Byte,
    ): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val theirType = buffer.get()
        val size = buffer.int
        if (version != VERSION || theirType != type || size !in 1..MAX_PAYLOAD) throw IOException("Unexpected pairing packet")
        return ByteArray(size).also { input.readFully(it) }
    }

    override fun close() {
        context?.destroy()
        context = null
        runCatching { tls?.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val HEADER_SIZE = 6
        const val VERSION: Byte = 1
        const val SPAKE2_MSG: Byte = 0
        const val PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val EXPORTED_LABEL = "adb-label\u0000"
        const val EXPORTED_SIZE = 64

        /**
         * The TLS exporter value both ends mix into the password. The platform's Conscrypt has it but does not
         * expose it, so the hidden-API check is lifted for that one class first.
         */
        @SuppressLint("PrivateApi")
        fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/Conscrypt;")
            val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
            val export =
                conscrypt.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
            return export.invoke(null, socket, EXPORTED_LABEL, null, EXPORTED_SIZE) as ByteArray
        }
    }
}

/** The pairing code was wrong or has expired. */
class AdbWrongCodeException : IOException("Wrong or expired pairing code")

/** SPAKE2 and AES-GCM from BoringSSL, in libpocketpilot_adb.so (src/main/cpp/adb_pairing.cpp). */
internal class PairingContext private constructor(
    private var pointer: Long,
) {
    val message: ByteArray = nativeMessage(pointer)

    fun initCipher(theirMessage: ByteArray): Boolean = nativeInitCipher(pointer, theirMessage)

    fun encrypt(plain: ByteArray): ByteArray? = nativeEncrypt(pointer, plain)

    fun decrypt(sealed: ByteArray): ByteArray? = nativeDecrypt(pointer, sealed)

    fun destroy() {
        if (pointer != 0L) nativeDestroy(pointer)
        pointer = 0L
    }

    private external fun nativeMessage(pointer: Long): ByteArray

    private external fun nativeInitCipher(
        pointer: Long,
        theirMessage: ByteArray,
    ): Boolean

    private external fun nativeEncrypt(
        pointer: Long,
        plain: ByteArray,
    ): ByteArray?

    private external fun nativeDecrypt(
        pointer: Long,
        sealed: ByteArray,
    ): ByteArray?

    private external fun nativeDestroy(pointer: Long)

    companion object {
        init {
            System.loadLibrary("pocketpilot_adb")
        }

        fun create(password: ByteArray): PairingContext? = nativeCreate(password).takeIf { it != 0L }?.let(::PairingContext)

        @JvmStatic
        private external fun nativeCreate(password: ByteArray): Long
    }
}
