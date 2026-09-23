package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 出題 API の統合テスト。
 *
 * 最重要は **レスポンスに正解が含まれないこと**。
 * ここが漏れると、クライアント側で採点できてしまい、アプリの前提が崩れる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizDeliveryApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val tenant: UUID = UUID.fromString("a1a1a1a1-1111-1111-1111-111111111111")
    private val user: UUID = UUID.fromString("b2b2b2b2-2222-2222-2222-222222222222")

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
        awsCategory = createCategory("AWS")
        authCategory = createCategory("認証認可")
        // レベル 2 に 2 つの難易度を置き、レベル横断の出題を確かめられるようにする
        saa = createDifficulty(awsCategory, "SAA", 2)
        dva = createDifficulty(awsCategory, "DVA", 2)
        authBeginner = createDifficulty(authCategory, "初級", 2)
    }

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        admin.update("DELETE FROM answer.answers WHERE user_id = ?", user)
        admin.update("DELETE FROM quiz.choices WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.quizzes WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.difficulties WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.categories WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM core.tenants WHERE id = ?", tenant)
        admin.update("DELETE FROM core.users WHERE id = ?", user)
    }

    private fun createCategory(name: String): UUID {
        val result = mockMvc.post("/api/t/golf/categories") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, CategoryResponse::class.java).id
    }

    private fun createDifficulty(categoryId: UUID, name: String, level: Int): UUID {
        val result = mockMvc.post("/api/t/golf/categories/$categoryId/difficulties") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","level":$level}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, DifficultyResponse::class.java).id
    }

    private fun createQuiz(categoryId: UUID, difficultyId: UUID, question: String, status: String = "published"): UUID {
        val choices = (1..4).joinToString(",") { """{"body":"選択肢 $it","isCorrect":${it == 1}}""" }
        val result = mockMvc.post("/api/t/golf/quizzes") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"categoryId":"$categoryId","difficultyId":"$difficultyId",
                 "question":"$question","explanation":"解説",
                 "choices":[$choices],"status":"$status"}
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readValue(result.response.contentAsString, QuizResponse::class.java).id
    }

    private fun markAnswered(quizId: UUID) {
        // 回答 API は DEV-22 で作るため、ここでは履歴を直接入れる
        val choiceId = TestPostgres.adminJdbcTemplate.queryForObject(
            "SELECT id FROM quiz.choices WHERE quiz_id = ? ORDER BY sort_order LIMIT 1",
            UUID::class.java,
            quizId,
        )
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO answer.answers (user_id, quiz_id, choice_id, is_correct) VALUES (?, ?, ?, true)",
            user,
            quizId,
            choiceId,
        )
    }

    @Test
    @DisplayName("レスポンスに正解が含まれない")
    fun deliveredQuizDoesNotExposeCorrectAnswer() {
        createQuiz(awsCategory, saa, "問題 1")

        val result = mockMvc.get("/api/t/golf/play/quizzes").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].choices.length()") { value(4) }
        }.andReturn()

        val body = result.response.contentAsString
        // isCorrect というキー自体が存在しないことを確かめる。
        // 値が false でも、キーがあれば「正解でないもの」が分かってしまう
        assert(!body.contains("isCorrect")) { "出題レスポンスに正解情報が含まれている: $body" }
        assert(!body.contains("explanation")) { "出題レスポンスに解説が含まれている: $body" }
    }

    @Test
    @DisplayName("下書きのクイズは出題されない")
    fun draftQuizIsNotDelivered() {
        createQuiz(awsCategory, saa, "下書きの問題", status = "draft")

        mockMvc.get("/api/t/golf/play/quizzes").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("全問出題モードは条件に合うクイズをすべて返す")
    fun allModeReturnsEverything() {
        repeat(3) { createQuiz(awsCategory, saa, "問題 $it") }

        mockMvc.get("/api/t/golf/play/quizzes?mode=all&limit=100").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
        }
    }

    @Test
    @DisplayName("出題数を指定するとその件数だけ返る")
    fun limitIsRespected() {
        repeat(5) { createQuiz(awsCategory, saa, "問題 $it") }

        mockMvc.get("/api/t/golf/play/quizzes?mode=random&limit=2").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("難易度を指定するとその難易度だけが対象になる")
    fun canFilterByDifficulty() {
        createQuiz(awsCategory, saa, "SAA の問題")
        createQuiz(awsCategory, dva, "DVA の問題")

        mockMvc.get("/api/t/golf/play/quizzes?difficultyId=$saa&mode=all").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("SAA の問題") }
        }
    }

    @Test
    @DisplayName("レベルを指定すると同じレベルの難易度がすべて対象になる")
    fun levelIncludesAllDifficultiesAtThatLevel() {
        createQuiz(awsCategory, saa, "SAA の問題")
        createQuiz(awsCategory, dva, "DVA の問題")

        // SAA と DVA はどちらもレベル 2。レベル指定は難易度を 1 つに絞らない
        mockMvc.get("/api/t/golf/play/quizzes?level=2&categoryId=$awsCategory&mode=all").andExpect {
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("カテゴリを指定しなければレベルでカテゴリ横断の出題になる")
    fun levelWorksAcrossCategories() {
        createQuiz(awsCategory, saa, "AWS の問題")
        createQuiz(authCategory, authBeginner, "認証認可の問題")

        // 名前は SAA と初級で異なるが、どちらもレベル 2
        mockMvc.get("/api/t/golf/play/quizzes?level=2&mode=all").andExpect {
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("未回答優先では、まだ解いていないクイズが先に出る")
    fun unansweredComesFirst() {
        val answered = createQuiz(awsCategory, saa, "回答済みの問題")
        createQuiz(awsCategory, saa, "未回答の問題")
        markAnswered(answered)

        mockMvc.get("/api/t/golf/play/quizzes?mode=unanswered&limit=1") {
            header("X-User-Id", user.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("未回答の問題") }
        }
    }

    @Test
    @DisplayName("未回答が尽きたら回答済みも出す")
    fun answeredIsUsedWhenNoUnansweredLeft() {
        val first = createQuiz(awsCategory, saa, "問題 1")
        val second = createQuiz(awsCategory, saa, "問題 2")
        markAnswered(first)
        markAnswered(second)

        // 「もう解く問題がありません」ではなく、復習として出す
        mockMvc.get("/api/t/golf/play/quizzes?mode=unanswered&limit=2") {
            header("X-User-Id", user.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    @DisplayName("該当するクイズが無ければ空を返す")
    fun emptyResultIsOk() {
        mockMvc.get("/api/t/golf/play/quizzes?mode=all").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("出題数が上限を超えると 400")
    fun limitOverMaximumIsRejected() {
        mockMvc.get("/api/t/golf/play/quizzes?limit=101").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    @DisplayName("知らない出題モードを指定すると 400")
    fun unknownModeIsRejected() {
        mockMvc.get("/api/t/golf/play/quizzes?mode=unknown").andExpect {
            status { isBadRequest() }
        }
    }
}
