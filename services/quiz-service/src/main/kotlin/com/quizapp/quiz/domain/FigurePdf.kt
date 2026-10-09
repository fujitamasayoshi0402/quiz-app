package com.quizapp.quiz.domain

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * 解説に付ける PDF の資料。**本体は読み直さず、そのまま置く。** 中身を書き換えない。
 *
 * PDF の中のスクリプトやリンクは、ブラウザの PDF ビューアが扱う。配るのはアプリと別のオリジン（図のドメイン）で、
 * 図と同じく CSP（`sandbox`）と `nosniff` が付く。
 *
 * 解説の中には、1 ページ目を画像にしたもの（[preview]）を出し、押すと PDF を開く（ADR-0021）。
 */
object FigurePdf {
    const val CONTENT_TYPE = "application/pdf"
    const val MAX_BYTES = 20L * 1024 * 1024
    const val TOO_LARGE = "PDF は 20 MB までです"
    private const val ENCRYPTED = "パスワードのかかった PDF は入れられません"
    private const val UNREADABLE = "PDF として読めません"
    private const val HALF_PIXEL = 0.5f
    private val SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** 先頭が PDF の印（`%PDF-`）で始まるか */
    fun matches(head: ByteArray): Boolean =
        head.size >= SIGNATURE.size && SIGNATURE.indices.all { head[it] == SIGNATURE[it] }

    /**
     * 1 ページ目を画像にする。長い辺を [FigureImage.MAX_SIDE] に合わせて描き、JPEG にする。
     * 資料は写真を含むことが多く、PNG では大きさが中身によって大きく変わる。細部は、押して開いた PDF で見る。
     *
     * 開くのにパスワードが要る PDF は受け付けない。解説を見る人も開けない。
     */
    fun preview(pdf: ByteArray): FigureImage =
        FigureImage.of(renderFirstPage(pdf) ?: throw IllegalArgumentException(UNREADABLE), ImageFormat.JPEG)

    /**
     * ページがない、または大きさのないページなら null。
     * 読み込みの部品は、壊れたファイルに IOException 以外の例外も投げる。どれも「読めない PDF」として 400 にし、
     * 利用者に内部の例外の文言を見せない
     */
    @Suppress("TooGenericExceptionCaught")
    private fun renderFirstPage(pdf: ByteArray): BufferedImage? = try {
        Loader.loadPDF(pdf).use(::render)
    } catch (e: InvalidPasswordException) {
        throw IllegalArgumentException(ENCRYPTED, e)
    } catch (e: Exception) {
        throw IllegalArgumentException(UNREADABLE, e)
    }

    private fun render(document: PDDocument): BufferedImage? {
        val longSide = document.pages.firstOrNull()?.cropBox?.let { max(it.width, it.height) }
        if (longSide == null || longSide <= 0) return null
        return PDFRenderer(document)
            // 埋め込まれた大きな画像を、描く大きさまで間引いて読む。メモリに原寸の画像を持たない
            .apply { isSubsamplingAllowed = true }
            // 画素数は切り捨てで決まる。割り算の誤差で 1 px 欠けないよう、半画素ぶん足す
            .renderImage(0, (FigureImage.MAX_SIDE + HALF_PIXEL) / longSide, ImageType.RGB)
    }
}
