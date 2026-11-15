package app.pocketpilot.capability.shizuku.adb

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.Socket
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * PocketPilot's adb identity: a 2048-bit RSA key that wireless debugging pairs with. The private key is kept in
 * [file], encrypted with an AES key from the Android Keystore, so only this app on this phone can use it.
 */
class AdbKey(
    private val file: File,
) {
    private val privateKey: RSAPrivateKey = loadOrCreate()
    private val publicKey: RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey
    private val certificate: X509Certificate by lazy { SelfSignedCertificate.create(publicKey, privateKey) }

    /** The key as adbd stores it, sent during pairing and classic adb auth. */
    val adbPublicKey: ByteArray by lazy { AdbPublicKey.encode(publicKey, NAME) }

    /** Signs an adb AUTH token: PKCS#1 v1.5 padding around a SHA-1 DigestInfo, as adbd's RSA_verify expects. */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SHA1_PADDING)
        return cipher.doFinal(token)
    }

    /** TLS 1.3 with this key as the client certificate. adbd's certificate is self-signed, so any is accepted. */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager()), arrayOf(TrustAll), SecureRandom()) }
    }

    private fun keyManager() =
        object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyTypes: Array<out String>,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = if ("RSA" in keyTypes) ALIAS else null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null

            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out Principal>?,
            ): Array<String>? = null

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out Principal>?,
            ): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = null
        }

    private fun loadOrCreate(): RSAPrivateKey {
        val stored = runCatching { if (file.exists()) decrypt(file.readBytes()) else null }.getOrNull()
        if (stored != null) {
            runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(stored)) as RSAPrivateKey }
                .getOrNull()
                ?.let { return it }
        }
        // A key that cannot be read (Keystore reset, file damaged) is replaced; the phone has to be paired again.
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4))
        val key = generator.generateKeyPair().private as RSAPrivateKey
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(encrypt(key.encoded))
        check(temp.renameTo(file)) { "Could not save the adb key" }
        return key
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    private fun wrappingKey(): Key {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        store.getKey(WRAPPING_ALIAS, null)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** adbd's certificate is self-signed; pairing and the paired key list are what authenticate it. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val NAME = "pocketpilot"
        const val ALIAS = "adb"
        const val KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_ALIAS = "pocketpilot_adb_key"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val AES_BITS = 256
        const val KEY_BITS = 2048
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "pocketpilot-adbkey".toByteArray()

        /** 0x00 0x01, 0xff padding, 0x00, then the DER prefix of a SHA-1 DigestInfo: 256 - 20 bytes in all. */
        val SHA1_PADDING: ByteArray =
            byteArrayOf(0x00, 0x01) +
                ByteArray(256 - 20 - 2 - 1 - 15) { 0xff.toByte() } +
                byteArrayOf(0x00) +
                byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
