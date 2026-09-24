package com.quizapp.auth

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
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
 * 利用者はテストごとに作る。共有の利用者だと、他のテストが作った所属が一覧に混ざる。
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

    private lateinit var user: UUID
    private lateinit var romeo: TestTenant
    private lateinit var sierra: TestTenant

    @BeforeEach
    fun setUp() {
        val admin = TestPostgres.adminJdbcTemplate
        user = TestAuth.createUser("一覧の利用者")
        val stranger = TestAuth.createUser("別の利用者")

        romeo = TestTenant.create("ロメオ").join(user, "admin")
        sierra = TestTenant.create("シエラ").join(user, "member")
        // 別の利用者だけが所属している。利用者で絞り忘れると一覧に出る
        TestTenant.create("タンゴ").join(stranger, "admin")
        // 所属を外れた
        val left = TestTenant.create("ユニフォーム").join(user, "member")
        admin.update("UPDATE core.tenant_members SET deleted_at = now() WHERE tenant_id = ?", left.id)
        // 所属したまま、テナントが削除された
        val deleted = TestTenant.create("ヴィクター").join(user, "admin")
        admin.update("UPDATE core.tenants SET deleted_at = now() WHERE id = ?", deleted.id)
    }

    @Test
    @DisplayName("有効な所属だけを、そこでのロールとともに名前順で返す")
    fun listsActiveMemberships() {
        // 作った順（ロメオが先）ではなく名前順に並ぶ
        assertThat(tenantsOf(user)).containsExactly(
            Triple(sierra.slug, "シエラ", "member"),
            Triple(romeo.slug, "ロメオ", "admin"),
        )
    }

    @Test
    @DisplayName("どこにも所属していなければ空の一覧")
    fun emptyWhenNoMembership() {
        assertThat(tenantsOf(TestAuth.createUser())).isEmpty()
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
