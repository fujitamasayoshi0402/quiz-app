package com.quizapp.tenant.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * テナントからの退会（`DELETE /api/t/{slug}/play/membership`、ADR-0030）。
 *
 * - 所属が外れ、そのテナントでの回答の履歴とランキングへの参加が消える
 * - **ほかの人の履歴と、同じ人のほかのテナントでの履歴は残る。** テナント境界のテスト（`TenantBoundaryApiTest`）は、
 *   試す人がそのテナントの最後の管理者で、退会が拒まれる。消す範囲は、ここで確かめる
 * - 最後の管理者と、共有のアカウントは退会できない
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantLeavingApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var other: TestTenant
    private lateinit var user: UUID
    private lateinit var neighbor: UUID

    /** テナントごとの、ただ 1 つのクイズ */
    private val quizOf = mutableMapOf<UUID, UUID>()

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("退会するテナント").withAdmin()
        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET visibility = 'public' WHERE id = ?", tenant.id)
        other = TestTenant.create("残るテナント").withAdmin()
        listOf(tenant, other).forEach { t ->
            val fixture = PlayFixture(mockMvc, objectMapper, t.slug)
            val category = fixture.category("AWS")
            quizOf[t.id] = fixture.quiz(category, fixture.difficulty(category, "SAA", 2), "問題")
        }
        user = TestAuth.createUser("退会する人")
        neighbor = TestAuth.createUser("残る人")
        tenant.join(user).join(neighbor)
        other.join(user)
    }

    @Test
    @DisplayName("退会すると所属が外れ、そのテナントでの履歴とランキングへの参加と印と復習の予定が消える。ほかの人と、ほかのテナントの履歴は残る")
    fun leaves() {
        solve(tenant, user)
        participate(tenant, user, "退会する人")
        bookmark(tenant, user)
        solve(tenant, neighbor)
        participate(tenant, neighbor, "残る人")
        bookmark(tenant, neighbor)
        solve(other, user)
        participate(other, user, "退会する人")
        bookmark(other, user)

        leave(tenant, user).andExpect { status { isNoContent() } }

        assertThat(myTenants(user)).doesNotContain(tenant.slug).contains(other.slug)
        mockMvc.get("/api/t/${tenant.slug}/play/categories") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isNotFound() } }

        assertThat(rows("answer.attempts", tenant, user)).isZero()
        assertThat(rows("answer.answers", tenant, user)).isZero()
        assertThat(rows("answer.ranking_entries", tenant, user)).isZero()
        assertThat(rows("answer.bookmarks", tenant, user)).isZero()
        assertThat(rows("answer.review_schedules", tenant, user)).isZero()
        assertThat(rows("answer.attempts", tenant, neighbor)).isEqualTo(1)
        assertThat(rows("answer.ranking_entries", tenant, neighbor)).isEqualTo(1)
        assertThat(rows("answer.bookmarks", tenant, neighbor)).isEqualTo(1)
        assertThat(rows("answer.review_schedules", tenant, neighbor)).isEqualTo(1)
        assertThat(rows("answer.attempts", other, user)).isEqualTo(1)
        assertThat(rows("answer.ranking_entries", other, user)).isEqualTo(1)
        assertThat(rows("answer.bookmarks", other, user)).isEqualTo(1)
        assertThat(rows("answer.review_schedules", other, user)).isEqualTo(1)
    }

    @Test
    @DisplayName("もう一度参加しても、前の履歴は戻らない")
    fun rejoinsWithoutHistory() {
        solve(tenant, user)
        leave(tenant, user).andExpect { status { isNoContent() } }

        mockMvc.post("/api/me/public-tenants/${tenant.slug}/join") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/t/${tenant.slug}/play/history/attempts") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("最後の管理者は退会できない。ほかに管理者がいれば退会できる")
    fun lastAdminCannotLeave() {
        val admin = TestAuth.createUser("管理者")
        val lonely = TestTenant.create("管理者が 1 人").join(admin, "admin").join(user)

        leave(lonely, admin).andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("last_admin") }
        }
        assertThat(myTenants(admin)).contains(lonely.slug)

        lonely.join(TestAuth.createUser("2 人目の管理者"), "admin")
        leave(lonely, admin).andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("一般ユーザーが最後の 1 人でも退会できる。管理者は残る")
    fun memberCanLeave() {
        leave(tenant, neighbor).andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("共有のアカウントは退会できない")
    fun sharedAccountCannotLeave() {
        val shared = TestAuth.createUser("共有のアカウント")
        TestPostgres.adminJdbcTemplate.update("UPDATE core.users SET shared = true WHERE id = ?", shared)
        tenant.join(shared)

        leave(tenant, shared).andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("shared_account") }
        }
        assertThat(myTenants(shared)).contains(tenant.slug)
    }

    @Test
    @DisplayName("所属していないテナントからは退会できない。テナントがあることも明かさない")
    fun notMember() {
        leave(other, neighbor).andExpect { status { isNotFound() } }
    }

    private fun leave(t: TestTenant, userId: UUID): ResultActionsDsl =
        mockMvc.delete("/api/t/${t.slug}/play/membership") { header("Authorization", TestAuth.bearer(userId)) }

    private fun myTenants(userId: UUID): List<String> {
        val body = mockMvc.get("/api/me/tenants") { header("Authorization", TestAuth.bearer(userId)) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val tenants = objectMapper.readTree(body)
        return (0 until tenants.size()).map { tenants[it]["slug"].asString() }
    }

    private fun solve(t: TestTenant, userId: UUID) {
        val attempt = mockMvc.post("/api/t/${t.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
        val attemptId = attempt["id"].asString()
        val quiz = attempt["quizzes"][0]
        mockMvc.post("/api/t/${t.slug}/play/attempts/$attemptId/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
        mockMvc.post("/api/t/${t.slug}/play/attempts/$attemptId/complete") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
    }

    private fun participate(t: TestTenant, userId: UUID, name: String) {
        mockMvc.put("/api/t/${t.slug}/play/ranking/participation") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("name" to name))
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
    }

    /** テナントのクイズに「あとで見直す」印を付ける */
    private fun bookmark(t: TestTenant, userId: UUID) {
        mockMvc.put("/api/t/${t.slug}/play/bookmarks/${quizOf.getValue(t.id)}") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isNoContent() } }
    }

    /** 行レベルセキュリティを通さない所有者の接続で数える */
    private fun rows(table: String, t: TestTenant, userId: UUID): Int = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table WHERE tenant_id = ? AND user_id = ?",
        Int::class.java,
        t.id,
        userId,
    )!!
}
