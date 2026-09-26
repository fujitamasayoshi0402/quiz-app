package com.quizapp.answer.controller

import com.quizapp.answer.usecase.HistoryUseCase
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 履歴の統合テスト。
 *
 * SQL が担うことをここで見る。
 * - **本人の記録だけを返すこと。** 同じテナントの別の利用者の挑戦と回答は数えない
 * - 完了した挑戦だけを、新しい順に、続きの位置で区切って返すこと
 * - 正答率を、クイズごとの最新の回答で数えること。途中でやめた挑戦の回答も含めること
 * - 削除・非公開のクイズへの回答を正答率から外し、挑戦の成績は記録したときのまま残すこと
 *
 * テナントの境界は [com.quizapp.tenant.TenantBoundaryApiTest] が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryApiTest {

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
    private lateinit var aws: UUID
    private lateinit var saa: UUID

    @BeforeEach
    fun setUp() {
        user = TestAuth.createUser("履歴を見る人")
        otherUser = TestAuth.createUser("同じテナントの別の人")
        tenant = TestTenant.create().withAdmin().join(user).join(otherUser)
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        aws = fixture.category("AWS")
        saa = fixture.difficulty(aws, "SAA", 2)
    }

    // --- 挑戦の一覧 -----------------------------------------------------------

    @Test
    @DisplayName("完了した挑戦を新しい順に、成績とカテゴリの名前を付けて返す")
    fun listsCompletedAttempts() {
        fixture.quiz(aws, saa, "AWS 1")
        fixture.quiz(aws, saa, "AWS 2")
        val auth = fixture.category("認証")
        fixture.quiz(auth, fixture.difficulty(auth, "基礎", 1), "認証 1")

        val older = start(user, """"categoryId":"$aws","difficultyId":"$saa"""")
        answer(user, older, 0, correct = true)
        answer(user, older, 1, correct = false)
        complete(user, older)

        // カテゴリを絞らず、1 問だけ解いて終える
        val newer = start(user)
        answer(user, newer, 0, correct = true)
        complete(user, newer)

        val page = attempts(user)
        val items = page["items"]

        assertThat(items.values().map { it["id"].asString() }).containsExactly(newer.id(), older.id())
        assertThat(items[0].has("categoryId")).describedAs("カテゴリを絞らなかった").isFalse()
        assertThat(items[0].score()).isEqualTo(Score(total = 3, answered = 1, correct = 1))
        assertThat(items[1]["categoryName"].asString()).isEqualTo("AWS")
        assertThat(items[1]["difficultyName"].asString()).isEqualTo("SAA")
        assertThat(items[1].score()).isEqualTo(Score(total = 2, answered = 2, correct = 1))
        assertThat(page.has("nextCursor")).isFalse()
    }

    @Test
    @DisplayName("途中でやめた挑戦と、回答中の挑戦は出さない")
    fun excludesUnfinishedAttempts() {
        fixture.quiz(aws, saa, "AWS 1")
        val abandoned = start(user)
        answer(user, abandoned, 0, correct = true)
        abandon(user, abandoned)
        start(user)

        assertThat(attempts(user)["items"].isEmpty).isTrue()
    }

    @Test
    @DisplayName("1 ページを超えると、続きの位置から重複も抜けもなく残りを取れる")
    fun paginates() {
        fixture.quiz(aws, saa, "AWS 1")
        val created = (0..HistoryUseCase.PAGE_SIZE).map { start(user).also { complete(user, it) }.id() }

        val first = attempts(user)
        val second = attempts(user, cursor = first["nextCursor"].asString())

        assertThat(first["items"].size()).isEqualTo(HistoryUseCase.PAGE_SIZE)
        assertThat(second.has("nextCursor")).isFalse()
        assertThat((first["items"].values() + second["items"].values()).map { it["id"].asString() }).isEqualTo(created.reversed())
    }

    @Test
    @DisplayName("壊れた続きの位置は 400")
    fun rejectsBrokenCursor() {
        mockMvc.get("/api/t/${tenant.slug}/play/history/attempts") {
            param("cursor", "broken")
            header("Authorization", TestAuth.bearer(user))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("続きの位置の指定が正しくありません") }
        }
    }

    // --- カテゴリ別の正答率 ---------------------------------------------------

    @Test
    @DisplayName("正答率は、クイズごとに最新の回答で数える")
    fun countsLatestAnswer() {
        fixture.quiz(aws, saa, "AWS 1")
        fixture.quiz(aws, saa, "AWS 2")

        start(user).also { answer(user, it, 0, correct = false) }.let { complete(user, it) }
        assertThat(awsScore(user)).isEqualTo(Score(total = 2, answered = 1, correct = 0))

        // 解き直して正解すれば上がる。延べでは数えない
        start(user).also { answer(user, it, 0, correct = true) }.let { complete(user, it) }
        assertThat(awsScore(user)).isEqualTo(Score(total = 2, answered = 1, correct = 1))

        // 最新が誤りなら下がる
        start(user).also { answer(user, it, 0, correct = false) }.let { complete(user, it) }
        assertThat(awsScore(user)).isEqualTo(Score(total = 2, answered = 1, correct = 0))
    }

    @Test
    @DisplayName("途中でやめた挑戦の回答も、正答率に数える")
    fun countsAnswersInAbandonedAttempts() {
        fixture.quiz(aws, saa, "AWS 1")
        val attempt = start(user)
        answer(user, attempt, 0, correct = true)
        abandon(user, attempt)

        assertThat(awsScore(user)).isEqualTo(Score(total = 1, answered = 1, correct = 1))
    }

    @Test
    @DisplayName("削除・下書きにしたクイズへの回答は正答率から外し、挑戦の成績は記録したときのまま残す")
    fun excludesWithdrawnQuizzesFromScores() {
        val deleted = fixture.quiz(aws, saa, "削除する問題")
        val drafted = fixture.quiz(aws, saa, "下書きに戻す問題")
        fixture.quiz(aws, saa, "残る問題")
        val attempt = start(user)
        (0..2).forEach { answer(user, attempt, it, correct = true) }
        complete(user, attempt)

        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$deleted") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }
        toDraft(drafted, "下書きに戻す問題")

        assertThat(awsScore(user)).isEqualTo(Score(total = 1, answered = 1, correct = 1))
        assertThat(attempts(user)["items"][0].score()).isEqualTo(Score(total = 3, answered = 3, correct = 3))
    }

    // --- 本人の記録だけ -------------------------------------------------------

    @Test
    @DisplayName("同じテナントの別の利用者の挑戦と回答は、一覧にも正答率にも出ない")
    fun hidesOtherUsersRecords() {
        fixture.quiz(aws, saa, "AWS 1")
        val others = start(otherUser)
        answer(otherUser, others, 0, correct = true)
        complete(otherUser, others)

        assertThat(attempts(user)["items"].isEmpty).isTrue()
        assertThat(awsScore(user)).isEqualTo(Score(total = 1, answered = 0, correct = 0))
        // 別の利用者自身には見える。絞り込みが効きすぎて何も返さない、のではないことを確かめる
        assertThat(attempts(otherUser)["items"].values().map { it["id"].asString() }).containsExactly(others.id())
        assertThat(awsScore(otherUser)).isEqualTo(Score(total = 1, answered = 1, correct = 1))
    }

    // --- ヘルパー -------------------------------------------------------------

    private data class Score(val total: Int, val answered: Int, val correct: Int)

    private fun JsonNode.score() =
        Score(this["totalCount"].asInt(), this["answeredCount"].asInt(), this["correctCount"].asInt())

    /** AWS カテゴリの成績。total は出題できるクイズの数 */
    private fun awsScore(userId: UUID): Score = categories(userId)
        .first { it["categoryId"].asString() == aws.toString() }
        .let { Score(it["quizCount"].asInt(), it["answeredCount"].asInt(), it["correctCount"].asInt()) }

    /** 並びを固定して、テストから何問目かを指定できるようにする。[criteria] は JSON のオブジェクトの中身 */
    private fun start(userId: UUID, criteria: String = ""): JsonNode {
        val extra = if (criteria.isEmpty()) "" else ",$criteria"
        return mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all","order":"registered"$extra}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString.let(objectMapper::readTree)
    }

    /** [PlayFixture] は「選択肢 1」を正解にする。選択肢は並びが変わるため、本文で選ぶ */
    private fun answer(userId: UUID, attempt: JsonNode, index: Int, correct: Boolean) {
        val quiz = attempt["quizzes"][index]
        val choice = quiz["choices"].first { (it["body"].asString() == "選択肢 1") == correct }
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/answers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quizId":"${quiz["id"].asString()}","choiceId":"${choice["id"].asString()}"}"""
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
    }

    private fun complete(userId: UUID, attempt: JsonNode) {
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/complete") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }
    }

    private fun abandon(userId: UUID, attempt: JsonNode) {
        mockMvc.post("/api/t/${tenant.slug}/play/attempts/${attempt.id()}/abandon") {
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isNoContent() } }
    }

    private fun toDraft(quizId: UUID, question: String) {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        mockMvc.put("/api/t/${tenant.slug}/admin/quizzes/$quizId") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$aws","difficultyId":"$saa","question":"$question",
                 "explanation":"解説","choices":[$choices],"status":"draft"}
            """.trimIndent()
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isOk() } }
    }

    private fun attempts(userId: UUID, cursor: String? = null): JsonNode =
        mockMvc.get("/api/t/${tenant.slug}/play/history/attempts") {
            cursor?.let { param("cursor", it) }
            header("Authorization", TestAuth.bearer(userId))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)

    private fun categories(userId: UUID): JsonNode = mockMvc.get("/api/t/${tenant.slug}/play/history/categories") {
        header("Authorization", TestAuth.bearer(userId))
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)

    private fun JsonNode.id(): String = this["id"].asString()
}
