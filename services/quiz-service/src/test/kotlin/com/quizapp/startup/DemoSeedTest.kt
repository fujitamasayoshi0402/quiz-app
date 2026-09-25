package com.quizapp.startup

import com.quizapp.quiz.support.TestPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager

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
 *
 * 他のテストと同じく片付けない。**数えるのはシードのテナントに絞る。**
 * コンテナは全テストで共有しているため、絞らないと他のテストが作った行まで数える。
 */
@SpringBootTest
class DemoSeedTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val DEMO_SLUG = "demo"

        /** シードが入れるテナント。条件に埋め込んで使う */
        private const val SEEDED = "(SELECT id FROM core.tenants WHERE slug IN ('demo', 'geo-club'))"
    }

    private val seed = ClassPathResource("db/seed/R__demo_data.sql")

    private fun applySeed() = ResourceDatabasePopulator(seed).execute(TestPostgres.adminJdbcTemplate.dataSource!!)

    private fun count(sql: String): Int = TestPostgres.adminJdbcTemplate.queryForObject(sql, Int::class.java) ?: 0

    @Test
    @DisplayName("二度流しても増えない")
    fun seedIsIdempotent() {
        applySeed()
        val first = listOf(
            count("SELECT count(*) FROM quiz.categories WHERE tenant_id IN $SEEDED"),
            count("SELECT count(*) FROM quiz.difficulties WHERE tenant_id IN $SEEDED"),
            count("SELECT count(*) FROM quiz.quizzes WHERE tenant_id IN $SEEDED"),
            count("SELECT count(*) FROM quiz.choices WHERE tenant_id IN $SEEDED"),
            count("SELECT count(*) FROM core.tenant_members WHERE tenant_id IN $SEEDED"),
        )

        applySeed()

        assertThat(
            listOf(
                count("SELECT count(*) FROM quiz.categories WHERE tenant_id IN $SEEDED"),
                count("SELECT count(*) FROM quiz.difficulties WHERE tenant_id IN $SEEDED"),
                count("SELECT count(*) FROM quiz.quizzes WHERE tenant_id IN $SEEDED"),
                count("SELECT count(*) FROM quiz.choices WHERE tenant_id IN $SEEDED"),
                count("SELECT count(*) FROM core.tenant_members WHERE tenant_id IN $SEEDED"),
            ),
        ).isEqualTo(first)
    }

    @Test
    @DisplayName("行レベルセキュリティが効くロールでも流せる")
    fun seedPassesRowLevelSecurity() {
        // Aurora でマイグレーションを流す quiz はスーパーユーザーではなく、FORCE ROW LEVEL SECURITY で
        // 所有者にもポリシーが効く（ADR-0014）。ローカルとテストの quiz はスーパーユーザーなので、この条件を再現できない。
        // 同じく RLS の対象になる quiz_app で流す。テナントを設定せずに入れようとすると、ここで拒否される。
        // Flyway と同じく、全体を 1 つのトランザクションで流す
        DriverManager.getConnection(TestPostgres.container.jdbcUrl, "quiz_app", "quiz_app").use { connection ->
            connection.autoCommit = false
            ScriptUtils.executeSqlScript(connection, seed)
            connection.commit()
        }

        assertThat(count("SELECT count(*) FROM quiz.choices WHERE tenant_id IN $SEEDED")).isGreaterThan(0)
    }

    @Test
    @DisplayName("公開クイズは選択肢 4 つ・正解 1 つを満たす")
    fun publishedQuizzesSatisfyInvariants() {
        applySeed()

        val broken = count(
            """
            SELECT count(*) FROM quiz.quizzes q
            WHERE q.tenant_id IN $SEEDED AND q.status = 'published'
              AND ( (SELECT count(*) FROM quiz.choices c WHERE c.quiz_id = q.id) <> 4
                 OR (SELECT count(*) FROM quiz.choices c WHERE c.quiz_id = q.id AND c.is_correct) <> 1 )
            """,
        )

        assertThat(broken).isZero()
        assertThat(count("SELECT count(*) FROM quiz.quizzes WHERE tenant_id IN $SEEDED AND status = 'published'"))
            .isGreaterThan(0)
    }

    @Test
    @DisplayName("カテゴリごとに難易度の体系が異なる")
    fun categoriesHaveTheirOwnDifficultyScales() {
        applySeed()

        val scales = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT c.name AS category, count(d.id) AS levels
            FROM quiz.categories c JOIN quiz.difficulties d ON d.category_id = c.id
            WHERE c.tenant_id IN $SEEDED
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
                WHERE tenant_id IN $SEEDED
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

    @Test
    @DisplayName("所属の数が 0 / 1 / 2 以上の利用者がそろっている")
    fun membershipCountsCoverTenantSelection() {
        applySeed()

        // `/` の挙動は所属の数で変わる（招待なしの表示 / 自動で遷移 / 選択画面）。どれもデモで見せられるようにする
        val counts = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT u.external_id, count(m.id) AS tenants FROM core.users u
            LEFT JOIN core.tenant_members m ON m.user_id = u.id AND m.deleted_at IS NULL
            WHERE u.external_id LIKE 'demo-%'
            GROUP BY u.external_id ORDER BY u.external_id
            """,
        ) { rs, _ -> rs.getString("external_id") to rs.getInt("tenants") }

        assertThat(counts).containsExactly("demo-admin" to 2, "demo-member" to 1, "demo-outsider" to 0)
    }

    @Test
    @DisplayName("同じ利用者が、テナントによって違うロールを持つ")
    fun rolesDifferByTenant() {
        applySeed()

        val roles = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT t.slug, m.role FROM core.tenant_members m
            JOIN core.users u ON u.id = m.user_id
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE u.external_id = 'demo-admin' ORDER BY t.slug
            """,
        ) { rs, _ -> rs.getString("slug") to rs.getString("role") }

        assertThat(roles).containsExactly("demo" to "admin", "geo-club" to "member")
    }
}
