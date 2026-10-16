package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 管理のクイズの一覧に載せる、回答の集計（DEV-158）。
 *
 * SQL が担うことをここで見る。
 * - 利用者をまたいで数え、解き直しもそれぞれ数えること
 * - **別のテナントの回答を数えないこと。** 集計の SQL はテナントで絞らず、行レベルセキュリティに任せている
 *
 * 件数によって SQL が増えないことは [com.quizapp.QueryCountApiTest] が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizAnswerTallyApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("利用者をまたいで、解き直しも含めて数える。回答のないクイズは 0 件")
    fun talliesAnswersOfEveryone() {
        val play = Play(TestTenant.create().withAdmin())
        val (first, second, unanswered) = play.quizzes(3)
        val alice = play.member()
        val bob = play.member()

        play.solve(alice, true, false)
        play.solve(bob, true, true)
        play.solve(alice, true)

        assertThat(play.tallies()).isEqualTo(
            mapOf(first to (3 to 3), second to (2 to 1), unanswered to (0 to 0)),
        )
    }

    @Test
    @DisplayName("別のテナントの回答は、同じクイズを指していても数えない")
    fun ignoresOtherTenants() {
        val play = Play(TestTenant.create().withAdmin())
        val quiz = play.quizzes(1).single()
        play.solve(play.member(), true)

        val other = Play(TestTenant.create().withAdmin())
        other.quizzes(1)
        other.solve(other.member(), false)
        // API からは別のテナントのクイズに答えられない。DB で回答の行を書き換えて、同じクイズを指させる
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE answer.answers SET quiz_id = ? WHERE tenant_id = ?",
            quiz,
            other.tenant.id,
        )

        assertThat(play.tallies()).isEqualTo(mapOf(quiz to (1 to 1)))
    }

    private inner class Play(val tenant: TestTenant) {
        private val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        private val category = fixture.category("AWS")
        private val difficulty = fixture.difficulty(category, "SAA", 2)

        fun quizzes(count: Int): List<UUID> = (1..count).map { fixture.quiz(category, difficulty, "問題 $it") }

        fun member(): UUID = TestAuth.createUser().also { tenant.join(it) }

        /** 登録順に出題し、先頭から [results] の数だけ答えて終える。true なら正解を選ぶ */
        fun solve(userId: UUID, vararg results: Boolean) {
            val attempt = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"scope":"all","order":"registered"}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
            val attemptId = attempt["id"].asString()

            results.forEachIndexed { index, correct ->
                val quiz = attempt["quizzes"][index]
                // [PlayFixture] は「選択肢 1」を正解にする。選択肢は並びが変わるため、本文で選ぶ
                val choice = quiz["choices"].first { (it["body"].asString() == "選択肢 1") == correct }
                mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${choice["id"].asString()}"}"""
                    header("Authorization", TestAuth.bearer(userId))
                }.andExpect { status { isOk() } }
            }
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/complete") {
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
        }

        /** クイズの ID ごとに、回答の数と正解の数 */
        fun tallies(): Map<UUID, Pair<Int, Int>> = mockMvc.get("/api/t/${tenant.slug}/admin/quizzes") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            .let(objectMapper::readTree)
            .toList()
            .associate {
                UUID.fromString(it["id"].asString()) to
                    (it["answerCount"].asInt() to it["correctCount"].asInt())
            }
    }
}
