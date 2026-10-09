package com.quizapp.quiz.controller

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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
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

    private fun createCategory(slug: String, name: String): UUID {
        val body = objectMapper.writeValueAsString(mapOf("name" to name))
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

    private fun reorder(slug: String, ids: List<UUID>): ResultActionsDsl =
        mockMvc.put("/api/t/$slug/admin/categories/order") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("ids" to ids))
        }

    private fun listedNames(slug: String): List<String> =
        mockMvc.get("/api/t/$slug/admin/categories") { auth() }.andReturn().response.contentAsString
            .let { objectMapper.readValue(it, Array<CategorySummaryResponse>::class.java) }.map { it.name }

    @Test
    @DisplayName("作ったカテゴリは末尾に並ぶ")
    fun newCategoryGoesLast() {
        createCategory(tenantA.slug, "1 つ目")
        createCategory(tenantA.slug, "2 つ目")
        createCategory(tenantA.slug, "3 つ目")

        assertThat(listedNames(tenantA.slug)).containsExactly("1 つ目", "2 つ目", "3 つ目")
    }

    @Test
    @DisplayName("並べ替えた順で返る。名前を直しても並びは変わらない")
    fun reordersCategories() {
        val first = createCategory(tenantA.slug, "1 つ目")
        val second = createCategory(tenantA.slug, "2 つ目")
        val third = createCategory(tenantA.slug, "3 つ目")

        reorder(tenantA.slug, listOf(third, first, second)).andExpect { status { isNoContent() } }
        mockMvc.put("/api/t/${tenantA.slug}/admin/categories/$first") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"1 つ目（直した）"}"""
        }.andExpect { status { isOk() } }

        assertThat(listedNames(tenantA.slug)).containsExactly("3 つ目", "1 つ目（直した）", "2 つ目")
    }

    @Test
    @DisplayName("並べ替えは、今あるカテゴリをちょうど 1 回ずつ含まなければ 409。並びは変えない")
    fun rejectsOutdatedOrder() {
        val first = createCategory(tenantA.slug, "1 つ目")
        val second = createCategory(tenantA.slug, "2 つ目")

        listOf(listOf(second), listOf(second, first, UUID.randomUUID()), listOf(second, second)).forEach { ids ->
            reorder(tenantA.slug, ids).andExpect {
                status { isConflict() }
                jsonPath("$.detail") { value("並べ替えている間に項目が変わりました。最新の一覧を取り直してから、並べ替えてください") }
            }
        }
        assertThat(listedNames(tenantA.slug)).containsExactly("1 つ目", "2 つ目")
    }

    @Test
    @DisplayName("一覧には、クイズの数と公開の数が付く。削除したクイズは数えない")
    fun listsQuizCounts() {
        val fixture = PlayFixture(mockMvc, objectMapper, tenantA.slug)
        val category = fixture.category("クイズのあるカテゴリ")
        val difficulty = fixture.difficulty(category, "初級", 1)
        fixture.quiz(category, difficulty, "公開 1")
        fixture.quiz(category, difficulty, "公開 2")
        fixture.quiz(category, difficulty, "下書き", status = "draft")
        val deleted = fixture.quiz(category, difficulty, "消した")
        mockMvc.delete("/api/t/${tenantA.slug}/admin/quizzes/$deleted") {
            auth()
        }.andExpect { status { isNoContent() } }
        createCategory(tenantA.slug, "空のカテゴリ")

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$[0].quizCount") { value(3) }
            jsonPath("$[0].publishedQuizCount") { value(2) }
            jsonPath("$[1].quizCount") { value(0) }
            jsonPath("$[1].publishedQuizCount") { value(0) }
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
            content = """{"name":"乗っ取り"}"""
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
            content = """{"name":""}"""
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
