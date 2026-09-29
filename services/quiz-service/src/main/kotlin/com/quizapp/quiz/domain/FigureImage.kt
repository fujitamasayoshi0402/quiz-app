package com.quizapp.quiz.domain

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.HexFormat
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/** 受け付ける画像の形式。種類は中身の先頭のバイトで決め、ファイル名や申告された種類は信じない */
enum class ImageFormat(val contentType: String, internal val formatName: String, signatureHex: String) {
    PNG("image/png", "png", "89504E470D0A1A0A"),
    JPEG("image/jpeg", "jpeg", "FFD8FF"),
    ;

    /** ファイルの先頭に必ずあるバイト列 */
    private val signature: ByteArray = HexFormat.of().parseHex(signatureHex)

    companion object {
        fun detect(bytes: ByteArray): ImageFormat? = entries.firstOrNull { format ->
            bytes.size >= format.signature.size && format.signature.indices.all { bytes[it] == format.signature[it] }
        }

        fun fromContentType(contentType: String): ImageFormat? = entries.firstOrNull { it.contentType == contentType }
    }
}

/**
 * 解説図として置く画像（ADR-0020）。**上がってきたものをそのまま配らず、画像として読み直す。**
 *
 * - 読み直すと、EXIF などのメタデータ（写真を撮った場所を含む）が落ちる。画像に別の形式を重ねたファイルも、画像だけになる
 * - 写真の向き（EXIF の Orientation）は、落とす前に画素へ反映する。反映しないと、スマホの写真が横倒しになる
 * - 長い辺を [MAX_SIDE] までに縮める。解説の中で見るには十分で、配る量も減る
 * - 展開する前に、ヘッダで画素数を確かめる。小さなファイルが巨大な画像に展開されるのを防ぐ。
 *   読むときも縮めながら読み（間引き）、メモリに大きな画像を持たない
 *
 * 形式は変えない。PNG は PNG（透過を残す）、JPEG は JPEG で置く。
 */
class FigureImage private constructor(val bytes: ByteArray, val format: ImageFormat) {

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_PIXELS = 50_000_000L
        const val MAX_SIDE = 2000
        private const val JPEG_QUALITY = 0.9f

        const val UNSUPPORTED = "PNG か JPEG の画像を選んでください"
        const val TOO_LARGE = "画像は 10 MB までです"
        private const val UNREADABLE = "画像として読めません"
        private const val TOO_MANY_PIXELS = "画像の画素数が多すぎます（5,000 万画素まで）"

        fun from(uploaded: ByteArray): FigureImage {
            require(uploaded.size <= MAX_BYTES) { TOO_LARGE }
            val format = requireNotNull(ImageFormat.detect(uploaded)) { UNSUPPORTED }
            val orientation = if (format == ImageFormat.JPEG) ExifOrientation.read(uploaded) else ExifOrientation.NORMAL
            val decoded = decode(uploaded, format)
            return FigureImage(encode(redraw(decoded, orientation, format), format), format)
        }

        /** 描いた画像から作る。PDF の 1 ページ目（[FigurePdf.preview]）に使う。大きさは描く側が [MAX_SIDE] に合わせる */
        internal fun of(image: BufferedImage, format: ImageFormat): FigureImage =
            FigureImage(encode(image, format), format)

        /**
         * 画像として読む。画素数が多すぎれば、展開せずに拒む。
         * 読み込みの部品は、壊れたファイルに IOException 以外の例外も投げる。
         * どれも「読めない画像」として 400 にし、利用者に内部の例外の文言を見せない
         */
        @Suppress("TooGenericExceptionCaught")
        private fun decode(bytes: ByteArray, format: ImageFormat): BufferedImage {
            val reader = ImageIO.getImageReadersByFormatName(format.formatName).next()
            val decoded = try {
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    // メタデータは読まない。向きは ExifOrientation が読む
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width.toLong() * height > MAX_PIXELS) {
                        null
                    } else {
                        // 長い辺が MAX_SIDE の 1〜2 倍になるよう間引いて読む。残りは redraw で縮める
                        val step = max(1, max(width, height) / MAX_SIDE)
                        reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
                    }
                }
            } catch (e: Exception) {
                // CMYK の JPEG など、標準の読み込みが扱えないものもここに来る
                throw IllegalArgumentException(UNREADABLE, e)
            } finally {
                reader.dispose()
            }
            return decoded ?: throw IllegalArgumentException(TOO_MANY_PIXELS)
        }

        /** 縮めて、向きを反映して、新しい画像に描き直す。元の画像の付帯情報は持ち越さない */
        private fun redraw(source: BufferedImage, orientation: Int, format: ImageFormat): BufferedImage {
            val scale = minOf(1.0, MAX_SIDE.toDouble() / max(source.width, source.height))
            val width = max(1, (source.width * scale).roundToInt())
            val height = max(1, (source.height * scale).roundToInt())
            val turned = ExifOrientation.swapsSides(orientation)
            val type = if (format == ImageFormat.PNG) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val target = BufferedImage(if (turned) height else width, if (turned) width else height, type)
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.transform(ExifOrientation.transform(orientation, width, height))
                graphics.drawImage(source, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
            return target
        }

        private fun encode(image: BufferedImage, format: ImageFormat): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName(format.formatName).next()
            val out = ByteArrayOutputStream()
            try {
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    val param = writer.defaultWriteParam
                    if (format == ImageFormat.JPEG) {
                        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                        param.compressionQuality = JPEG_QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            return out.toByteArray()
        }
    }
}

