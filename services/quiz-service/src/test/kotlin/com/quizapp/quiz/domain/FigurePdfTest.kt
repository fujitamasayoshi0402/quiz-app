package com.quizapp.quiz.domain

import com.quizapp.support.TestPdfs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** 解説の中に出す、PDF の 1 ページ目の画像（ADR-0021） */
class FigurePdfTest {

    private fun read(image: FigureImage): BufferedImage = ImageIO.read(image.bytes.inputStream())

    /** JPEG は色が少しずれるため、どちらの色に近いかで見る */
    private fun isRed(rgb: Int): Boolean = Color(rgb).let { it.red > 200 && it.green < 80 && it.blue < 80 }

    private fun isBlue(rgb: Int): Boolean = Color(rgb).let { it.blue > 200 && it.red < 80 && it.green < 80 }

    @Test
    @DisplayName("1 ページ目だけを、JPEG の画像にする")
    fun rendersFirstPage() {
        val preview = FigurePdf.preview(TestPdfs.halves(pages = 3))

        assertThat(preview.format).isEqualTo(ImageFormat.JPEG)
        val image = read(preview)
        assertThat(isRed(image.getRGB(image.width / 4, image.height / 2))).isTrue()
        assertThat(isBlue(image.getRGB(image.width * 3 / 4, image.height / 2))).isTrue()
    }

    @Test
    @DisplayName("ページの長い辺を 2,000 px に合わせる。横長でも縦長でも、縦横の比は変えない")
    fun fitsLongSide() {
        val wide = read(FigurePdf.preview(TestPdfs.halves(width = 400f, height = 200f)))
        assertThat(wide.width).isEqualTo(FigureImage.MAX_SIDE)
        assertThat(wide.height).isEqualTo(FigureImage.MAX_SIDE / 2)

        // A4 の縦（595×842 ポイント）
        val tall = read(FigurePdf.preview(TestPdfs.halves(width = 595f, height = 842f)))
        assertThat(tall.height).isEqualTo(FigureImage.MAX_SIDE)
        assertThat(tall.width).isBetween(1412, 1414)
    }

    @Test
    @DisplayName("開くのにパスワードが要る PDF は受け付けない。解説を見る人も開けない")
    fun rejectsPasswordProtected() {
        assertThatIllegalArgumentException()
            .isThrownBy { FigurePdf.preview(TestPdfs.protected(userPassword = "user-password")) }
            .withMessage("パスワードのかかった PDF は入れられません")
    }

    @Test
    @DisplayName("印刷や編集を制限しているだけの PDF は、開けるので画像にできる")
    fun acceptsOwnerPasswordOnly() {
        val image = read(FigurePdf.preview(TestPdfs.protected(userPassword = "")))

        assertThat(image.width).isEqualTo(FigureImage.MAX_SIDE)
    }

    @Test
    @DisplayName("PDF として読めないものと、ページのない PDF は受け付けない")
    fun rejectsUnreadable() {
        assertThatIllegalArgumentException()
            .isThrownBy { FigurePdf.preview("%PDF-1.4\n壊れた中身".toByteArray()) }
            .withMessage("PDF として読めません")
        assertThatIllegalArgumentException()
            .isThrownBy { FigurePdf.preview(TestPdfs.empty()) }
            .withMessage("PDF として読めません")
    }
}
