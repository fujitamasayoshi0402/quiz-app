package com.quizapp.notification.controller

import com.quizapp.notification.domain.SlackWebhookStoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 通知先のエラーを RFC 9457 の Problem Details で返す。
 * 共通のもの（入力の誤り、認証、テナント）は [com.quizapp.controller.ApiExceptionHandler] が返す。
 */
@RestControllerAdvice
class SlackWebhookExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** SSM に届かなかった。設定も削除も、DB ごと元に戻してある（`SlackWebhookUseCase`） */
    @ExceptionHandler(SlackWebhookStoreUnavailableException::class)
    fun handleStoreUnavailable(e: SlackWebhookStoreUnavailableException): ProblemDetail {
        log.error("Slack の通知先を SSM に書けませんでした", e)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "通知先を保存できませんでした。しばらくしてから、やり直してください",
        ).apply { title = "保存できませんでした" }
    }
}
