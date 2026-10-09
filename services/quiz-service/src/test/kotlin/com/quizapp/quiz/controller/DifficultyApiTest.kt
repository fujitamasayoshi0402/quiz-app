package com.quizapp.quiz.controller

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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
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
            content = """{"name":"$name"}"""
        }.andExpect { status { isCreated() } }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    private fun createDifficulty(slug: String, categoryId: UUID, name: String, level: Int): UUID {
        val result = mockMvc.post("/api/t/$slug/admin/categories/$categoryId/difficulties") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level}"""
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
        createDifficulty(tenantA.slug, categoryId, "SAA", 2)
        createDifficulty(tenantA.slug, categoryId, "DVA", 2)
        createDifficulty(tenantA.slug, categoryId, "SOA", 2)

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
        createDifficulty(tenantA.slug, categoryId, "SAA", 2)

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
    private fun reorder(slug: String, categoryId: UUID, ids: List<UUID>): ResultActionsDsl =
        mockMvc.put("/api/t/$slug/admin/categories/$categoryId/difficulties/order") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("ids" to ids))
        }

    private fun listedNames(slug: String, categoryId: UUID): List<String> =
        mockMvc.get("/api/t/$slug/admin/categories/$categoryId/difficulties") { auth() }.andReturn()
            .response.contentAsString.let { objectMapper.readValue(it, Array<DifficultyResponse>::class.java) }
            .map { it.name }

    @Test
    @DisplayName("同じレベルの中は、並べ替えた順で返る。レベルの順は変わらない")
    fun reordersWithinLevel() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        val clf = createDifficulty(tenantA.slug, categoryId, "CLF", 1)
        val saa = createDifficulty(tenantA.slug, categoryId, "SAA", 2)
        val dva = createDifficulty(tenantA.slug, categoryId, "DVA", 2)
        val soa = createDifficulty(tenantA.slug, categoryId, "SOA", 2)

        reorder(tenantA.slug, categoryId, listOf(soa, clf, saa, dva)).andExpect { status { isNoContent() } }

        assertThat(listedNames(tenantA.slug, categoryId)).containsExactly("CLF", "SOA", "SAA", "DVA")
    }

    @Test
    @DisplayName("レベルを変えた難易度は、新しいレベルの末尾に並ぶ。名前だけを直しても並びは変わらない")
    fun levelChangeGoesLast() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        val clf = createDifficulty(tenantA.slug, categoryId, "CLF", 1)
        val saa = createDifficulty(tenantA.slug, categoryId, "SAA", 2)
        val dva = createDifficulty(tenantA.slug, categoryId, "DVA", 2)
        reorder(tenantA.slug, categoryId, listOf(saa, clf, dva)).andExpect { status { isNoContent() } }

        update(categoryId, clf, "CLF", 2)
        update(categoryId, saa, "SAA（直した）", 2)

        assertThat(listedNames(tenantA.slug, categoryId)).containsExactly("SAA（直した）", "DVA", "CLF")
    }

    @Test
    @DisplayName("並べ替えは、そのカテゴリの今ある難易度をちょうど 1 回ずつ含まなければ 409。別のカテゴリの難易度も含められない")
    fun rejectsOutdatedOrder() {
        val categoryId = createCategory(tenantA.slug, "AWS")
        val clf = createDifficulty(tenantA.slug, categoryId, "CLF", 1)
        val saa = createDifficulty(tenantA.slug, categoryId, "SAA", 2)
        val other = createDifficulty(tenantA.slug, createCategory(tenantA.slug, "別"), "初級", 1)

        listOf(listOf(saa), listOf(saa, clf, other), listOf(saa, other)).forEach { ids ->
            reorder(tenantA.slug, categoryId, ids).andExpect {
                status { isConflict() }
                jsonPath("$.detail") { value("並べ替えている間に項目が変わりました。最新の一覧を取り直してから、並べ替えてください") }
            }
        }
        assertThat(listedNames(tenantA.slug, categoryId)).containsExactly("CLF", "SAA")
    }

    private fun update(categoryId: UUID, id: UUID, name: String, level: Int) {
        mockMvc.put("/api/t/${tenantA.slug}/admin/categories/$categoryId/difficulties/$id") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level}"""
        }.andExpect { status { isOk() } }
    }

    private fun MockHttpServletRequestDsl.auth(user: UUID = TestAuth.ADMIN) {
        header("Authorization", TestAuth.bearer(user))
    }
}
