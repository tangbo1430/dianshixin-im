package dev.icos.app.data.remote

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict reader for the server's uncompressed 24/32-bit BI_RGB BMP subset. */
internal object AvatarBmp {
    private data class Layout(val width: Int, val height: Int, val topDown: Boolean, val bits: Int, val offset: Int, val stride: Int)

    private fun layout(bytes: ByteArray): Layout? = runCatching {
        require(bytes.size in 54..20 * 1024 * 1024)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get(0) == 'B'.code.toByte() && b.get(1) == 'M'.code.toByte())
        val declared = b.getInt(2).toLong() and 0xffffffffL
        val offset = b.getInt(10)
        val dib = b.getInt(14)
        require(declared == bytes.size.toLong() && dib >= 40 && 14L + dib <= bytes.size)
        val width = b.getInt(18)
        val signedHeight = b.getInt(22)
        val planes = b.getShort(26).toInt() and 0xffff
        val bits = b.getShort(28).toInt() and 0xffff
        val compression = b.getInt(30)
        require(width in 1..8192 && signedHeight != 0 && signedHeight != Int.MIN_VALUE)
        val height = kotlin.math.abs(signedHeight)
        require(height <= 8192 && planes == 1 && bits in setOf(24, 32) && compression == 0)
        val pixels = width.toLong() * height
        require(pixels <= 40_000_000L)
        val stride = (((width.toLong() * bits + 31) / 32) * 4).also { require(it <= Int.MAX_VALUE) }.toInt()
        require(offset >= 14 + dib && offset.toLong() + stride.toLong() * height == bytes.size.toLong())
        Layout(width, height, signedHeight < 0, bits, offset, stride)
    }.getOrNull()

    fun isSupported(bytes: ByteArray): Boolean = layout(bytes) != null

    fun decode(bytes: ByteArray): Bitmap? {
        val l = layout(bytes) ?: return null
        return runCatching {
            val pixels = IntArray(l.width * l.height)
            for (y in 0 until l.height) {
                val sourceY = if (l.topDown) y else l.height - 1 - y
                var p = l.offset + sourceY * l.stride
                for (x in 0 until l.width) {
                    val blue = bytes[p++].toInt() and 0xff
                    val green = bytes[p++].toInt() and 0xff
                    val red = bytes[p++].toInt() and 0xff
                    val alpha = if (l.bits == 32) bytes[p++].toInt() and 0xff else 0xff
                    pixels[y * l.width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            Bitmap.createBitmap(pixels, l.width, l.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}
