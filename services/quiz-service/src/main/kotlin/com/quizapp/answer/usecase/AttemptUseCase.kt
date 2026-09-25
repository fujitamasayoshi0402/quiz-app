package com.quizapp.answer.usecase

import com.quizapp.answer.domain.Answer
import com.quizapp.answer.domain.Attempt
import com.quizapp.answer.domain.AttemptRepository
import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.auth.UserContext
import com.quizapp.quiz.domain.DeliveredChoice
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.quiz.domain.DeliveryScope
import com.quizapp.tenant.TenantTransaction
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 挑戦の開始・回答・完了。
 *
 * **採点はここで行う。** quiz モジュールからは「どれが正解か」という事実だけを受け取り、
 * 正誤の判定は answer 側の責務として持つ（ADR-0004）。
 */
@Service
class AttemptUseCase(
    private val repository: AttemptRepository,
    private val catalog: QuizCatalog,
    private val tenantTransaction: TenantTransaction,
) {
    /**
     * 挑戦を始める。
     *
     * 中断中の挑戦があれば [AttemptInProgressException] を投げ、再開するか破棄するかを選ばせる。
     * 黙って破棄すると、解きかけの記録が予告なく消える。
     */
    fun start(criteria: DeliveryCriteria, discardInProgress: Boolean): AttemptView = tenantTransaction.execute {
        val userId = UserContext.require()

        repository.findInProgress(userId)?.let { existing ->
            if (!discardInProgress) throw AttemptInProgressException(summarize(existing))
            repository.finish(requireNotNull(existing.id), AttemptStatus.ABANDONED)
        }

        val quizzes = catalog.select(criteria, userId)
        if (quizzes.isEmpty()) throw NoQuizAvailableException()

        val attempt = repository.create(Attempt.start(userId, criteria, quizzes.map { it.id }))
        AttemptView(
            id = requireNotNull(attempt.id),
            status = attempt.status.label(),
            scope = attempt.scope.label(),
            quizzes = quizzes.map(attempt::arrange),
            answeredQuizIds = emptyList(),
            excludedCount = 0,
        )
    }

    /** 中断中の挑戦。なければ null。 */
    fun current(): AttemptView? = tenantTransaction.executeNullable {
        repository.findInProgress(UserContext.require())?.let { view(it) }
    }

    fun resume(attemptId: UUID): AttemptView = tenantTransaction.execute { view(load(attemptId)) }

    /**
     * 1 問に回答する。
     *
     * 応答には正誤・正解・解説を含める。**出題方式によって変えない。**
     * まとめて見せる模試モードでは、クライアントが受け取った解説を保持して結果画面で出す。
     * 方式ごとに応答を変えると、採点の経路が 2 本になる。
     */
    fun answer(attemptId: UUID, quizId: UUID, choiceId: UUID): AnswerResult = tenantTransaction.execute {
        val attempt = load(attemptId)
        if (!attempt.isInProgress) throw AttemptAlreadyFinishedException()
        if (quizId !in attempt.quizIds) throw QuizNotInAttemptException()

        val key = catalog.findAnswerKeys(listOf(quizId))[quizId] ?: throw QuizNoLongerAvailableException()
        // 他のクイズの選択肢 ID を渡された場合と、出題後にクイズが編集された場合の両方をここで弾く
        if (choiceId !in key.choiceIds) throw InvalidChoiceException()

        repository.record(
            Answer(
                attemptId = attemptId,
                userId = attempt.userId,
                quizId = quizId,
                choiceId = choiceId,
                isCorrect = choiceId == key.correctChoiceId,
            ),
        )

        AnswerResult(
            isCorrect = choiceId == key.correctChoiceId,
            correctChoiceId = key.correctChoiceId,
            explanation = key.explanation,
            answeredCount = repository.findAnswers(attemptId).size,
            totalCount = attempt.quizIds.size,
        )
    }

    /**
     * 挑戦を終えて結果を返す。
     *
     * 完了済みの挑戦に対しては結果を返すだけにする。
     * 結果画面の再読み込みで失敗させる理由がない。
     */
    fun complete(attemptId: UUID): AttemptResult = tenantTransaction.execute {
        val attempt = load(attemptId)
        when (attempt.status) {
            AttemptStatus.ABANDONED -> throw AttemptAlreadyFinishedException()
            AttemptStatus.IN_PROGRESS -> repository.finish(attemptId, AttemptStatus.COMPLETED)
            AttemptStatus.COMPLETED -> Unit
        }
        result(attempt.copy(status = AttemptStatus.COMPLETED))
    }

    fun abandon(attemptId: UUID) = tenantTransaction.executeWithoutResult {
        val attempt = load(attemptId)
        if (!attempt.isInProgress) throw AttemptAlreadyFinishedException()
        repository.finish(attemptId, AttemptStatus.ABANDONED)
    }

    /**
     * 挑戦を読み込む。**他人の挑戦は「存在しない」として扱う。**
     *
     * 権限エラーと区別すると、ID の存在有無が分かってしまう。
     */
    private fun load(id: UUID): Attempt {
        val userId = UserContext.require()
        val attempt = repository.findById(id) ?: throw AttemptNotFoundException()
        if (attempt.userId != userId) throw AttemptNotFoundException()
        return attempt
    }

    /**
     * 出題リストを today の状態で引き直す。
     *
     * 削除・非公開になったクイズはここで落ちる。**件数が減りうる**ことを [AttemptView.excludedCount] で伝える。
     * 引き直すことで、クイズが編集されて選択肢 ID が変わっていても、
     * 再開したクライアントは最新の ID を受け取る。
     */
    private fun view(attempt: Attempt): AttemptView {
        val deliverable = catalog.findDeliverable(attempt.quizIds).associateBy { it.id }
        // findDeliverable は順序を保証しない。出題順は挑戦が持っている
        val quizzes = attempt.quizIds.mapNotNull { deliverable[it] }.map(attempt::arrange)

        return AttemptView(
            id = requireNotNull(attempt.id),
            status = attempt.status.label(),
            scope = attempt.scope.label(),
            quizzes = quizzes,
            answeredQuizIds = repository.findAnswers(requireNotNull(attempt.id)).map { it.quizId },
            excludedCount = attempt.quizIds.size - quizzes.size,
        )
    }

    private fun result(attempt: Attempt): AttemptResult {
        val attemptId = requireNotNull(attempt.id)
        val answers = repository.findAnswers(attemptId).associateBy { it.quizId }
        val quizzes = catalog.findDeliverable(attempt.quizIds).associateBy { it.id }
        val keys = catalog.findAnswerKeys(attempt.quizIds)

        val results = attempt.quizIds.mapNotNull { quizId ->
            val quiz = quizzes[quizId]?.let(attempt::arrange) ?: return@mapNotNull null
            val key = keys[quizId] ?: return@mapNotNull null
            QuizResult(
                quizId = quizId,
                question = quiz.question,
                choices = quiz.choices,
                selectedChoiceId = answers[quizId]?.choiceId,
                correctChoiceId = key.correctChoiceId,
                isCorrect = answers[quizId]?.isCorrect ?: false,
                explanation = key.explanation,
            )
        }

        return AttemptResult(
            id = attemptId,
            status = attempt.status.label(),
            totalCount = attempt.quizIds.size,
            answeredCount = answers.size,
            correctCount = answers.values.count { it.isCorrect },
            results = results,
        )
    }

    private fun AttemptStatus.label() = name.lowercase()

    private fun DeliveryScope.label() = name.lowercase()

    private fun summarize(attempt: Attempt) = AttemptSummary(
        id = requireNotNull(attempt.id),
        totalCount = attempt.quizIds.size,
        answeredCount = repository.findAnswers(requireNotNull(attempt.id)).size,
    )
}

