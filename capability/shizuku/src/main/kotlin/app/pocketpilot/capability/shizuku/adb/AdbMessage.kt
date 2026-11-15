package app.pocketpilot.capability.shizuku.adb

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constants of the adb wire protocol (system/core/adb/protocol.txt). */
internal object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}

/** One adb packet: a 24-byte little-endian header and its payload. */
internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray = EMPTY,
) {
    fun toByteArray(): ByteArray =
        ByteBuffer
            .allocate(HEADER_LENGTH + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum(data))
            .putInt(command xor -1)
            .put(data)
            .array()

    companion object {
        const val HEADER_LENGTH = 24

        /** adbd never sends more than its max payload; anything larger is a broken or hostile stream. */
        private const val MAX_PAYLOAD = 1024 * 1024

        private val EMPTY = ByteArray(0)

        /** A string payload, ending in the NUL adbd expects. */
        fun of(
            command: Int,
            arg0: Int,
            arg1: Int,
            text: String,
        ) = AdbMessage(command, arg0, arg1, "$text\u0000".toByteArray())

        fun readFrom(input: DataInputStream): AdbMessage {
            val header = ByteArray(HEADER_LENGTH)
            input.readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val crc = buffer.int
            val magic = buffer.int
            if (length !in 0..MAX_PAYLOAD) throw IOException("adb packet too large: $length")
            val data = ByteArray(length)
            input.readFully(data)
            if (magic != command xor -1) throw IOException("Bad adb packet magic")
            // adbd stopped filling in the checksum with protocol version 0x01000001, so only a non-zero one is checked.
            if (crc != 0 && crc != checksum(data)) throw IOException("Bad adb packet checksum")
            return AdbMessage(command, arg0, arg1, data)
        }

        fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xff }
    }
}
