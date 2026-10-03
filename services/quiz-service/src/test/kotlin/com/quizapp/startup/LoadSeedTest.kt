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
 * 負荷試験（tests/load）のシードの検証（DEV-112）。確かめる観点は [DemoSeedTest] と同じ。
 * 行を `generate_series` で作るため、識別子が流すたびに同じになることも、ここで分かる。
 */
@SpringBootTest
class LoadSeedTest {

    @Autowired private lateinit var integrityChecks: IntegrityChecks

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private val TENANT_ID: UUID = UUID.fromString("619c1526-2954-4876-bd19-7b878f9172c5")
    }

    private val seed = ClassPathResource("db/seed/R__load_data.sql")

    private fun applySeed() = ResourceDatabasePopulator(seed).execute(TestPostgres.adminJdbcTemplate.dataSource!!)

    private fun count(table: String): Int = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table WHERE tenant_id = ?",
        Int::class.java,
        TENANT_ID,
    ) ?: 0

    private fun counts() = listOf(
        "quiz.categories",
        "quiz.difficulties",
        "quiz.quizzes",
        "quiz.choices",
        "core.tenant_members",
    ).map(::count)

    @Test
    @DisplayName("二度流しても増えない")
    fun seedIsIdempotent() {
        applySeed()
        val first = counts()

        applySeed()

        assertThat(counts()).isEqualTo(first)
        assertThat(first).containsExactly(3, 9, 180, 720, 51)
    }

    @Test
    @DisplayName("行レベルセキュリティが効くロールでも流せる")
    fun seedPassesRowLevelSecurity() {
        // 理由は DemoSeedTest と同じ。Aurora の quiz にも RLS が効く
        DriverManager.getConnection(TestPostgres.container.jdbcUrl, "quiz_app", "quiz_app").use { connection ->
            connection.autoCommit = false
            ScriptUtils.executeSqlScript(connection, seed)
            connection.commit()
        }

        assertThat(count("quiz.choices")).isEqualTo(720)
    }

    @Test
    @DisplayName("データの整合性の決まりを満たす")
    fun satisfiesIntegrityRules() {
        applySeed()

        assertThat(integrityChecks.check(TENANT_ID)).isEmpty()
    }

    @Test
    @DisplayName("管理者 1 人と一般ユーザー 50 人が、メールアドレスだけで登録されている")
    fun membersAreLinkableByEmail() {
        applySeed()

        // external_id がないので、同じアドレスの Cognito の利用者が最初にログインしたときに結び付く
        val roles = TestPostgres.adminJdbcTemplate.query(
            """
            SELECT m.role, count(*) AS members, count(u.external_id) AS linked FROM core.tenant_members m
            JOIN core.users u ON u.id = m.user_id
            WHERE m.tenant_id = ? AND u.email LIKE 'load-%@example.com'
            GROUP BY m.role ORDER BY m.role
            """,
            { rs, _ -> Triple(rs.getString("role"), rs.getInt("members"), rs.getInt("linked")) },
            TENANT_ID,
        )

        assertThat(roles).containsExactly(Triple("admin", 1, 0), Triple("member", 50, 0))
    }
}
