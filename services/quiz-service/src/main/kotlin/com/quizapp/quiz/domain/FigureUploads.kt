package com.quizapp.quiz.domain

/**
 * ブラウザが上げるファイル（ADR-0020）。種類は中身の先頭のバイトで決め、申告された種類やファイル名は信じない。
 *
 * | 中身      | 置き方                                     | 大きさ   |
 * | --------- | ------------------------------------------ | -------- |
 * | PNG・JPEG | 画像として読み直す（[FigureImage]）          | 10 MB まで |
 * | PDF       | そのまま置く。解説のリンクから新しいタブで開く | 20 MB まで |
 */
object FigureUploads {
    const val UNSUPPORTED = "PNG・JPEG の画像か、PDF を選んでください"

    /** 種類を決めるのに読む、先頭のバイト数。最も長い PNG の印に合わせる */
    const val HEAD_BYTES = 8

    /** 申告された種類から、上げてよい大きさの上限と、超えたときの理由。受け付けない種類なら null */
    fun limitFor(contentType: String): Pair<Long, String>? = when {
        ImageFormat.fromContentType(contentType) != null -> FigureImage.MAX_BYTES to FigureImage.TOO_LARGE
        contentType == FigurePdf.CONTENT_TYPE -> FigurePdf.MAX_BYTES to FigurePdf.TOO_LARGE
        else -> null
    }
}

/**
 * 解説に付ける PDF の資料。**読み直さず、そのまま置く。** ページを画像にしたり、中身を書き換えたりしない。
 *
 * PDF の中のスクリプトやリンクは、ブラウザの PDF ビューアが扱う。配るのはアプリと別のオリジン（図のドメイン）で、
 * 図と同じく CSP（`sandbox`）と `nosniff` が付く。
 */
object FigurePdf {
    const val CONTENT_TYPE = "application/pdf"
    const val MAX_BYTES = 20L * 1024 * 1024
    const val TOO_LARGE = "PDF は 20 MB までです"
    private val SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** 先頭が PDF の印（`%PDF-`）で始まるか */
    fun matches(head: ByteArray): Boolean =
        head.size >= SIGNATURE.size && SIGNATURE.indices.all { head[it] == SIGNATURE[it] }
}
