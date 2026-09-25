package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
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

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenantA: TestTenant
    private lateinit var tenantB: TestTenant

    @BeforeEach
    fun setUp() {
        tenantA = TestTenant.create("アルファ").withAdmin()
        tenantB = TestTenant.create("ブラボー").withAdmin()
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
        createCategory(tenantA.slug, "AWS")

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("AWS") }
        }
    }

    @Test
    @DisplayName("一覧は並び順で返る")
    fun listIsOrdered() {
        createCategory(tenantA.slug, "あとに出る", sortOrder = 2)
        createCategory(tenantA.slug, "さきに出る", sortOrder = 1)

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("さきに出る") }
            jsonPath("$[1].name") { value("あとに出る") }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリは一覧に現れない")
    fun categoriesAreIsolatedPerTenant() {
        createCategory(tenantA.slug, "アルファのカテゴリ")
        createCategory(tenantB.slug, "ブラボーのカテゴリ")

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("アルファのカテゴリ") }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリ ID を指定しても取得できない")
    fun cannotReadAnotherTenantCategory() {
        val idOfB = createCategory(tenantB.slug, "ブラボーのカテゴリ")

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$idOfB") { auth() }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("他テナントのカテゴリ ID を指定した更新は通らない")
    fun cannotUpdateAnotherTenantCategory() {
        val idOfB = createCategory(tenantB.slug, "ブラボーのカテゴリ")

        mockMvc.put("/api/t/${tenantA.slug}/admin/categories/$idOfB") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"乗っ取り","sortOrder":0}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("削除すると一覧から消える")
    fun deleteRemovesFromList() {
        val id = createCategory(tenantA.slug, "消す対象")

        mockMvc.delete("/api/t/${tenantA.slug}/admin/categories/$id") { auth() }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("削除は論理削除で、行そのものは残る")
    fun deleteIsSoftDelete() {
        val id = createCategory(tenantA.slug, "消す対象")
        mockMvc.delete("/api/t/${tenantA.slug}/admin/categories/$id") { auth() }.andExpect { status { isNoContent() } }

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
        mockMvc.post("/api/t/${tenantA.slug}/admin/categories") {
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
        header("Authorization", TestAuth.bearer(user))
    }
}
