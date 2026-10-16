package com.quizapp.answer.domain

import java.util.UUID

/** 利用者ごとの、問題を次に出す日。回答を受けた挑戦と同じトランザクションで書く */
interface ReviewScheduleRepository {
    fun find(userId: UUID, quizId: UUID): ReviewSchedule?

    fun save(userId: UUID, schedule: ReviewSchedule)

    fun findAll(userId: UUID): List<ReviewSchedule>
}
