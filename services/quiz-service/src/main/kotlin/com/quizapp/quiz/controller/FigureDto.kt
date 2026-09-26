package com.quizapp.quiz.controller

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class CreateFigureRequest(
    @field:Schema(description = "draw.io の原本（`<mxfile>` の XML）。1 MB まで")
    val source: String,
    @field:Schema(description = "書き出した SVG。ルートが SVG の名前空間の `svg` 要素であること。1 MB まで")
    val svg: String,
)

data class FigureResponse(val id: UUID)

data class FigureSourceResponse(
    @field:Schema(description = "draw.io の原本。描き直すときに draw.io へ読み込ませる")
    val source: String,
)
