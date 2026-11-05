package dev.icos.app.ui

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object AppLockCrypto {
    fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, AppLockViewModel.ITERATIONS, AppLockViewModel.HASH_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun matches(stored: ByteArray, candidate: ByteArray): Boolean = MessageDigest.isEqual(stored, candidate)

    fun lockoutDuration(failures: Int): Long = when {
        failures < 5 -> 0L
        else -> (1_000L shl (failures - 5).coerceAtMost(8)).coerceAtMost(300_000L)
    }
}
