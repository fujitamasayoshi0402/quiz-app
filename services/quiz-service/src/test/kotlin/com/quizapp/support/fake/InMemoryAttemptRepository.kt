package com.quizapp.support.fake

import com.quizapp.answer.domain.Answer
import com.quizapp.answer.domain.Attempt
import com.quizapp.answer.domain.AttemptRepository
import com.quizapp.answer.domain.AttemptStatus
import com.quizapp.answer.domain.DuplicateAnswerException
import java.time.Instant
import java.util.UUID

/**
 * メモリ上の [AttemptRepository]。
 *
 * テナントは区別しない。テナントの分離は DB（行レベルセキュリティ）の責務で、API テストが確かめる。
 * 同じクイズへの二重回答は、本物の一意制約と同じく [DuplicateAnswerException] にする。
 */
class InMemoryAttemptRepository : AttemptRepository {

    private val attempts = linkedMapOf<UUID, Attempt>()
    private val answers = mutableListOf<Answer>()

    override fun create(attempt: Attempt): Attempt {
        val saved = attempt.copy(id = UUID.randomUUID(), startedAt = Instant.now())
        attempts[requireNotNull(saved.id)] = saved
        return saved
    }

    override fun findById(id: UUID): Attempt? = attempts[id]

    override fun findInProgress(userId: UUID): Attempt? =
        attempts.values.firstOrNull { it.userId == userId && it.isInProgress }

    override fun finish(id: UUID, status: AttemptStatus): Boolean {
        val attempt = attempts[id] ?: return false
        attempts[id] = attempt.copy(status = status, finishedAt = Instant.now())
        return true
    }

    override fun record(answer: Answer): Answer {
        if (answers.any { it.attemptId == answer.attemptId && it.quizId == answer.quizId }) {
            throw DuplicateAnswerException(answer.quizId)
        }
        val saved = answer.copy(id = UUID.randomUUID(), answeredAt = Instant.now())
        answers += saved
        return saved
    }

    override fun findAnswers(attemptId: UUID): List<Answer> = answers.filter { it.attemptId == attemptId }
}
