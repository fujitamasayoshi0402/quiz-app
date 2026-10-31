package com.quizapp.warmup

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * 止まっている DB を先に起こす（[DatabaseWarmUp]）。**`/api` 配下で、認証を求めない唯一の API。**
 *
 * - 認証すると利用者を DB から引くため、回数を確かめる前に DB が起きてしまう。認証の除外は `ApiAccessConfigurer` が持つ
 * - 誰でも呼べるが、起こすのは 1 日の上限まで。返すものはなく、何も読めず、何も変えない
 * - 呼ぶのは web のサーバーだけ。画面の API クライアントは使わないため、OpenAPI の定義には載せない
 */
@Hidden
@RestController
class WarmUpController(private val warmUp: DatabaseWarmUp) {

    @PostMapping(PATH)
    fun warmUp(): ResponseEntity<Void> {
        warmUp.warmUp()
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val PATH = "/api/warm-up"
    }
}

@Configuration
@EnableConfigurationProperties(WarmUpProperties::class)
class WarmUpConfiguration {

    @Bean
    fun databaseWarmUp(
        jdbcTemplate: JdbcTemplate,
        activity: DatabaseActivity,
        properties: WarmUpProperties,
    ): DatabaseWarmUp = DatabaseWarmUp(
        touchDatabase = { jdbcTemplate.queryForObject("SELECT 1", Int::class.java) },
        activity = activity,
        properties = properties,
        clock = Clock.systemUTC(),
    )
}
