package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.AttemptCursor
import com.quizapp.answer.domain.AttemptHistoryQuery
import com.quizapp.answer.domain.CompletedAttempt
import com.quizapp.quiz.domain.DeliveryScope
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [AttemptHistoryQuery] の実装。answer スキーマだけを読む。
 *
 * 表示のたびに集計する。1 人が 1 つのテナントで持つ挑戦と回答は、
 * 利用者の索引（`attempts_user_time_idx`、`answers_user_quiz_idx`）で絞れる件数にとどまる。
 * 集計した値を別に持つと、回答のたびに更新する経路と、ずれたときに直す手段が要る。
 */
@Component
class AttemptHistoryJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : AttemptHistoryQuery {

    override fun findCompleted(userId: UUID, after: AttemptCursor?, limit: Int): List<CompletedAttempt> {
        val params = mutableMapOf<String, Any>("userId" to userId, "limit" to limit)
        // 行の比較は ORDER BY と同じ並びで前後を決める。終えた時刻が同じなら ID で決まる
        val continuation = after?.let {
            params["afterFinishedAt"] = OffsetDateTime.ofInstant(it.finishedAt, ZoneOffset.UTC)
            params["afterId"] = it.id
            "AND (a.finished_at, a.id) < (:afterFinishedAt, :afterId)"
        }.orEmpty()

        return jdbcTemplate.query(
            """
            SELECT a.id, a.category_id, a.difficulty_id, a.level, a.scope, a.started_at, a.finished_at,
                   (SELECT count(*) FROM answer.attempt_quizzes q WHERE q.attempt_id = a.id) AS total_count,
                   count(ans.id) AS answered_count,
                   count(ans.id) FILTER (WHERE ans.is_correct) AS correct_count
            FROM answer.attempts a
            LEFT JOIN answer.answers ans ON ans.attempt_id = a.id
            WHERE a.user_id = :userId AND a.status = 'completed' $continuation
            GROUP BY a.id
            ORDER BY a.finished_at DESC, a.id DESC
            LIMIT :limit
            """,
            params,
        ) { rs, _ -> rs.toCompletedAttempt() }
    }

    override fun findLatestResults(userId: UUID): Map<UUID, Boolean> = jdbcTemplate.query(
        """
        SELECT DISTINCT ON (quiz_id) quiz_id, is_correct
        FROM answer.answers
        WHERE user_id = :userId
        ORDER BY quiz_id, answered_at DESC, id DESC
        """,
        mapOf("userId" to userId),
    ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) to rs.getBoolean("is_correct") }
        .toMap()

    private fun ResultSet.toCompletedAttempt() = CompletedAttempt(
        id = getObject("id", UUID::class.java),
        categoryId = getObject("category_id", UUID::class.java),
        difficultyId = getObject("difficulty_id", UUID::class.java),
        level = getInt("level").takeUnless { wasNull() },
        scope = DeliveryScope.from(getString("scope")),
        startedAt = getObject("started_at", OffsetDateTime::class.java).toInstant(),
        finishedAt = getObject("finished_at", OffsetDateTime::class.java).toInstant(),
        totalCount = getInt("total_count"),
        answeredCount = getInt("answered_count"),
        correctCount = getInt("correct_count"),
    )
}
