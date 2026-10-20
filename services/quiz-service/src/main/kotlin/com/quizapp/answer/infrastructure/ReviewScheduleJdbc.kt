package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.ReviewSchedule
import com.quizapp.answer.domain.ReviewScheduleRepository
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

@Component
class ReviewScheduleJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : ReviewScheduleRepository {

    override fun find(userId: UUID, quizId: UUID): ReviewSchedule? = jdbcTemplate.query(
        "SELECT * FROM answer.review_schedules WHERE user_id = :userId AND quiz_id = :quizId",
        mapOf("userId" to userId, "quizId" to quizId),
    ) { rs, _ -> rs.toSchedule() }.singleOrNull()

    override fun save(userId: UUID, schedule: ReviewSchedule) {
        jdbcTemplate.update(
            """
            INSERT INTO answer.review_schedules (tenant_id, user_id, quiz_id, repetitions, interval_days, ease, due_on)
            VALUES (:tenantId, :userId, :quizId, :repetitions, :intervalDays, :ease, :dueOn)
            ON CONFLICT (tenant_id, user_id, quiz_id) DO UPDATE SET
                repetitions = excluded.repetitions,
                interval_days = excluded.interval_days,
                ease = excluded.ease,
                due_on = excluded.due_on,
                updated_at = now()
            """,
            mapOf(
                "tenantId" to TenantContext.require(),
                "userId" to userId,
                "quizId" to schedule.quizId,
                "repetitions" to schedule.repetitions,
                "intervalDays" to schedule.intervalDays,
                "ease" to schedule.ease,
                "dueOn" to schedule.dueOn,
            ),
        )
    }

    override fun findAll(userId: UUID): List<ReviewSchedule> = jdbcTemplate.query(
        "SELECT * FROM answer.review_schedules WHERE user_id = :userId",
        mapOf("userId" to userId),
    ) { rs, _ -> rs.toSchedule() }

    private fun ResultSet.toSchedule() = ReviewSchedule(
        quizId = getObject("quiz_id", UUID::class.java),
        repetitions = getInt("repetitions"),
        intervalDays = getInt("interval_days"),
        ease = getDouble("ease"),
        dueOn = getObject("due_on", LocalDate::class.java),
    )
}
