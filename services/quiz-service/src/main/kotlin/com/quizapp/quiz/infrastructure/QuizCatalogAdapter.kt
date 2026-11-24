package com.quizapp.quiz.infrastructure

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.quiz.domain.AnswerKey
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryScope
import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableCategoryQuery
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.usecase.PlayableContentCache
import com.quizapp.quiz.usecase.QuizDeliveryUseCase
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [QuizCatalog] の実装。quiz モジュールが自分のデータだけを読む。
 *
 * answer モジュールはこのクラスを知らない。インターフェース越しに使う。
 * 逆向き（[com.quizapp.quiz.domain.AnsweredQuizzes]）の実装は、answer モジュールが持つ。
 */
@Component
class QuizCatalogAdapter(
    private val deliveryUseCase: QuizDeliveryUseCase,
    private val quizRepository: QuizRepository,
    private val playableCategories: PlayableCategoryQuery,
    private val content: PlayableContentCache,
) : QuizCatalog {

    override fun select(criteria: DeliveryCriteria, userId: UUID): List<DeliveredQuiz> =
        deliveryUseCase.select(criteria, userId)

    /** 候補は、出題の範囲ごとにメモリに持つ。まだ持っていなければ、ここで DB から読む（DEV-240） */
    override fun selectFromMemory(criteria: DeliveryCriteria): List<DeliveredQuiz>? {
        if (!content.enabled || criteria.scope != DeliveryScope.ALL) return null
        val candidates = content.candidates(criteria.categoryId, criteria.difficultyId, criteria.level)
        return deliveryUseCase.selectFrom(candidates, criteria)
    }

    override fun findDeliverable(quizIds: List<UUID>): List<DeliveredQuiz> = deliveryUseCase.findDeliverable(quizIds)

    /**
     * 採点に必要な情報を返す。
     *
     * 正解を持たないクイズは返さない。公開済みのクイズは必ず正解を 1 つ持つ不変条件があるが、
     * ここで落としておけば、その前提が崩れても正解なしのまま採点されることはない。
     */
    override fun findAnswerKeys(quizIds: List<UUID>): Map<UUID, AnswerKey> =
        quizRepository.findPublishedByIds(quizIds).mapNotNull { quiz ->
            val correctIds = quiz.choices.filter { it.isCorrect }.mapNotNull { it.id }
            val firstCorrect = correctIds.firstOrNull() ?: return@mapNotNull null
            val id = quiz.id ?: return@mapNotNull null
            id to AnswerKey(
                quizId = id,
                correctChoiceId = firstCorrect,
                correctChoiceIds = correctIds.toSet(),
                choiceIds = quiz.choices.mapNotNull { it.id }.toSet(),
                explanation = quiz.explanation,
                sourceUrl = quiz.sourceUrl,
            )
        }.toMap()

    override fun findPlayableCategories(): List<PlayableCategory> = playableCategories.findAll()

    override fun findCategoryIds(quizIds: Collection<UUID>): Map<UUID, UUID> =
        playableCategories.findCategoryIds(quizIds)

    override fun findPlayableQuizIds(): Set<UUID> = playableCategories.findQuizIds()
}
