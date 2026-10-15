package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.FigureSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.net.URI
import java.time.Instant
import java.util.UUID

data class CreateFigureRequest(
    @field:Schema(description = "draw.io の原本（`<mxfile>` の XML）。1 MB まで")
    val source: String,
    @field:Schema(description = "書き出した SVG。ルートが SVG の名前空間の `svg` 要素であること。1 MB まで")
    val svg: String,
)

data class FigureResponse(val id: UUID)

data class FigureDetailResponse(
    val id: UUID,
    @field:Schema(
        description = "種類。drawio（draw.io の原本と SVG。描き直せる）、image（PNG か JPEG）、pdf（PDF の資料。本文の中には 1 ページ目の画像を出す）",
        allowableValues = ["drawio", "image", "pdf"],
    )
    val kind: String,
)

/** 図の一覧の 1 件（DEV-165） */
data class FigureSummaryResponse(
    val id: UUID,
    @field:Schema(description = "種類。drawio、image、pdf", allowableValues = ["drawio", "image", "pdf"])
    val kind: String,
    val createdAt: Instant,
    @field:Schema(description = "解説でこの図を使っている、公開中か下書きの問題の数。1 以上なら消せない")
    val quizCount: Int,
    @field:Schema(description = "解説でこの図を使っている、ゴミ箱の問題の数。消すと、それらの問題はゴミ箱から戻せなくなる")
    val trashedQuizCount: Int,
) {
    companion object {
        fun from(figure: FigureSummary) = FigureSummaryResponse(
            figure.id,
            figure.kind.name.lowercase(),
            figure.createdAt,
            figure.quizCount,
            figure.trashedQuizCount,
        )
    }
}

data class StartFigureUploadRequest(
    @field:Schema(description = "ファイルの種類。image/png、image/jpeg、application/pdf のどれか。置くときは本体の先頭のバイトで決める")
    val contentType: String,
    @field:Schema(description = "本体の大きさ（バイト）。画像は 10 MB、PDF は 20 MB まで。署名に含めるため、上げる本体の大きさと一致させる")
    val size: Long,
)

data class FigureUploadResponse(
    @field:Schema(description = "図の ID。上げ終えたら、この ID で完了を伝える")
    val id: UUID,
    @field:Schema(description = "本体を PUT する URL（期限 5 分）。検査の前の置き場所を指し、ここに置いたものは誰にも配られない")
    val url: URI,
    @field:Schema(description = "PUT に付けるヘッダ。署名に含めているため、この値のまま送る")
    val headers: Map<String, String>,
)

data class FigureSourceResponse(
    @field:Schema(description = "draw.io の原本。描き直すときに draw.io へ読み込ませる")
    val source: String,
)
