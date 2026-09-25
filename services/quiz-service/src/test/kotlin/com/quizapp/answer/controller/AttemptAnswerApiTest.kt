package com.quizapp.answer.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 回答・再開・結果の統合テスト。
 *
 * 重点は 3 つ。
 * - **採点の結果と解説が、回答したあとにだけ返ること**
 * - **中断した挑戦を、別の端末からでも再開できること**（サーバーだけで状態が完結している）
 * - **他人の挑戦に触れないこと**
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttemptAnswerApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var user: UUID
    private lateinit var otherUser: UUID

    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        user = TestAuth.createUser("回答する人")
        // 所属はしている別人。挑戦の持ち主かどうかだけを確かめたいため
        otherUser = TestAuth.createUser("別の人")
        tenant = TestTenant.create().withAdmin().join(user).join(otherUser)
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    // --- 回答 -----------------------------------------------------------------

    @Test
    @DisplayName("正解すると、正誤と解説が返る")
    fun correctAnswerReturnsExplanation() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0)).andExpect {
            status { isOk() }
            jsonPath("$.isCorrect") { value(true) }
            jsonPath("$.explanation") { value("問題 1 の解説") }
            jsonPath("$.answeredCount") { value(1) }
            jsonPath("$.totalCount") { value(1) }
        }
    }

    @Test
    @DisplayName("間違えても正解の選択肢と解説が返る")
    fun wrongAnswerAlsoReturnsExplanation() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        answer(attempt.id(), attempt.quizId(0), attempt.wrongChoiceId(0)).andExpect {
            status { isOk() }
            jsonPath("$.isCorrect") { value(false) }
            jsonPath("$.correctChoiceId") { value(attempt.correctChoiceId(0)) }
            jsonPath("$.explanation") { value("問題 1 の解説") }
        }
    }

    @Test
    @DisplayName("同じクイズに二度回答すると 409")
    fun cannotAnswerTwice() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0)).andExpect { status { isOk() } }
        answer(attempt.id(), attempt.quizId(0), attempt.wrongChoiceId(0)).andExpect { status { isConflict() } }
    }

    @Test
    @DisplayName("出題されていないクイズには回答できない")
    fun cannotAnswerQuizOutsideAttempt() {
        fixture.quiz(category, difficulty, "出題される問題")
        val attempt = startAttempt()
        val outsider = fixture.quiz(category, difficulty, "出題されない問題")

        answer(attempt.id(), outsider.toString(), attempt.correctChoiceId(0))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    @DisplayName("他のクイズの選択肢 ID では回答できない")
    fun cannotAnswerWithForeignChoice() {
        fixture.quiz(category, difficulty, "問題 1")
        fixture.quiz(category, difficulty, "問題 2")
        val attempt = startAttempt()

        // 1 問目に対して 2 問目の選択肢を送る
        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(1))
            .andExpect { status { isBadRequest() } }
    }

    // --- 中断と再開 -----------------------------------------------------------

    @Test
    @DisplayName("中断中の挑戦があると、新しく始められない")
    fun cannotStartWhileInProgress() {
        repeat(2) { fixture.quiz(category, difficulty, "問題 $it") }
        val attempt = startAttempt()
        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0)).andExpect { status { isOk() } }

        // 黙って破棄しない。件数を返して、再開するか破棄するかを選ばせる
        start("""{"scope":"all"}""").andExpect {
            status { isConflict() }
            jsonPath("$.attemptId") { value(attempt.id()) }
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.answeredCount") { value(1) }
        }
    }

    @Test
    @DisplayName("破棄を指定すれば新しく始められる")
    fun canDiscardAndStart() {
        fixture.quiz(category, difficulty, "問題 1")
        val first = startAttempt()

        val second = start("""{"scope":"all","discardInProgress":true}""")
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString.let { objectMapper.readTree(it) }

        assert(first.id() != second.id()) { "破棄したのに同じ挑戦が返っている" }
        // 破棄した挑戦は再開できない
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/${first.id()}") { header("X-User-Id", user.toString()) }
            .andExpect { jsonPath("$.status") { value("abandoned") } }
    }

    @Test
    @DisplayName("中断中の挑戦を取得できる。無ければ 204")
    fun currentAttempt() {
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/current") { header("X-User-Id", user.toString()) }
            .andExpect { status { isNoContent() } }

        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        mockMvc.get("/api/t/${tenant.slug}/play/attempts/current") { header("X-User-Id", user.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(attempt.id()) }
            }
    }

    @Test
    @DisplayName("再開すると、出題順と回答済みが分かる")
    fun resumeKeepsOrderAndProgress() {
        repeat(3) { fixture.quiz(category, difficulty, "問題 $it") }
        val attempt = startAttempt()
        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0)).andExpect { status { isOk() } }

        // ブラウザに何も保存していなくても、サーバーだけで続きが分かる
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/${attempt.id()}") { header("X-User-Id", user.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.quizzes.length()") { value(3) }
                jsonPath("$.quizzes[0].id") { value(attempt.quizId(0)) }
                jsonPath("$.quizzes[2].id") { value(attempt.quizId(2)) }
                jsonPath("$.answeredQuizIds.length()") { value(1) }
                jsonPath("$.answeredQuizIds[0]") { value(attempt.quizId(0)) }
                jsonPath("$.excludedCount") { value(0) }
            }
    }

    @Test
    @DisplayName("再開時に、削除されたクイズは除外される")
    fun resumeExcludesDeletedQuiz() {
        repeat(2) { fixture.quiz(category, difficulty, "問題 $it") }
        val attempt = startAttempt()

        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/${attempt.quizId(1)}") {
            header("X-User-Id", TestAuth.ADMIN.toString())
        }.andExpect { status { isNoContent() } }

        // 出題リストに外部キーを貼っていないため、参照先は消えうる。件数で伝える
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/${attempt.id()}") { header("X-User-Id", user.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.quizzes.length()") { value(1) }
                jsonPath("$.excludedCount") { value(1) }
            }
    }

    // --- 結果 -----------------------------------------------------------------

    @Test
    @DisplayName("完了すると、全問の解説つきで結果が返る")
    fun completeReturnsAllExplanations() {
        repeat(3) { fixture.quiz(category, difficulty, "問題 $it") }
        val attempt = startAttempt()
        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0)).andExpect { status { isOk() } }
        answer(attempt.id(), attempt.quizId(1), attempt.wrongChoiceId(1)).andExpect { status { isOk() } }

        // 模試モードはここで初めて答え合わせをする。未回答の 1 問も並ぶ
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/complete") {
            header("X-User-Id", user.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("completed") }
            jsonPath("$.totalCount") { value(3) }
            jsonPath("$.answeredCount") { value(2) }
            jsonPath("$.correctCount") { value(1) }
            jsonPath("$.results.length()") { value(3) }
            jsonPath("$.results[0].isCorrect") { value(true) }
            jsonPath("$.results[1].isCorrect") { value(false) }
            jsonPath("$.results[1].explanation") { exists() }
            jsonPath("$.results[2].selectedChoiceId") { doesNotExist() }
        }
    }

    @Test
    @DisplayName("完了した挑戦には回答できず、中断中にも現れない")
    fun completedAttemptIsClosed() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/complete") {
            header("X-User-Id", user.toString())
        }.andExpect { status { isOk() } }

        answer(attempt.id(), attempt.quizId(0), attempt.correctChoiceId(0))
            .andExpect { status { isConflict() } }
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/current") { header("X-User-Id", user.toString()) }
            .andExpect { status { isNoContent() } }
    }

    @Test
    @DisplayName("結果は何度でも取得できる")
    fun completeIsIdempotent() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        repeat(2) {
            mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/complete") {
                header("X-User-Id", user.toString())
            }.andExpect { status { isOk() } }
        }
    }

    // --- 他人の挑戦 -----------------------------------------------------------

    @Test
    @DisplayName("他人の挑戦は見えないし、回答もできない")
    fun cannotTouchAnotherUsersAttempt() {
        fixture.quiz(category, difficulty, "問題 1")
        val attempt = startAttempt()

        // 権限エラーと区別すると、ID が存在することが分かってしまう
        mockMvc.get("/api/t/${tenant.slug}/play/attempts/${attempt.id()}") {
            header("X-User-Id", otherUser.toString())
        }.andExpect { status { isNotFound() } }

        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${attempt.quizId(0)}","choiceId":"${attempt.correctChoiceId(0)}"}"""
            header("X-User-Id", otherUser.toString())
        }.andExpect { status { isNotFound() } }
    }

    // --- ヘルパー -------------------------------------------------------------

    private fun start(body: String): ResultActionsDsl = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        header("X-User-Id", user.toString())
    }

    /** 並びを固定して、テストから出題順を指定できるようにする。 */
    private fun startAttempt(): JsonNode = start("""{"scope":"all","order":"registered"}""")
        .andExpect { status { isCreated() } }
        .andReturn().response.contentAsString
        .let { objectMapper.readTree(it) }

    private fun answer(attemptId: String, quizId: String, choiceId: String): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/$attemptId/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"$quizId","choiceId":"$choiceId"}"""
            header("X-User-Id", user.toString())
        }

    private fun JsonNode.id(): String = this["id"].asString()

    private fun JsonNode.quizId(index: Int): String = this["quizzes"][index]["id"].asString()

    /** 選択肢は出題のたびに並びが変わるため、位置ではなく本文で選ぶ。[PlayFixture] は「選択肢 1」を正解にする */
    private fun JsonNode.correctChoiceId(quizIndex: Int): String = choiceId(quizIndex) { it == "選択肢 1" }

    private fun JsonNode.wrongChoiceId(quizIndex: Int): String = choiceId(quizIndex) { it != "選択肢 1" }

    private fun JsonNode.choiceId(quizIndex: Int, body: (String) -> Boolean): String =
        this["quizzes"][quizIndex]["choices"].first { body(it["body"].asString()) }["id"].asString()
}
