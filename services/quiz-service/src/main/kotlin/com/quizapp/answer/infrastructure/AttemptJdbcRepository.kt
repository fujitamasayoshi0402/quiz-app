package com.quizapp.answer.infrastructure

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AttemptJdbcRepository : CrudRepository<AttemptEntity, UUID> {

    @Query("SELECT * FROM answer.attempts WHERE user_id = :userId AND status = 'in_progress'")
    fun findInProgress(userId: UUID): AttemptEntity?

    /**
     * 挑戦を終える。
     *
     * `finished_at` を同時に設定する。CHECK 制約が
     * 「終了した挑戦は必ず終了時刻を持つ」ことを求めるため、片方だけの更新は通らない。
     */
    @Modifying
    @Query(
        """
        UPDATE answer.attempts SET status = :status, finished_at = now()
        WHERE id = :id AND status = 'in_progress'
        """,
    )
    fun finish(id: UUID, status: String): Int
}

@Repository
interface AnswerJdbcRepository : CrudRepository<AnswerEntity, UUID> {

    @Query("SELECT * FROM answer.answers WHERE attempt_id = :attemptId ORDER BY answered_at")
    fun findByAttemptId(attemptId: UUID): List<AnswerEntity>
}
