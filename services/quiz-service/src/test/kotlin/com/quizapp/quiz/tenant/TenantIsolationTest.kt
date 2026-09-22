package com.quizapp.quiz.tenant

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.UUID

/**
 * 行レベルセキュリティによるテナント分離が、コネクションプール越しでも機能することを確認する。
 *
 * **接続プールを 1 本に固定している。** すべてのトランザクションが同じ物理接続を使うため、
 * セッション変数が前のトランザクションから残っていれば必ず検出できる。
 * プールが複数あると、たまたま別の接続が割り当たって問題が隠れることがある。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
    ],
)
@Testcontainers
class TenantIsolationTest {

    companion object {
        /**
         * アプリケーション用ロールは docker-entrypoint-initdb.d で作る。
         * dev 環境と同じスクリプトを使い、ロール定義が二重管理にならないようにしている。
         */
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("quiz")
            .withUsername("quiz")
            .withPassword("quiz")
            .withCopyFileToContainer(
                MountableFile.forHostPath("../../infra/docker/postgres/init/01-create-app-role.sql"),
                "/docker-entrypoint-initdb.d/01-create-app-role.sql",
            )

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            // アプリケーションは非スーパーユーザーで接続する。
            // スーパーユーザーは FORCE ROW LEVEL SECURITY でも RLS をバイパスするため
            registry.add("spring.datasource.username") { "quiz_app" }
            registry.add("spring.datasource.password") { "quiz_app" }
            // マイグレーション（DDL）はスキーマ所有者が実行する
            registry.add("spring.flyway.user") { "quiz" }
            registry.add("spring.flyway.password") { "quiz" }
        }
    }

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var tenantSession: TenantSession

    private val tenantA: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val tenantB: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        // tenants は RLS の対象外なので、テナントを設定せずに登録できる
        transactionTemplate.execute {
            jdbcTemplate.update(
                "INSERT INTO core.tenants (id, slug, name) VALUES (?, 'tenant-a', 'テナントA'), (?, 'tenant-b', 'テナントB')",
                tenantA,
                tenantB,
            )
        }
        insertCategory(tenantA, "AWS")
        insertCategory(tenantB, "認証認可")
    }

    @AfterEach
    fun tearDown() {
        transactionTemplate.execute {
            // 片付けは RLS を通さずに行いたいので、所有者権限を持つ Flyway 用の接続ではなく
            // テナントごとに削除する
            listOf(tenantA, tenantB).forEach { tenant ->
                tenantSession.apply(tenant)
                jdbcTemplate.update("DELETE FROM quiz.categories")
            }
            jdbcTemplate.update("DELETE FROM core.tenants")
        }
    }

    private fun insertCategory(tenant: UUID, name: String) {
        transactionTemplate.execute {
            tenantSession.apply(tenant)
            jdbcTemplate.update("INSERT INTO quiz.categories (tenant_id, name) VALUES (?, ?)", tenant, name)
        }
    }

    private fun categoryNames(): List<String> =
        jdbcTemplate.queryForList("SELECT name FROM quiz.categories ORDER BY name", String::class.java)
            .filterNotNull()

    @Test
    @DisplayName("テナントを設定すると、そのテナントの行だけが見える")
    fun onlyOwnTenantRowsAreVisible() {
        val namesForA = transactionTemplate.execute {
            tenantSession.apply(tenantA)
            categoryNames()
        }
        assertThat(namesForA).containsExactly("AWS")

        val namesForB = transactionTemplate.execute {
            tenantSession.apply(tenantB)
            categoryNames()
        }
        assertThat(namesForB).containsExactly("認証認可")
    }

    @Test
    @DisplayName("トランザクションが終わるとセッション変数は破棄され、次のトランザクションに持ち越されない")
    fun sessionVariableDoesNotLeakToNextTransaction() {
        transactionTemplate.execute {
            tenantSession.apply(tenantA)
            assertThat(tenantSession.current()).isEqualTo(tenantA.toString())
        }

        // 接続プールは 1 本なので、ここでは必ず直前と同じ物理接続が使われる。
        // SET LOCAL 相当の設定はコミット時に破棄されるため、値は残っていないはず
        transactionTemplate.execute {
            assertThat(tenantSession.current()).isNull()
            assertThat(categoryNames()).isEmpty()
        }
    }

    @Test
    @DisplayName("テナントを設定しなければ 1 件も見えない")
    fun nothingIsVisibleWithoutTenant() {
        val names = transactionTemplate.execute { categoryNames() }
        assertThat(names).isEmpty()
    }

    @Test
    @DisplayName("他テナントの tenant_id を指定した挿入は拒否される")
    fun cannotInsertIntoAnotherTenant() {
        // Spring は SQLState 42501（権限不足）を BadSqlGrammarException に分類するため、
        // ラップされた例外のメッセージには理由が残らない。原因は根本例外まで辿る必要がある。
        // 利用者向けのエラー応答を組み立てるときも、この分類をそのまま「文法エラー」として
        // 扱わないよう注意する（DEV-19）
        assertThatThrownBy {
            transactionTemplate.execute {
                tenantSession.apply(tenantA)
                jdbcTemplate.update(
                    "INSERT INTO quiz.categories (tenant_id, name) VALUES (?, ?)",
                    tenantB,
                    "侵入",
                )
            }
        }.isInstanceOf(BadSqlGrammarException::class.java)
            .rootCause()
            .hasMessageContaining("row-level security")
    }

    @Test
    @DisplayName("トランザクションの外ではテナントを設定できない")
    fun cannotApplyTenantOutsideTransaction() {
        assertThatThrownBy { tenantSession.apply(tenantA) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("トランザクションの外では")
    }
}
