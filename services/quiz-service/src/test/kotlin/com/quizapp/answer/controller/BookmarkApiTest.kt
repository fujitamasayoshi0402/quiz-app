package com.quizapp.answer.controller

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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 「あとで見直す」印の統合テスト（DEV-159）。
 *
 * - 付け外しが何度呼んでも同じ結果になること
 * - 付けられるのは、いま出題できるクイズだけであること。一覧も、いま出題できるものに絞ること
 * - 印は本人のものだけが効くこと（一覧と出題の両方）
 *
 * テナントの境界は [com.quizapp.tenant.TenantBoundaryApiTest] が見る。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookmarkApiTest {

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
    private lateinit var user: UUID

    @BeforeEach
    fun setUp() {
        tenant = TestTenant.create().withAdmin()
        fixture = PlayFixture(mockMvc, objectMapper, tenant.slug)
        category = fixture.category("AWS")
        difficulty = fixture.difficulty(category, "SAA", 2)
        user = TestAuth.createUser().also { tenant.join(it) }
    }

    @Test
    @DisplayName("付けた印が一覧に出て、外すと消える。どちらも何度呼んでも同じ")
    fun addAndRemoveAreIdempotent() {
        val quiz = quiz("問題")

        add(quiz).andExpect { status { isNoContent() } }
        add(quiz).andExpect { status { isNoContent() } }
        assertThat(list()).containsExactly(quiz)
        // 設定画面が範囲ごとに数えるため、カテゴリと難易度も返す
        mockMvc.get("/api/t/${tenant.slug}/play/bookmarks") {
            header("Authorization", TestAuth.bearer(user))
        }.andExpect {
            jsonPath("$.items[0].categoryId") { value(category.toString()) }
            jsonPath("$.items[0].difficultyId") { value(difficulty.toString()) }
        }

        remove(quiz).andExpect { status { isNoContent() } }
        remove(quiz).andExpect { status { isNoContent() } }
        assertThat(list()).isEmpty()
    }

    @Test
    @DisplayName("いま出題できないクイズ（下書き、存在しない）には付けられない")
    fun rejectsUndeliverableQuiz() {
        val draft = fixture.quiz(category, difficulty, "下書き", status = "draft")

        add(draft).andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("指定されたクイズは存在しません") }
        }
        add(UUID.randomUUID()).andExpect { status { isNotFound() } }
        assertThat(list()).isEmpty()
    }

    @Test
    @DisplayName("付けたあとで削除されたクイズは、一覧に出ない")
    fun hidesDeletedQuiz() {
        val kept = quiz("残る")
        val deleted = quiz("消す")
        add(kept)
        add(deleted)

        mockMvc.delete("/api/t/${tenant.slug}/admin/quizzes/$deleted") {
            header("Authorization", TestAuth.bearer(TestAuth.ADMIN))
        }.andExpect { status { isNoContent() } }

        assertThat(list()).containsExactly(kept)
    }

    @Test
    @DisplayName("ほかの利用者の印は、一覧にも出題にも効かない")
    fun othersBookmarksDoNotCount() {
        val quiz = quiz("問題")
        val other = TestAuth.createUser().also { tenant.join(it) }
        add(quiz, other)

        assertThat(list()).isEmpty()
        start(user).andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    @DisplayName("出題対象 bookmarked は、印の付いた問題だけを出す")
    fun deliversBookmarkedOnly() {
        val marked = quiz("見直す")
        quiz("見直さない")
        add(marked)

        start(user).andExpect {
            status { isCreated() }
            jsonPath("$.scope") { value("bookmarked") }
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].id") { value(marked.toString()) }
        }
    }

    private fun quiz(question: String): UUID = fixture.quiz(category, difficulty, question)

    private fun add(quizId: UUID, userId: UUID = user): ResultActionsDsl =
        mockMvc.put("/api/t/${tenant.slug}/play/bookmarks/$quizId") {
            header("Authorization", TestAuth.bearer(userId))
        }

    private fun remove(quizId: UUID): ResultActionsDsl =
        mockMvc.delete("/api/t/${tenant.slug}/play/bookmarks/$quizId") {
            header("Authorization", TestAuth.bearer(user))
        }

    private fun list(): List<UUID> = mockMvc.get("/api/t/${tenant.slug}/play/bookmarks") {
        header("Authorization", TestAuth.bearer(user))
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        .let { objectMapper.readTree(it)["items"] }
        .toList().map { UUID.fromString(it["quizId"].asString()) }

    private fun start(userId: UUID): ResultActionsDsl = mockMvc.post("/api/t/${tenant.slug}/play/attempts") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"scope":"bookmarked"}"""
        header("Authorization", TestAuth.bearer(userId))
    }
}
