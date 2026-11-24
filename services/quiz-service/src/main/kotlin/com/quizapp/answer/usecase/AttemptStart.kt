package com.quizapp.answer.usecase

import com.quizapp.answer.domain.Attempt
import com.quizapp.answer.domain.AttemptRepository
import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.answer.domain.ConcurrentAttemptStartException
import com.quizapp.answer.domain.QuizCatalog
import com.quizapp.auth.UserContext
import com.quizapp.quiz.domain.DeliveredQuiz
import com.quizapp.quiz.domain.DeliveryCriteria
import com.quizapp.tenant.TenantTransaction
import java.util.UUID

/**
 * 挑戦を始める。[AttemptUseCase] から分けた。
 *
 * メモリで始める経路（DEV-240）と DB で始める経路の 2 本があり、どちらも中断中の挑戦の扱いを同じにする。
 */
class AttemptStart(
    private val repository: AttemptRepository,
    private val catalog: QuizCatalog,
    private val tenantTransaction: TenantTransaction,
    private val memory: AttemptMemory,
) {
    /**
     * 挑戦を始める。
     *
     * 中断中の挑戦があれば [AttemptInProgressException] を投げ、再開するか破棄するかを選ばせる。
     * 黙って破棄すると、解きかけの記録が予告なく消える。
     *
     * 同時に始めて先を越されたときも、先に始まった挑戦を同じように知らせる（DEV-226）。
     *
     * **DB を待たずに返せるときは、メモリで始める**（ADR-0038 の 2、DEV-240）。DB への保存は裏で続ける。
     * 中断中の挑戦をまだメモリに持っていないとき（起動の直後）と、選ぶのに回答が要る出題対象では、今どおり DB で始める
     */
    fun start(criteria: DeliveryCriteria, discardInProgress: Boolean): AttemptView {
        val userId = UserContext.require()
        return memory.locked(userId) {
            startInMemory(userId, criteria, discardInProgress) ?: startInDatabase(userId, criteria, discardInProgress)
        }
    }

    /** DB に触れずに始める。始められなければ null（DB で始める） */
    private fun startInMemory(userId: UUID, criteria: DeliveryCriteria, discardInProgress: Boolean): AttemptView? {
        val known = memory.known(userId) ?: return null
        // 中断中なら、候補を読む前に断る。候補をまだ持っていないと、読み込みで DB を待つ
        val existing = known.attempt
        if (existing != null && !discardInProgress) {
            throw AttemptInProgressException(
                AttemptSummary(requireNotNull(existing.id), existing.quizIds.size, known.answeredCount),
            )
        }
        return catalog.selectFromMemory(criteria)?.let { quizzes -> startWith(userId, criteria, existing, quizzes) }
    }

    /** [existing] があれば破棄して、[quizzes] で始める。保存は裏で行う */
    private fun startWith(
        userId: UUID,
        criteria: DeliveryCriteria,
        existing: Attempt?,
        quizzes: List<DeliveredQuiz>,
    ): AttemptView {
        if (quizzes.isEmpty()) throw NoQuizAvailableException()

        // ID はここで決める。保存を待たずに、出題の並び（[Attempt.arrange]）と応答に使う
        val attempt = Attempt.start(userId, criteria, quizzes.map { it.id }).copy(id = UUID.randomUUID())
        val attemptId = requireNotNull(attempt.id)
        memory.remember(userId, attempt)
        memory.save(userId, attemptId) {
            tenantTransaction.executeWithoutResult {
                existing?.let { repository.finish(requireNotNull(it.id), AttemptStatus.ABANDONED) }
                repository.create(attempt)
            }
        }
        return started(attempt, quizzes)
    }

    private fun startInDatabase(userId: UUID, criteria: DeliveryCriteria, discardInProgress: Boolean): AttemptView {
        memory.awaitSaves(userId)
        val (attempt, quizzes) = try {
            tenantTransaction.execute { createInDatabase(userId, criteria, discardInProgress) }
        } catch (e: ConcurrentAttemptStartException) {
            memory.forget(userId)
            throw raced(e)
        }
        memory.remember(userId, attempt)
        return started(attempt, quizzes)
    }

    private fun createInDatabase(
        userId: UUID,
        criteria: DeliveryCriteria,
        discardInProgress: Boolean,
    ): Pair<Attempt, List<DeliveredQuiz>> {
        repository.findInProgress(userId)?.let { existing ->
            if (!discardInProgress) {
                val summary = summarize(existing)
                memory.remember(userId, existing, summary.answeredCount)
                throw AttemptInProgressException(summary)
            }
            repository.finish(requireNotNull(existing.id), AttemptStatus.ABANDONED)
        }

        val quizzes = catalog.select(criteria, userId)
        if (quizzes.isEmpty()) throw NoQuizAvailableException()

        return repository.create(Attempt.start(userId, criteria, quizzes.map { it.id })) to quizzes
    }

    private fun started(attempt: Attempt, quizzes: List<DeliveredQuiz>) = AttemptView(
        id = requireNotNull(attempt.id),
        status = attempt.status.label(),
        scope = attempt.scope.label(),
        quizzes = quizzes.map(attempt::arrange),
        answeredQuizIds = emptyList(),
        excludedCount = 0,
    )

    /** 先を越された挑戦を知らせる。その間に先の挑戦が終わっていれば、知らせるものがないので元の例外を返す */
    private fun raced(e: ConcurrentAttemptStartException): RuntimeException =
        summarizeInProgress()?.let { AttemptInProgressException(it, e) } ?: e

    private fun summarizeInProgress(): AttemptSummary? = tenantTransaction.executeNullable {
        repository.findInProgress(UserContext.require())?.let(::summarize)
    }

    private fun summarize(attempt: Attempt) = AttemptSummary(
        id = requireNotNull(attempt.id),
        totalCount = attempt.quizIds.size,
        answeredCount = repository.findAnswers(requireNotNull(attempt.id)).size,
    )
}
