package com.quizapp.answer.usecase

import com.quizapp.answer.domain.AttemptCursor
import com.quizapp.auth.CurrentUser
import com.quizapp.auth.UserContext
import com.quizapp.support.fake.FakeAttemptHistory
import com.quizapp.support.fake.FakeQuizCatalog
import com.quizapp.support.fake.fakeTenantTransaction
import com.quizapp.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 履歴の単体テスト。
 *
 * 回答をカテゴリに振り分けて数えることと、一覧の区切り方を確かめる。
 * 本人の記録だけを返すこと、クイズごとに最新の回答を選ぶことは SQL の責務なので API テストが見る。
 */
class HistoryUseCaseTest {

    private val history = FakeAttemptHistory()
    private val catalog = FakeQuizCatalog()
    private val useCase = HistoryUseCase(history, catalog, fakeTenantTransaction())

    @BeforeEach
    fun setUp() {
        TenantContext.set(UUID.randomUUID())
        UserContext.set(CurrentUser(UUID.randomUUID()))
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        UserContext.clear()
    }

    @Nested
    @DisplayName("カテゴリ別の正答率")
    inner class Categories {
        private val aws = catalog.category("AWS")
        private val saa = catalog.difficulty(aws, "SAA")
        private val auth = catalog.category("認証")
        private val basics = catalog.difficulty(auth, "基礎")

        @Test
        @DisplayName("最新の回答の正誤を、クイズの属するカテゴリごとに数える")
        fun countsByCategory() {
            val q1 = catalog.add("AWS 1", saa)
            val q2 = catalog.add("AWS 2", saa)
            catalog.add("AWS 3", saa)
            val q4 = catalog.add("認証 1", basics)
            history.latestResults += mapOf(q1.id to true, q2.id to false, q4.id to true)

            assertThat(useCase.categories()).containsExactly(
                CategoryScore(aws, "AWS", quizCount = 3, answeredCount = 2, correctCount = 1),
                CategoryScore(auth, "認証", quizCount = 1, answeredCount = 1, correctCount = 1),
            )
        }

        @Test
        @DisplayName("回答していないカテゴリも、解いた数 0 で返す")
        fun includesUnansweredCategories() {
            catalog.add("AWS 1", saa)

            assertThat(useCase.categories())
                .containsExactly(CategoryScore(aws, "AWS", quizCount = 1, answeredCount = 0, correctCount = 0))
        }

        @Test
        @DisplayName("削除・非公開にしたクイズへの回答は数えない")
        fun excludesWithdrawnQuizzes() {
            val kept = catalog.add("残る問題", saa)
            val withdrawn = catalog.add("消える問題", saa)
            history.latestResults += mapOf(kept.id to true, withdrawn.id to false)

            catalog.withdraw(withdrawn.id)

            assertThat(useCase.categories())
                .containsExactly(CategoryScore(aws, "AWS", quizCount = 1, answeredCount = 1, correctCount = 1))
        }

        @Test
        @DisplayName("出題できるクイズがなくなったカテゴリは返さない")
        fun excludesCategoriesWithoutPlayableQuizzes() {
            val only = catalog.add("唯一の問題", basics)
            catalog.add("AWS 1", saa)
            history.latestResults[only.id] = true

            catalog.withdraw(only.id)

            assertThat(useCase.categories().map { it.categoryId }).containsExactly(aws)
        }
    }

    @Nested
    @DisplayName("完了した挑戦の一覧")
    inner class Attempts {

        @Test
        @DisplayName("1 ページに収まれば、続きの位置を返さない")
        fun singlePage() {
            repeat(HistoryUseCase.PAGE_SIZE) { history.complete(finishedAt = minutesAgo(it)) }

            val page = useCase.attempts(after = null)

            assertThat(page.items).hasSize(HistoryUseCase.PAGE_SIZE)
            assertThat(page.nextCursor).isNull()
        }

        @Test
        @DisplayName("続きの位置から、重複も抜けもなく残りを取れる")
        fun continuesFromCursor() {
            val all = (0..HistoryUseCase.PAGE_SIZE).map { history.complete(finishedAt = minutesAgo(it)) }

            val first = useCase.attempts(after = null)
            val second = useCase.attempts(after = AttemptCursor.decode(requireNotNull(first.nextCursor)))

            assertThat(first.items).hasSize(HistoryUseCase.PAGE_SIZE)
            assertThat(second.nextCursor).isNull()
            assertThat(first.items.map { it.id } + second.items.map { it.id }).isEqualTo(all.map { it.id })
        }

        @Test
        @DisplayName("カテゴリと難易度の名前を付ける。いま出題されていないものは null")
        fun namesCategoryAndDifficulty() {
            val aws = catalog.category("AWS")
            val saa = catalog.difficulty(aws, "SAA")
            catalog.add("AWS 1", saa)
            val removed = catalog.category("削除したカテゴリ")

            history.complete(finishedAt = minutesAgo(0), categoryId = aws, difficultyId = saa)
            history.complete(finishedAt = minutesAgo(1), categoryId = removed)
            history.complete(finishedAt = minutesAgo(2))

            val items = useCase.attempts(after = null).items

            assertThat(items.map { Triple(it.categoryId, it.categoryName, it.difficultyName) }).containsExactly(
                Triple(aws, "AWS", "SAA"),
                Triple(removed, null, null),
                Triple(null, null, null),
            )
        }
    }

    @Nested
    @DisplayName("続きの位置")
    inner class Cursor {
        @Test
        @DisplayName("文字列にして戻すと、同じ位置になる")
        fun roundTrip() {
            val cursor = AttemptCursor(Instant.parse("2026-09-26T01:02:03.456789Z"), UUID.randomUUID())

            assertThat(AttemptCursor.decode(cursor.encode())).isEqualTo(cursor)
        }

        @Test
        @DisplayName("壊れた値は入力の誤りとして扱う")
        fun rejectsBrokenValue() {
            listOf("", "not-base64!", AttemptCursor(Instant.now(), UUID.randomUUID()).encode().drop(4)).forEach {
                assertThatThrownBy { AttemptCursor.decode(it) }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    private fun minutesAgo(minutes: Int): Instant = BASE.minusSeconds(minutes * 60L)

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-26T00:00:00Z")
    }
}