/**
 * 回答中の挑戦。出題リストは正解も解説も持たない。
 *
 * [status] と [scope] を文字列で持つのは、API の表記を小文字に揃えるため。
 * クイズの `status` が `published` / `draft` を返しているので、ここだけ大文字にしない。
 */
data class AttemptView(
    val id: UUID,
    val status: String,
    val scope: String,
    val quizzes: List<DeliveredQuiz>,
    val answeredQuizIds: List<UUID>,
    val excludedCount: Int,
)

data class AnswerResult(
    // Kotlin の `isCorrect` は Java の getter 規約では `correct` と読まれる。
    // Jackson は Kotlin のプロパティ名で出すため、明示しないと定義と実際の JSON がずれる
    @get:Schema(name = "isCorrect")
    val isCorrect: Boolean,
    val correctChoiceId: UUID,
    val explanation: String,
    val answeredCount: Int,
    val totalCount: Int,
)

/** 結果画面。模試モードではここで全問の解説を見せる。 */
data class AttemptResult(
    val id: UUID,
    val status: String,
    val totalCount: Int,
    val answeredCount: Int,
    val correctCount: Int,
    val results: List<QuizResult>,
)

data class QuizResult(
    val quizId: UUID,
    val question: String,
    val choices: List<DeliveredChoice>,
    val selectedChoiceId: UUID?,
    val correctChoiceId: UUID,
    // Kotlin の `isCorrect` は Java の getter 規約では `correct` と読まれる。
    // Jackson は Kotlin のプロパティ名で出すため、明示しないと定義と実際の JSON がずれる
    @get:Schema(name = "isCorrect")
    val isCorrect: Boolean,
    val explanation: String,
)

data class AttemptSummary(val id: UUID, val totalCount: Int, val answeredCount: Int)

class AttemptInProgressException(val summary: AttemptSummary) : RuntimeException("中断中の挑戦があります: ${summary.id}")

class AttemptNotFoundException : RuntimeException("挑戦が見つかりません")

class AttemptAlreadyFinishedException : RuntimeException("この挑戦はすでに終了しています")

class NoQuizAvailableException : RuntimeException("条件に合うクイズがありません")

class QuizNotInAttemptException : RuntimeException("この挑戦で出題されていないクイズです")

class QuizNoLongerAvailableException : RuntimeException("このクイズは出題できなくなりました")

/**
 * 送られた選択肢が、そのクイズのものではない。
 *
 * 他のクイズの ID を渡された場合と、**出題後にクイズが編集されて選択肢 ID が変わった**場合を
 * 区別できない。メッセージは後者を想定して、再読み込みを促す形にしている。
 */
class InvalidChoiceException : RuntimeException("選択肢が正しくありません。クイズが編集された可能性があります。画面を読み込み直してください")
