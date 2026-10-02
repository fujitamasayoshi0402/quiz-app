package com.quizapp.ratelimit

import com.quizapp.auth.UserContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Duration

/**
 * 重い操作。利用者ごとの上限（[RateLimitProperties.perUser]）に加えて、厳しい上限（[RateLimitProperties.heavy]）を掛ける。
 *
 * 付けるのは、CPU を使うもの（画像の読み直し、PDF の描画）、まとめて書くもの（取り込み）、
 * 何度も試されると困るもの（招待の作成、招待のトークンでの確認と受け入れ）。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HeavyOperation

/** 上限を超えた。[retryAfter] たてば受け付けられる */
class RateLimitExceededException(val retryAfter: Duration) : RuntimeException("要求が多すぎます") {
    /** `Retry-After` に入れる秒。切り上げる。0 秒と返すと、すぐに送り直されてまた超える */
    val retryAfterSeconds: Long get() = maxOf(1, retryAfter.plusNanos(NANOS_PER_SECOND - 1).seconds)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * 利用者ごとの流量の上限（DEV-125）。API Gateway のスロットリングは API 全体に 1 つで、1 人が使い切ると全員が 429 になる。
 *
 * 画面からの要求は web の proxy（Amplify）を通って届くため、API Gateway から見た送り元の IP は利用者を表さない。
 * **アクセストークンから特定した利用者で数える。**
 *
 * 認証を確かめた後、テナントの所属を確かめる前に動く（`ApiAccessConfigurer`）。超えた要求で、所属を DB に問い合わせない。
 * アクセストークンを持たない要求は、ここまで来ない（401）。それは API Gateway の全体の上限で受ける
 */
class RateLimitInterceptor(private val perUser: RateLimiter, private val heavy: RateLimiter) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val userId = UserContext.require()
        perUser.tryAcquire(userId)?.let { throw RateLimitExceededException(it) }
        if (handler is HandlerMethod && handler.hasMethodAnnotation(HeavyOperation::class.java)) {
            heavy.tryAcquire(userId)?.let { throw RateLimitExceededException(it) }
        }
        return true
    }
}

/**
 * 上限の値。既定値をどの環境でも使う。
 *
 * 利用者ごとの上限は、API Gateway の全体の上限（modules/quiz-service の api.tf）より小さくする。同じだと、1 人で全員を止められる
 */
@ConfigurationProperties("app.rate-limit")
data class RateLimitProperties(
    /**
     * 人が画面から使う速さには当たらない。画面を 1 つ開くと並べて数件を読み、スモークテストは 1 人で 42 件を続けて呼ぶ。
     * API Gateway の全体の上限（20 件/秒、バースト 60）の 1/4
     */
    val perUser: RateLimit = RateLimit(capacity = 30, refill = 5, per = Duration.ofSeconds(1)),
    /** 1 分に 20 件。図を描きながら何度か保存しても、当たらない */
    val heavy: RateLimit = RateLimit(capacity = 20, refill = 20, per = Duration.ofMinutes(1)),
)
