package com.quizapp.logging

import com.quizapp.tenant.TenantResolutionFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 要求ごとの ID を決めて MDC に載せ、要求の終わりに 1 行を出す。最も外側で動く。
 *
 * **ID は API Gateway の要求の ID を引き継ぐ。** API Gateway が `X-Request-Id` に入れて送る（`modules/quiz-service` の api.tf）。
 * API Gateway のアクセスログの `requestId` と同じ値になり、両方のログを結び付けられる。
 * ヘッダが無い（ローカル）か、形がおかしいときは作る。応答の `X-Request-Id` にも返す。
 *
 * 終わりの 1 行は、メソッド、ルートの型、ステータス、かかった時間を持つ。
 * **ステータスの項目（`http.response.status_code`）は、アラームが数える**（modules/quiz-service の alarms.tf）。名前を変えると鳴らなくなる
 * - **パスは載せない。ルートの型（`/api/t/{slug}/play/attempts/{attemptId}`）を載せる。**
 *   招待の受け入れのパスにはトークンが入る。型なら値が入らず、同じ API で集計できる
 * - `/api` の外（ECS のヘルスチェック）は出さない。15 秒ごとに届き、ログの大半を占める
 * - 扱っていない例外で終わったときは、ERROR で例外ごと出す。
 *   Tomcat も同じ例外を出すが、そちらは要求の文脈を持たない（MDC はここで消えている）
 */
@Component
@Order(RequestLogFilter.ORDER)
class RequestLogFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 例外は受け取って出すだけで、そのまま投げ直す */
    @Suppress("TooGenericExceptionCaught")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { VALID_ID.matches(it) } ?: UUID.randomUUID().toString()
        MDC.put(LogContext.REQUEST_ID, requestId)
        response.setHeader(HEADER, requestId)
        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (request.requestURI.startsWith("/api/")) logCompletion(request, response, startedAt, failure)
            // スレッドはプールで使い回されるため、必ず消す。残すと次の要求のログに前の要求の ID が載る
            LogContext.clear()
        }
    }

    private fun logCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        startedAt: Long,
        failure: Throwable?,
    ) {
        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        // 例外が外まで来たときは、まだステータスが決まっていない。このあと Tomcat が 500 を返す
        val status = if (failure != null) HttpServletResponse.SC_INTERNAL_SERVER_ERROR else response.status
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val event = if (failure != null) log.atError().setCause(failure) else log.atInfo()
        event
            .addKeyValue("http.request.method", request.method)
            .addKeyValue("http.route", route)
            .addKeyValue("http.response.status_code", status)
            .addKeyValue("http.duration_ms", durationMs)
            .log("{} {} {} {}ms", request.method, route ?: "（ルートなし）", status, durationMs)
    }

    companion object {
        /** テナントの解決より前。テナントを引くときのログにも、要求の ID を載せる */
        const val ORDER = TenantResolutionFilter.ORDER - 10

        const val HEADER = "X-Request-Id"

        /** API Gateway の要求の ID（`Kx1q3hXYtjMEJ5A=` のような形）と UUID が通る。改行や長すぎる値は捨てて作り直す */
        private val VALID_ID = Regex("[A-Za-z0-9=_-]{1,64}")
    }
}
