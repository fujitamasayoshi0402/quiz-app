package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryOrder
import com.quizapp.quiz.domain.DeliveryScope
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizStatus
import com.quizapp.support.fake.FakeAnsweredQuizzes
import com.quizapp.support.fake.InMemoryQuizRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 出題の選び方。出題対象・並び・出題数の組み合わせを確かめる。
 *
 * 候補の絞り込み（カテゴリ・難易度・レベル）と並べ替えは SQL の責務なので、ここでは扱わない。
 */
class QuizDeliveryUseCaseTest {

    private val quizzes = InMemoryQuizRepository()
    private val answered = FakeAnsweredQuizzes()
    private val useCase = QuizDeliveryUseCase(quizzes, answered)

    private val user = UUID.randomUUID()
    private val category = UUID.randomUUID()
    private val difficulty = UUID.randomUUID()

    private fun save(question: String, status: QuizStatus = QuizStatus.PUBLISHED): UUID {
        val choices = (1..4).map { Choice(body = "選択肢 $it", isCorrect = it == 1) }
        return requireNotNull(quizzes.save(Quiz(null, category, difficulty, question, "解説", choices, status)).id)
    }

    /** 登録順に並べた候補と、そのうち未回答・回答済みのもの */
    private data class Candidates(val all: List<UUID>, val unanswered: List<UUID>, val answered: List<UUID>)

    /** q1〜q4 を登録し、q1 と q3 を回答済みにする */
    private fun fourQuizzesHalfAnswered(): Candidates {
        val ids = (1..4).map { save("q$it") }
        answered.answer(user, ids[0])
        answered.answer(user, ids[2])
        return Candidates(all = ids, unanswered = listOf(ids[1], ids[3]), answered = listOf(ids[0], ids[2]))
    }

    private fun select(
        scope: DeliveryScope = DeliveryScope.ALL,
        order: DeliveryOrder = DeliveryOrder.REGISTERED,
        limit: Int = DeliveryCriteria.MAX_QUIZ_COUNT,
    ) = useCase.select(DeliveryCriteria(scope = scope, order = order, limit = limit), user).map { it.id }

    @Test
    @DisplayName("下書きは出題しない")
    fun excludesDrafts() {
        val published = save("公開")
        save("下書き", QuizStatus.DRAFT)

        assertThat(select()).containsExactly(published)
    }

    @Test
    @DisplayName("すべて: 回答済みも含めて出す")
    fun allIncludesAnswered() {
        val candidates = fourQuizzesHalfAnswered()

        assertThat(select()).containsExactlyElementsOf(candidates.all)
    }

    @Test
    @DisplayName("未回答を優先: 未回答を先に、回答済みをあとに並べる")
    fun unansweredFirst() {
        val candidates = fourQuizzesHalfAnswered()

        assertThat(select(DeliveryScope.UNANSWERED))
            .containsExactlyElementsOf(candidates.unanswered + candidates.answered)
    }

    @RepeatedTest(5)
    @DisplayName("未回答を優先: ランダムでも、未回答が先に来る")
    fun unansweredFirstEvenWhenShuffled() {
        val candidates = fourQuizzesHalfAnswered()

        val selected = select(DeliveryScope.UNANSWERED, DeliveryOrder.RANDOM)

        // 全体を混ぜると、未回答を先に出すという指定が効かなくなる
        assertThat(selected.take(2)).containsExactlyInAnyOrderElementsOf(candidates.unanswered)
        assertThat(selected.drop(2)).containsExactlyInAnyOrderElementsOf(candidates.answered)
    }

    @Test
    @DisplayName("未回答を優先: 出題数で切るのは並べたあと。未回答が残る")
    fun limitAppliesAfterOrdering() {
        val candidates = fourQuizzesHalfAnswered()

        assertThat(select(DeliveryScope.UNANSWERED, limit = 2)).containsExactlyElementsOf(candidates.unanswered)
    }

    @Test
    @DisplayName("未回答のみ: 回答済みで埋めず、尽きたらそこで終わる")
    fun unansweredOnly() {
        val candidates = fourQuizzesHalfAnswered()

        assertThat(select(DeliveryScope.UNANSWERED_ONLY, limit = 10)).containsExactlyElementsOf(candidates.unanswered)
    }

    @Test
    @DisplayName("他の利用者の回答は、未回答かどうかに影響しない")
    fun otherUsersAnswersDoNotCount() {
        val ids = (1..2).map { save("q$it") }
        answered.answer(UUID.randomUUID(), ids[0])

        assertThat(select(DeliveryScope.UNANSWERED_ONLY)).containsExactlyElementsOf(ids)
    }

    @Test
    @DisplayName("再開用の引き直しでは、非公開に戻したクイズを落とす")
    fun findDeliverableExcludesUnpublished() {
        val kept = save("公開のまま")
        val unpublished = save("非公開に戻す")
        quizzes.save(requireNotNull(quizzes.findById(unpublished)).copy(status = QuizStatus.DRAFT))

        assertThat(useCase.findDeliverable(listOf(kept, unpublished)).map { it.id }).containsExactly(kept)
    }
}
