package com.quizapp.startup

import com.quizapp.quiz.infrastructure.integrity.IntegrityChecks
import com.quizapp.quiz.support.TestPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * デモ用シードの検証。
 *
 * repeatable マイグレーションは**内容を変えるたびに再実行される**ため、
 * 二度流しても壊れないことがそのまま要件になる。
 *
 * あわせて、シードが**ドメインの不変条件を満たしていること**を確かめる。
 * SQL で直接入れるため、公開クイズの「選択肢ちょうど 4 つ・正解ちょうど 1 つ」は
 * アプリケーション層の検証を通らない。DB の制約だけでは行数の条件を表現できない。
 * dev ではデータの整合性の確認（`IntegrityCheck`）も同じ決まりを当てるが、気づくのはデプロイの後になる。
 *
 * Flyway の履歴を汚さないよう、ここでは locations を切り替えず SQL を直接流す。
 *
 * 他のテストと同じく片付けない。**数えるのはシードのテナントに絞る。**
 * コンテナは全テストで共有しているため、絞らないと他のテストが作った行まで数える。
 */
@SpringBootTest
class DemoSeedTest {

    @Autowired private lateinit var integrityChecks: IntegrityChecks

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
    @DisplayName("画面で編集して選択肢が入れ直されたクイズがあっても流し直せ、選択肢は増えない")
    fun seedSkipsQuizzesWhoseChoicesWereReplaced() {
        applySeed()
        // 画面でクイズを編集すると、選択肢は新しい識別子で入れ直される（DEV-104 のデプロイで、dev がこの状態だった）
        val quiz = "808eab59-8191-526d-8019-0ffb31f67840"
        val tenant = "7fd43527-dbbf-525e-9f33-f48e4e507fd1"
        TestPostgres.adminJdbcTemplate.update("DELETE FROM quiz.choices WHERE quiz_id = ?::uuid", quiz)
        (1..4).forEach { order ->
            TestPostgres.adminJdbcTemplate.update(
                """
                INSERT INTO quiz.choices (tenant_id, quiz_id, body, is_correct, sort_order)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                """,
                tenant,
                quiz,
                "編集した選択肢 $order",
                order == 1,
                order,
            )
        }

        applySeed()

        assertThat(count("SELECT count(*) FROM quiz.choices WHERE quiz_id = '$quiz'")).isEqualTo(4)
        assertThat(count("SELECT count(*) FROM quiz.choices WHERE quiz_id = '$quiz' AND body LIKE '編集した選択肢%'"))
            .isEqualTo(4)
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
    @DisplayName("データの整合性の決まりを満たす（公開クイズの選択肢と正解と解説、解説が指す図、削除の連鎖）")
    fun satisfiesIntegrityRules() {
        applySeed()

        // 決まりはデータの整合性の確認（DEV-115）と同じものを使う。ここで書き直すと、2 つがずれる
        val tenants = TestPostgres.adminJdbcTemplate.queryForList(
            "SELECT id FROM core.tenants WHERE id IN $SEEDED",
            UUID::class.java,
        ).filterNotNull()

        assertThat(tenants).hasSize(2)
        assertThat(tenants.flatMap(integrityChecks::check)).isEmpty()
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

        // デモのアカウントは external_id を持たないため、メールアドレスで見分ける
        val roles = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT coalesce(u.external_id, u.email) AS who, m.role FROM core.tenant_members m
            JOIN core.users u ON u.id = m.user_id
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE t.slug = '$DEMO_SLUG'
            """,
        ) { rs, _ -> rs.getString("who") to rs.getString("role") }

        assertThat(roles).containsExactlyInAnyOrder(
            "demo-admin" to "admin",
            "demo-member" to "member",
            "demo@example.com" to "member",
        )
    }

    @Test
    @DisplayName("デモのアカウントは、メールアドレスだけで登録し、デモのテナントにだけ一般ユーザーとして所属する")
    fun demoAccountIsLinkableMemberOfDemoOnly() {
        applySeed()

        // external_id がないので、同じアドレスの Cognito の利用者が最初にログインしたときに結び付く（DEV-104）。
        // 所属が 1 つなので、ログインするとデモのテナントへそのまま移る。一般ユーザーなので、クイズは変えられない
        val memberships = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT u.external_id, t.slug, m.role FROM core.users u
            JOIN core.tenant_members m ON m.user_id = u.id AND m.deleted_at IS NULL
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE lower(u.email) = 'demo@example.com'
            """,
        ) { rs, _ -> Triple(rs.getString("external_id"), rs.getString("slug"), rs.getString("role")) }

        assertThat(memberships).containsExactly(Triple(null, DEMO_SLUG, "member"))
    }

    @Test
    @DisplayName("デモのアカウントは共有のアカウントで、テナントを作れない。ほかのシードの利用者は作れる")
    fun onlyDemoAccountIsShared() {
        applySeed()

        // パスワードを公開しているので、作れると最初の 1 人が上限を使い切る（ADR-0028）。
        // DB はほかのテストと共有で、ほかのテストが印を付けた利用者もいる。シードの利用者だけを見る
        val shared = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT coalesce(external_id, email) AS who, shared FROM core.users
            WHERE external_id IN ('demo-admin', 'demo-member', 'demo-outsider') OR lower(email) = 'demo@example.com'
            """,
        ) { rs, _ -> rs.getString("who") to rs.getBoolean("shared") }

        assertThat(shared).containsExactlyInAnyOrder(
            "demo-admin" to false,
            "demo-member" to false,
            "demo-outsider" to false,
            "demo@example.com" to true,
        )
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
