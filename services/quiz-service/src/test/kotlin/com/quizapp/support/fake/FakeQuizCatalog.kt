package com.quizapp.support.fake

import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.quiz.domain.AnswerKey
import com.quizapp.quiz.domain.DeliveredChoice
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import java.util.UUID

/**
 * 出題できるクイズの一覧を持つ [QuizCatalog]。
 *
 * 出題の選び方（絞り込み・並び）は quiz 側の責務なので、ここでは登録順に出題数まで返すだけにする。
 * [findDeliverable] は**逆順で返す**。本物は順序を保証しないため、
 * 呼び出し側が戻り値の順序に頼っていればテストで分かるようにしている。
 */
class FakeQuizCatalog : QuizCatalog {

    private val quizzes = linkedMapOf<UUID, DeliveredQuiz>()
    private val keys = mutableMapOf<UUID, AnswerKey>()

    /** 出題できるクイズを足す。**先頭の選択肢が正解。** */
    fun add(question: String = "問題"): DeliveredQuiz {
        val id = UUID.randomUUID()
        val choices = (1..CHOICES).map { DeliveredChoice(UUID.randomUUID(), "$question の選択肢 $it") }
        val quiz = DeliveredQuiz(id, UUID.randomUUID(), UUID.randomUUID(), question, choices)
        quizzes[id] = quiz
        keys[id] = AnswerKey(id, choices.first().id, choices.map { it.id }.toSet(), "$question の解説")
        return quiz
    }

    /** 削除・非公開にする。出題も採点もできなくなる */
    fun withdraw(quizId: UUID) {
        quizzes.remove(quizId)
        keys.remove(quizId)
    }

    /** 選択肢を作り直す。管理画面での編集にあたり、選択肢の ID が変わる */
    fun edit(quizId: UUID): DeliveredQuiz {
        val quiz = requireNotNull(quizzes[quizId])
        val choices = quiz.choices.map { it.copy(id = UUID.randomUUID()) }
        val edited = quiz.copy(choices = choices)
        quizzes[quizId] = edited
        keys[quizId] = requireNotNull(keys[quizId]).copy(
            correctChoiceId = choices.first().id,
            choiceIds = choices.map { it.id }.toSet(),
        )
        return edited
    }

    override fun select(criteria: DeliveryCriteria, userId: UUID): List<DeliveredQuiz> =
        quizzes.values.take(criteria.limit)

    override fun findDeliverable(quizIds: List<UUID>): List<DeliveredQuiz> =
        quizIds.mapNotNull { quizzes[it] }.reversed()

    override fun findAnswerKeys(quizIds: List<UUID>): Map<UUID, AnswerKey> =
        quizIds.mapNotNull { keys[it] }.associateBy { it.quizId }

    private companion object {
        const val CHOICES = 4
    }
}
