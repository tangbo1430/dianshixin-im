package dev.icos.app.fileio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Narrow SAF/share adapter. Only granted content:// URIs are read, once per intent token. */
class AndroidFileIoAdapter(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.noBackupFilesDir, "file-import-staging")
    private val consumedIntents = ConcurrentHashMap.newKeySet<String>()

    data class Staged(val id: UUID, val file: File, val sha256: String, val bytes: Long)

    fun stage(uri: Uri, intentToken: String, maxBytes: Long = TabularFileCore.MAX_INPUT_BYTES, permissionAlreadyGranted: Boolean = false): Staged {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority != null) { "CONTENT_URI_REQUIRED" }
        val intentToken = intentToken.trim()
        require(intentToken.isNotBlank() && intentToken.length <= 256 && consumedIntents.add(intentToken)) { "DUPLICATE_INTENT" }
        require(maxBytes in 1..TabularFileCore.MAX_INPUT_BYTES)
        if (!permissionAlreadyGranted) runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        check(root.isDirectory || root.mkdirs())
        val id = UUID.randomUUID()
        val originalName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val extension = originalName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT).takeIf { it in setOf("csv", "xlsx", "xls") }
            ?: when (resolver.getType(uri)?.lowercase(java.util.Locale.ROOT)) {
                "text/csv" -> "csv"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                "application/vnd.ms-excel" -> "xls"
                else -> "csv"
            }
        val target = File(root, "$id.$extension")
        val temp = File(root, "$id.$extension.pending")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count = Math.addExact(count, n.toLong())
                        require(count <= maxBytes) { "INPUT_TOO_LARGE" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("CONTENT_UNREADABLE")
            android.system.Os.rename(temp.absolutePath, target.absolutePath)
            return Staged(id, target, digest.digest().joinToString("") { "%02x".format(it) }, count)
        } catch (failure: Exception) {
            temp.delete(); target.delete(); consumedIntents.remove(intentToken)
            throw failure
        }
    }

    fun open(staged: Staged): java.io.InputStream {
        require(staged.file.parentFile?.canonicalFile == root.canonicalFile && staged.file.nameWithoutExtension == staged.id.toString()) { "STAGING_PATH_INVALID" }
        return staged.file.inputStream()
    }

    fun remove(staged: Staged) {
        require(staged.file.parentFile?.canonicalFile == root.canonicalFile && staged.file.nameWithoutExtension == staged.id.toString()) { "STAGING_PATH_INVALID" }
        staged.file.delete()
    }

    fun fileProviderRoot(): File = File(root.parentFile, "file-export")
}
