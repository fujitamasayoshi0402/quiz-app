package com.quizapp.support

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * テストで上げる画像を作る。写真の向き（EXIF）や、残ってはいけない付帯情報（位置情報など）も付けられる。
 *
 * 付帯情報には [LOCATION_MARKER] を含める。読み直したあとの画像に、この文字列が残っていないことを確かめる
 */
object TestImages {
    const val LOCATION_MARKER = "GPS-35.6812N-139.7671E"

    /** 左半分が赤、右半分が青の画像。向きを反映したかを、色の位置で確かめる */
    fun halves(width: Int, height: Int, alpha: Boolean = false): BufferedImage {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) image.setRGB(x, y, if (x < width / 2) Color.RED.rgb else Color.BLUE.rgb)
        }
        return image
    }

    fun png(image: BufferedImage = halves(40, 20)): ByteArray = encode(image, "png")

    fun jpeg(image: BufferedImage = halves(40, 20)): ByteArray = encode(image, "jpeg")

    /** PNG に、文字の付帯情報（tEXt）を足す。IEND の直前に入れる */
    fun pngWithText(png: ByteArray = png(), text: String = "Location\u0000$LOCATION_MARKER"): ByteArray {
        val iend = png.size - IEND_CHUNK_SIZE
        return png.copyOfRange(0, iend) + chunk("tEXt", text.toByteArray(Charsets.ISO_8859_1)) +
            png.copyOfRange(iend, png.size)
    }

    /** 幅と高さだけが大きい PNG。ヘッダだけで、画素のデータはない */
    fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0).array()
        return PNG_SIGNATURE + chunk("IHDR", ihdr) + chunk("IEND", ByteArray(0))
    }

    /**
     * JPEG に EXIF（APP1）を足す。向き（Orientation）と、残ってはいけない文字（[LOCATION_MARKER]）を持たせる。
     * JFIF の APP0 の直後に入れる
     */
    fun jpegWithExif(orientation: Int, jpeg: ByteArray = jpeg()): ByteArray {
        val marker = LOCATION_MARKER.toByteArray(Charsets.ISO_8859_1)
        val tiff = ByteBuffer.allocate(8 + 2 + 12 + 4)
            .put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(8)
            .putShort(1)
            // Orientation（0x0112）、SHORT、1 個
            .putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
            .putInt(0)
            .array()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff + marker
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
            ByteBuffer.allocate(2).putShort((payload.size + 2).toShort()).array() + payload
        val afterApp0 = 2 + 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        return jpeg.copyOfRange(0, afterApp0) + app1 + jpeg.copyOfRange(afterApp0, jpeg.size)
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** IEND は長さ（4）・種類（4）・CRC（4）で、中身がない */
    private const val IEND_CHUNK_SIZE = 12

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        return ByteBuffer.allocate(4 + 4 + data.size + 4)
            .putInt(data.size).put(typeBytes).put(data).putInt(crc.value.toInt())
            .array()
    }

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}
