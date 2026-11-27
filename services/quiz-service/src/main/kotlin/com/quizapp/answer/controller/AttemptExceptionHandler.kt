package com.quizapp.answer.controller

import com.quizapp.answer.usecase.AttemptStillSavingException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 挑戦のエラーのうち、メモリで始めた挑戦（DEV-240）にかかわるもの。
 * ほかの挑戦のエラーは [com.quizapp.controller.ApiExceptionHandler] が返す。
 */
@RestControllerAdvice
class AttemptExceptionHandler {

    /**
     * 挑戦の保存（止まっていた DB の復帰）が、待てる長さのうちに終わらなかった。少し待ってもう一度送れば通る。
     * 回答は自動では送り直さない（`apps/web/src/app/providers.tsx`）ため、画面にこのメッセージが出る
     */
    @ExceptionHandler(AttemptStillSavingException::class)
    fun handleStillSaving(e: AttemptStillSavingException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.message ?: "準備をしています").apply {
            title = "準備をしています"
        }
}
