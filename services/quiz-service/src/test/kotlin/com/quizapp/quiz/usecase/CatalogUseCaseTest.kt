package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.support.fake.InMemoryCategoryRepository
import com.quizapp.support.fake.InMemoryDifficultyRepository
import com.quizapp.support.fake.InMemoryQuizRepository
import com.quizapp.support.fake.RecordingDeletionRepository
import com.quizapp.support.fake.fakeTenantTransaction
import com.quizapp.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 難易度とクイズの操作で、**親子の組み合わせを確かめているか**。
 *
 * URL や入力でカテゴリと難易度を別々に受け取るため、組み合わせの食い違いが入口になる。
 * 確かめ忘れると、別カテゴリの難易度を自カテゴリのものとして操作できてしまう。
 */
class CatalogUseCaseTest {

    private val categories = InMemoryCategoryRepository()
    private val difficulties = InMemoryDifficultyRepository()
    private val quizzes = InMemoryQuizRepository()
    private val deletion = RecordingDeletionRepository()
    private val transaction = fakeTenantTransaction()

    private val difficultyUseCase = DifficultyUseCase(difficulties, categories, deletion, transaction)
    private val quizUseCase = QuizUseCase(quizzes, categories, difficulties, deletion, transaction)

    private lateinit var aws: UUID
    private lateinit var auth: UUID
    private lateinit var saa: UUID
    private lateinit var beginner: UUID

    @BeforeEach
    fun setUp() {
        TenantContext.set(UUID.randomUUID())
        aws = requireNotNull(categories.save(Category(name = "AWS")).id)
        auth = requireNotNull(categories.save(Category(name = "認証認可")).id)
        saa = requireNotNull(difficulties.save(Difficulty(categoryId = aws, name = "SAA", level = 2)).id)
        beginner = requireNotNull(difficulties.save(Difficulty(categoryId = auth, name = "初級", level = 1)).id)
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Nested
    @DisplayName("難易度")
    inner class Difficulties {
        @Test
        @DisplayName("別カテゴリの難易度は、URL のカテゴリ配下として取得・更新・削除できない")
        fun rejectsDifficultyOfAnotherCategory() {
            assertThatThrownBy { difficultyUseCase.get(auth, saa) }
                .isInstanceOf(DifficultyNotFoundException::class.java)
            assertThatThrownBy { difficultyUseCase.update(auth, saa, "書き換え", 1, 0, null) }
                .isInstanceOf(DifficultyNotFoundException::class.java)
            assertThatThrownBy { difficultyUseCase.delete(auth, saa) }
                .isInstanceOf(DifficultyNotFoundException::class.java)

            assertThat(difficulties.findById(saa)?.name).isEqualTo("SAA")
            assertThat(deletion.deleted).isEmpty()
        }

        @Test
        @DisplayName("存在しないカテゴリの一覧は、空ではなく「カテゴリが無い」として返す")
        fun unknownCategoryIsNotAnEmptyList() {
            assertThatThrownBy { difficultyUseCase.list(UUID.randomUUID()) }
                .isInstanceOf(CategoryNotFoundException::class.java)
        }

        @Test
        @DisplayName("カテゴリ配下に作る")
        fun createsUnderCategory() {
            val created = difficultyUseCase.create(aws, "DVA", 2, 1, null)

            assertThat(difficultyUseCase.list(aws).map { it.id }).contains(created.id)
            assertThat(created.categoryId).isEqualTo(aws)
        }
    }

    @Nested
    @DisplayName("クイズ")
    inner class Quizzes {
        private val choices = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }

        @Test
        @DisplayName("別カテゴリの難易度では作れない。理由を入力エラーとして返す")
        fun rejectsMismatchedDifficulty() {
            assertThatIllegalArgumentException()
                .isThrownBy { quizUseCase.create(aws, beginner, "問題", "解説", choices, QuizStatus.PUBLISHED) }
                .withMessage("指定された難易度はこのカテゴリのものではありません")
            assertThat(quizzes.search()).isEmpty()
        }

        @Test
        @DisplayName("存在しないカテゴリ・難易度は、それぞれ見つからないとして返す")
        fun unknownParents() {
            assertThatThrownBy { quizUseCase.create(UUID.randomUUID(), saa, "問題", "解説", choices, QuizStatus.DRAFT) }
                .isInstanceOf(CategoryNotFoundException::class.java)
            assertThatThrownBy { quizUseCase.create(aws, UUID.randomUUID(), "問題", "解説", choices, QuizStatus.DRAFT) }
                .isInstanceOf(DifficultyNotFoundException::class.java)
        }

        @Test
        @DisplayName("更新では、対象のクイズがあるかを先に確かめる")
        fun updateChecksQuizFirst() {
            // カテゴリも存在しないが、先に「クイズが無い」と分かる
            assertThatThrownBy {
                quizUseCase.update(UUID.randomUUID(), UUID.randomUUID(), saa, "問題", "解説", choices, QuizStatus.DRAFT)
            }.isInstanceOf(QuizNotFoundException::class.java)
        }

        @Test
        @DisplayName("公開の条件はドメインが守る。ユースケースを通しても崩せない")
        fun publishingRulesStillApply() {
            assertThatIllegalArgumentException()
                .isThrownBy { quizUseCase.create(aws, saa, "問題", "解説", choices.take(3), QuizStatus.PUBLISHED) }
            assertThat(quizzes.search()).isEmpty()
        }
    }
}
