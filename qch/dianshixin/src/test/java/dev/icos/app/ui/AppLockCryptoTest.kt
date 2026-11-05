package dev.icos.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockCryptoTest {
    @Test fun derivesSaltedPbkdf2AndComparesInConstantTimeApi() {
        val pin = "123456".toCharArray()
        val salt = ByteArray(AppLockViewModel.SALT_LENGTH) { it.toByte() }
        val first = AppLockCrypto.derive(pin, salt)
        val second = AppLockCrypto.derive("123456".toCharArray(), salt.copyOf())
        assertArrayEquals(first, second)
        assertTrue(AppLockCrypto.matches(first, second))
        assertFalse(AppLockCrypto.matches(first, AppLockCrypto.derive("123457".toCharArray(), salt)))
        assertFalse(AppLockCrypto.matches(first, AppLockCrypto.derive("123456".toCharArray(), ByteArray(salt.size) { (it + 1).toByte() })))
    }

    @Test fun lockoutBackoffIsBounded() {
        assertTrue(AppLockCrypto.lockoutDuration(4) == 0L)
        assertTrue(AppLockCrypto.lockoutDuration(5) == 1_000L)
        assertTrue(AppLockCrypto.lockoutDuration(6) == 2_000L)
        assertTrue(AppLockCrypto.lockoutDuration(30) == 256_000L)
    }
}
