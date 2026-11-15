package app.pocketpilot.capability.shizuku.adb

import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A minimal self-signed X.509 v3 certificate for the adb TLS handshake. adbd only compares the key in it with
 * the paired keys, so the subject and dates are fixed. Written out by hand in DER to avoid a crypto library.
 */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(
        publicKey: PublicKey,
        privateKey: PrivateKey,
    ): X509Certificate {
        val algorithm = sequence(tag(OID, SHA256_WITH_RSA), byteArrayOf(NULL, 0))
        val name = sequence(set(sequence(tag(OID, COMMON_NAME), tag(UTF8_STRING, "00".toByteArray()))))
        val validity = sequence(tag(UTC_TIME, "700101000000Z".toByteArray()), tag(UTC_TIME, "491231235959Z".toByteArray()))
        val tbs =
            sequence(
                tag(CONTEXT_0, tag(INTEGER, byteArrayOf(2))),
                tag(INTEGER, byteArrayOf(1)),
                algorithm,
                name,
                validity,
                name,
                publicKey.encoded,
            )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(tbs)
        val der = sequence(tbs, algorithm, tag(BIT_STRING, byteArrayOf(0) + signer.sign()))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun sequence(vararg parts: ByteArray) = tag(SEQUENCE, concat(parts))

    private fun set(vararg parts: ByteArray) = tag(SET, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun tag(
        tag: Byte,
        content: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toInt())
        val size = content.size
        when {
            size < 0x80 -> {
                out.write(size)
            }

            size < 0x100 -> {
                out.write(0x81)
                out.write(size)
            }

            else -> {
                out.write(0x82)
                out.write(size shr 8)
                out.write(size and 0xff)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private const val INTEGER: Byte = 0x02
    private const val BIT_STRING: Byte = 0x03
    private const val NULL: Byte = 0x05
    private const val OID: Byte = 0x06
    private const val UTF8_STRING: Byte = 0x0c
    private const val UTC_TIME: Byte = 0x17
    private const val SEQUENCE: Byte = 0x30
    private const val SET: Byte = 0x31
    private const val CONTEXT_0: Byte = 0xa0.toByte()
}
