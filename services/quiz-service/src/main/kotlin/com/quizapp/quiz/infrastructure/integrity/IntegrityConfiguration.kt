package com.quizapp.quiz.infrastructure.integrity

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import java.time.Clock

/**
 * データの整合性を確かめる仕組みを組み立てる（DEV-115）。起動のときには DB に接続しない。
 * 定期的な呼び出しは、Outbox の拾い直しと同じスケジューラ（`EventsConfiguration` の `@EnableScheduling`）に載せる
 */
@Configuration
@EnableConfigurationProperties(IntegrityProperties::class)
class IntegrityConfiguration(private val properties: IntegrityProperties) {

    @Bean
    fun integrityCheck(checks: IntegrityChecks, databaseActivity: DatabaseActivity): IntegrityCheck =
        IntegrityCheck(checks, databaseActivity, properties, Clock.systemUTC())

    @Bean
    fun integrityCheckSchedule(integrityCheck: IntegrityCheck): SchedulingConfigurer =
        SchedulingConfigurer { registrar ->
            if (properties.enabled) {
                registrar.addFixedDelayTask(
                    FixedDelayTask(integrityCheck::tick, properties.interval, properties.interval),
                )
            }
        }
}
