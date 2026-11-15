package app.pocketpilot.capability.shizuku.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The public key in adb's own format (libcrypto_utils android_pubkey.c), base64 encoded and followed by a name,
 * as adbd stores it after pairing or "Always allow".
 */
internal object AdbPublicKey {
    private const val MODULUS_BYTES = 2048 / 8
    private const val MODULUS_WORDS = MODULUS_BYTES / 4
    private const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    fun encode(
        key: RSAPublicKey,
        name: String,
    ): ByteArray {
        require(key.modulus.bitLength() <= MODULUS_BYTES * 8) { "adb keys are 2048-bit RSA" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv =
            key.modulus
                .mod(r32)
                .modInverse(r32)
                .negate()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.valueOf(2), key.modulus)
        val buffer =
            ByteBuffer
                .allocate(STRUCT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MODULUS_WORDS)
                .putInt(n0inv.toInt())
        words(key.modulus).forEach { buffer.putInt(it) }
        words(rr).forEach { buffer.putInt(it) }
        buffer.putInt(key.publicExponent.toInt())
        return Base64.getEncoder().encode(buffer.array()) + " $name\u0000".toByteArray()
    }

    /** [value] as little-endian 32-bit words, padded with zeros. */
    private fun words(value: BigInteger): IntArray {
        val mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
        return IntArray(MODULUS_WORDS) { i -> value.shiftRight(32 * i).and(mask).toInt() }
    }
}
