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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * AI が技術ニュースから作った下書きを、管理者が見つけて確かめ、公開して解かれるまで（ADR-0031、DEV-162）。
 *
 * 受け箱からの取り込みは [com.quizapp.quiz.infrastructure.newsquiz.NewsQuizImportApiTest] が見る。
 * ここでは、取り込んだあとの形（出典の URL を持つ下書き）を SQL で作る
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDraftApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SOURCE = "https://aws.amazon.com/about-aws/whats-new/2026/10/example/"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var member: UUID
    private lateinit var category: UUID
    private lateinit var difficulty: UUID
    private lateinit var fixture: PlayFixture

    @BeforeEach
    fun setUp() {
        member = TestAuth.createUser("解く人")
        tenant = TestTenant.create().withAdmin().join(member)
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS の最新情報")
        difficulty = fixture.difficulty(category, "ニュース", 1)
    }

    /** 取り込んだ下書きと同じ形にする。出典の URL は、取り込みのときにだけ入る */
    private fun aiDraft(question: String): UUID = fixture.quiz(category, difficulty, question, status = "draft").also {
        TestPostgres.adminJdbcTemplate.update("UPDATE quiz.quizzes SET source_url = ? WHERE id = ?", SOURCE, it)
    }

    @Test
    @DisplayName("一覧は出典の URL を返し、AI が作ったものと人が作ったもので絞り込める")
    fun searchesAiDrafts() {
        aiDraft("AI の問題")
        fixture.quiz(category, difficulty, "人の問題", status = "draft")

        search("").andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[?(@.question == 'AI の問題')].sourceUrl") { value(SOURCE) }
        }
        search("?aiGenerated=true").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("AI の問題") }
        }
        search("?aiGenerated=false&status=draft").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("人の問題") }
            jsonPath("$[0].sourceUrl") { doesNotExist() }
        }
    }

    @Test
    @DisplayName("直して公開しても、出典の URL は残る。解く人には、採点の結果と答え合わせで出典の URL が返る")
    fun keepsSourceThroughReviewAndShowsItToPlayers() {
        val id = aiDraft("AI の問題")
        val choices = (1..4).joinToString(",") { """{"body":"直した選択肢 $it","isCorrect":${it == 1}}""" }

        mockMvc.put("/api/t/${tenant.slug}/admin/quizzes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$category","difficultyId":"$difficulty","question":"直した問題",
                 "explanation":"直した解説","choices":[$choices],"status":"published"}
            """.trimIndent()
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("published") }
            jsonPath("$.sourceUrl") { value(SOURCE) }
        }

        val attempt = json(
            mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"scope":"all","order":"registered"}"""
                header("Authorization", TestAuth.bearer(member))
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
        )
        val attemptId = attempt["id"].asString()
        val quiz = attempt["quizzes"][0]
        // 解き終えるまで、AI が作ったかどうかも見せない。答えの手がかりにはならないが、出す必要もない
        assertThat(quiz.has("sourceUrl")).isFalse()

        mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""
            header("Authorization", TestAuth.bearer(member))
        }.andExpect {
            status { isOk() }
            jsonPath("$.sourceUrl") { value(SOURCE) }
        }

        mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/complete") {
            header("Authorization", TestAuth.bearer(member))
        }.andExpect {
            status { isOk() }
            jsonPath("$.results[0].sourceUrl") { value(SOURCE) }
        }
    }

    @Test
    @DisplayName("人が作ったクイズは、解く人にも出典の URL を返さない")
    fun noSourceForHumanQuizzes() {
        fixture.quiz(category, difficulty, "人の問題")
        val attempt = json(
            mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"scope":"all","order":"registered"}"""
                header("Authorization", TestAuth.bearer(member))
            }.andReturn().response.contentAsString,
        )
        val quiz = attempt["quizzes"][0]

        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""
            header("Authorization", TestAuth.bearer(member))
        }.andExpect {
            status { isOk() }
            jsonPath("$.sourceUrl") { doesNotExist() }
        }
    }

    private fun search(query: String) = mockMvc.get("/api/t/${tenant.slug}/admin/quizzes$query") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }.andExpect { status { isOk() } }

    private fun json(body: String): JsonNode = objectMapper.readTree(body)
}
