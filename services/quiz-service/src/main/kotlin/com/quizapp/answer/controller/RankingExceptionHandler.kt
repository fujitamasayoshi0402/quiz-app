package com.quizapp.answer.controller

import com.quizapp.answer.domain.DuplicateRankingNameException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * ランキングのエラーを RFC 9457 の Problem Details で返す。
 * 共通のもの（入力の誤り、認証、テナント）は [com.quizapp.controller.ApiExceptionHandler] が返す。
 */
@RestControllerAdvice
class RankingExceptionHandler {

    /** 送られた名前は応答で繰り返さない。画面は入力欄にまだ持っている */
    @ExceptionHandler(DuplicateRankingNameException::class)
    fun handleDuplicateName(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "その名前は、このテナントですでに使われています",
    ).apply { title = "この名前は使えません" }
}
