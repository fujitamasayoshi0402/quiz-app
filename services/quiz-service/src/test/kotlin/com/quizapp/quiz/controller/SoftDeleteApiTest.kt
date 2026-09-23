package com.quizapp.quiz.controller

import com.quizapp.quiz.support.TestPostgres
import com.quizapp.support.PlayFixture
import com.quizapp.support.TestAuth
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
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 論理削除と復活の統合テスト。
 *
 * 重点は 3 つ。
 * - **連鎖削除**: 親を消したら、配下が一覧からも出題からも消えること
 * - **連鎖復活**: 一緒に消えたものだけが戻り、別の操作で消したものは戻らないこと
 * - **通常の取得 API が削除済みを返さないこと**（ADR-0007 の完了条件）
 */
@SpringBootTest
@AutoConfigureMockMvc
class SoftDeleteApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) = TestPostgres.configure(registry)

        private const val SLUG = "kilo"
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val tenant: UUID = UUID.fromString("aaaabbbb-cccc-dddd-eeee-ffff00001111")
    private val player: UUID = UUID.fromString("aaaabbbb-cccc-dddd-eeee-ffff00002222")

    private lateinit var fixture: PlayFixture
    private lateinit var category: UUID
    private lateinit var saa: UUID
    private lateinit var dva: UUID

    @BeforeEach
    fun setUp() {
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.tenants (id, slug, name) VALUES (?, '$SLUG', 'キロ')",
            tenant,
        )
        TestPostgres.adminJdbcTemplate.update(
            "INSERT INTO core.users (id, external_id, display_name) VALUES (?, 'trash-player', '回答者')",
            player,
        )
        TestAuth.ensureUsers()
        TestAuth.joinAsAdmin(tenant)
        TestAuth.join(tenant, player, "member")

        fixture = PlayFixture(mockMvc, objectMapper, SLUG)
        category = fixture.category("AWS")
        saa = fixture.difficulty(category, "SAA", 2)
        dva = fixture.difficulty(category, "DVA", 2)
    }

    @AfterEach
    fun tearDown() {
        val admin = TestPostgres.adminJdbcTemplate
        admin.update("DELETE FROM answer.answers WHERE user_id = ?", player)
        admin.update("DELETE FROM answer.attempts WHERE user_id = ?", player)
        admin.update("DELETE FROM quiz.choices WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.quizzes WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.difficulties WHERE tenant_id = ?", tenant)
        admin.update("DELETE FROM quiz.categories WHERE tenant_id = ?", tenant)
        TestAuth.leaveAll(tenant)
        admin.update("DELETE FROM core.tenants WHERE id = ?", tenant)
        admin.update("DELETE FROM core.users WHERE id = ?", player)
    }

    // --- 連鎖削除 -------------------------------------------------------------

    @Test
    @DisplayName("カテゴリを削除すると、配下の難易度とクイズも一覧から消える")
    fun deletingCategoryCascades() {
        fixture.quiz(category, saa, "問題 1")
        fixture.quiz(category, dva, "問題 2")

        deleteCategory(category).andExpect { status { isNoContent() } }

        get("/admin/categories").andExpect { jsonPath("$.length()") { value(0) } }
        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(0) } }
        // カテゴリごと消えているので、難易度は親から辿れない
        get("/admin/categories/$category/difficulties").andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("削除したカテゴリのクイズは出題されない")
    fun deletedCategoryQuizzesAreNotDelivered() {
        fixture.quiz(category, saa, "問題 1")

        deleteCategory(category).andExpect { status { isNoContent() } }

        // 連鎖削除が無いと、カテゴリだけ消えてクイズが出題され続ける
        mockMvc.post("/api/t/$SLUG/play/attempts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"scope":"all"}"""
            header("X-User-Id", player.toString())
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    @DisplayName("難易度を削除すると、その難易度のクイズも消える")
    fun deletingDifficultyCascadesToQuizzes() {
        fixture.quiz(category, saa, "SAA の問題")
        fixture.quiz(category, dva, "DVA の問題")

        deleteDifficulty(saa).andExpect { status { isNoContent() } }

        get("/admin/quizzes").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("DVA の問題") }
        }
        // カテゴリと、もう一方の難易度は残る
        get("/admin/categories").andExpect { jsonPath("$.length()") { value(1) } }
        get("/admin/categories/$category/difficulties").andExpect { jsonPath("$.length()") { value(1) } }
    }

    @Test
    @DisplayName("クイズを削除しても、カテゴリと難易度は残る")
    fun deletingQuizDoesNotTouchParents() {
        val quiz = fixture.quiz(category, saa, "問題 1")

        deleteQuiz(quiz).andExpect { status { isNoContent() } }

        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(0) } }
        get("/admin/categories").andExpect { jsonPath("$.length()") { value(1) } }
        get("/admin/categories/$category/difficulties").andExpect { jsonPath("$.length()") { value(2) } }
    }

    // --- 影響範囲 -------------------------------------------------------------

    @Test
    @DisplayName("削除前に巻き込む件数が分かる")
    fun deletionImpactIsReported() {
        repeat(3) { fixture.quiz(category, saa, "問題 $it") }

        get("/admin/categories/$category/deletion-impact").andExpect {
            status { isOk() }
            jsonPath("$.difficultyCount") { value(2) }
            jsonPath("$.quizCount") { value(3) }
        }
        get("/admin/categories/$category/difficulties/$saa/deletion-impact").andExpect {
            jsonPath("$.quizCount") { value(3) }
        }
    }

    @Test
    @DisplayName("配下が空でも影響範囲を返す")
    fun impactIsReportedEvenWhenEmpty() {
        val empty = fixture.category("空のカテゴリ")

        // 0 件でも確認を挟む。黙って消すと、消えたことに気づく機会が無い
        get("/admin/categories/$empty/deletion-impact").andExpect {
            status { isOk() }
            jsonPath("$.difficultyCount") { value(0) }
            jsonPath("$.quizCount") { value(0) }
        }
    }

    // --- 削除済み一覧 ---------------------------------------------------------

    @Test
    @DisplayName("削除したものが一覧に出る。親が削除済みの子は復活できないと示す")
    fun trashShowsWhatCanBeRestored() {
        fixture.quiz(category, saa, "問題 1")
        deleteCategory(category).andExpect { status { isNoContent() } }

        get("/admin/trash").andExpect {
            status { isOk() }
            jsonPath("$.categories.length()") { value(1) }
            jsonPath("$.categories[0].name") { value("AWS") }
            jsonPath("$.categories[0].restorable") { value(true) }
            jsonPath("$.difficulties.length()") { value(2) }
            // 親のカテゴリが消えているので、難易度だけを戻すことはできない
            jsonPath("$.difficulties[0].restorable") { value(false) }
            jsonPath("$.quizzes.length()") { value(1) }
            jsonPath("$.quizzes[0].restorable") { value(false) }
            jsonPath("$.quizzes[0].categoryName") { value("AWS") }
        }
    }

    // --- 連鎖復活 -------------------------------------------------------------

    @Test
    @DisplayName("カテゴリを復活させると、一緒に消えたものも戻る")
    fun restoringCategoryRestoresItsBatch() {
        fixture.quiz(category, saa, "問題 1")
        fixture.quiz(category, dva, "問題 2")
        deleteCategory(category).andExpect { status { isNoContent() } }

        restore("categories", category).andExpect { status { isNoContent() } }

        get("/admin/categories").andExpect { jsonPath("$.length()") { value(1) } }
        get("/admin/categories/$category/difficulties").andExpect { jsonPath("$.length()") { value(2) } }
        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(2) } }
        get("/admin/trash").andExpect {
            jsonPath("$.categories.length()") { value(0) }
            jsonPath("$.quizzes.length()") { value(0) }
        }
    }

    @Test
    @DisplayName("カテゴリより前に個別に消したクイズは、カテゴリを戻しても戻らない")
    fun quizDeletedBeforeCategoryStaysDeleted() {
        val deletedEarlier = fixture.quiz(category, saa, "先に消した問題")
        fixture.quiz(category, saa, "あとで巻き込まれた問題")

        deleteQuiz(deletedEarlier).andExpect { status { isNoContent() } }
        deleteCategory(category).andExpect { status { isNoContent() } }
        restore("categories", category).andExpect { status { isNoContent() } }

        // 意図して消したものが、親の復活で勝手に戻ってはいけない
        get("/admin/quizzes").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].question") { value("あとで巻き込まれた問題") }
        }
        get("/admin/trash").andExpect { jsonPath("$.quizzes.length()") { value(1) } }
    }

    @Test
    @DisplayName("個別に戻したあとで親を戻しても壊れない")
    fun restoringPartiallyThenParentIsSafe() {
        val quiz = fixture.quiz(category, saa, "問題 1")
        deleteCategory(category).andExpect { status { isNoContent() } }

        // 親が消えているうちは子だけを戻せない
        restore("quizzes", quiz).andExpect {
            status { isConflict() }
            jsonPath("$.obstacle") { value("parent_deleted") }
        }

        restore("categories", category).andExpect { status { isNoContent() } }
        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(1) } }

        // もう一度消して、クイズだけ戻してから親を戻す
        deleteCategory(category).andExpect { status { isNoContent() } }
        restore("categories", category).andExpect { status { isNoContent() } }
        deleteQuiz(quiz).andExpect { status { isNoContent() } }
        restore("quizzes", quiz).andExpect { status { isNoContent() } }

        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(1) } }
    }

    @Test
    @DisplayName("難易度を復活させても、カテゴリは巻き上げて戻さない")
    fun restoringDifficultyDoesNotRestoreCategory() {
        fixture.quiz(category, saa, "問題 1")
        deleteDifficulty(saa).andExpect { status { isNoContent() } }

        restore("difficulties", saa).andExpect { status { isNoContent() } }

        get("/admin/categories/$category/difficulties").andExpect { jsonPath("$.length()") { value(2) } }
        get("/admin/quizzes").andExpect { jsonPath("$.length()") { value(1) } }
    }

    @Test
    @DisplayName("同じ名前で作り直されていると復活できない")
    fun cannotRestoreWhenNameIsTaken() {
        deleteCategory(category).andExpect { status { isNoContent() } }

        // 部分ユニークインデックスなので、削除済みと同じ名前で作り直せる
        fixture.category("AWS")

        restore("categories", category).andExpect {
            status { isConflict() }
            jsonPath("$.obstacle") { value("name_taken") }
        }
    }

    @Test
    @DisplayName("存在しない ID の復活は 404")
    fun restoringUnknownIsNotFound() {
        restore("categories", UUID.randomUUID()).andExpect { status { isNotFound() } }
        restore("quizzes", UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    @DisplayName("生きているものは復活できない")
    fun cannotRestoreLivingItem() {
        restore("categories", category).andExpect { status { isNotFound() } }
    }

    // --- ヘルパー -------------------------------------------------------------

    private fun MockHttpServletRequestDsl.auth() {
        header("X-User-Id", TestAuth.ADMIN.toString())
    }

    private fun get(path: String): ResultActionsDsl = mockMvc.get("/api/t/$SLUG$path") { auth() }

    private fun restore(kind: String, id: UUID): ResultActionsDsl =
        mockMvc.post("/api/t/$SLUG/admin/trash/$kind/$id/restore") { auth() }

    private fun deleteCategory(id: UUID) = mockMvc.delete("/api/t/$SLUG/admin/categories/$id") { auth() }

    private fun deleteDifficulty(id: UUID) =
        mockMvc.delete("/api/t/$SLUG/admin/categories/$category/difficulties/$id") { auth() }

    private fun deleteQuiz(id: UUID) = mockMvc.delete("/api/t/$SLUG/admin/quizzes/$id") { auth() }
}
