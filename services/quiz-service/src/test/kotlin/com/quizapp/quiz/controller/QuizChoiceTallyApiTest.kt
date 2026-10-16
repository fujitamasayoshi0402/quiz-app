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
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 選択肢ごとに選ばれた数と、保存しても選択肢の ID を保つこと（DEV-170）。
 *
 * 回答は選んだ選択肢の ID で残る。保存のたびに ID を作り直すと、数が 0 に戻る。
 * - 保存の要求に ID を書けば引き継ぐ。書かなければ新しい ID。ほかのクイズの ID は引き継がない
 * - 数はテナントの全員の回答から。消した選択肢への回答は、別に数える
 * - **別のテナントの回答を数えない。** 集計の SQL はテナントで絞らず、行レベルセキュリティに任せている
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizChoiceTallyApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("保存の要求に書いた ID は引き継ぎ、書かなかった選択肢は新しい ID になる")
    fun keepsChoiceIdsOnSave() {
        val play = Play(TestTenant.create().withAdmin())
        val quiz = play.quiz()
        val before = play.detail(quiz)["choices"].toList()

        // 1 つ目の文言を直し、4 つ目は ID を書かずに送る
        play.save(
            quiz,
            before.mapIndexed { index, choice ->
                Triple(
                    choice["id"].asString().takeIf { index < 3 },
                    if (index == 0) "選択肢 1（直した）" else choice["body"].asString(),
                    index == 0,
                )
            },
        )

        val after = play.detail(quiz)["choices"].toList().map { it["id"].asString() }
        val ids = before.map { it["id"].asString() }
        assertThat(after.take(3)).isEqualTo(ids.take(3))
        assertThat(after[3]).isNotIn(ids)
    }

    @Test
    @DisplayName("ほかのクイズの選択肢の ID は引き継がない。同じ ID が 2 度あれば、2 つ目は新しい ID")
    fun ignoresForeignAndDuplicatedIds() {
        val play = Play(TestTenant.create().withAdmin())
        val quiz = play.quiz()
        val other = play.quiz("ほかの問題")
        val own = play.detail(quiz)["choices"].toList().map { it["id"].asString() }
        val foreign = play.detail(other)["choices"].toList().first()["id"].asString()

        play.save(
            quiz,
            listOf(
                Triple(foreign, "ほかのクイズの ID", true),
                Triple(own[1], "選択肢 2", false),
                Triple(own[1], "同じ ID をもう一度", false),
                Triple(own[3], "選択肢 4", false),
            ),
        )

        val after = play.detail(quiz)["choices"].toList().map { it["id"].asString() }
        assertThat(after[0]).isNotIn(own + foreign)
        assertThat(after[1]).isEqualTo(own[1])
        assertThat(after[2]).isNotIn(own + foreign)
        assertThat(after[3]).isEqualTo(own[3])
        // ほかのクイズの選択肢は、そのまま残る
        assertThat(play.detail(other)["choices"].toList().first()["id"].asString()).isEqualTo(foreign)
    }

    @Test
    @DisplayName("選択肢ごとに、全員が選んだ数を返す。消した選択肢への回答は別に数え、保存しても数は残る")
    fun countsAnswersPerChoice() {
        val play = Play(TestTenant.create().withAdmin())
        val quiz = play.quiz()
        play.answer(play.member(), "選択肢 1")
        play.answer(play.member(), "選択肢 2")
        play.answer(play.member(), "選択肢 2")
        play.answer(play.member(), "選択肢 4")

        // 4 つ目を新しい選択肢に替え、ほかは ID を引き継いで保存する
        val choices = play.detail(quiz)["choices"].toList()
        play.save(
            quiz,
            choices.mapIndexed { index, choice ->
                if (index == 3) {
                    Triple(null, "新しい選択肢", false)
                } else {
                    Triple(choice["id"].asString(), choice["body"].asString(), index == 0)
                }
            },
        )

        val detail = play.detail(quiz)
        assertThat(detail["choices"].toList().map { it["body"].asString() to it["answerCount"].asInt() })
            .containsExactly("選択肢 1" to 1, "選択肢 2" to 2, "選択肢 3" to 0, "新しい選択肢" to 0)
        assertThat(detail["removedChoiceAnswerCount"].asInt()).isEqualTo(1)
    }

    @Test
    @DisplayName("別のテナントの回答は、同じ選択肢を指していても数えない")
    fun ignoresOtherTenants() {
        val play = Play(TestTenant.create().withAdmin())
        val quiz = play.quiz()
        play.answer(play.member(), "選択肢 1")
        val choice = play.detail(quiz)["choices"].toList().first()["id"].asString()

        val other = Play(TestTenant.create().withAdmin())
        other.quiz()
        other.answer(other.member(), "選択肢 2")
        // API からは別のテナントのクイズに答えられない。DB で回答の行を書き換えて、同じ選択肢を指させる
        TestPostgres.adminJdbcTemplate.update(
            "UPDATE answer.answers SET quiz_id = ?, choice_id = ? WHERE tenant_id = ?",
            quiz,
            UUID.fromString(choice),
            other.tenant.id,
        )

        val detail = play.detail(quiz)
        assertThat(detail["choices"].toList().map { it["answerCount"].asInt() }).containsExactly(1, 0, 0, 0)
        assertThat(detail["removedChoiceAnswerCount"].asInt()).isZero()
    }

    private inner class Play(val tenant: TestTenant) {
        private val fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        private val category = fixture.category("AWS")
        private val difficulty = fixture.difficulty(category, "SAA", 2)

        fun quiz(question: String = "問題"): UUID = fixture.quiz(category, difficulty, question)

        fun member(): UUID = TestAuth.createUser().also { tenant.join(it) }

        /** 1 問だけ出題し、本文が [body] の選択肢を選んで終える */
        fun answer(userId: UUID, body: String) {
            val attempt = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"scope":"all","order":"registered","limit":1}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
            val attemptId = attempt["id"].asString()
            val quiz = attempt["quizzes"][0]
            val choice = quiz["choices"].first { it["body"].asString() == body }

            mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${choice["id"].asString()}"}"""
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/complete") {
                header("Authorization", TestAuth.bearer(userId))
            }.andExpect { status { isOk() } }
        }

        /** 選択肢を（ID、本文、正解か）で並べて、公開のまま保存する */
        fun save(quizId: UUID, choices: List<Triple<String?, String, Boolean>>) {
            val body = mapOf(
                "categoryId" to category,
                "difficultyId" to difficulty,
                "question" to "問題",
                "explanation" to "解説",
                "status" to "published",
                "choices" to
                    choices.map { (id, text, correct) -> mapOf("id" to id, "body" to text, "isCorrect" to correct) },
            )
            mockMvc.put("/api/t/${tenant.slug}/admin/quizzes/$quizId") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
                header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            }.andExpect { status { isOk() } }
        }

        fun detail(quizId: UUID): JsonNode = mockMvc.get("/api/t/${tenant.slug}/admin/quizzes/$quizId") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
    }
}
