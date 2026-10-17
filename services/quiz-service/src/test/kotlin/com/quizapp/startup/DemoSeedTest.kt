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
 * デモ用シード（dev）と、デモのテナントの土台（dev と prod。DEV-191）の検証。
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

    /** デモのテナントの土台（dev と prod）と、dev のシード。Flyway と同じく、土台を先に流す */
    private val base = ClassPathResource("db/demo/R__demo_base.sql")
    private val seed = ClassPathResource("db/seed/R__demo_data.sql")

    private fun applyBase() = ResourceDatabasePopulator(base).execute(TestPostgres.adminJdbcTemplate.dataSource!!)

    private fun applySeed() = ResourceDatabasePopulator(base, seed).execute(TestPostgres.adminJdbcTemplate.dataSource!!)

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
        val quiz = "8a0fe98a-5ca7-526d-bc59-143bab566d83"
        val tenant = "9efd94a0-e517-573b-b46e-0f1ae96fc342"
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
    @DisplayName("技術のカテゴリは、すでに入っている DB では、配下の難易度とクイズごと 1 回の削除としてゴミ箱へ移る")
    fun techCategoriesAreTrashedWithChildren() {
        // dev に残っている状態を作る。DEV-164 で外したクイズも、同じカテゴリの下にある
        val tenant = "7fd43527-dbbf-525e-9f33-f48e4e507fd1"
        val category = "63b3c609-bfa5-5db4-bc9a-ec709936969d"
        val difficulty = "c39ca199-7f94-5d1b-b862-9667de4ba6c9"
        val quiz = "808eab59-8191-526d-8019-0ffb31f67840"
        val trimmed = "e9d199ac-f69c-57da-841a-aadbdd7ced12"
        applySeed()
        insertOldTechRows(tenant, category, difficulty, listOf(quiz, trimmed))

        applySeed()
        applySeed()

        // アプリでカテゴリを消したときと同じく、カテゴリ・難易度・クイズが同じバッチになり、ゴミ箱からまとめて戻せる
        val batches = TestPostgres.adminJdbcTemplate.queryForList(
            """
            SELECT deletion_batch_id::text FROM quiz.categories
            WHERE id = '$category' AND deleted_at IS NOT NULL
            UNION ALL SELECT deletion_batch_id::text FROM quiz.difficulties
            WHERE id = '$difficulty' AND deleted_at IS NOT NULL
            UNION ALL SELECT deletion_batch_id::text FROM quiz.quizzes
            WHERE id = '$quiz' AND deleted_at IS NOT NULL
            """,
            String::class.java,
        )
        assertThat(batches).hasSize(3)
        assertThat(batches.distinct()).hasSize(1)
        // DEV-164 で外したクイズは、それより前の削除のバッチのまま
        assertThat(
            count(
                """
                SELECT count(*) FROM quiz.quizzes WHERE id = '$trimmed'
                AND deletion_batch_id = 'a7bc86a8-2965-532e-af48-036fbe9034ce'
                """,
            ),
        ).isEqualTo(1)
    }

    /** DEV-188 より前のシードが入れた技術のカテゴリ・難易度・クイズを、同じ識別子で作る（消えていれば戻す） */
    private fun insertOldTechRows(tenant: String, category: String, difficulty: String, quizzes: List<String>) {
        TestPostgres.adminJdbcTemplate.update(
            """
            INSERT INTO quiz.categories (id, tenant_id, name, sort_order) VALUES (?::uuid, ?::uuid, 'AWS / インフラ', 1)
            ON CONFLICT (id) DO UPDATE SET deleted_at = NULL, deletion_batch_id = NULL
            """,
            category,
            tenant,
        )
        TestPostgres.adminJdbcTemplate.update(
            """
            INSERT INTO quiz.difficulties (id, tenant_id, category_id, name, level, sort_order)
            VALUES (?::uuid, ?::uuid, ?::uuid, 'CLF', 1, 1)
            ON CONFLICT (id) DO UPDATE SET deleted_at = NULL, deletion_batch_id = NULL
            """,
            difficulty,
            tenant,
            category,
        )
        quizzes.forEach { id ->
            TestPostgres.adminJdbcTemplate.update(
                """
                INSERT INTO quiz.quizzes (id, tenant_id, category_id, difficulty_id, question, explanation, status)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, '解説', 'draft')
                ON CONFLICT (id) DO UPDATE SET deleted_at = NULL, deletion_batch_id = NULL
                """,
                id,
                tenant,
                category,
                difficulty,
                "外すクイズ $id",
            )
        }
    }

    @Test
    @DisplayName("デモのテナントには、クイズを入れず、AI の受け箱のカテゴリだけを入れる")
    fun demoTenantHasOnlyTheAiInbox() {
        applySeed()

        // デモのクイズはパック（content/demo/）から API で流し込む（ADR-0034）
        val categories = TestPostgres.adminJdbcTemplate.queryForList(
            """
            SELECT c.name FROM quiz.categories c JOIN core.tenants t ON t.id = c.tenant_id
            WHERE t.slug = '$DEMO_SLUG' AND c.deleted_at IS NULL
            """,
            String::class.java,
        )
        assertThat(categories).containsExactly("AWS の最新情報")
        assertThat(
            count(
                """
                SELECT count(*) FROM quiz.quizzes q JOIN core.tenants t ON t.id = q.tenant_id
                WHERE t.slug = '$DEMO_SLUG' AND q.deleted_at IS NULL AND q.status = 'published'
                """,
            ),
        ).isZero()
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
            ScriptUtils.executeSqlScript(connection, base)
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
    @DisplayName("土台だけ（prod）では、デモのテナントに共有のデモのアカウントと運用者だけが所属し、dev の利用者とテナントは入らない")
    fun baseAloneHasDemoTenantWithoutDevFixtures() {
        // prod は db/demo だけを流す（application-demo.yml）。DB はほかのテストと共有のため、土台が入れる行だけを見る
        applyBase()
        applyBase()

        val memberships = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT u.email, m.role FROM core.tenant_members m
            JOIN core.users u ON u.id = m.user_id
            JOIN core.tenants t ON t.id = m.tenant_id
            WHERE t.slug = '$DEMO_SLUG' AND m.deleted_at IS NULL AND u.email IS NOT NULL
            """,
        ) { rs, _ -> rs.getString("email") to rs.getString("role") }
        assertThat(memberships).contains("demo@example.com" to "member", "max3max7.0402@gmail.com" to "admin")
        // アドレスは一意。二度流しても、運用者は 1 人のまま
        assertThat(count("SELECT count(*) FROM core.users WHERE lower(email) = 'max3max7.0402@gmail.com'")).isEqualTo(1)
        // 土台は、クイズとカテゴリを入れない。デモのパックを流し込む
        assertThat(
            count(
                """
                SELECT count(*) FROM quiz.categories c JOIN core.tenants t ON t.id = c.tenant_id
                WHERE t.slug = '$DEMO_SLUG' AND c.id NOT IN ('2f6c1b8e-4d0a-5c3e-9b7f-a1e2d3c4b5a6')
                AND c.deleted_at IS NULL
                """,
            ),
        ).isZero()
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
            "max3max7.0402@gmail.com" to "admin",
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
