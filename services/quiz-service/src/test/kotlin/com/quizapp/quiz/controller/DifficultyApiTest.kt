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
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 難易度 API の統合テスト。
 *
 * 難易度はカテゴリ配下のリソースなので、**カテゴリとの所属関係が守られること**を重点的に確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifficultyApiTest {

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
        tenantA = TestTenant.create("チャーリー").withAdmin()
        tenantB = TestTenant.create("デルタ").withAdmin()
    }

    private fun createCategory(slug: String, name: String): UUID {
        val result = mockMvc.post("/api/t/$slug/admin/categories") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","sortOrder":0}"""
        }.andExpect { status { isCreated() } }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    private fun createDifficulty(slug: String, categoryId: UUID, name: String, level: Int, sortOrder: Int = 0): UUID {
        val result = mockMvc.post("/api/t/$slug/admin/categories/$categoryId/difficulties") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level,"sortOrder":$sortOrder}"""
        }.andExpect { status { isCreated() } }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, DifficultyResponse::class.java).id
    }

    @Test
    @DisplayName("カテゴリに難易度を追加できる")
    fun createAndList() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        createDifficulty(tenantA.slug, categoryId, "CLF", 1)

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("CLF") }
            jsonPath("$[0].level") { value(1) }
        }
    }

    @Test
    @DisplayName("同じレベルの難易度を複数登録できる")
    fun multipleDifficultiesCanShareLevel() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        createDifficulty(tenantA.slug, categoryId, "SAA", 2, sortOrder = 1)
        createDifficulty(tenantA.slug, categoryId, "DVA", 2, sortOrder = 2)
        createDifficulty(tenantA.slug, categoryId, "SOA", 2, sortOrder = 3)

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            // AWS のアソシエイト級のように、同じ難度帯に複数の種類が並ぶ体系を表現できる
            jsonPath("$[0].name") { value("SAA") }
            jsonPath("$[1].name") { value("DVA") }
            jsonPath("$[2].name") { value("SOA") }
        }
    }

    @Test
    @DisplayName("一覧はレベル順、同一レベル内は並び順で返る")
    fun listIsOrderedByLevelThenSortOrder() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        createDifficulty(tenantA.slug, categoryId, "SAP", 3)
        createDifficulty(tenantA.slug, categoryId, "CLF", 1)
        createDifficulty(tenantA.slug, categoryId, "SAA", 2, sortOrder = 1)

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("CLF") }
            jsonPath("$[1].name") { value("SAA") }
            jsonPath("$[2].name") { value("SAP") }
        }
    }

    @Test
    @DisplayName("カテゴリごとに異なる難易度体系を持てる")
    fun eachCategoryHasItsOwnScale() {
        val aws = createCategory(tenantA.slug, "AWS")
        val auth = createCategory(tenantA.slug, "認証認可")
        createDifficulty(tenantA.slug, aws, "CLF", 1)
        createDifficulty(tenantA.slug, auth, "初級", 1)

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$aws/difficulties") { auth() }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("CLF") }
        }
        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$auth/difficulties") { auth() }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("初級") }
        }
    }

    @Test
    @DisplayName("別カテゴリの難易度は、URL のカテゴリを変えても取得できない")
    fun cannotAccessDifficultyThroughWrongCategory() {
        val aws = createCategory(tenantA.slug, "AWS")
        val auth = createCategory(tenantA.slug, "認証認可")
        val clf = createDifficulty(tenantA.slug, aws, "CLF", 1)

        // URL 上は認証認可カテゴリの配下として AWS の難易度を指定する
        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$auth/difficulties/$clf") { auth() }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリを指定した一覧取得は 404 を返す")
    fun cannotListDifficultiesOfAnotherTenant() {
        val categoryOfB = createCategory(tenantB.slug, "デルタのカテゴリ")

        // 空リストではなく 404 を返す。
        // 空リストだと「カテゴリは存在するが難易度が無い」という誤った情報を与えてしまう
        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$categoryOfB/difficulties") { auth() }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    @DisplayName("削除すると一覧から消える")
    fun deleteRemovesFromList() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        val id = createDifficulty(tenantA.slug, categoryId, "CLF", 1)

        mockMvc.delete("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties/$id") { auth() }
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties") { auth() }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("レベルが 0 以下なら 400 を返す")
    fun levelMustBePositive() {
        val categoryId = createCategory(tenantA.slug, "AWS")

        mockMvc.post("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"おかしなレベル","level":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.level") { exists() }
        }
    }

    @Test
    @DisplayName("存在しないカテゴリなら 404 を返す")
    fun unknownCategoryReturnsNotFound() {
        val unknown = UUID.randomUUID()

        mockMvc.get("/api/t/${tenantA.slug}/admin/categories/$unknown/difficulties") { auth() }.andExpect {
            status { isNotFound() }
        }
    }

    /** テナント配下のエンドポイントは所属していないと触れない。既定は共有管理者。 */
    private fun MockHttpServletRequestDsl.auth(user: UUID = TestAuth.ADMIN) {
        header("Authorization", TestAuth.bearer(user))
    }
}
