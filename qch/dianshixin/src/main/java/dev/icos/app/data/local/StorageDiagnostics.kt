package dev.icos.app.data.local

import android.os.Build
import dev.icos.app.BuildConfig
import org.json.JSONObject

internal enum class IntegrityResult { OK, FAILED, UNAVAILABLE }

/** Only these fixed fields may leave the device; query rows and exception messages stay private. */
internal data class StorageDiagnosticReport(
    val appVersionCode: Int,
    val androidApi: Int,
    val accountMode: Boolean,
    val roomVersion: Int?,
    val cipher: IntegrityResult,
    val sqlite: IntegrityResult,
    val references: IntegrityResult,
) {
    val healthy: Boolean
        get() = cipher == IntegrityResult.OK && sqlite == IntegrityResult.OK && references == IntegrityResult.OK

    fun exportJson(): String = JSONObject()
        .put("format", "icos-storage-diagnostics")
        .put("formatVersion", 1)
        .put("appVersionCode", appVersionCode)
        .put("androidApi", androidApi)
        .put("datasetMode", if (accountMode) "ACCOUNT" else "GUEST")
        .put("roomVersion", roomVersion ?: JSONObject.NULL)
        .put("cipherIntegrity", cipher.name)
        .put("sqliteIntegrity", sqlite.name)
        .put("foreignKeyIntegrity", references.name)
        .toString(2)
}

internal object StorageDiagnostics {
    fun inspect(database: DatasetDatabase, accountMode: Boolean): StorageDiagnosticReport {
        val db = database.openHelper.readableDatabase
        val roomVersion = runCatching {
            db.query("PRAGMA user_version").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0).also { check(it > 0) }
            }
        }.getOrNull()
        val encrypted = runCatching {
            db.query("PRAGMA cipher_version").use { cursor ->
                cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank()
            }
        }.getOrDefault(false)
        fun checkResult(block: () -> Boolean): IntegrityResult =
            runCatching { if (block()) IntegrityResult.OK else IntegrityResult.FAILED }
                .getOrDefault(IntegrityResult.UNAVAILABLE)
        val cipher = if (!encrypted) IntegrityResult.UNAVAILABLE else checkResult {
            db.query("PRAGMA cipher_integrity_check").use { cursor ->
                var valid = true
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) != "ok") valid = false
                }
                valid
            }
        }
        val sqlite = checkResult {
            db.query("PRAGMA integrity_check(100)").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()
            }
        }
        val references = checkResult {
            db.query("PRAGMA foreign_key_check").use { cursor -> !cursor.moveToFirst() }
        }
        return StorageDiagnosticReport(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, accountMode,
            roomVersion, cipher, sqlite, references)
    }
}
