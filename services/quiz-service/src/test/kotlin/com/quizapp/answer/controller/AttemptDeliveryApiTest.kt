package com.quizapp.answer.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 挑戦の開始（＝出題）の統合テスト。
 *
 * 最重要は **応答に正解と解説が含まれないこと**。
 * ここが漏れるとクライアント側で採点できてしまい、アプリの前提が崩れる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttemptDeliveryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val tenant: UUID = UUID.fromString("a1a1a1a1-1111-1111-1111-111111111111")
    private val user: UUID = UUID.fromString("b2b2b2b2-2222-2222-2222-222222222222")

    private lateinit var fixture: PlayFixture
    private lateinit var awsCategory: UUID
    private lateinit var authCategory: UUID
    private lateinit var saa: UUID
    private lateinit var dva: UUID
    private lateinit var authBeginner: UUID

    @BeforeEach
    fun setUp() {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenants (id, slug, name) VALUES (?, 'golf', 'ゴルフ')",
            tenant,
        )
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.users (id, external_id, display_name) VALUES (?, 'stub-user', 'テスト利用者')",
            user,
        )
        fixture = PlayFixture(mockMvc, objectMapper, "golf")
        awsCategory = fixture.category("AWS")
        authCategory = fixture.category("認証認可")
        // レベル 2 に 2 つの難易度を置き、レベル横断の出題を確かめられるようにする
        saa = fixture.difficulty(awsCategory, "SAA", 2)
        dva = fixture.difficulty(awsCategory, "DVA", 2)
        authBeginner = fixture.difficulty(authCategory, "初級", 2)
    }

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        admin.update("DELETE FROM answer.answers WHERE user_id = ?", user)
        admin.update("DELETE FROM answer.attempts WHERE user_id = ?", user)
        admin.update("DELETE FROM quiz.choices WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.quizzes WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.difficulties WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.categories WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM core.tenants WHERE id = ?", tenant)
        admin.update("DELETE FROM core.users WHERE id = ?", user)
    }

    private fun start(body: String): ResultActionsDsl =
        mockMvc.post("/api/t/golf/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("X-User-Id", user.toString())
        }

    /** 中断中は 1 件までなので、続けて始めるテストでは毎回破棄する。 */
    private fun restart(body: String): ResultActionsDsl =
        start(body.dropLast(1) + ""","discardInProgress":true}""")

    @Test
    @DisplayName("応答に正解と解説が含まれない")
    fun deliveredQuizDoesNotExposeCorrectAnswer() {
        fixture.quiz(awsCategory, saa, "問題 1")

        val body = start("""{"scope":"all"}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].choices.length()") { value(4) }
            jsonPath("$.status") { value("in_progress") }
        }.andReturn().response.contentAsString

        // isCorrect というキー自体が存在しないことを確かめる。
        // 値が false でも、キーがあれば「正解でないもの」が分かってしまう
        assert(!body.contains("isCorrect")) { "出題の応答に正解情報が含まれている: $body" }
        assert(!body.contains("explanation")) { "出題の応答に解説が含まれている: $body" }
    }

    @Test
    @DisplayName("下書きのクイズは出題されない")
    fun draftQuizIsNotDelivered() {
        fixture.quiz(awsCategory, saa, "下書きの問題", status = "draft")

        // 出題できるクイズが 1 件もないので、挑戦を始められない
        start("""{"scope":"all"}""").andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    @DisplayName("出題数を省略すると上限まで返る")
    fun omittedLimitReturnsEverything() {
        repeat(3) { fixture.quiz(awsCategory, saa, "問題 $it") }

        // 既定値をサーバーが持たないので、黙って 10 問に切られることがない
        start("""{"scope":"all"}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(3) }
        }
    }

    @Test
    @DisplayName("出題数を指定するとその件数だけ返る")
    fun limitIsRespected() {
        repeat(5) { fixture.quiz(awsCategory, saa, "問題 $it") }

        start("""{"scope":"all","limit":2}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("登録順を指定すると並びが安定する")
    fun registeredOrderIsStable() {
        fixture.quiz(awsCategory, saa, "問題 A")
        fixture.quiz(awsCategory, saa, "問題 B")

        start("""{"scope":"all","order":"registered"}""").andExpect {
            jsonPath("$.quizzes[0].question") { value("問題 A") }
            jsonPath("$.quizzes[1].question") { value("問題 B") }
        }
    }

    @Test
    @DisplayName("難易度を指定するとその難易度だけが対象になる")
    fun canFilterByDifficulty() {
        fixture.quiz(awsCategory, saa, "SAA の問題")
        fixture.quiz(awsCategory, dva, "DVA の問題")

        start("""{"difficultyId":"$saa","scope":"all"}""").andExpect {
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].question") { value("SAA の問題") }
        }
    }

    @Test
    @DisplayName("レベルを指定すると同じレベルの難易度がすべて対象になる")
    fun levelIncludesAllDifficultiesAtThatLevel() {
        fixture.quiz(awsCategory, saa, "SAA の問題")
        fixture.quiz(awsCategory, dva, "DVA の問題")

        // SAA と DVA はどちらもレベル 2。レベル指定は難易度を 1 つに絞らない
        start("""{"level":2,"categoryId":"$awsCategory","scope":"all"}""").andExpect {
            jsonPath("$.quizzes.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("カテゴリを指定しなければレベルでカテゴリ横断の出題になる")
    fun levelWorksAcrossCategories() {
        fixture.quiz(awsCategory, saa, "AWS の問題")
        fixture.quiz(authCategory, authBeginner, "認証認可の問題")

        // 名前は SAA と初級で異なるが、どちらもレベル 2
        start("""{"level":2,"scope":"all"}""").andExpect {
            jsonPath("$.quizzes.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("未回答優先では、まだ解いていないクイズが先に出る")
    fun unansweredComesFirst() {
        val answered = fixture.quiz(awsCategory, saa, "回答済みの問題")
        fixture.quiz(awsCategory, saa, "未回答の問題")
        answerOnce(answered)

        restart("""{"scope":"unanswered","limit":1}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].question") { value("未回答の問題") }
        }
    }

    @Test
    @DisplayName("未回答優先は、未回答が尽きたら回答済みで埋める")
    fun unansweredFallsBackToAnswered() {
        val first = fixture.quiz(awsCategory, saa, "問題 1")
        val second = fixture.quiz(awsCategory, saa, "問題 2")
        answerOnce(first)
        answerOnce(second)

        // 「もう解く問題がありません」ではなく、復習として出す
        restart("""{"scope":"unanswered","limit":2}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("未回答のみは、回答済みで埋めない")
    fun unansweredOnlyDoesNotFallBack() {
        val answered = fixture.quiz(awsCategory, saa, "回答済みの問題")
        fixture.quiz(awsCategory, saa, "未回答の問題")
        answerOnce(answered)

        // 2 問を要求しても、未回答の 1 問しか返らない。
        // 「残り何問あるか」が意味を持つ使い方のため、回答済みを混ぜない
        restart("""{"scope":"unanswered_only","limit":2}""").andExpect {
            status { isCreated() }
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].question") { value("未回答の問題") }
        }
    }

    @Test
    @DisplayName("未回答のみで、未回答が尽きていれば始められない")
    fun unansweredOnlyFailsWhenNothingLeft() {
        val only = fixture.quiz(awsCategory, saa, "唯一の問題")
        answerOnce(only)

        restart("""{"scope":"unanswered_only"}""").andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    @DisplayName("該当するクイズが無ければ 422")
    fun noQuizIsUnprocessable() {
        start("""{"scope":"all"}""").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.title") { value("出題できるクイズがありません") }
        }
    }

    @Test
    @DisplayName("出題数が上限を超えると 400")
    fun limitOverMaximumIsRejected() {
        fixture.quiz(awsCategory, saa, "問題")

        start("""{"limit":101}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.limit") { exists() }
        }
    }

    @Test
    @DisplayName("知らない出題対象を指定すると 400")
    fun unknownScopeIsRejected() {
        fixture.quiz(awsCategory, saa, "問題")

        start("""{"scope":"unknown"}""").andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("利用者が特定できなければ 401")
    fun missingUserIsUnauthorized() {
        fixture.quiz(awsCategory, saa, "問題")

        mockMvc.post("/api/t/golf/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    /** 回答履歴を 1 件作る。出題の絞り込みを試すための下ごしらえ。 */
    private fun answerOnce(quizId: UUID) {
        val choiceId = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT id FROM quiz.choices WHERE quiz_id = ? ORDER BY sort_order LIMIT 1",
            UUID::class.java,
            quizId,
        )
        val attemptId = TestPostgres.adminJdbcTemplate.queryForObject(
            """
            INSERT INTO answer.attempts (user_id, scope, status, finished_at)
            VALUES (?, 'all', 'completed', now()) RETURNING id
            """,
            UUID::class.java,
            user,
        )
        TestPostgres.adminJdbcTemplate.update(
            """
            INSERT INTO answer.answers (attempt_id, user_id, quiz_id, choice_id, is_correct)
            VALUES (?, ?, ?, ?, true)
            """,
            attemptId,
            user,
            quizId,
            choiceId,
        )
    }
}
