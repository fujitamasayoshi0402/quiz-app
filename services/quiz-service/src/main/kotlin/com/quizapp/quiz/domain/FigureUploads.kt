package com.quizapp.quiz.domain

/**
 * ブラウザが上げるファイル（ADR-0020）。種類は中身の先頭のバイトで決め、申告された種類やファイル名は信じない。
 *
 * | 中身      | 置き方                                                         | 大きさ   |
 * | --------- | -------------------------------------------------------------- | -------- |
 * | PNG・JPEG | 画像として読み直す（[FigureImage]）                              | 10 MB まで |
 * | PDF       | そのまま置き、1 ページ目を画像にして別に置く（[FigurePdf]）       | 20 MB まで |
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
