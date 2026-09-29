package com.quizapp.quiz.domain

import com.quizapp.support.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 解説図として置く画像（ADR-0020）。
 *
 * 守りたいのは 3 つ。**付帯情報（位置情報など）を残さない**、**写真の向きを崩さない**、**画像でないものを置かない**。
 */
class FigureImageTest {

    private fun read(image: FigureImage): BufferedImage = ImageIO.read(image.bytes.inputStream())

    /** JPEG は色が少しずれるため、どちらの色に近いかで見る */
    private fun isRed(rgb: Int): Boolean = Color(rgb).let { it.red > 200 && it.blue < 80 }

    private fun isBlue(rgb: Int): Boolean = Color(rgb).let { it.blue > 200 && it.red < 80 }

    private fun ByteArray.contains(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    @Nested
    @DisplayName("付帯情報")
    inner class Metadata {
        @Test
        @DisplayName("JPEG の EXIF（位置情報など）は残らない")
        fun dropsExif() {
            val uploaded = TestImages.jpegWithExif(orientation = 1)
            assertThat(uploaded.contains(TestImages.LOCATION_MARKER)).isTrue()

            val image = FigureImage.from(uploaded)

            assertThat(image.format).isEqualTo(ImageFormat.JPEG)
            assertThat(image.bytes.contains(TestImages.LOCATION_MARKER)).isFalse()
            assertThat(image.bytes.contains("Exif")).isFalse()
        }

        @Test
        @DisplayName("PNG の文字の付帯情報は残らない。透過は残す")
        fun dropsPngText() {
            val uploaded = TestImages.pngWithText(TestImages.png(TestImages.halves(40, 20, alpha = true)))
            assertThat(uploaded.contains(TestImages.LOCATION_MARKER)).isTrue()

            val image = FigureImage.from(uploaded)

            assertThat(image.format).isEqualTo(ImageFormat.PNG)
            assertThat(image.bytes.contains(TestImages.LOCATION_MARKER)).isFalse()
            assertThat(read(image).colorModel.hasAlpha()).isTrue()
        }
    }

    @Nested
    @DisplayName("写真の向き")
    inner class Orientation {
        @ParameterizedTest(name = "向き {0} なら、左半分の赤は {1}")
        @CsvSource("1, 左", "3, 右", "6, 上", "8, 下")
        @DisplayName("EXIF の向きを画素に反映してから、EXIF を落とす")
        fun appliesOrientation(orientation: Int, redSide: String) {
            val image = read(FigureImage.from(TestImages.jpegWithExif(orientation)))

            val turned = orientation == 6 || orientation == 8
            assertThat(image.width).isEqualTo(if (turned) 20 else 40)
            assertThat(image.height).isEqualTo(if (turned) 40 else 20)
            // 色の境目から離れた点を見る
            val (red, blue) = when (redSide) {
                "左" -> (5 to 10) to (35 to 10)
                "右" -> (35 to 10) to (5 to 10)
                "上" -> (10 to 5) to (10 to 35)
                else -> (10 to 35) to (10 to 5)
            }
            assertThat(isRed(image.getRGB(red.first, red.second))).isTrue()
            assertThat(isBlue(image.getRGB(blue.first, blue.second))).isTrue()
        }

        @Test
        @DisplayName("壊れた EXIF は無視して、そのままの向きで置く")
        fun ignoresBrokenExif() {
            val uploaded = TestImages.jpegWithExif(orientation = 6).also {
                // TIFF の IFD の位置を、APP1 の外を指すように壊す
                val tiff = String(it, Charsets.ISO_8859_1).indexOf("MM\u0000*")
                it[tiff + 4] = 0x7F
            }

            assertThat(read(FigureImage.from(uploaded)).width).isEqualTo(40)
        }
    }

    @Nested
    @DisplayName("大きさ")
    inner class Size {
        @Test
        @DisplayName("長い辺を 2,000 px までに縮める。縦横の比は保つ")
        fun shrinksLongSide() {
            val image = read(FigureImage.from(TestImages.png(TestImages.halves(5000, 1000))))

            assertThat(image.width).isEqualTo(FigureImage.MAX_SIDE)
            assertThat(image.height).isEqualTo(400)
        }

        @Test
        @DisplayName("小さい画像は大きくしない")
        fun keepsSmallImage() {
            val image = read(FigureImage.from(TestImages.png()))

            assertThat(image.width).isEqualTo(40)
            assertThat(image.height).isEqualTo(20)
        }

        @Test
        @DisplayName("画素数が 5,000 万を超える画像は、展開する前に拒む")
        fun rejectsTooManyPixels() {
            assertThatIllegalArgumentException()
                .isThrownBy { FigureImage.from(TestImages.pngHeaderOnly(10_000, 10_000)) }
                .withMessage("画像の画素数が多すぎます（5,000 万画素まで）")
        }

        @Test
        @DisplayName("10 MB を超えるものは読まない")
        fun rejectsTooLargeFile() {
            val uploaded = TestImages.png().copyOf((FigureImage.MAX_BYTES + 1).toInt())

            assertThatIllegalArgumentException().isThrownBy {
                FigureImage.from(uploaded)
            }.withMessage(FigureImage.TOO_LARGE)
        }
    }

    @Nested
    @DisplayName("形式")
    inner class Format {
        @Test
        @DisplayName("PNG でも JPEG でもないもの（GIF、HTML など）は置かない")
        fun rejectsOtherFormats() {
            listOf("GIF89a".toByteArray(), "<html><script>alert(1)</script></html>".toByteArray()).forEach { uploaded ->
                assertThatIllegalArgumentException().isThrownBy {
                    FigureImage.from(uploaded)
                }.withMessage(FigureImage.UNSUPPORTED)
            }
        }

        @Test
        @DisplayName("先頭だけ PNG に見せかけたものは、画像として読めない")
        fun rejectsFakeSignature() {
            val uploaded = TestImages.png().copyOf(16) + "<script>alert(1)</script>".toByteArray()

            assertThatIllegalArgumentException().isThrownBy { FigureImage.from(uploaded) }.withMessage("画像として読めません")
        }

        @Test
        @DisplayName("画像のあとに別のデータを重ねたファイルも、読み直すと画像だけになる")
        fun dropsTrailingData() {
            val uploaded = TestImages.png() + "<script>${TestImages.LOCATION_MARKER}</script>".toByteArray()

            val image = FigureImage.from(uploaded)

            assertThat(image.bytes.contains(TestImages.LOCATION_MARKER)).isFalse()
        }
    }
}
