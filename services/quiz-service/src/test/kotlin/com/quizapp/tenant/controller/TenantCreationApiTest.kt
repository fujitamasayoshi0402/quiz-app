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
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

/**
 * ログインした人が、自分のテナントを作る（`/api/me/tenants`、`/api/me/tenant-creation`、ADR-0028）。
 *
 * テナントの外にある API なので、テナント境界のテスト（`TenantBoundaryApiTest`）の対象にならない。
 * **ここが境界の検証を兼ねる。** 作ったテナントは、作った人のほかには見えない
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantCreationApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SLUG_RANDOM_LENGTH = 16
    }

    @Autowired private lateinit var mockMvc: MockMvc

    private lateinit var user: UUID
    private lateinit var slug: String

    @BeforeEach
    fun setUp() {
        user = TestAuth.createUser("テナントを作る人")
        slug = "mine-" + UUID.randomUUID().toString().replace("-", "").take(SLUG_RANDOM_LENGTH)
    }

    @Test
    @DisplayName("作ると管理者として所属し、管理の API を使える。非公開で、上限が付く")
    fun creates() {
        status(user).andExpect {
            jsonPath("$.allowed") { value(true) }
            jsonPath("$.reason") { doesNotExist() }
        }

        create(user, slug, "  わたしのテナント  ").andExpect {
            status { isCreated() }
            jsonPath("$.slug") { value(slug) }
            jsonPath("$.name") { value("わたしのテナント") }
            jsonPath("$.role") { value("admin") }
        }

        val row = TestPostgres.adminJdbcTemplate.queryForMap(
            "SELECT created_by, visibility, quiz_limit, figure_limit FROM core.tenants WHERE slug = ?",
            slug,
        )
        assertThat(row["created_by"]).isEqualTo(user)
        assertThat(row["visibility"]).isEqualTo("private")
        assertThat(row["quiz_limit"]).isEqualTo(100)
        assertThat(row["figure_limit"]).isEqualTo(50)
        mockMvc.get("/api/t/$slug/admin/categories") { header("Authorization", TestAuth.bearer(user)) }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/me/tenants") { header("Authorization", TestAuth.bearer(user)) }
            .andExpect { jsonPath("$[?(@.slug == '$slug')].role") { value("admin") } }
    }

    @Test
    @DisplayName("1 人 1 つ。作ったあとは作れない理由を返し、2 つ目は 409")
    fun onlyOnePerUser() {
        create(user, slug).andExpect { status { isCreated() } }

        status(user).andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("already_created") }
        }
        create(user, "$slug-2").andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("already_created") }
        }
        assertThat(tenantsWithSlug("$slug-2")).isZero()
    }

    @Test
    @DisplayName("作ったテナントを消せば、また作れる")
    fun canCreateAgainAfterDeletion() {
        create(user, slug).andExpect { status { isCreated() } }
        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET deleted_at = now() WHERE slug = ?", slug)

        create(user, "$slug-2").andExpect { status { isCreated() } }
    }

    @Test
    @DisplayName("共有のアカウントは作れない")
    fun sharedAccountCannotCreate() {
        TestPostgres.adminJdbcTemplate.update("UPDATE core.users SET shared = true WHERE id = ?", user)

        status(user).andExpect {
            jsonPath("$.allowed") { value(false) }
            jsonPath("$.reason") { value("shared_account") }
        }
        create(user, slug).andExpect {
            status { isConflict() }
            jsonPath("$.reason") { value("shared_account") }
        }
        assertThat(tenantsWithSlug(slug)).isZero()
    }

    @Test
    @DisplayName("slug がほかのテナントと重なれば 409。削除されたテナントの slug は使える")
    fun slugMustBeUnique() {
        val taken = TestTenant.create()

        create(user, taken.slug).andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("URL に使う名前が重なっています") }
        }
        assertThat(roleOf(taken.id)).isNull()

        TestPostgres.adminJdbcTemplate.update("UPDATE core.tenants SET deleted_at = now() WHERE id = ?", taken.id)
        create(user, taken.slug).andExpect { status { isCreated() } }
    }

    @Test
    @DisplayName("slug の形と名前を確かめる")
    fun validatesInput() {
        listOf("ab", "Upper-case", "-start", "end-", "a".repeat(33), "日本語").forEach { bad ->
            create(user, bad).andExpect { status { isBadRequest() } }
        }
        create(user, slug, "   ").andExpect { status { isBadRequest() } }
        create(user, slug, "あ".repeat(101)).andExpect { status { isBadRequest() } }
        assertThat(tenantsWithSlug(slug)).isZero()
    }

    @Test
    @DisplayName("作ったテナントは、ほかの人には存在しないものとして見える")
    fun othersCannotSeeIt() {
        create(user, slug).andExpect { status { isCreated() } }
        val other = TestAuth.createUser("ほかの人")

        mockMvc.get("/api/t/$slug/play/categories") { header("Authorization", TestAuth.bearer(other)) }
            .andExpect { status { isNotFound() } }
        mockMvc.get("/api/me/tenants") { header("Authorization", TestAuth.bearer(other)) }
            .andExpect { jsonPath("$[?(@.slug == '$slug')]") { isEmpty() } }
        mockMvc.get("/api/me/public-tenants") { header("Authorization", TestAuth.bearer(other)) }
            .andExpect { jsonPath("$[?(@.slug == '$slug')]") { isEmpty() } }
    }

    @Test
    @DisplayName("作ったテナントは公開にできない。設定は、公開できないことを返す")
    fun cannotBePublic() {
        create(user, slug).andExpect { status { isCreated() } }

        mockMvc.get("/api/t/$slug/admin/settings") { header("Authorization", TestAuth.bearer(user)) }
            .andExpect {
                jsonPath("$.visibility") { value("private") }
                jsonPath("$.canBePublic") { value(false) }
            }
        updateVisibility("public").andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("公開できないテナントです") }
        }
        updateVisibility("private").andExpect { status { isOk() } }
        assertThat(
            TestPostgres.adminJdbcTemplate.queryForObject(
                "SELECT visibility FROM core.tenants WHERE slug = ?",
                String::class.java,
                slug,
            ),
        ).isEqualTo("private")
    }

    @Test
    @DisplayName("利用者を示さないと 401")
    fun anonymousIsUnauthorized() {
        mockMvc.get("/api/me/tenant-creation").andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/me/tenants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug":"$slug","name":"名前"}"""
        }.andExpect { status { isUnauthorized() } }
        assertThat(tenantsWithSlug(slug)).isZero()
    }

    private fun status(userId: UUID) = mockMvc.get("/api/me/tenant-creation") {
        header("Authorization", TestAuth.bearer(userId))
    }.andExpect { status { isOk() } }

    private fun create(userId: UUID, slug: String, name: String = "テナント"): ResultActionsDsl =
        mockMvc.post("/api/me/tenants") {
            header("Authorization", TestAuth.bearer(userId))
            contentType = MediaType.APPLICATION_JSON
            content = """{"slug":"$slug","name":"$name"}"""
        }

    private fun updateVisibility(visibility: String) = mockMvc.put("/api/t/$slug/admin/settings") {
        header("Authorization", TestAuth.bearer(user))
        contentType = MediaType.APPLICATION_JSON
        content = """{"visibility":"$visibility"}"""
    }

    private fun tenantsWithSlug(slug: String): Int = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM core.tenants WHERE slug = ?",
        Int::class.java,
        slug,
    ) ?: 0

    private fun roleOf(tenantId: UUID): String? = TestPostgres.adminJdbcTemplate.queryForList(
        "SELECT role FROM core.tenant_members WHERE tenant_id = ? AND user_id = ?",
        String::class.java,
        tenantId,
        user,
    ).firstOrNull()
}
