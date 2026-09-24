package com.quizapp.answer.controller

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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 挑戦がテナントをまたがないことの検証（DEV-37）。
 *
 * **2 つのテナントに所属する同じ利用者**で確かめる。
 * 本人確認（user_id）だけでは区別できない組み合わせであり、ここがテナント境界だけで守られる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttemptTenantIsolationApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var lima: TestTenant
    private lateinit var mike: TestTenant
    private lateinit var player: UUID

    @BeforeEach
    fun setUp() {
        player = TestAuth.createUser("掛け持ち")
        lima = TestTenant.create("リマ").withAdmin().join(player)
        mike = TestTenant.create("マイク").withAdmin().join(player)

        listOf(lima, mike).forEach { tenant ->
            val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
            val category = fixture.category("AWS")
            fixture.quiz(category, fixture.difficulty(category, "SAA", 2), "${tenant.slug} の問題")
        }
    }

    @Test
    @DisplayName("別テナントで中断中の挑戦は、中断中の挑戦として返らない")
    fun currentDoesNotReturnAnotherTenantsAttempt() {
        start(mike.slug).andExpect { status { isCreated() } }

        mockMvc.get("/api/t/${lima.slug}/play/attempts/current") {
            header("X-User-Id", player.toString())
        }.andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("別テナントの挑戦は、本人であっても参照・回答・終了できない")
    fun cannotOperateAnotherTenantsAttempt() {
        val attempt = startAttempt(mike.slug)
        val id = attempt["id"].asString()
        val quiz = attempt["quizzes"][0]
        val body = """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""

        mockMvc.get("/api/t/${lima.slug}/play/attempts/$id") {
            header("X-User-Id", player.toString())
        }.andExpect { status { isNotFound() } }
        post(lima.slug, "/$id/answers", body).andExpect { status { isNotFound() } }
        post(lima.slug, "/$id/complete").andExpect { status { isNotFound() } }
        post(lima.slug, "/$id/abandon").andExpect { status { isNotFound() } }

        // 404 を返しつつ裏で書き換えていないこと
        assertThat(statusOf(id)).isEqualTo("in_progress")
        assertThat(answerCount(id)).isZero()
    }

    @Test
    @DisplayName("中断中の挑戦は、テナントごとに 1 件ずつ持てる")
    fun inProgressAttemptIsPerTenant() {
        start(mike.slug).andExpect { status { isCreated() } }
        start(lima.slug).andExpect { status { isCreated() } }
    }

    private fun start(slug: String): ResultActionsDsl = post(slug, "", """{"scope":"all","order":"registered"}""")

    private fun startAttempt(slug: String): JsonNode = start(slug)
        .andExpect { status { isCreated() } }
        .andReturn().response.contentAsString
        .let { objectMapper.readTree(it) }

    private fun post(slug: String, path: String, body: String? = null): ResultActionsDsl =
        mockMvc.post("/api/t/$slug/play/attempts$path") {
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            header("X-User-Id", player.toString())
        }

    private fun statusOf(attemptId: String): String? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT status FROM answer.attempts WHERE id = ?::uuid",
        String::class.java,
        attemptId,
    )

    private fun answerCount(attemptId: String): Int = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT count(*) FROM answer.answers WHERE attempt_id = ?::uuid",
        Int::class.java,
        attemptId,
    ) ?: 0
}
