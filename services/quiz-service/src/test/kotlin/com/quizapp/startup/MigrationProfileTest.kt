package com.quizapp.startup

import com.quizapp.MIGRATE_PROFILE
import com.quizapp.QuizServiceApplication
import com.quizapp.quiz.support.TestPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * マイグレーションをアプリの起動から切り離したことの検証。
 *
 * アプリはスキーマ所有者の認証情報を持たず、起動してもスキーマに触れない。
 * マイグレーションは migrate プロファイルで同じイメージを起動して流し、HTTP は受けない。
 *
 * ほかのテストと共有しているデータベースは、すでにマイグレーション済みで「流したかどうか」を区別できない。
 * **テストごとに空のデータベースを作って確かめる。**
 *
 * 設定はテスト用に差し替えず、`application.yml` / `application-migrate.yml` をそのまま読む。
 * 差し替えるのは接続先だけ。
 */
class MigrationProfileTest {

    @Test
    @DisplayName("アプリとして起動しても、マイグレーションは流れない")
    fun applicationDoesNotMigrate() {
        val database = createEmptyDatabase()

        start(database).use { context ->
            assertThat(context.environment.getProperty(LOCAL_SERVER_PORT)).isNotNull()
            assertThat(migrated(database)).isFalse()
        }
    }

    @Test
    @DisplayName("migrate プロファイルはマイグレーションを流し、HTTP を受けない")
    fun migrateProfileMigratesWithoutWebServer() {
        val database = createEmptyDatabase()

        start(database, listOf(MIGRATE_PROFILE)).use { context ->
            assertThat(context.environment.getProperty(LOCAL_SERVER_PORT)).isNull()
            assertThat(migrated(database)).isTrue()
        }
    }

    @Test
    @DisplayName("migrate プロファイルは、アプリ用の接続を使わない")
    fun migrateProfileDoesNotUseApplicationConnection() {
        val database = createEmptyDatabase()

        // AWS のマイグレーションのタスクは quiz（所有者）にしか接続できない（ADR-0014）。
        // アプリ用の接続（quiz_app）に一度でもつなぎに行くと、そこで起動に失敗する
        start(database, listOf(MIGRATE_PROFILE), listOf(UNUSABLE_APPLICATION_PASSWORD)).use {
            assertThat(migrated(database)).isTrue()
        }
    }

    @Test
    @DisplayName("アプリは起動するときに DB へつながない")
    fun applicationStartsWithoutConnecting() {
        val database = createEmptyDatabase()

        // 起動のたびにつなぐと、一時停止している Aurora（min 0 ACU）を起こしてしまう。
        // Spring Data JDBC は既定で接続して DB の種類を調べるため、application.yml で指定している
        start(database, extraArgs = listOf(UNUSABLE_APPLICATION_PASSWORD)).use { context ->
            assertThat(context.environment.getProperty(LOCAL_SERVER_PORT)).isNotNull()
        }
    }

    private fun createEmptyDatabase(): String {
        val name = "migration_${UUID.randomUUID().toString().replace("-", "")}"
        TestPostgres.adminJdbcTemplate.execute("CREATE DATABASE $name")
        return name
    }

    /**
     * コマンドライン引数で渡す。`SpringApplicationBuilder.properties` は優先度が最も低く、
     * `application.yml` の接続先に負ける。
     */
    private fun start(
        database: String,
        profiles: List<String> = emptyList(),
        extraArgs: List<String> = emptyList(),
    ): ConfigurableApplicationContext = SpringApplicationBuilder(QuizServiceApplication::class.java)
        .profiles(*profiles.toTypedArray())
        .run("--spring.datasource.url=${url(database)}", "--server.port=0", *extraArgs.toTypedArray())

    private fun migrated(database: String): Boolean {
        val jdbc = JdbcTemplate(
            DriverManagerDataSource(url(database), "quiz", "quiz").apply {
                setDriverClassName("org.postgresql.Driver")
            },
        )
        return jdbc.queryForObject(
            "SELECT to_regclass('core.flyway_schema_history') IS NOT NULL",
            Boolean::class.java,
        ) == true
    }

    private fun url(database: String) =
        "jdbc:postgresql://${TestPostgres.container.host}:${TestPostgres.container.firstMappedPort}/$database"

    private companion object {
        /** Web サーバーが起動したときにだけ設定される */
        const val LOCAL_SERVER_PORT = "local.server.port"

        /** アプリ用の接続（quiz_app）を使えなくする。つなぎに行けば認証で失敗する */
        const val UNUSABLE_APPLICATION_PASSWORD = "--spring.datasource.password=unusable"
    }
}
