package com.arpalaser.iot.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash de contraseñas con PBKDF2-HMAC-SHA256 + sal aleatoria por usuario (ISO/IEC 27400 / OWASP MASVS-CRYPTO).
 * Las contraseñas NUNCA se guardan en texto plano: solo se almacena "iteraciones:sal:hash".
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 60_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun hash(password: CharArray, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val dk = derive(password, salt, iterations)
        return "$iterations:${salt.toHex()}:${dk.toHex()}"
    }

    /** Verificación en tiempo constante para evitar ataques de temporización. */
    fun verify(password: CharArray, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = parts[1].hexToBytes() ?: return false
        val expected = parts[2].hexToBytes() ?: return false
        val actual = derive(password, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
