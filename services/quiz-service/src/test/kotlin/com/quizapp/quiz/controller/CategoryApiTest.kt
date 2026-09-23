package com.quizapp.quiz.controller

import tools.jackson.databind.ObjectMapper
import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID

/**
 * カテゴリ API の統合テスト。
 *
 * とくに**テナントをまたいだアクセスが遮断されること**を確認する。
 * これは機能の確認ではなく、情報漏洩が起きないことの検証にあたる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val tenantA: UUID = UUID.fromString("aaaa1111-1111-1111-1111-111111111111")
    private val tenantB: UUID = UUID.fromString("bbbb2222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenants (id, slug, name) VALUES (?, 'alpha', 'アルファ'), (?, 'bravo', 'ブラボー')",
            tenantA,
            tenantB,
        )
        TestAuth.ensureUsers()
        TestAuth.joinAsAdmin(tenantA, tenantB)
    }

    @AfterEach
    fun tearDown() {
        // RLS を通さない接続で片付ける。quiz_app では 1 行も消えないまま成功してしまう
        TestPostgres.adminJdbcTemplate.update(
            "DELETE FROM quiz.categories WHERE tenant_id IN (?, ?)",
            tenantA,
            tenantB,
        )
        TestAuth.leaveAll(tenantA, tenantB)
        TestPostgres.adminJdbcTemplate.update("DELETE FROM core.tenants WHERE id IN (?, ?)", tenantA, tenantB)
    }

    private fun createCategory(slug: String, name: String, sortOrder: Int = 0): UUID {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "sortOrder" to sortOrder))
        val result = mockMvc.post("/api/t/$slug/admin/categories") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }.andReturn()

        // JsonNode を辿るより、レスポンスの型にそのまま読み込むほうが崩れに気づける
        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    @Test
    @DisplayName("カテゴリを作成すると一覧に現れる")
    fun createAndList() {
        createCategory("alpha", "AWS")

        mockMvc.get("/api/t/alpha/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("AWS") }
        }
    }

    @Test
    @DisplayName("一覧は並び順で返る")
    fun listIsOrdered() {
        createCategory("alpha", "あとに出る", sortOrder = 2)
        createCategory("alpha", "さきに出る", sortOrder = 1)

        mockMvc.get("/api/t/alpha/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("さきに出る") }
            jsonPath("$[1].name") { value("あとに出る") }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリは一覧に現れない")
    fun categoriesAreIsolatedPerTenant() {
        createCategory("alpha", "アルファのカテゴリ")
        createCategory("bravo", "ブラボーのカテゴリ")

        mockMvc.get("/api/t/alpha/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("アルファのカテゴリ") }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリ ID を指定しても取得できない")
    fun cannotReadAnotherTenantCategory() {
        val idOfB = createCategory("bravo", "ブラボーのカテゴリ")

        mockMvc.get("/api/t/alpha/admin/categories/$idOfB") { auth() }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("他テナントのカテゴリ ID を指定した更新は通らない")
    fun cannotUpdateAnotherTenantCategory() {
        val idOfB = createCategory("bravo", "ブラボーのカテゴリ")

        mockMvc.put("/api/t/alpha/admin/categories/$idOfB") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"乗っ取り","sortOrder":0}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("削除すると一覧から消える")
    fun deleteRemovesFromList() {
        val id = createCategory("alpha", "消す対象")

        mockMvc.delete("/api/t/alpha/admin/categories/$id") { auth() }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/t/alpha/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("削除は論理削除で、行そのものは残る")
    fun deleteIsSoftDelete() {
        val id = createCategory("alpha", "消す対象")
        mockMvc.delete("/api/t/alpha/admin/categories/$id") { auth() }.andExpect { status { isNoContent() } }

        // 行が残っているかは RLS を通さない接続で確認する
        val deletedAt = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT deleted_at FROM quiz.categories WHERE id = ?",
            Instant::class.java,
            id,
        )
        checkNotNull(deletedAt) { "論理削除なので行は残り、deleted_at が入るはず" }
    }

    @Test
    @DisplayName("名前が空なら 400 を返す")
    fun blankNameIsRejected() {
        mockMvc.post("/api/t/alpha/admin/categories") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"","sortOrder":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.name") { exists() }
        }
    }

    @Test
    @DisplayName("存在しないテナントなら 404 を返す")
    fun unknownTenantReturnsNotFound() {
        mockMvc.get("/api/t/unknown/admin/categories") { auth() }.andExpect { status { isNotFound() } }
    }

    /** テナント配下のエンドポイントは所属していないと触れない。既定は共有管理者。 */
    private fun MockHttpServletRequestDsl.auth(user: UUID = TestAuth.ADMIN) {
        header("X-User-Id", user.toString())
    }
}
