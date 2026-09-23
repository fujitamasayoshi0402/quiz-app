package com.quizapp.quiz.infrastructure

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.quiz.domain.AnswerKey
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.usecase.QuizDeliveryUseCase
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [QuizCatalog] の実装。quiz モジュールが自分のデータだけを読む。
 *
 * answer モジュールはこのクラスを知らない。インターフェース越しに使う。
 * [com.quizapp.answer.infrastructure.AnsweredQuizzesJdbc] と対になる、逆向きの実装。
 */
@Component
class QuizCatalogAdapter(
    private val deliveryUseCase: QuizDeliveryUseCase,
    private val quizRepository: QuizRepository,
) : QuizCatalog {

    override fun select(criteria: DeliveryCriteria, userId: UUID): List<DeliveredQuiz> =
        deliveryUseCase.select(criteria, userId)

    override fun findDeliverable(quizIds: List<UUID>): List<DeliveredQuiz> =
        deliveryUseCase.findDeliverable(quizIds)

    /**
     * 採点に必要な情報を返す。
     *
     * 正解を持たないクイズは返さない。公開済みのクイズは必ず正解を 1 つ持つ不変条件があるが、
     * ここで落としておけば、その前提が崩れても正解なしのまま採点されることはない。
     */
    override fun findAnswerKeys(quizIds: List<UUID>): Map<UUID, AnswerKey> =
        quizRepository.findPublishedByIds(quizIds).mapNotNull { quiz ->
            val correct = quiz.choices.firstOrNull { it.isCorrect } ?: return@mapNotNull null
            val id = quiz.id ?: return@mapNotNull null
            id to AnswerKey(
                quizId = id,
                correctChoiceId = requireNotNull(correct.id),
                choiceIds = quiz.choices.mapNotNull { it.id }.toSet(),
                explanation = quiz.explanation,
            )
        }.toMap()
}
