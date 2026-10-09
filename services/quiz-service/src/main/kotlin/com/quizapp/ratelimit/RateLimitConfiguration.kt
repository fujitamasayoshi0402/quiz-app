package com.quizapp.ratelimit

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 利用者ごとの流量の上限を組み立てる（DEV-125）。登録の順番は `ApiAccessConfigurer` が持つ */
@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration(private val properties: RateLimitProperties) {

    /** `app.rate-limit.enabled=false` で作らない。テストで止める。同じ利用者で多くの要求を続けて送るため */
    @Bean
    @ConditionalOnProperty("app.rate-limit.enabled", matchIfMissing = true)
    fun rateLimitInterceptor(): RateLimitInterceptor = RateLimitInterceptor(
        perUser = RateLimiter(properties.perUser, Clock.systemUTC()),
        heavy = RateLimiter(properties.heavy, Clock.systemUTC()),
    )
}
