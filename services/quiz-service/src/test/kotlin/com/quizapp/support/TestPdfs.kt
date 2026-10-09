package com.quizapp.support

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.awt.Color
import java.io.ByteArrayOutputStream

/** テストで上げる PDF を作る */
object TestPdfs {
    /**
     * 1 ページ目の左半分が赤、右半分が青の PDF。大きさは [width]×[height] ポイント。
     * 2 ページ目からは緑一色にする。1 ページ目を画像にしたかを、色で確かめる
     */
    fun halves(width: Float = 400f, height: Float = 200f, pages: Int = 1): ByteArray = PDDocument().use { document ->
        repeat(pages) { index ->
            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)
            val (left, right) = if (index == 0) Color.RED to Color.BLUE else Color.GREEN to Color.GREEN
            PDPageContentStream(document, page).use { content ->
                content.setNonStrokingColor(left)
                content.addRect(0f, 0f, width / 2, height)
                content.fill()
                content.setNonStrokingColor(right)
                content.addRect(width / 2, 0f, width / 2, height)
                content.fill()
            }
        }
        save(document)
    }

    /** パスワードを掛けた PDF。[userPassword] が空なら、開くのには要らない（印刷や編集の制限だけ） */
    fun protected(userPassword: String): ByteArray = Loader.loadPDF(halves()).use { document ->
        document.protect(StandardProtectionPolicy("owner-password", userPassword, AccessPermission()))
        save(document)
    }

    /** ページのない PDF */
    fun empty(): ByteArray = PDDocument().use(::save)

    private fun save(document: PDDocument): ByteArray = ByteArrayOutputStream().also { document.save(it) }.toByteArray()
}
