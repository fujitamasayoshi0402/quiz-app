package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import org.junit.jupiter.api.AfterEach
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

    private val tenantA: UUID = UUID.fromString("cccc1111-1111-1111-1111-111111111111")
    private val tenantB: UUID = UUID.fromString("dddd2222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenants (id, slug, name) VALUES (?, 'charlie', 'チャーリー'), (?, 'delta', 'デルタ')",
            tenantA,
            tenantB,
        )
    }

    @AfterEach
    fun tearDown() {
        TestPostgres.adminJdbcTemplate.update(
            "DELETE FROM quiz.difficulties WHERE tenant_id IN (?, ?)",
            tenantA,
            tenantB,
        )
        TestPostgres.adminJdbcTemplate.update("DELETE FROM quiz.categories WHERE tenant_id IN (?, ?)", tenantA, tenantB)
        TestPostgres.adminJdbcTemplate.update("DELETE FROM core.tenants WHERE id IN (?, ?)", tenantA, tenantB)
    }

    private fun createCategory(slug: String, name: String): UUID {
        val result = mockMvc.post("/api/t/$slug/categories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","sortOrder":0}"""
        }.andExpect { status { isCreated() } }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    private fun createDifficulty(slug: String, categoryId: UUID, name: String, level: Int, sortOrder: Int = 0): UUID {
        val result = mockMvc.post("/api/t/$slug/categories/$categoryId/difficulties") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level,"sortOrder":$sortOrder}"""
        }.andExpect { status { isCreated() } }.andReturn()

        return objectMapper.readValue(result.response.contentAsString, DifficultyResponse::class.java).id
    }

    @Test
    @DisplayName("カテゴリに難易度を追加できる")
    fun createAndList() {
        val categoryId = createCategory("charlie", "AWS")
        createDifficulty("charlie", categoryId, "CLF", 1)

        mockMvc.get("/api/t/charlie/categories/$categoryId/difficulties").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("CLF") }
            jsonPath("$[0].level") { value(1) }
        }
    }

    @Test
    @DisplayName("同じレベルの難易度を複数登録できる")
    fun multipleDifficultiesCanShareLevel() {
        val categoryId = createCategory("charlie", "AWS")
        createDifficulty("charlie", categoryId, "SAA", 2, sortOrder = 1)
        createDifficulty("charlie", categoryId, "DVA", 2, sortOrder = 2)
        createDifficulty("charlie", categoryId, "SOA", 2, sortOrder = 3)

        mockMvc.get("/api/t/charlie/categories/$categoryId/difficulties").andExpect {
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
        val categoryId = createCategory("charlie", "AWS")
        createDifficulty("charlie", categoryId, "SAP", 3)
        createDifficulty("charlie", categoryId, "CLF", 1)
        createDifficulty("charlie", categoryId, "SAA", 2, sortOrder = 1)

        mockMvc.get("/api/t/charlie/categories/$categoryId/difficulties").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("CLF") }
            jsonPath("$[1].name") { value("SAA") }
            jsonPath("$[2].name") { value("SAP") }
        }
    }

    @Test
    @DisplayName("カテゴリごとに異なる難易度体系を持てる")
    fun eachCategoryHasItsOwnScale() {
        val aws = createCategory("charlie", "AWS")
        val auth = createCategory("charlie", "認証認可")
        createDifficulty("charlie", aws, "CLF", 1)
        createDifficulty("charlie", auth, "初級", 1)

        mockMvc.get("/api/t/charlie/categories/$aws/difficulties").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("CLF") }
        }
        mockMvc.get("/api/t/charlie/categories/$auth/difficulties").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("初級") }
        }
    }

    @Test
    @DisplayName("別カテゴリの難易度は、URL のカテゴリを変えても取得できない")
    fun cannotAccessDifficultyThroughWrongCategory() {
        val aws = createCategory("charlie", "AWS")
        val auth = createCategory("charlie", "認証認可")
        val clf = createDifficulty("charlie", aws, "CLF", 1)

        // URL 上は認証認可カテゴリの配下として AWS の難易度を指定する
        mockMvc.get("/api/t/charlie/categories/$auth/difficulties/$clf").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    @DisplayName("他テナントのカテゴリを指定した一覧取得は 404 を返す")
    fun cannotListDifficultiesOfAnotherTenant() {
        val categoryOfB = createCategory("delta", "デルタのカテゴリ")

        // 空リストではなく 404 を返す。
        // 空リストだと「カテゴリは存在するが難易度が無い」という誤った情報を与えてしまう
        mockMvc.get("/api/t/charlie/categories/$categoryOfB/difficulties").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    @DisplayName("削除すると一覧から消える")
    fun deleteRemovesFromList() {
        val categoryId = createCategory("charlie", "AWS")
        val id = createDifficulty("charlie", categoryId, "CLF", 1)

        mockMvc.delete("/api/t/charlie/categories/$categoryId/difficulties/$id")
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/t/charlie/categories/$categoryId/difficulties").andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("レベルが 0 以下なら 400 を返す")
    fun levelMustBePositive() {
        val categoryId = createCategory("charlie", "AWS")

        mockMvc.post("/api/t/charlie/categories/$categoryId/difficulties") {
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

        mockMvc.get("/api/t/charlie/categories/$unknown/difficulties").andExpect {
            status { isNotFound() }
        }
    }
}
