package com.quizapp.answer.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import org.assertj.core.api.Assertions.assertThat
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

    private val lima: UUID = UUID.fromString("f6f6f6f6-6666-6666-6666-666666666661")
    private val mike: UUID = UUID.fromString("f6f6f6f6-6666-6666-6666-666666666662")
    private val player: UUID = UUID.fromString("f6f6f6f6-6666-6666-6666-666666666663")

    @BeforeEach
    fun setUp() {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenants (id, slug, name) VALUES (?, 'lima', 'リマ'), (?, 'mike', 'マイク')",
            lima,
            mike,
        )
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.users (id, external_id, display_name) VALUES (?, 'stub-two-tenants', '掛け持ち')",
            player,
        )
        TestAuth.ensureUsers()
        TestAuth.joinAsAdmin(lima, mike)
        TestAuth.join(lima, player, "member")
        TestAuth.join(mike, player, "member")

        listOf("lima", "mike").forEach { slug ->
            val fixture = PlayFixture(mockMvc, objectMapper, slug)
            val category = fixture.category("AWS")
            fixture.quiz(category, fixture.difficulty(category, "SAA", 2), "$slug の問題")
        }
    }

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        admin.update("DELETE FROM answer.attempts WHERE user_id = ?", player)
        listOf("choices", "quizzes", "difficulties", "categories").forEach { table ->
            admin.update("DELETE FROM quiz.$table WHERE tenant_id IN (?, ?)", lima, mike)
        }
        TestAuth.leaveAll(lima, mike)
        admin.update("DELETE FROM core.tenants WHERE id IN (?, ?)", lima, mike)
        admin.update("DELETE FROM core.users WHERE id = ?", player)
    }

    @Test
    @DisplayName("別テナントで中断中の挑戦は、中断中の挑戦として返らない")
    fun currentDoesNotReturnAnotherTenantsAttempt() {
        start("mike").andExpect { status { isCreated() } }

        mockMvc.get("/api/t/lima/play/attempts/current") {
            header("X-User-Id", player.toString())
        }.andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("別テナントの挑戦は、本人であっても参照・回答・終了できない")
    fun cannotOperateAnotherTenantsAttempt() {
        val attempt = startAttempt("mike")
        val id = attempt["id"].asString()
        val quiz = attempt["quizzes"][0]
        val body = """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""

        mockMvc.get("/api/t/lima/play/attempts/$id") {
            header("X-User-Id", player.toString())
        }.andExpect { status { isNotFound() } }
        post("lima", "/$id/answers", body).andExpect { status { isNotFound() } }
        post("lima", "/$id/complete").andExpect { status { isNotFound() } }
        post("lima", "/$id/abandon").andExpect { status { isNotFound() } }

        // 404 を返しつつ裏で書き換えていないこと
        assertThat(statusOf(id)).isEqualTo("in_progress")
        assertThat(answerCount(id)).isZero()
    }

    @Test
    @DisplayName("中断中の挑戦は、テナントごとに 1 件ずつ持てる")
    fun inProgressAttemptIsPerTenant() {
        start("mike").andExpect { status { isCreated() } }
        start("lima").andExpect { status { isCreated() } }
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
