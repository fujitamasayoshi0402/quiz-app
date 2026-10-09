package com.quizapp.quiz.infrastructure.integrity

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * データの整合性の決まり（DEV-115）を、DB に当てる。
 *
 * 管理 API で正しいデータを作り、**ドメインを通らない SQL**（所有者の接続。シードや手で流す SQL と同じ）で崩して、見つかることを確かめる。
 * いつ流すか（使っている間に 1 日 1 回）は `IntegrityCheckTest` が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrityChecksApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var checks: IntegrityChecks

    private val sql = TestPostgres.adminJdbcTemplate

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("キロ").withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    private fun violations() = checks.check(tenant.id).map { it.rule to it.subjectId }

    @Test
    @DisplayName("管理 API で作ったものは崩れていない")
    fun validData() {
        val figure = fixture.figure()
        val quiz = fixture.quiz(category, difficulty, "公開")
        fixture.quiz(category, difficulty, "下書き", status = "draft")
        sql.update("UPDATE quiz.quizzes SET explanation = ? WHERE id = ?", "![図](figure:$figure)", quiz)

        assertThat(violations()).isEmpty()
        assertThat(checks.tenantIds()).contains(tenant.id)
    }

    @Test
    @DisplayName("公開中のクイズの選択肢が 4 つでない。下書きは 4 つまでなら崩れていない")
    fun choiceCount() {
        val published = fixture.quiz(category, difficulty, "公開")
        val draft = fixture.quiz(category, difficulty, "下書き", status = "draft")
        // 正解でない選択肢を 1 つずつ消す
        sql.update(
            """
            DELETE FROM quiz.choices WHERE id IN (
                SELECT DISTINCT ON (quiz_id) id FROM quiz.choices
                WHERE quiz_id IN (?, ?) AND NOT is_correct ORDER BY quiz_id, sort_order DESC
            )
            """.trimIndent(),
            published,
            draft,
        )

        assertThat(violations()).containsExactly(IntegrityRule.CHOICE_COUNT to published)
    }

    @Test
    @DisplayName("下書きでも、選択肢が 5 つ以上なら崩れている")
    fun tooManyChoicesOnDraft() {
        val draft = fixture.quiz(category, difficulty, "下書き", status = "draft")
        sql.update(
            "INSERT INTO quiz.choices (tenant_id, quiz_id, body, sort_order) VALUES (?, ?, '5 つ目', 99)",
            tenant.id,
            draft,
        )

        assertThat(violations()).containsExactly(IntegrityRule.CHOICE_COUNT to draft)
    }

    @Test
    @DisplayName("公開中のクイズに正解がない")
    fun noCorrectChoice() {
        val quiz = fixture.quiz(category, difficulty, "公開")
        sql.update("UPDATE quiz.choices SET is_correct = false WHERE quiz_id = ?", quiz)

        assertThat(violations()).containsExactly(IntegrityRule.CORRECT_CHOICE_COUNT to quiz)
    }

    @Test
    @DisplayName("公開中のクイズの解説が空白だけ。全角の空白も空とみなす")
    fun blankExplanation() {
        val quiz = fixture.quiz(category, difficulty, "公開")
        sql.update("UPDATE quiz.quizzes SET explanation = ? WHERE id = ?", " 　\n", quiz)

        assertThat(violations()).containsExactly(IntegrityRule.EXPLANATION_BLANK to quiz)
    }

    @Test
    @DisplayName("解説が、ない図と、別のテナントの図を指している")
    fun missingFigure() {
        val other = TestTenant.create("リマ").withAdmin()
        val otherFigure = PlayFixture(mockMvc, objectMapper, other.slug).figure()
        val ownFigure = fixture.figure()
        val nowhere = fixture.quiz(category, difficulty, "ない図")
        val foreign = fixture.quiz(category, difficulty, "別のテナントの図", status = "draft")
        sql.update("UPDATE quiz.quizzes SET explanation = ? WHERE id = ?", "[図](figure:${UUID.randomUUID()})", nowhere)
        sql.update(
            "UPDATE quiz.quizzes SET explanation = ? WHERE id = ?",
            "![自分の図](figure:$ownFigure)\n![別の図](figure:$otherFigure)",
            foreign,
        )

        assertThat(violations()).containsExactlyInAnyOrder(
            IntegrityRule.MISSING_FIGURE to nowhere,
            IntegrityRule.MISSING_FIGURE to foreign,
        )
    }

    @Test
    @DisplayName("削除済みのカテゴリの下に、削除されていない難易度とクイズがある")
    fun aliveUnderDeletedCategory() {
        val quiz = fixture.quiz(category, difficulty, "公開")
        sql.update("UPDATE quiz.categories SET deleted_at = now() WHERE id = ?", category)

        assertThat(violations()).containsExactlyInAnyOrder(
            IntegrityRule.ALIVE_UNDER_DELETED_PARENT to quiz,
            IntegrityRule.ALIVE_UNDER_DELETED_PARENT to difficulty,
        )
    }

    @Test
    @DisplayName("削除済みのクイズは確かめない。消すときに崩れたものを残しても、出題されない")
    fun ignoresDeletedQuizzes() {
        val quiz = fixture.quiz(category, difficulty, "公開")
        sql.update("DELETE FROM quiz.choices WHERE quiz_id = ?", quiz)
        sql.update("UPDATE quiz.quizzes SET deleted_at = now() WHERE id = ?", quiz)

        assertThat(violations()).isEmpty()
    }

    @Test
    @DisplayName("別のテナントの崩れたものは、このテナントの結果に入らない")
    fun scopedToTenant() {
        val other = TestTenant.create("マイク").withAdmin()
        val otherFixture = PlayFixture(mockMvc, objectMapper, other.slug)
        val otherCategory = otherFixture.category("AWS")
        val otherQuiz = otherFixture.quiz(otherCategory, otherFixture.difficulty(otherCategory, "SAA", 2), "公開")
        sql.update("UPDATE quiz.choices SET is_correct = false WHERE quiz_id = ?", otherQuiz)

        assertThat(violations()).isEmpty()
        assertThat(checks.check(other.id)).extracting<UUID> { it.tenantId }.containsOnly(other.id)
    }
}
