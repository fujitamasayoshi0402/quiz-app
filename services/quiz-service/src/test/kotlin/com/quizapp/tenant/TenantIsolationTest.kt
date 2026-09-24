package com.quizapp.tenant

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
class TenantIsolationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var tenantSession: TenantSession

    private lateinit var tenantA: UUID
    private lateinit var tenantB: UUID

    @BeforeEach
    fun setUp() {
        tenantA = TestTenant.create("テナントA").id
        tenantB = TestTenant.create("テナントB").id
        insertCategory(tenantA, "AWS")
        insertCategory(tenantB, "認証認可")
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
    @DisplayName("quiz / answer スキーマの全テーブルが tenant_id を持ち、RLS が強制されている")
    fun everyTenantScopedTableHasRowLevelSecurity() {
        // テーブルを足したときに RLS を付け忘れると、そのテーブルだけ境界が DB で守られなくなる。
        // core は境界そのものを定義するテーブルのため対象外（V5 のコメントを参照）
        val tables = TestPostgres.adminJdbcTemplate.queryForList(
            """
            SELECT n.nspname || '.' || c.relname AS name,
                   c.relrowsecurity AS enabled,
                   c.relforcerowsecurity AS forced,
                   EXISTS (SELECT 1 FROM information_schema.columns col
                           WHERE col.table_schema = n.nspname AND col.table_name = c.relname
                             AND col.column_name = 'tenant_id') AS has_tenant_id,
                   EXISTS (SELECT 1 FROM pg_policies p
                           WHERE p.schemaname = n.nspname AND p.tablename = c.relname
                             AND p.policyname = 'tenant_isolation') AS has_policy
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r' AND n.nspname IN ('quiz', 'answer')
            """,
        )

        assertThat(tables).isNotEmpty
        assertThat(tables).allSatisfy { table ->
            assertThat(table)
                .describedAs(table["name"].toString())
                .containsEntry("enabled", true)
                .containsEntry("forced", true)
                .containsEntry("has_tenant_id", true)
                .containsEntry("has_policy", true)
        }
    }

    @Test
    @DisplayName("トランザクションの外ではテナントを設定できない")
    fun cannotApplyTenantOutsideTransaction() {
        assertThatThrownBy { tenantSession.apply(tenantA) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("トランザクションの外では")
    }
}
