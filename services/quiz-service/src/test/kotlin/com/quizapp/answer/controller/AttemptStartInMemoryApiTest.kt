package com.quizapp.answer.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.SqlStatementCounter
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
 * 「始める」をメモリから返す（ADR-0038 の 2、DEV-240）。**DB が止まっていても、最初の問題がすぐ出て、1 問目に答えられる。**
 *
 * DB を止める代わりに、「始める」の要求が SQL を 1 本も送らないことを確かめる（[com.quizapp.cache.ReadCacheApiTest] と同じ）。
 * 挑戦の保存は裏のスレッドで行うため、数えない。回答は、その保存が終わるのを待ってから DB に書く。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttemptStartInMemoryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            TestPostgres.configure(registry)
            registry.add("app.read-cache.enabled") { "true" }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var user: UUID
    private lateinit var category: UUID

    @BeforeEach
    fun setUp() {
        user = TestAuth.createUser("見に来た人")
        tenant = TestTenant.create().withAdmin().join(user)
        val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("まずは5問")
        val difficulty = fixture.difficulty(category, "やさしい", 1)
        (1..3).forEach { fixture.quiz(category, difficulty, "問題 $it") }
    }

    @Test
    @DisplayName("出題の画面で中断中の挑戦を読んだあとは、「始める」が SQL を送らずに返り、そのまま 1 問目に答えられる")
    fun startsWithoutDatabase() {
        current().andExpect { status { isNoContent() } }
        start() // 候補をメモリに読み込む
            .andExpect { status { isCreated() } }
            .let { abandon(id(it)) }

        lateinit var started: JsonNode
        val statements = SqlStatementCounter.count {
            started = json(start().andExpect { status { isCreated() } })
        }

        assertThat(statements).isZero()
        assertThat(
            started["quizzes"].toList().map {
                it["question"].asString()
            },
        ).isEqualTo(listOf("問題 1", "問題 2", "問題 3"))
        answerFirst(started).andExpect {
            status { isOk() }
            jsonPath("$.isCorrect") { value(true) }
            jsonPath("$.answeredCount") { value(1) }
        }
        assertThat(attemptStatus(UUID.fromString(started["id"].asString()))).isEqualTo("in_progress")
    }

    @Test
    @DisplayName("中断中の挑戦があれば、SQL を送らずに 409 を返す。答えた数も添える")
    fun refusesWhileInProgressWithoutDatabase() {
        current()
        val first = json(start().andExpect { status { isCreated() } })
        answerFirst(first).andExpect { status { isOk() } }

        val statements = SqlStatementCounter.count {
            start().andExpect {
                status { isConflict() }
                jsonPath("$.attemptId") { value(first["id"].asString()) }
                jsonPath("$.answeredCount") { value(1) }
            }
        }

        assertThat(statements).isZero()
    }

    @Test
    @DisplayName("破棄して始めると、前の挑戦は破棄され、新しい挑戦が中断中になる")
    fun discardsPreviousInBackground() {
        current()
        val first = json(start().andExpect { status { isCreated() } })

        val second = json(start(discard = true).andExpect { status { isCreated() } })
        answerFirst(second).andExpect { status { isOk() } }

        assertThat(attemptStatus(UUID.fromString(first["id"].asString()))).isEqualTo("abandoned")
        current().andExpect {
            status { isOk() }
            jsonPath("$.id") { value(second["id"].asString()) }
        }
    }

    @Test
    @DisplayName("終えた挑戦はメモリからも外れ、次の「始める」は 409 にならない")
    fun startsAgainAfterComplete() {
        current()
        val first = json(start().andExpect { status { isCreated() } })
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${first["id"].asString()}/complete") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { isOk() } }

        start().andExpect { status { isCreated() } }
    }

    @Test
    @DisplayName("起動の直後（中断中の挑戦をまだ知らない）は、今どおり DB で確かめて始める")
    fun startsInDatabaseWhenUnknown() {
        val statements = SqlStatementCounter.count { start().andExpect { status { isCreated() } } }

        assertThat(statements).isPositive()
        // DB で始めた挑戦も、メモリに持つ。次は DB を読まずに 409 を返す
        assertThat(SqlStatementCounter.count { start().andExpect { status { isConflict() } } }).isZero()
    }

    @Test
    @DisplayName("選ぶのに回答が要る出題対象（未回答優先）は、今どおり DB で始める")
    fun startsInDatabaseForUnanswered() {
        current()

        val statements = SqlStatementCounter.count {
            start(scope = "unanswered").andExpect { status { isCreated() } }
        }

        assertThat(statements).isPositive()
    }

    private fun start(discard: Boolean = false, scope: String = "all"): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$category","scope":"$scope","order":"registered","limit":5,
                 "discardInProgress":$discard}
            """.trimIndent()
            header("Authorization", TestAuth.bearer(user))
        }

    private fun current(): ResultActionsDsl = mockMvc.get("/api/t/${tenant.slug}/play/attempts/current") {
        header("Authorization", TestAuth.bearer(user))
    }

    private fun abandon(attemptId: UUID) {
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/abandon") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect { status { is2xxSuccessful() } }
    }

    /** 先頭のクイズに、正解（「選択肢 1」）で答える */
    private fun answerFirst(attempt: JsonNode): ResultActionsDsl {
        val quiz = attempt["quizzes"][0]
        val correct = quiz["choices"].first { it["body"].asString() == "選択肢 1" }["id"].asString()
        return mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${quiz["id"].asString()}","choiceId":"$correct"}"""
            header("Authorization", TestAuth.bearer(user))
        }
    }

    private fun id(result: ResultActionsDsl): UUID = UUID.fromString(json(result)["id"].asString())

    private fun json(result: ResultActionsDsl): JsonNode =
        objectMapper.readTree(result.andReturn().response.contentAsString)

    private fun attemptStatus(attemptId: UUID): String? = TestPostgres.adminJdbcTemplate.queryForObject(
        "SELECT status FROM answer.attempts WHERE id = ?",
        String::class.java,
        attemptId,
    )
}
