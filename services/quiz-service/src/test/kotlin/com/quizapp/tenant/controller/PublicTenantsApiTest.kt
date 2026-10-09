package com.quizapp.tenant.controller

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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 公開テナントの一覧と参加（`/api/me/public-tenants`、ADR-0025）。
 *
 * テナントの外にある API なので、テナント境界のテスト（`TenantBoundaryApiTest`）の対象にならない。
 * **ここが境界の検証を兼ねる。** 非公開のテナントは一覧に出ず、参加もできず、あることも明かさない。
 *
 * テストの DB は共有で、ほかのテストが公開にしたテナントも一覧に出る。確かめるのは、このテストで作ったテナントだけ
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicTenantsApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val NOT_FOUND = "指定されたテナントは存在しません"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var user: UUID
    private lateinit var alpha: TestTenant
    private lateinit var bravo: TestTenant
    private lateinit var hidden: TestTenant
    private lateinit var deleted: TestTenant

    @BeforeEach
    fun setUp() {
        user = TestAuth.createUser("公開テナントを探す人")
        // 作った順（ブラボーが先）ではなく名前順に並ぶ
        bravo = TestTenant.create("公開 ブラボー").withAdmin().public()
        alpha = TestTenant.create("公開 アルファ").withAdmin().public()
        hidden = TestTenant.create("非公開 チャーリー").withAdmin()
        deleted = TestTenant.create("削除済み デルタ").withAdmin().public()
        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET deleted_at = now() WHERE id = ?", deleted.id)
    }

    @Test
    @DisplayName("公開していて削除されていないテナントだけを、名前順に返す")
    fun listsOnlyPublicTenants() {
        val listed = list(user).filter { it.slug in mine() }

        assertThat(listed).containsExactly(
            Listed(alpha.slug, "公開 アルファ", false),
            Listed(bravo.slug, "公開 ブラボー", false),
        )
    }

    @Test
    @DisplayName("参加すると一般ユーザーとして所属し、所属の一覧と出題に出る。一覧では参加済みになる")
    fun joins() {
        join(alpha.slug, user).andExpect {
            status { isOk() }
            jsonPath("$.slug") { value(alpha.slug) }
            jsonPath("$.name") { value("公開 アルファ") }
            jsonPath("$.role") { value("member") }
        }

        assertThat(roleOf(alpha, user)).isEqualTo("member")
        assertThat(list(user).single { it.slug == alpha.slug }.joined).isTrue()
        mockMvc.get("/api/t/${alpha.slug}/play/categories") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isOk() } }
    }

    @Test
    @DisplayName("もう一度参加しても、所属は増えない")
    fun joinIsIdempotent() {
        join(alpha.slug, user).andExpect { status { isOk() } }
        join(alpha.slug, user).andExpect { status { isOk() } }

        assertThat(memberRows(alpha, user)).isEqualTo(1)
    }

    @Test
    @DisplayName("管理者が参加を押しても、一般ユーザーに下がらない")
    fun adminStaysAdmin() {
        join(alpha.slug, TestAuth.ADMIN).andExpect {
            status { isOk() }
            jsonPath("$.role") { value("admin") }
        }

        assertThat(roleOf(alpha, TestAuth.ADMIN)).isEqualTo("admin")
    }

    @Test
    @DisplayName("非公開・削除済み・存在しないテナントには参加できず、どれも同じ 404 を返す")
    fun cannotJoinNonPublicTenants() {
        val bodies = listOf(hidden.slug, deleted.slug, "no-such-tenant").map { slug ->
            join(slug, user).andExpect {
                status { isNotFound() }
                jsonPath("$.detail") { value(NOT_FOUND) }
            }.andReturn().response.contentAsString
        }

        // 応答の違いから、非公開のテナントがあることを読み取れない。違うのは、要求したパス（instance）だけ
        val withoutPath = bodies.map { body ->
            objectMapper.readTree(body).properties().filter { it.key != "instance" }.associate { it.key to it.value }
        }
        assertThat(withoutPath.distinct()).hasSize(1)
        assertThat(roleOf(hidden, user)).isNull()
        assertThat(roleOf(deleted, user)).isNull()
    }

    @Test
    @DisplayName("非公開に戻すと一覧から消えて参加できなくなるが、参加した人は残る")
    fun backToPrivateKeepsMembers() {
        join(alpha.slug, user).andExpect { status { isOk() } }
        val newcomer = TestAuth.createUser("戻したあとに来た人")

        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET visibility = 'private' WHERE id = ?", alpha.id)

        assertThat(list(newcomer).map { it.slug }).doesNotContain(alpha.slug)
        join(alpha.slug, newcomer).andExpect { status { isNotFound() } }
        assertThat(roleOf(alpha, user)).isEqualTo("member")
    }

    @Test
    @DisplayName("利用者を示さないと 401")
    fun anonymousIsUnauthorized() {
        mockMvc.get("/api/me/public-tenants").andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/me/public-tenants/${alpha.slug}/join").andExpect { status { isUnauthorized() } }
    }

    private data class Listed(val slug: String, val name: String, val joined: Boolean)

    private fun mine() = setOf(alpha.slug, bravo.slug, hidden.slug, deleted.slug)

    private fun TestTenant.public(): TestTenant = apply {
        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET visibility = 'public' WHERE id = ?", id)
    }

    private fun list(userId: UUID): List<Listed> = mockMvc.get("/api/me/public-tenants") {
        header("Authorization", TestAuth.bearer(userId))
    }.andExpect { status { isOk() } }
        .andReturn().response.contentAsString
        .let(objectMapper::readTree)
        .values().map { Listed(it["slug"].asString(), it["name"].asString(), it["joined"].asBoolean()) }

    private fun join(slug: String, userId: UUID) = mockMvc.post("/api/me/public-tenants/$slug/join") {
        header("Authorization", TestAuth.bearer(userId))
    }

    private fun roleOf(tenant: TestTenant, userId: UUID): String? = TestPostgres.adminJdbcTemplate.queryForList(
        "SELECT role FROM core.tenant_members WHERE tenant_id = ? AND user_id = ? AND deleted_at IS NULL",
        String::class.java,
        tenant.id,
        userId,
    ).firstOrNull()

    private fun memberRows(tenant: TestTenant, userId: UUID): Int? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.tenant_members WHERE tenant_id = ? AND user_id = ?",
        Int::class.java,
        tenant.id,
        userId,
    )
}
