package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.FigureInUseException
import com.quizapp.quiz.usecase.FigureNotFoundException
import com.quizapp.quiz.usecase.FigureUploadNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 解説図の API のエラー応答。共通の [com.quizapp.controller.ApiExceptionHandler] と同じ形（RFC 9457）で返す。
 *
 * 別テナントの図も「存在しない」と答える。あるかどうかを区別させない
 */
@RestControllerAdvice(assignableTypes = [FigureController::class, PlayFigureController::class])
class FigureExceptionHandler {

    @ExceptionHandler(FigureUploadNotFoundException::class)
    fun handleUploadNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "上げたファイルが見つかりません。もう一度上げてください").apply {
            title = "リソースが見つかりません"
        }

    /** **使っている問題の問題文を `quizzes` で返す。** 管理者が、どの解説から外せばよいかを知るため。同じテナントの問題なので、見せてよい */
    @ExceptionHandler(FigureInUseException::class)
    fun handleFigureInUse(e: FigureInUseException): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "この図は、公開中か下書きの問題の解説で使われています。先に解説から外してください",
    ).apply {
        title = "使われている図は消せません"
        setProperty("quizzes", e.questions)
    }

    @ExceptionHandler(FigureNotFoundException::class)
    fun handleFigureNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された図は存在しません").apply {
            title = "リソースが見つかりません"
        }
}
