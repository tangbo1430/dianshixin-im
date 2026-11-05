package dev.icos.app.ui

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class MarkerRecord(private val digest: ByteArray, private val valid: Boolean = true) {
    fun encode(): ByteArray = MAGIC + byteArrayOf(VERSION) + digest

    fun matches(values: Map<String, *>): Boolean = valid && MessageDigest.isEqual(digest, preferenceDigest(values))

    override fun equals(other: Any?): Boolean = other is MarkerRecord && valid == other.valid && MessageDigest.isEqual(digest, other.digest)
    override fun hashCode(): Int = digest.contentHashCode() * 31 + valid.hashCode()

    companion object {
        private val MAGIC = byteArrayOf(0x49, 0x43, 0x4f, 0x53)
        private const val VERSION: Byte = 1
        private const val DIGEST_LENGTH = 32
        val INVALID = MarkerRecord(ByteArray(DIGEST_LENGTH), false)

        fun fromPreferences(values: Map<String, *>): MarkerRecord = MarkerRecord(preferenceDigest(values))

        fun decode(bytes: ByteArray): MarkerRecord? {
            if (bytes.size != MAGIC.size + 1 + DIGEST_LENGTH) return null
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[MAGIC.size] != VERSION) return null
            return MarkerRecord(bytes.copyOfRange(MAGIC.size + 1, bytes.size))
        }

        private fun preferenceDigest(values: Map<String, *>): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                values.toSortedMap().forEach { (key, value) ->
                    output.writeUTF(key)
                    when (value) {
                        is String -> { output.writeByte(1); output.writeUTF(value) }
                        is Boolean -> { output.writeByte(2); output.writeBoolean(value) }
                        is Int -> { output.writeByte(3); output.writeInt(value) }
                        is Long -> { output.writeByte(4); output.writeLong(value) }
                        is Float -> { output.writeByte(5); output.writeInt(value.toRawBits()) }
                        is Set<*> -> {
                            output.writeByte(6)
                            val strings = value.map { it as? String ?: error("Unsupported preference set") }.sorted()
                            output.writeInt(strings.size)
                            strings.forEach(output::writeUTF)
                        }
                        null -> error("Null preference value")
                        else -> error("Unsupported preference value type")
                    }
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
        }
    }
}

internal enum class LockPreferenceState { NEVER_CONFIGURED, VALID_UNCONFIGURED, VALID_CONFIGURED, CORRUPT }

internal fun classifyLockPreferences(
    marker: MarkerRecord?,
    values: Map<String, *>,
    preferenceFilePresent: Boolean,
    pinHashKey: String,
    pinSaltKey: String,
    initializedKey: String,
): LockPreferenceState {
    val hasHash = values.containsKey(pinHashKey)
    val hasSalt = values.containsKey(pinSaltKey)
    if (hasHash.xor(hasSalt)) return LockPreferenceState.CORRUPT
    if (marker == null) return if (preferenceFilePresent || values.isNotEmpty()) LockPreferenceState.CORRUPT else LockPreferenceState.NEVER_CONFIGURED
    if (!preferenceFilePresent || !marker.matches(values)) return LockPreferenceState.CORRUPT
    val hasInitialization = values[initializedKey] == true
    if (!hasHash) return if (hasInitialization) LockPreferenceState.VALID_UNCONFIGURED else LockPreferenceState.CORRUPT
    if (values[initializedKey] != false) return LockPreferenceState.CORRUPT
    return LockPreferenceState.VALID_CONFIGURED
}
