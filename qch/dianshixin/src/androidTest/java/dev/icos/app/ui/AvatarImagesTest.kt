package dev.icos.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.data.remote.AvatarBmp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AvatarImagesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encoderProducesServerBmpWithCorrectRowsPaddingAndWhiteTransparency() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.RED)
        source.setPixel(1, 0, Color.BLUE)
        source.setPixel(2, 1, Color.GREEN)
        try {
            val bytes = AvatarImages.encodeUpload(source)
            assertTrue(AvatarBmp.isSupported(bytes))
            val decoded = checkNotNull(AvatarBmp.decode(bytes))
            try {
                assertEquals(78, bytes.size)
                assertEquals(3, decoded.width)
                assertEquals(2, decoded.height)
                assertEquals(Color.RED, decoded.getPixel(0, 0))
                assertEquals(Color.BLUE, decoded.getPixel(1, 0))
                assertEquals(Color.GREEN, decoded.getPixel(2, 1))
                assertEquals(Color.WHITE, decoded.getPixel(2, 0))
            } finally { decoded.recycle() }
        } finally { source.recycle() }
    }

    @Test fun pngAndJpegImportConvertToBmpAndRespectExif() {
        val source = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val file = File.createTempFile("avatar-input-", ".tmp", context.cacheDir)
        try {
            listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG).forEach { format ->
                file.outputStream().use { assertTrue(source.compress(format, 100, it)) }
                if (format == Bitmap.CompressFormat.JPEG) {
                    ExifInterface(file.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                        saveAttributes()
                    }
                }
                val bytes = AvatarImages.readUpload(context, Uri.fromFile(file))
                assertTrue(AvatarBmp.isSupported(bytes))
                val decoded = checkNotNull(AvatarBmp.decode(bytes))
                try {
                    assertEquals(if (format == Bitmap.CompressFormat.JPEG) 5 else 9, decoded.width)
                    assertEquals(if (format == Bitmap.CompressFormat.JPEG) 9 else 5, decoded.height)
                } finally { decoded.recycle() }
            }
            val original = AvatarImages.encodeUpload(source)
            file.writeBytes(original)
            assertArrayEquals(original, AvatarImages.readUpload(context, Uri.fromFile(file)))
        } finally { assertTrue(file.delete()); source.recycle() }
    }

    @Test fun conversionCapsLongestSideAt1024() {
        val source = Bitmap.createBitmap(2049, 1024, Bitmap.Config.ARGB_8888)
        try {
            val decoded = checkNotNull(AvatarBmp.decode(AvatarImages.encodeUpload(source)))
            try {
                assertEquals(1024, decoded.width)
                assertEquals(511, decoded.height)
            } finally { decoded.recycle() }
        } finally { source.recycle() }
    }

    @Test fun invalidAndOversizedInputAreRejected() {
        val file = File.createTempFile("avatar-invalid-", ".tmp", context.cacheDir)
        try {
            file.writeText("not an image")
            assertThrows(IllegalArgumentException::class.java) { AvatarImages.readUpload(context, Uri.fromFile(file)) }
            file.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                repeat(20) { output.write(buffer) }
                output.write(0)
            }
            assertThrows(IllegalArgumentException::class.java) { AvatarImages.readUpload(context, Uri.fromFile(file)) }
        } finally { assertTrue(file.delete()) }
    }

    @Test fun albumSavePublishesDecodablePngWithoutLegacyWritePermission() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val uri = try { AvatarImages.saveToAlbum(context, bitmap) { true } } finally { bitmap.recycle() }
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Pictures/店时信/", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(3, decoded.width)
                assertEquals(2, decoded.height)
                assertEquals(Color.MAGENTA, decoded.getPixel(0, 0))
            } finally { decoded.recycle() }
        } finally { assertEquals(1, context.contentResolver.delete(uri, null, null)) }
    }

    @Test fun staleSessionDoesNotCreateOrPublishAnAlbumImage() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        fun imageCount(): Int = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/店时信/"), null,
        )!!.use { it.count }
        val before = imageCount()
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IllegalStateException::class.java) { AvatarImages.saveToAlbum(context, bitmap) { false } }
            assertEquals(before, imageCount())
            var checks = 0
            assertThrows(IllegalStateException::class.java) { AvatarImages.saveToAlbum(context, bitmap) { ++checks == 1 } }
            assertEquals(2, checks)
            assertEquals(before, imageCount())
        } finally { bitmap.recycle() }
    }
}
