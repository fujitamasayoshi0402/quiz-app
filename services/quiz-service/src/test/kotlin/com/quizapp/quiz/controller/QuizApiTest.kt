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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * クイズ API の統合テスト。
 *
 * 重点は 2 つ。
 * - **公開時の不変条件**（選択肢 4 つ・正解 1 つ）が守られること
 * - **カテゴリと難易度の組み合わせ**が矛盾しないこと
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenantA: TestTenant
    private lateinit var tenantB: TestTenant

    private lateinit var awsCategory: UUID
    private lateinit var authCategory: UUID
    private lateinit var saaDifficulty: UUID
    private lateinit var authBeginner: UUID

    @BeforeEach
    fun setUp() {
        tenantA = TestTenant.create("エコー").withAdmin()
        tenantB = TestTenant.create("フォックストロット").withAdmin()
        awsCategory = createCategory(tenantA.slug, "AWS")
        authCategory = createCategory(tenantA.slug, "認証認可")
        saaDifficulty = createDifficulty(tenantA.slug, awsCategory, "SAA", 2)
        authBeginner = createDifficulty(tenantA.slug, authCategory, "初級", 1)
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

    private fun quizJson(
        categoryId: UUID = awsCategory,
        difficultyId: UUID = saaDifficulty,
        question: String = "VPC エンドポイントを使う目的は？",
        explanation: String = "NAT Gateway を使わずに AWS サービスへ到達するため。",
        choiceCount: Int = 4,
        correctCount: Int = 1,
        status: String = "draft",
    ): String {
        val choices = (1..choiceCount).map { i ->
            """{"body":"選択肢 $i","isCorrect":${i <= correctCount}}"""
        }
        return """
            {"categoryId":"$categoryId","difficultyId":"$difficultyId",
             "question":"$question","explanation":"$explanation",
             "choices":[${choices.joinToString(",")}],"status":"$status"}
        """.trimIndent()
    }

    private fun createQuiz(body: String): UUID {
        val result = mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, QuizResponse::class.java).id
    }

    @Test
    @DisplayName("選択肢 4 つ・正解 1 つのクイズを公開できる")
    fun createPublishedQuiz() {
        val id = createQuiz(quizJson(status = "published"))

        mockMvc.get("/api/t/${tenantA.slug}/admin/quizzes/$id") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("published") }
            jsonPath("$.choices.length()") { value(4) }
            jsonPath("$.choices[0].isCorrect") { value(true) }
        }
    }

    @Test
    @DisplayName("選択肢の順序が保存される")
    fun choiceOrderIsPreserved() {
        val id = createQuiz(quizJson(status = "published"))

        mockMvc.get("/api/t/${tenantA.slug}/admin/quizzes/$id") { auth() }.andExpect {
            jsonPath("$.choices[0].body") { value("選択肢 1") }
            jsonPath("$.choices[3].body") { value("選択肢 4") }
        }
    }

    @Test
    @DisplayName("下書きなら選択肢が揃っていなくても保存できる")
    fun draftAllowsIncompleteChoices() {
        val id = createQuiz(quizJson(choiceCount = 2, status = "draft"))

        mockMvc.get("/api/t/${tenantA.slug}/admin/quizzes/$id") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("draft") }
            jsonPath("$.choices.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("選択肢が 4 つ未満のまま公開しようとすると 400")
    fun cannotPublishWithoutFourChoices() {
        mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(choiceCount = 3, status = "published")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("正解が無いまま公開しようとすると 400")
    fun cannotPublishWithoutCorrectChoice() {
        mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(correctCount = 0, status = "published")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("正解が 2 つあると 400")
    fun cannotHaveTwoCorrectChoices() {
        mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(correctCount = 2, status = "published")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("カテゴリに属さない難易度を指定すると 400")
    fun rejectsDifficultyFromAnotherCategory() {
        // AWS カテゴリに、認証認可カテゴリの難易度を組み合わせる
        mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(categoryId = awsCategory, difficultyId = authBeginner)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("下書きから公開へ更新できる")
    fun canPublishDraft() {
        val id = createQuiz(quizJson(status = "draft"))

        mockMvc.put("/api/t/${tenantA.slug}/admin/quizzes/$id") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(status = "published")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("published") }
        }
    }

    @Test
    @DisplayName("更新で選択肢を差し替えられる")
    fun canReplaceChoices() {
        val id = createQuiz(quizJson(status = "draft"))

        mockMvc.put("/api/t/${tenantA.slug}/admin/quizzes/$id") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$awsCategory","difficultyId":"$saaDifficulty",
                 "question":"差し替え後","explanation":"説明",
                 "choices":[{"body":"新しい選択肢","isCorrect":true}],"status":"draft"}
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.choices.length()") { value(1) }
            jsonPath("$.choices[0].body") { value("新しい選択肢") }
        }
    }

    @Test
    @DisplayName("カテゴリと状態で絞り込める")
    fun canFilterByCategoryAndStatus() {
        createQuiz(quizJson(status = "published"))
        createQuiz(quizJson(status = "draft"))

        mockMvc.get("/api/t/${tenantA.slug}/admin/quizzes?status=published") { auth() }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].status") { value("published") }
        }
        mockMvc.get("/api/t/${tenantA.slug}/admin/quizzes?categoryId=$authCategory") { auth() }.andExpect {
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("他テナントからはクイズが見えない")
    fun quizzesAreIsolatedPerTenant() {
        createQuiz(quizJson(status = "published"))

        mockMvc.get("/api/t/${tenantB.slug}/admin/quizzes") { auth() }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("存在しないカテゴリを指定すると 404")
    fun unknownCategoryReturnsNotFound() {
        mockMvc.post("/api/t/${tenantA.slug}/admin/quizzes") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = quizJson(categoryId = UUID.randomUUID())
        }.andExpect { status { isNotFound() } }
    }

    /** テナント配下のエンドポイントは所属していないと触れない。既定は共有管理者。 */
    private fun MockHttpServletRequestDsl.auth(user: UUID = TestAuth.ADMIN) {
        header("Authorization", TestAuth.bearer(user))
    }
}
