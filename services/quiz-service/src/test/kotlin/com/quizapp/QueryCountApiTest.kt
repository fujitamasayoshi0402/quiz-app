package com.quizapp

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.SqlStatementCounter
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * クイズを何件も読む API が、**クイズの数によらず同じ本数の SQL で済むこと**（DEV-112）。
 *
 * 選択肢をクイズ 1 件ごとに問い合わせていたとき（N+1）、負荷試験で Aurora の CPU を使い切った。
 * テストのデータは少ないため、速さには表れない。本数を数えて、2 問のときと 12 問のときで同じであることを確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QueryCountApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val FEW = 2
        private const val MANY = 12
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("管理のクイズの一覧")
    fun adminQuizList() {
        assertThat(countFor(MANY) { it.adminList() }).isEqualTo(countFor(FEW) { it.adminList() })
    }

    @Test
    @DisplayName("挑戦を始める（出題の候補を読む）")
    fun startAttempt() {
        assertThat(countFor(MANY) { it.start() }).isEqualTo(countFor(FEW) { it.start() })
    }

    @Test
    @DisplayName("挑戦を終えて結果を取る（出題したクイズを読む）")
    fun completeAttempt() {
        assertThat(countFor(MANY) { it.complete() }).isEqualTo(countFor(FEW) { it.complete() })
    }

    @Test
    @DisplayName("学習の記録（回答した日を数える）")
    fun studyDays() {
        assertThat(countFor(MANY) { it.studyDays() }).isEqualTo(countFor(FEW) { it.studyDays() })
    }

    /** [quizzes] 問を入れたテナントを用意し、[operation] で送った SQL の本数を返す */
    private fun countFor(quizzes: Int, operation: (Scenario) -> Int): Int = operation(Scenario(quizzes))

    private inner class Scenario(quizzes: Int) {
        private val user = TestAuth.createUser("数える人")
        private val tenant = TestTenant.create().withAdmin().join(user)
        private val category: UUID

        init {
            val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
            category = fixture.category("カテゴリ")
            val difficulty = fixture.difficulty(category, "難易度", 1)
            repeat(quizzes) { fixture.quiz(category, difficulty, "問題 $it") }
        }

        fun adminList(): Int = SqlStatementCounter.count {
            mockMvc.get("/api/t/${tenant.slug}/admin/quizzes?categoryId=$category") {
                header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            }.andExpect { status { isOk() } }
        }

        fun start(): Int = SqlStatementCounter.count { startAttempt() }

        /** 出題したクイズにすべて答え、回答の数をクイズの数にそろえてから数える */
        fun studyDays(): Int {
            val attempt = startAttempt()
            attempt["quizzes"].toList().forEach { quiz ->
                mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/answers") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"quizId":"${quiz["id"].asString()}","choiceId":"${quiz["choices"][0]["id"].asString()}"}"""
                    header("Authorization", TestAuth.bearer(user))
                }.andExpect { status { isOk() } }
            }
            return SqlStatementCounter.count {
                mockMvc.get("/api/t/${tenant.slug}/play/history/days") {
                    header("Authorization", TestAuth.bearer(user))
                }.andExpect { status { isOk() } }
            }
        }

        fun complete(): Int {
            val attempt = startAttempt()
            return SqlStatementCounter.count {
                mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt["id"].asString()}/complete") {
                    header("Authorization", TestAuth.bearer(user))
                }.andExpect { status { isOk() } }
            }
        }

        private fun startAttempt(): JsonNode = objectMapper.readTree(
            mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"categoryId":"$category","discardInProgress":true}"""
                header("Authorization", TestAuth.bearer(user))
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
        )
    }
}
