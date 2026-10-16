package com.quizapp.support.fake

import com.quizapp.answer.domain.ReviewSchedule
import com.quizapp.answer.domain.ReviewScheduleRepository
import java.util.UUID

/** 利用者と問題ごとに、最後に保存した予定を覚える。テナントは区別しない（SQL の責務で、API テストが見る） */
class InMemoryReviewScheduleRepository : ReviewScheduleRepository {
    private val rows = mutableMapOf<Pair<UUID, UUID>, ReviewSchedule>()

    override fun find(userId: UUID, quizId: UUID): ReviewSchedule? = rows[userId to quizId]

    override fun save(userId: UUID, schedule: ReviewSchedule) {
        rows[userId to schedule.quizId] = schedule
    }

    override fun findAll(userId: UUID): List<ReviewSchedule> = rows.filterKeys { it.first == userId }.values.toList()
}
