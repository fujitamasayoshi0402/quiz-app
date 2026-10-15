package com.quizapp.quiz.infrastructure.newsquiz

import com.quizapp.quiz.infrastructure.outbox.DatabaseActivity
import com.quizapp.quiz.usecase.QuizImportUseCase
import com.quizapp.tenant.TenantTransaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import software.amazon.awssdk.services.s3.S3Client
import java.time.Clock

/**
 * 技術ニュースの下書きの取り込みを組み立てる（ADR-0031）。起動のときには AWS にも DB にも接続しない。
 *
 * S3 のクライアントは、図のもの（接続先、認証情報）を使い回す。バケットだけが違う。
 * 定期的な呼び出しは、Outbox の拾い直しと同じスケジューラに載せる。止めている（既定）ときは載せない
 */
@Configuration
@EnableConfigurationProperties(NewsQuizProperties::class)
class NewsQuizConfiguration(private val properties: NewsQuizProperties) {

    @Bean
    fun newsQuizImport(
        @Qualifier("figureS3Client") s3: S3Client,
        jdbcTemplate: JdbcTemplate,
        tenantTransaction: TenantTransaction,
        importer: QuizImportUseCase,
        databaseActivity: DatabaseActivity,
    ): NewsQuizImport = NewsQuizImport(
        S3NewsQuizInbox(s3, properties.bucket),
        NewsQuizDrafts(jdbcTemplate, tenantTransaction),
        importer,
        databaseActivity,
        properties,
        Clock.systemUTC(),
    )

    @Bean
    fun newsQuizImportSchedule(newsQuizImport: NewsQuizImport): SchedulingConfigurer =
        SchedulingConfigurer { registrar ->
            if (properties.enabled) {
                registrar.addFixedDelayTask(
                    FixedDelayTask(newsQuizImport::tick, properties.interval, properties.interval),
                )
            }
        }
}
