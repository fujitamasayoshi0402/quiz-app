package com.quizapp.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 利用者ごとの流量の上限を超えたとき（DEV-125）、RFC 9457 の形で 429 を返す。ほかのエラーと同じ形（`ApiExceptionHandler`）。
 *
 * `Retry-After` に、次に受け付けられるまでの秒を入れる。画面は 4xx を再試行しない。待たずに送り直しても、また 429 になる
 */
@RestControllerAdvice
class RateLimitExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(RateLimitExceededException::class)
    fun handle(e: RateLimitExceededException): ResponseEntity<ProblemDetail> {
        log.warn("流量の上限を超えました。{} 秒後から受け付けます", e.retryAfterSeconds)
        val body = ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS,
            "短い時間に多くの操作が行われました。しばらく待ってからやり直してください",
        ).apply { title = "要求が多すぎます" }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(body)
    }
}
