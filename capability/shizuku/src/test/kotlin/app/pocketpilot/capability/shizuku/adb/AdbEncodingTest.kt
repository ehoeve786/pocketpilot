package app.pocketpilot.capability.shizuku.adb

import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbEncodingTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey

    @Test
    fun `certificate is a valid self-signed v3 certificate for the key`() {
        val certificate = SelfSignedCertificate.create(keyPair.public, keyPair.private)
        certificate.verify(keyPair.public)
        certificate.checkValidity()
        assertEquals(3, certificate.version)
        assertEquals(keyPair.public, certificate.publicKey)
    }

    @Test
    fun `adb public key holds the modulus, n0inv and exponent, then the name`() {
        val text = String(AdbPublicKey.encode(publicKey, "pocketpilot"))
        assertTrue(text.endsWith(" pocketpilot\u0000"))
        val raw = ByteBuffer.wrap(Base64.getDecoder().decode(text.substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, raw.capacity())
        assertEquals(64, raw.int)
        val n0inv = raw.int.toLong() and MASK
        val words = LongArray(64) { raw.int.toLong() and MASK }
        val modulus = words.foldRight(BigInteger.ZERO) { word, acc -> acc.shiftLeft(32).or(BigInteger.valueOf(word)) }
        assertEquals(publicKey.modulus, modulus)
        // n0inv is -1 / N[0] mod 2^32.
        assertEquals(MASK, (n0inv * words[0]) and MASK)
        raw.position(4 + 4 + 256 + 256)
        assertEquals(publicKey.publicExponent.toInt(), raw.int)
    }

    @Test
    fun `packets round-trip and a bad magic is refused`() {
        val bytes = AdbMessage.of(AdbProtocol.A_OPEN, 1, 0, "shell:id").toByteArray()
        val back = AdbMessage.readFrom(DataInputStream(bytes.inputStream()))
        assertEquals(AdbProtocol.A_OPEN, back.command)
        assertEquals("shell:id\u0000", String(back.data))

        bytes[20] = (bytes[20] + 1).toByte()
        assertFailsWith<IOException> { AdbMessage.readFrom(DataInputStream(bytes.inputStream())) }
    }

    @Test
    fun `an oversized payload length is refused before reading it`() {
        val header =
            ByteBuffer
                .allocate(AdbMessage.HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(AdbProtocol.A_WRTE)
                .putInt(1)
                .putInt(1)
                .putInt(Int.MAX_VALUE)
                .putInt(0)
                .putInt(AdbProtocol.A_WRTE xor -1)
                .array()
        assertFailsWith<IOException> { AdbMessage.readFrom(DataInputStream(header.inputStream())) }
    }

    private companion object {
        const val MASK = 0xffffffffL
    }
}
