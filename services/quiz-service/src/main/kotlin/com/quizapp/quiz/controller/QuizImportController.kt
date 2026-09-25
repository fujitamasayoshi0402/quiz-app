package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.QuizImportRejectedException
import com.quizapp.quiz.usecase.QuizImportRow
import com.quizapp.quiz.usecase.QuizImportUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * クイズの一括取り込み（管理者向け）。
 *
 * 受けるのは JSON だけ。CSV は画面で同じ形に変換してから送る。API の形を 1 つにして、
 * 検証とテナントの扱いを既存の API と同じ経路に乗せる。
 */
@RestController
@RequestMapping("/api/t/{slug}/admin/quizzes/import", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "クイズ（管理）", description = "管理者がクイズを CRUD する。**応答に正解を含む**")
class QuizImportController(private val useCase: QuizImportUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "importQuizzes",
        summary = "クイズをまとめて取り込む",
        description = "1 件でも取り込めない行があれば、1 件も取り込まずに 400 を返す。理由は `rows` に行ごとに入る",
    )
    fun import(@Valid @RequestBody request: ImportQuizzesRequest): ImportQuizzesResponse =
        ImportQuizzesResponse(importedCount = useCase.import(request.quizzes.map { it.toRow() }))

    /**
     * 取り込めない行がある。1 件も取り込んでいない。
     *
     * **不正な行をすべて `rows` で返す。** 1 つ直すたびに取り込み直して次の誤りを知る、という往復をさせない。
     * この API でしか起きないため、共通のハンドラ（ApiExceptionHandler）ではなくここに置く。
     */
    @ExceptionHandler(QuizImportRejectedException::class)
    fun handleRejected(e: QuizImportRejectedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "取り込めない行があります。1 件も取り込んでいません").apply {
            title = "入力内容に誤りがあります"
            setProperty("rows", e.rows.map { mapOf("index" to it.index, "messages" to it.messages) })
        }
}

data class ImportQuizzesRequest(
    @field:Size(min = 1, max = QuizImportUseCase.MAX_ROWS, message = "取り込めるのは {min}〜{max} 件です")
    val quizzes: List<ImportQuizRequest>,
)

/**
 * 取り込む 1 件。カテゴリと難易度は名前で指す。
 *
 * **ここでは中身を検証しない。** 項目ごとの検証（Bean Validation）で弾くと、
 * 最初に見つかった誤りだけが返り、残りの行の誤りは直したあとでないと分からない。
 * 全行の誤りをまとめて返すため、検証はユースケースで行う。
 */
data class ImportQuizRequest(
    val category: String,
    val difficulty: String,
    val question: String,
    val explanation: String = "",
    val choices: List<ChoiceRequest> = emptyList(),
    val status: String = "draft",
) {
    fun toRow() = QuizImportRow(
        category = category,
        difficulty = difficulty,
        question = question,
        explanation = explanation,
        choices = choices.map { QuizImportRow.ChoiceInput(body = it.body, isCorrect = it.isCorrect) },
        status = status,
    )
}

data class ImportQuizzesResponse(val importedCount: Int)
