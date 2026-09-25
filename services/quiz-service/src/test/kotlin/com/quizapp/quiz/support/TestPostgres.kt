package com.quizapp.quiz.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * テスト全体で 1 つの PostgreSQL コンテナを共有する。
 *
 * テストクラスごとに起動すると、その分だけ待ち時間が増える。
 * JVM の終了時にコンテナは Testcontainers の Ryuk が片付けるため、明示的な停止は書かない。
 */
object TestPostgres {

    val container: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("quiz")
        .withUsername("quiz")
        .withPassword("quiz")
        // アプリケーション用ロールは dev 環境と同じスクリプトで作る。
        // ロール定義が二重管理になると、本番とテストで権限がずれる
        .withCopyFileToContainer(
            org.testcontainers.utility.MountableFile.forHostPath(
                "../../infra/docker/postgres/init/01-create-app-role.sql",
            ),
            "/docker-entrypoint-initdb.d/01-create-app-role.sql",
        )
        .apply { start() }

    /**
     * 行レベルセキュリティを通さない接続。テナントや利用者の用意と、DB に残った行の確認に使う。
     *
     * アプリケーション用の `quiz_app` は行レベルセキュリティの対象なので、
     * テナントを設定しないまま読んでも 0 件になる。**エラーにならず静かに空振りする**ため、
     * アプリの外から DB を見るときは所有者（スーパーユーザー）で接続する。
     */
    val adminJdbcTemplate: JdbcTemplate by lazy {
        JdbcTemplate(
            DriverManagerDataSource(container.jdbcUrl, "quiz", "quiz").apply {
                setDriverClassName("org.postgresql.Driver")
            },
        )
    }

    /**
     * 接続設定をテストのコンテキストに流し込む。
     * アプリケーションは非スーパーユーザー、マイグレーションは所有者で接続する。
     *
     * 本番のアプリは起動時にマイグレーションしない（migrate プロファイルで別に流す）。
     * テストでは手順を 1 つにするため、同じコンテキストの起動時に流す。
     */
    fun configure(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { "quiz_app" }
        registry.add("spring.datasource.password") { "quiz_app" }
        registry.add("spring.flyway.enabled") { "true" }
        registry.add("spring.flyway.user") { "quiz" }
        registry.add("spring.flyway.password") { "quiz" }
        registry.add("app.auth.issuer") { com.quizapp.support.TestJwt.ISSUER }
        registry.add("app.auth.client-id") { com.quizapp.support.TestJwt.CLIENT_ID }
    }
}
