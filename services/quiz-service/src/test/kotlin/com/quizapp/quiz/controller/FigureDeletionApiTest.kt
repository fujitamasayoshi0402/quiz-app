package com.quizapp.quiz.controller

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
 * 図の一覧と、使われている図を消させない決まり（DEV-165）。
 *
 * - 一覧は、図ごとに使っている問題の数を、公開中・下書きとゴミ箱に分けて返す
 * - 公開中・下書きの問題が使っている図は消せない
 * - ゴミ箱の問題だけが使う図は消せる。その問題は、1 件でも、難易度・カテゴリと一緒でも、戻せなくなる
 */
@SpringBootTest
@AutoConfigureMockMvc
class FigureDeletionApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var tenant: TestTenant
    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var difficulty: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create("図を消すテナント").withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
    }

    private fun quizUsing(figure: UUID, question: String, status: String = "published"): UUID =
        fixture.quiz(category, difficulty, question, status, explanation = "解説\n\n![図](figure:$figure)")

    private fun list(): JsonNode = mockMvc.get("/api/t/${tenant.slug}/admin/figures") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let(objectMapper::readTree)

    private fun deleteFigure(id: UUID): ResultActionsDsl = mockMvc.delete("/api/t/${tenant.slug}/admin/figures/$id") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }

    private fun trash(path: String) = mockMvc.delete("/api/t/${tenant.slug}/admin/$path") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }.andExpect { status { isNoContent() } }

    private fun restore(path: String): ResultActionsDsl =
        mockMvc.post("/api/t/${tenant.slug}/admin/trash/$path/restore") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }

    private fun ResultActionsDsl.blockedByMissingFigure() = andExpect {
        status { isConflict() }
        jsonPath("$.obstacle") { value("missing_figure") }
    }

    @Test
    @DisplayName("一覧は新しい順で、使っている問題の数を、公開中・下書きとゴミ箱に分けて返す")
    fun listsFiguresWithUsage() {
        val older = fixture.figure()
        val newer = fixture.figure()
        quizUsing(older, "公開中の問題")
        quizUsing(older, "下書きの問題", status = "draft")
        trash("quizzes/${quizUsing(older, "ゴミ箱の問題")}")

        val figures = list()

        assertThat(figures.ids()).containsExactly(newer.toString(), older.toString())
        assertThat(figures[0]["kind"].asString()).isEqualTo("drawio")
        assertThat(figures[0]["quizCount"].asInt()).isZero()
        assertThat(figures[1]["quizCount"].asInt()).isEqualTo(2)
        assertThat(figures[1]["trashedQuizCount"].asInt()).isEqualTo(1)
    }

    @Test
    @DisplayName("公開中か下書きの問題が使っている図は消せない。使っている問題を返す")
    fun refusesToDeleteFigureInUse() {
        val figure = fixture.figure()
        quizUsing(figure, "下書きで使っている問題", status = "draft")

        deleteFigure(figure).andExpect {
            status { isConflict() }
            jsonPath("$.quizzes[0]") { value("下書きで使っている問題") }
        }
        assertThat(list().ids()).contains(figure.toString())
    }

    @Test
    @DisplayName("どの問題も使っていない図は消せる")
    fun deletesUnusedFigure() {
        val figure = fixture.figure()

        deleteFigure(figure).andExpect { status { isNoContent() } }

        assertThat(list().ids()).doesNotContain(figure.toString())
    }

    @Test
    @DisplayName("ゴミ箱の問題だけが使う図は消せる。その問題は戻せなくなる")
    fun deletesFigureUsedOnlyInTrash() {
        val figure = fixture.figure()
        val quiz = quizUsing(figure, "ゴミ箱へ移す問題")
        trash("quizzes/$quiz")

        deleteFigure(figure).andExpect { status { isNoContent() } }

        restore("quizzes/$quiz").blockedByMissingFigure()
    }

    @Test
    @DisplayName("難易度と一緒にゴミ箱へ移した問題も、図を消したら戻せない。難易度も戻らない")
    fun blocksDifficultyRestore() {
        val figure = fixture.figure()
        quizUsing(figure, "難易度と一緒に消える問題")
        trash("categories/$category/difficulties/$difficulty")
        deleteFigure(figure).andExpect { status { isNoContent() } }

        restore("difficulties/$difficulty").blockedByMissingFigure()

        assertThat(trashed("difficulties")).contains(difficulty.toString())
    }

    @Test
    @DisplayName("カテゴリと一緒にゴミ箱へ移した問題も、図を消したら戻せない。カテゴリも戻らない")
    fun blocksCategoryRestore() {
        val figure = fixture.figure()
        quizUsing(figure, "カテゴリと一緒に消える問題")
        trash("categories/$category")
        deleteFigure(figure).andExpect { status { isNoContent() } }

        restore("categories/$category").blockedByMissingFigure()

        assertThat(trashed("categories")).contains(category.toString())
    }

    private fun trashed(kind: String): List<String> = mockMvc.get("/api/t/${tenant.slug}/admin/trash") {
        header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
    }.andReturn().response.contentAsString.let(objectMapper::readTree)[kind].ids()

    @Test
    @DisplayName("図が残っていれば、ゴミ箱の問題は戻せる")
    fun restoresWhenFigureRemains() {
        val figure = fixture.figure()
        val quiz = quizUsing(figure, "戻せる問題")
        trash("quizzes/$quiz")

        restore("quizzes/$quiz").andExpect { status { isNoContent() } }
    }
}

/** 配列の各要素の `id`。Jackson 3 の JsonNode には、コレクションの map がそのまま使えない */
private fun JsonNode.ids(): List<String> = (0 until size()).map { this[it]["id"].asString() }
