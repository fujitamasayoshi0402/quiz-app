package com.quizapp.startup

import com.quizapp.quiz.support.TestPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * デモ用シードの検証。
 *
 * repeatable マイグレーションは**内容を変えるたびに再実行される**ため、
 * 二度流しても壊れないことがそのまま要件になる。
 *
 * あわせて、シードが**ドメインの不変条件を満たしていること**を確かめる。
 * SQL で直接入れるため、公開クイズの「選択肢ちょうど 4 つ・正解ちょうど 1 つ」は
 * アプリケーション層の検証を通らない。DB の制約だけでは行数の条件を表現できない。
 *
 * Flyway の履歴を汚さないよう、ここでは locations を切り替えず SQL を直接流す。
 */
@SpringBootTest
class DemoSeedTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val DEMO_SLUG = "demo"
    }

    private val seed = ClassPathResource("db/seed/R__demo_data.sql")

    private fun applySeed() =
        ResourceDatabasePopulator(seed).execute(TestPostgres.adminJdbcTemplate.dataSource!!)

    private fun count(sql: String): Int =
        TestPostgres.adminJdbcTemplate.queryForObject(sql, Int::class.java) ?: 0

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        val tenant = "(SELECT id FROM core.tenants WHERE slug = '$DEMO_SLUG')"
        admin.update("DELETE FROM quiz.choices WHERE tenant_id = $tenant")
        admin.update("DELETE FROM quiz.quizzes WHERE tenant_id = $tenant")
        admin.update("DELETE FROM quiz.difficulties WHERE tenant_id = $tenant")
        admin.update("DELETE FROM quiz.categories WHERE tenant_id = $tenant")
        admin.update("DELETE FROM core.tenant_members WHERE tenant_id = $tenant")
        admin.update("DELETE FROM core.users WHERE external_id IN ('demo-admin', 'demo-member')")
        admin.update("DELETE FROM core.tenants WHERE slug = '$DEMO_SLUG'")
    }

    @Test
    @DisplayName("二度流しても増えない")
    fun seedIsIdempotent() {
        applySeed()
        val first = listOf(
            count("SELECT count(*) FROM quiz.categories"),
            count("SELECT count(*) FROM quiz.difficulties"),
            count("SELECT count(*) FROM quiz.quizzes"),
            count("SELECT count(*) FROM quiz.choices"),
            count("SELECT count(*) FROM core.tenant_members"),
        )

        applySeed()

        assertThat(
            listOf(
                count("SELECT count(*) FROM quiz.categories"),
                count("SELECT count(*) FROM quiz.difficulties"),
                count("SELECT count(*) FROM quiz.quizzes"),
                count("SELECT count(*) FROM quiz.choices"),
                count("SELECT count(*) FROM core.tenant_members"),
            ),
        ).isEqualTo(first)
    }

    @Test
    @DisplayName("公開クイズは選択肢 4 つ・正解 1 つを満たす")
    fun publishedQuizzesSatisfyInvariants() {
        applySeed()

        val broken = count(
            """
            SELECT count(*) FROM quiz.quizzes q
            WHERE q.status = 'published'
              AND ( (SELECT count(*) FROM quiz.choices c WHERE c.quiz_id = q.id) <> 4
                 OR (SELECT count(*) FROM quiz.choices c WHERE c.quiz_id = q.id AND c.is_correct) <> 1 )
            """,
        )

        assertThat(broken).isZero()
        assertThat(count("SELECT count(*) FROM quiz.quizzes WHERE status = 'published'")).isGreaterThan(0)
    }

    @Test
    @DisplayName("カテゴリごとに難易度の体系が異なる")
    fun categoriesHaveTheirOwnDifficultyScales() {
        applySeed()

        val scales = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT c.name AS category, count(d.id) AS levels
            FROM quiz.categories c JOIN quiz.difficulties d ON d.category_id = c.id
            GROUP BY c.name ORDER BY c.name
            """,
        ) { rs, _ -> rs.getString("category") to rs.getInt("levels") }

        // 同じ数の難易度が並ぶだけのサンプルだと、体系を分けた設計を示せない
        assertThat(scales.map { it.second }.distinct()).hasSizeGreaterThan(1)
    }

    @Test
    @DisplayName("同じレベルに複数の難易度が並ぶ例が含まれる")
    fun sameLevelCanHoldMultipleDifficulties() {
        applySeed()

        // AWS のアソシエイト級（SAA / DVA）。level にユニーク制約を置かなかった理由の実例
        val duplicated = count(
            """
            SELECT count(*) FROM (
                SELECT category_id, level FROM quiz.difficulties
                GROUP BY category_id, level HAVING count(*) > 1
            ) AS t
            """,
        )

        assertThat(duplicated).isGreaterThan(0)
    }

    @Test
    @DisplayName("デモの管理者と一般ユーザーが所属している")
    fun demoMembersExist() {
        applySeed()

        val roles = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT u.external_id, m.role FROM core.tenant_members m
            JOIN core.users u ON u.id = m.user_id
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE t.slug = '$DEMO_SLUG' ORDER BY u.external_id
            """,
        ) { rs, _ -> rs.getString("external_id") to rs.getString("role") }

        assertThat(roles).containsExactly("demo-admin" to "admin", "demo-member" to "member")
    }
}
