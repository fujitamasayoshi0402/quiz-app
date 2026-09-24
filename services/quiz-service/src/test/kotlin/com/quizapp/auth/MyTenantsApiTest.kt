package com.quizapp.auth

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 所属テナントの一覧（`GET /api/me/tenants`）。
 *
 * テナントの外にある API なので、テナント境界のテスト（`TenantBoundaryApiTest`）の対象にならない。
 * **ここが境界の検証を兼ねる。** 返してよいのは、利用者本人の、いま有効な所属だけ。
 *
 * 利用者はこのテストだけで使う。共有の利用者だと、他のテストが作った所属が一覧に混ざる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyTenantsApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val user: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d4")
    private val stranger: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e5")

    private val romeo: UUID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000001")
    private val sierra: UUID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000002")
    private val tango: UUID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000003")
    private val uniform: UUID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000004")
    private val victor: UUID = UUID.fromString("0e0e0e0e-0000-4000-8000-000000000005")
    private val tenants get() = arrayOf(romeo, sierra, tango, uniform, victor)

    @BeforeEach
    fun setUp() {
        val admin = TestPostgres.adminJdbcTemplate
        admin.update(
            """
            INSERT INTO core.tenants (id, slug, name) VALUES
                (?, 'romeo', 'ロメオ'), (?, 'sierra', 'シエラ'), (?, 'tango', 'タンゴ'),
                (?, 'uniform', 'ユニフォーム'), (?, 'victor', 'ヴィクター')
            """,
            *tenants,
        )
        listOf(user to "一覧の利用者", stranger to "別の利用者").forEach { (id, name) ->
            admin.update(
                "INSERT INTO core.users (id, external_id, display_name) VALUES (?, ?, ?)",
                id,
                "test-$id",
                name,
            )
        }

        TestAuth.join(romeo, user, "admin")
        TestAuth.join(sierra, user, "member")
        // tango には別の利用者だけが所属している。利用者で絞り忘れると一覧に出る
        TestAuth.join(tango, stranger, "admin")
        // uniform は所属を外れた
        TestAuth.join(uniform, user, "member")
        admin.update("UPDATE core.tenant_members SET deleted_at = now() WHERE tenant_id = ?", uniform)
        // victor は所属したまま、テナントが削除された
        TestAuth.join(victor, user, "admin")
        admin.update("UPDATE core.tenants SET deleted_at = now() WHERE id = ?", victor)
    }

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        TestAuth.leaveAll(*tenants)
        admin.update("DELETE FROM core.tenants WHERE id IN (?, ?, ?, ?, ?)", *tenants)
        admin.update("DELETE FROM core.users WHERE id IN (?, ?)", user, stranger)
    }

    @Test
    @DisplayName("有効な所属だけを、そこでのロールとともに名前順で返す")
    fun listsActiveMemberships() {
        // 名前順と slug 順が逆になるようにしてある
        assertThat(tenantsOf(user)).containsExactly(
            Triple("sierra", "シエラ", "member"),
            Triple("romeo", "ロメオ", "admin"),
        )
    }

    @Test
    @DisplayName("どこにも所属していなければ空の一覧")
    fun emptyWhenNoMembership() {
        assertThat(tenantsOf(TestAuth.OUTSIDER)).isEmpty()
    }

    @Test
    @DisplayName("利用者を示さないと 401")
    fun anonymousIsUnauthorized() {
        mockMvc.get("/api/me/tenants").andExpect { status { isUnauthorized() } }
    }

    private fun tenantsOf(userId: UUID): List<Triple<String, String, String>> = mockMvc.get("/api/me/tenants") {
        header("X-User-Id", userId.toString())
    }.andExpect { status { isOk() } }
        .andReturn().response.contentAsString
        .let(objectMapper::readTree)
        .values().map { Triple(it["slug"].asString(), it["name"].asString(), it["role"].asString()) }
}
