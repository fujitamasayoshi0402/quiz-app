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

        start(database, MIGRATE_PROFILE).use { context ->
            assertThat(context.environment.getProperty(LOCAL_SERVER_PORT)).isNull()
            assertThat(migrated(database)).isTrue()
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
    private fun start(database: String, vararg profiles: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(QuizServiceApplication::class.java)
            .profiles(*profiles)
            .run("--spring.datasource.url=${url(database)}", "--server.port=0")

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
    }
}
