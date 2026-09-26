package com.quizapp.quiz.controller

import com.quizapp.quiz.usecase.FigureNotFoundException
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

    @ExceptionHandler(FigureNotFoundException::class)
    fun handleFigureNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された図は存在しません").apply {
            title = "リソースが見つかりません"
        }
}