/**
 * JPEG の EXIF から、写真の向き（Orientation、1〜8）を読む。無い、または読めなければ 1（そのまま）。
 *
 * 向きのためだけに、EXIF を読むライブラリは入れない。読むのは APP1 の先頭の IFD にある 1 つのタグだけで、
 * 位置はすべて範囲を確かめてから読む。壊れたファイルでも、例外ではなく 1 を返す。
 *
 * 位置や値は、JPEG と EXIF（TIFF）の仕様の数値そのもの。名前を付けてもかえって対応が追いにくいので、そのまま書く。
 * 読めないと分かった時点で、その場で返す。
 */
@Suppress("MagicNumber", "ReturnCount")
internal object ExifOrientation {
    const val NORMAL = 1
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP1 = 0xE1
    private const val ORIENTATION_TAG = 0x0112
    private const val IFD_ENTRY_SIZE = 12
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    fun read(jpeg: ByteArray): Int {
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == SOS || marker == EOI) return NORMAL
            val length = u16(jpeg, pos + 2, littleEndian = false)
            if (length < 2 || pos + 2 + length > jpeg.size) return NORMAL
            if (marker == APP1) orientationIn(jpeg, pos + 4, pos + 2 + length)?.let { return it }
            pos += 2 + length
        }
        return NORMAL
    }

    private fun orientationIn(bytes: ByteArray, start: Int, end: Int): Int? {
        if (end - start < EXIF_HEADER.size + 8) return null
        if (EXIF_HEADER.indices.any { bytes[start + it] != EXIF_HEADER[it] }) return null
        val tiff = start + EXIF_HEADER.size
        val littleEndian = when (String(bytes, tiff, 2, Charsets.ISO_8859_1)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        val ifdOffset = u32(bytes, tiff + 4, littleEndian)
        if (ifdOffset > end - tiff - 2) return null
        val ifd = tiff + ifdOffset.toInt()
        val count = u16(bytes, ifd, littleEndian)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * IFD_ENTRY_SIZE
            if (entry + IFD_ENTRY_SIZE > end) return null
            if (u16(bytes, entry, littleEndian) == ORIENTATION_TAG) {
                return u16(bytes, entry + 8, littleEndian).takeIf { it in 1..8 }
            }
        }
        return null
    }

    /** 向きを反映すると、縦と横が入れ替わるか（90 度・270 度の回転を含むもの） */
    fun swapsSides(orientation: Int): Boolean = orientation in 5..8

    /**
     * 幅 [width]・高さ [height] で描く画像を、正しい向きに置き直す変換。
     * 5〜8 は縦横が入れ替わり、描く先の幅は [height]、高さは [width] になる
     */
    fun transform(orientation: Int, width: Int, height: Int): AffineTransform {
        val w = width.toDouble()
        val h = height.toDouble()
        return when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)

            // 左右反転
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)

            // 180 度
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)

            // 上下反転
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)

            // 左上と右下を結ぶ線で反転
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)

            // 時計回りに 90 度
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)

            // 右上と左下を結ぶ線で反転
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)

            // 反時計回りに 90 度
            else -> AffineTransform()
        }
    }

    private fun u16(bytes: ByteArray, at: Int, littleEndian: Boolean): Int {
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    private fun u32(bytes: ByteArray, at: Int, littleEndian: Boolean): Long {
        val high = u16(bytes, if (littleEndian) at + 2 else at, littleEndian).toLong()
        val low = u16(bytes, if (littleEndian) at else at + 2, littleEndian).toLong()
        return (high shl 16) or low
    }
}
