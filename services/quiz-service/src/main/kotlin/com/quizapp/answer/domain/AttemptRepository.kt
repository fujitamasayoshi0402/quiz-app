package com.quizapp.answer.domain

import java.util.UUID

interface AttemptRepository {

    fun create(attempt: Attempt): Attempt

    fun findById(id: UUID): Attempt?

    /** 中断中の挑戦。1 ユーザーにつき最大 1 件（部分ユニークインデックスで保証）。 */
    fun findInProgress(userId: UUID): Attempt?

    /** 挑戦を終える。すでに終わっていれば false。 */
    fun finish(id: UUID, status: AttemptStatus): Boolean

    /** 同じ挑戦で同じクイズに二度答えた場合は [DuplicateAnswerException]。 */
    fun record(answer: Answer): Answer

    fun findAnswers(attemptId: UUID): List<Answer>
}

class DuplicateAnswerException(quizId: UUID) : RuntimeException("このクイズにはすでに回答しています: $quizId")
