package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.AnsweredQuizzes
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizRepository
import com.quizapp.quiz.tenant.TenantTransaction
import com.quizapp.quiz.tenant.UserContext
import org.springframework.stereotype.Service
import java.util.UUID

/** 出題の選び方。 */
enum class DeliveryMode {
    /** 条件に合うクイズをすべて出す */
    ALL,

    /** 条件に合うクイズから指定数をランダムに選ぶ */
    RANDOM,

    /** まだ回答していないクイズを優先して出す */
    UNANSWERED,
    ;

    companion object {
        fun from(value: String): DeliveryMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("出題モードは all / random / unanswered を指定してください: $value")
    }
}

/**
 * 出題。
 *
 * 返すクイズには**正解を含めない**。採点はサーバー側でのみ行う（DEV-22）。
 * 正解を落とす処理は [DeliveredQuiz] への変換で行い、
 * レスポンスの組み立てに任せない。うっかり漏らす経路を作らないため。
 */
@Service
class QuizDeliveryUseCase(
    private val quizRepository: QuizRepository,
    private val answeredQuizzes: AnsweredQuizzes,
    private val tenantTransaction: TenantTransaction,
) {
    fun deliver(
        categoryId: UUID?,
        difficultyId: UUID?,
        level: Int?,
        mode: DeliveryMode,
        limit: Int,
    ): List<DeliveredQuiz> {
        require(limit in 1..MAX_LIMIT) { "出題数は 1 以上 $MAX_LIMIT 以下で指定してください" }

        return tenantTransaction.execute {
            val candidates = quizRepository.findPublishedCandidates(categoryId, difficultyId, level)
            select(candidates, mode, limit).map(DeliveredQuiz::from)
        }
    }

    private fun select(candidates: List<Quiz>, mode: DeliveryMode, limit: Int): List<Quiz> = when (mode) {
        DeliveryMode.ALL -> candidates.take(limit)
        DeliveryMode.RANDOM -> candidates.shuffled().take(limit)
        DeliveryMode.UNANSWERED -> selectUnanswered(candidates, limit)
    }

    /**
     * 未回答を優先する。
     *
     * 未回答が足りない場合は回答済みで埋める。「もう解く問題がありません」と返すより、
     * 復習として出すほうが利用者の期待に近い。
     */
    private fun selectUnanswered(candidates: List<Quiz>, limit: Int): List<Quiz> {
        val userId = UserContext.require()
        val candidateIds = candidates.mapNotNull { it.id }
        val answered = answeredQuizzes.filterAnswered(userId, candidateIds)

        val (done, notYet) = candidates.partition { it.id in answered }
        return (notYet.shuffled() + done.shuffled()).take(limit)
    }

    companion object {
        const val MAX_LIMIT = 100
        const val DEFAULT_LIMIT = 10
    }
}

/**
 * 出題されたクイズ。**正解の情報を持たない。**
 *
 * ドメインの [Quiz] をそのまま返すと `isCorrect` が漏れる。
 * 型を分けることで、レスポンスの組み立てで気をつける必要をなくしている。
 */
data class DeliveredQuiz(
    val id: UUID,
    val categoryId: UUID,
    val difficultyId: UUID,
    val question: String,
    val choices: List<DeliveredChoice>,
) {
    companion object {
        fun from(quiz: Quiz) = DeliveredQuiz(
            id = requireNotNull(quiz.id),
            categoryId = quiz.categoryId,
            difficultyId = quiz.difficultyId,
            question = quiz.question,
            choices = quiz.choices.map { DeliveredChoice(requireNotNull(it.id), it.body) },
        )
    }
}

data class DeliveredChoice(
    val id: UUID,
    val body: String,
)
