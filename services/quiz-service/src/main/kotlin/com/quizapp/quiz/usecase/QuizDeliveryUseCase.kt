package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.AnsweredQuizzes
import com.quizapp.quiz.domain.DeliveredChoice
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryOrder
import com.quizapp.quiz.domain.DeliveryScope
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 出題するクイズを選ぶ。
 *
 * トランザクションとテナントの設定は呼び出し側（挑戦のユースケース）が張る。
 * ここを独立したトランザクションにすると、挑戦の保存と出題の選択が別々にコミットされ、
 * 途中で失敗したときに「出題されたクイズが分からない挑戦」が残る。
 */
@Service
class QuizDeliveryUseCase(private val quizRepository: QuizRepository, private val answeredQuizzes: AnsweredQuizzes) {
    fun select(criteria: DeliveryCriteria, userId: UUID): List<DeliveredQuiz> {
        val candidates = quizRepository.findPublishedCandidates(
            criteria.categoryId,
            criteria.difficultyId,
            criteria.level,
        )
        return narrow(candidates, criteria, userId)
            .take(criteria.limit)
            .map(::toDelivered)
    }

    /** 再開時に使う。削除・非公開になったクイズはここで落ちる。 */
    fun findDeliverable(quizIds: List<UUID>): List<DeliveredQuiz> =
        quizRepository.findPublishedByIds(quizIds).map(::toDelivered)

    /**
     * 出題対象で絞り、並びを決める。
     *
     * 未回答優先では**未回答と回答済みをそれぞれ並べてから連結する**。
     * 全体を並べ替えてしまうと、未回答を先に出すという指定が効かなくなる。
     */
    private fun narrow(candidates: List<Quiz>, criteria: DeliveryCriteria, userId: UUID): List<Quiz> {
        if (criteria.scope == DeliveryScope.ALL) return order(candidates, criteria.order)

        val answered = answeredQuizzes.filterAnswered(userId, candidates.mapNotNull { it.id })
        val (done, notYet) = candidates.partition { it.id in answered }

        return when (criteria.scope) {
            DeliveryScope.UNANSWERED_ONLY -> order(notYet, criteria.order)
            else -> order(notYet, criteria.order) + order(done, criteria.order)
        }
    }

    private fun order(quizzes: List<Quiz>, order: DeliveryOrder): List<Quiz> = when (order) {
        // 候補はリポジトリがレベル順・並び順・作成順で返している
        DeliveryOrder.REGISTERED -> quizzes

        DeliveryOrder.RANDOM -> quizzes.shuffled()
    }

    private fun toDelivered(quiz: Quiz) = DeliveredQuiz(
        id = requireNotNull(quiz.id),
        categoryId = quiz.categoryId,
        difficultyId = quiz.difficultyId,
        question = quiz.question,
        choices = quiz.choices.map { DeliveredChoice(requireNotNull(it.id), it.body) },
    )
}
