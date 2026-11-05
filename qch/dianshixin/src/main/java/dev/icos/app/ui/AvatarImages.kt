package dev.icos.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.icos.app.data.remote.AvatarBmp
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal object AvatarMediaPolicy {
    fun readPermissions(sdk: Int): Array<String> = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun needsWritePermission(sdk: Int): Boolean = sdk <= 28
}

internal object AvatarImages {
    fun readUpload(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected image")
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 20 * 1024 * 1024) { "Selected image exceeds 20 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (AvatarBmp.isSupported(bytes)) return bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L) { "Unsupported image dimensions" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode selected image")
        return try {
            val orientation = ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            try { encodeUpload(oriented) } finally { if (oriented !== bitmap) oriented.recycle() }
        } finally { bitmap.recycle() }
    }

    fun encodeUpload(source: Bitmap): ByteArray {
        val ratio = minOf(1.0, 1024.0 / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * ratio).toInt())
        val height = maxOf(1, (source.height * ratio).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            val stride = (width * 3 + 3) / 4 * 4
            val bytes = ByteArray(54 + stride * height)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('B'.code.toByte()).put('M'.code.toByte())
                putInt(bytes.size).putInt(0).putInt(54).putInt(40)
                putInt(width).putInt(height).putShort(1).putShort(24)
                putInt(0).putInt(stride * height)
            }
            val row = IntArray(width)
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                var offset = 54 + (height - 1 - y) * stride
                row.forEach { color ->
                    bytes[offset++] = Color.blue(color).toByte()
                    bytes[offset++] = Color.green(color).toByte()
                    bytes[offset++] = Color.red(color).toByte()
                }
            }
            check(AvatarBmp.isSupported(bytes)) { "Generated avatar is not a supported BMP" }
            return bytes
        } finally {
            bitmap.recycle()
        }
    }

    fun saveToAlbum(context: Context, bitmap: Bitmap, isCurrent: () -> Boolean): Uri {
        check(isCurrent()) { "Avatar or session changed" }
        val resolver = context.contentResolver
        val name = "avatar-${UUID.randomUUID()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/店时信")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "店时信")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create album directory")
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create album image")
        var completed = false
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open album image")
            output.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot encode album image") }
            check(isCurrent()) { "Avatar or session changed" }
            if (Build.VERSION.SDK_INT >= 29) {
                val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("Cannot publish album image")
            }
            completed = true
            return uri
        } finally {
            if (!completed && resolver.delete(uri, null, null) != 1) {
                throw IOException("Cannot remove incomplete album image")
            }
        }
    }
}
