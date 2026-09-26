package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * クイズの一括取り込みの統合テスト。
 *
 * 重点は 3 つ。
 * - **全か無か**: 1 行でも不正なら 1 件も入らない
 * - 不正な行と理由を**まとめて**返す。送られた値（名前など）は応答で繰り返さない
 * - カテゴリと難易度は、**このテナントの、削除されていないもの**だけを名前で指せる
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuizImportApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var awsCategory: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("ゴルフ").withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        awsCategory = fixture.category("AWS")
        fixture.difficulty(awsCategory, "SAA", 2)
        fixture.difficulty(fixture.category("認証認可"), "初級", 1)
    }

    private fun row(
        question: String,
        category: String = "AWS",
        difficulty: String = "SAA",
        explanation: String = "$question の解説",
        choiceCount: Int = 4,
        status: String? = null,
    ): Map<String, Any> = buildMap {
        put("category", category)
        put("difficulty", difficulty)
        put("question", question)
        put("explanation", explanation)
        put("choices", (1..choiceCount).map { mapOf("body" to "選択肢 $it", "isCorrect" to (it == 1)) })
        status?.let { put("status", it) }
    }

    private fun import(vararg rows: Map<String, Any>): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/admin/quizzes/import") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("quizzes" to rows.toList()))
        }

    private fun quizzesIn(categoryId: UUID) = mockMvc.get("/api/t/${tenant.slug}/admin/quizzes") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        param("categoryId", categoryId.toString())
    }

    @Test
    @DisplayName("正しい行はまとめて取り込まれる。状態を書かなければ下書きになる")
    fun importsAllRows() {
        import(
            row("  S3 のストレージクラスは？  "),
            row("IAM ロールを使う理由は？", status = "published"),
        ).andExpect {
            status { isCreated() }
            jsonPath("$.importedCount") { value(2) }
        }

        quizzesIn(awsCategory).andExpect {
            jsonPath("$.length()") { value(2) }
            // 前後の空白は落として保存する
            jsonPath("$[*].question") { value(containsInAnyOrder("S3 のストレージクラスは？", "IAM ロールを使う理由は？")) }
            jsonPath("$[?(@.question == 'S3 のストレージクラスは？')].status") { value("draft") }
            jsonPath("$[?(@.question == 'IAM ロールを使う理由は？')].status") { value("published") }
            jsonPath("$[0].choices.length()") { value(4) }
        }
    }

    @Test
    @DisplayName("1 行でも不正なら、1 件も取り込まない")
    fun rejectsAllWhenAnyRowIsInvalid() {
        import(
            row("正しい 1 行目"),
            row("存在しないカテゴリの行", category = "存在しないカテゴリ"),
            row("正しい 3 行目"),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("取り込めない行があります。1 件も取り込んでいません") }
            jsonPath("$.rows.length()") { value(1) }
            jsonPath("$.rows[0].index") { value(1) }
            jsonPath("$.rows[0].messages[0]") { value("カテゴリが見つかりません") }
        }

        quizzesIn(awsCategory).andExpect { jsonPath("$.length()") { value(0) } }
    }

    @Test
    @DisplayName("不正な行と理由を、まとめて返す")
    fun reportsEveryInvalidRow() {
        import(
            row("正しい行"),
            row("", category = "存在しないカテゴリ"),
            row("別のカテゴリの難易度", difficulty = "初級"),
            row("状態の誤り", status = "archived"),
            row("選択肢が足りない", choiceCount = 3, status = "published"),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.rows[*].index") { value(containsInAnyOrder(1, 2, 3, 4)) }
            // カテゴリが見つからなくても、内容の誤りもあわせて返す
            jsonPath("$.rows[0].messages") { value(containsInAnyOrder("カテゴリが見つかりません", "問題文を入力してください")) }
            jsonPath("$.rows[1].messages[0]") { value("難易度がこのカテゴリにありません") }
            jsonPath("$.rows[2].messages[0]") { value("状態は draft または published を指定してください") }
            jsonPath("$.rows[3].messages[0]") { value("公開するには選択肢が 4 個必要です") }
        }
    }

    @Test
    @DisplayName("同じカテゴリに同じ問題文があれば、取り込まない")
    fun rejectsDuplicateQuestions() {
        fixture.quiz(awsCategory, fixture.difficulty(awsCategory, "DVA", 2), "登録済みの問題")

        import(
            row("登録済みの問題"),
            row("ファイルの中で重複"),
            row("ファイルの中で重複"),
            // 別のカテゴリなら、同じ問題文でもよい
            row("ファイルの中で重複", category = "認証認可", difficulty = "初級"),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.rows.length()") { value(2) }
            jsonPath("$.rows[0].index") { value(0) }
            jsonPath("$.rows[0].messages[0]") { value("同じカテゴリに、同じ問題文のクイズがすでにあります") }
            jsonPath("$.rows[1].index") { value(2) }
            jsonPath("$.rows[1].messages[0]") { value("2 件目と問題文が重複しています") }
        }
    }

    @Test
    @DisplayName("別テナントのカテゴリは、名前で指しても見つからない。名前は応答に出さない")
    fun doesNotResolveOtherTenantsCategory() {
        val other = TestTenant.create("ホテル").withAdmin()
        val otherFixture = PlayFixture(mockMvc, objectMapper, other.slug)
        otherFixture.difficulty(otherFixture.category("他テナントのカテゴリ"), "他テナントの難易度", 1)

        val body = import(row("問題", category = "他テナントのカテゴリ", difficulty = "他テナントの難易度"))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.rows[0].messages[0]") { value("カテゴリが見つかりません") }
            }.andReturn().response.contentAsString

        assertThat(body).doesNotContain("他テナント")
    }

    @Test
    @DisplayName("削除したカテゴリは、名前で指しても見つからない")
    fun doesNotResolveDeletedCategory() {
        val deleted = fixture.category("削除したカテゴリ")
        fixture.difficulty(deleted, "初級", 1)
        mockMvc.delete("/api/t/${tenant.slug}/admin/categories/$deleted") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        import(row("問題", category = "削除したカテゴリ", difficulty = "初級")).andExpect {
            status { isBadRequest() }
            jsonPath("$.rows[0].messages[0]") { value("カテゴリが見つかりません") }
        }
    }

    @Test
    @DisplayName("1 回に取り込めるのは 1〜500 件")
    fun limitsRowCount() {
        import().andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.quizzes") { value("取り込めるのは 1〜500 件です") }
        }

        val rows = (1..501).map { row("問題 $it") }.toTypedArray()
        import(*rows).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.quizzes") { value("取り込めるのは 1〜500 件です") }
        }
        quizzesIn(awsCategory).andExpect { jsonPath("$.length()") { value(0) } }
    }
}
