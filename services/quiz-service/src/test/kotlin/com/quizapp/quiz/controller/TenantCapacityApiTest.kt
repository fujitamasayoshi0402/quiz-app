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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 利用者が作ったテナントの、クイズと図の数の上限（ADR-0028）。
 *
 * 上限は `core.tenants` の列にある。ここでは小さい値を入れて確かめる。上限のないテナント（列が空）は、ほかの API テストがそのまま見ている
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantCapacityApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect/></svg>"""
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var categoryId: UUID
    private lateinit var difficultyId: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("上限のあるテナント").withAdmin()
        categoryId = postJson("/admin/categories", """{"name":"AWS"}""").id()
        difficultyId = postJson("/admin/categories/$categoryId/difficulties", """{"name":"SAA","level":2}""").id()
    }

    @Test
    @DisplayName("クイズは上限まで作れる。超えると 409 で、何が上限に達したかを返す")
    fun quizzesUpToLimit() {
        limit(quizzes = 2)
        repeat(2) { createQuiz().andExpect { status { isCreated() } } }

        createQuiz().andExpect {
            status { isConflict() }
            jsonPath("$.resource") { value("quizzes") }
            jsonPath("$.limit") { value(2) }
        }
        assertThat(count("quiz.quizzes")).isEqualTo(2)
    }

    @Test
    @DisplayName("ゴミ箱のクイズも数える。消しても、上限は空かない")
    fun deletedQuizzesCount() {
        limit(quizzes = 1)
        val id = createQuiz().andExpect { status { isCreated() } }.id()
        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$id") { auth() }.andExpect { status { isNoContent() } }

        createQuiz().andExpect { status { isConflict() } }
    }

    @Test
    @DisplayName("取り込みで上限を超えるなら、1 件も取り込まない")
    fun importIsAllOrNothing() {
        limit(quizzes = 2)
        createQuiz().andExpect { status { isCreated() } }

        val rows = (1..2).map { importRow("取り込み $it") }
        postJson(
            "/admin/quizzes/import",
            objectMapper.writeValueAsString(mapOf("quizzes" to rows)),
            expectCreated = false,
        )
            .andExpect {
                status { isConflict() }
                jsonPath("$.resource") { value("quizzes") }
            }
        assertThat(count("quiz.quizzes")).isEqualTo(1)
    }

    @Test
    @DisplayName("図は上限まで作れる。超えると、draw.io の図も、画像と PDF のアップロードも 409")
    fun figuresUpToLimit() {
        limit(figures = 1)
        createFigure().andExpect { status { isCreated() } }

        createFigure().andExpect {
            status { isConflict() }
            jsonPath("$.resource") { value("figures") }
            jsonPath("$.limit") { value(1) }
        }
        postJson("/admin/figures/uploads", """{"contentType":"image/png","size":100}""", expectCreated = false)
            .andExpect { status { isConflict() } }
        assertThat(count("quiz.figures")).isEqualTo(1)
    }

    @Test
    @DisplayName("図を消せば、上限が空く")
    fun deletedFiguresDoNotCount() {
        limit(figures = 1)
        val id = createFigure().andExpect { status { isCreated() } }.id()
        mockMvc.delete("/api/t/${tenant.slug}/admin/figures/$id") { auth() }.andExpect { status { isNoContent() } }

        createFigure().andExpect { status { isCreated() } }
    }

    private fun limit(quizzes: Int? = null, figures: Int? = null) {
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE core.tenants SET quiz_limit = ?, figure_limit = ? WHERE id = ?",
            quizzes,
            figures,
            tenant.id,
        )
    }

    private fun createQuiz(): ResultActionsDsl {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        return postJson(
            "/admin/quizzes",
            """
            {"categoryId":"$categoryId","difficultyId":"$difficultyId","question":"問題 ${UUID.randomUUID()}",
             "explanation":"解説","choices":[$choices],"status":"draft"}
            """,
            expectCreated = false,
        )
    }

    private fun createFigure(): ResultActionsDsl = postJson(
        "/admin/figures",
        objectMapper.writeValueAsString(mapOf("source" to "<mxfile/>", "svg" to SVG)),
        expectCreated = false,
    )

    private fun importRow(question: String) = mapOf(
        "category" to "AWS",
        "difficulty" to "SAA",
        "question" to question,
        "explanation" to "解説",
        "choices" to (1..4).map { mapOf("body" to "選択肢 $it", "isCorrect" to (it == 1)) },
    )

    private fun postJson(path: String, body: String, expectCreated: Boolean = true): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}$path") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.also { result -> if (expectCreated) result.andExpect { status { isCreated() } } }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth() {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }

    private fun ResultActionsDsl.id(): UUID =
        UUID.fromString(objectMapper.readTree(andReturn().response.contentAsString)["id"].asString())

    private fun count(table: String): Int = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table WHERE tenant_id = ?",
        Int::class.java,
        tenant.id,
    ) ?: 0
}
