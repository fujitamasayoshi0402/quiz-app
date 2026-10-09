package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.OrderOutdatedException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 並べ替えの API のエラー応答（DEV-71）。共通の [com.quizapp.controller.ApiExceptionHandler] と同じ形（RFC 9457）で返す。
 */
@RestControllerAdvice(assignableTypes = [CategoryController::class, DifficultyController::class])
class OrderingExceptionHandler {

    /** 並べ替えている間に、ほかの操作で項目が増えたか消えた。一部だけを並べ替えると並びが崩れるため、読み込み直させる */
    @ExceptionHandler(OrderOutdatedException::class)
    fun handleOrderOutdated(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "並べ替えている間に項目が変わりました。最新の一覧を取り直してから、並べ替えてください",
    ).apply { title = "操作できない状態です" }
}
