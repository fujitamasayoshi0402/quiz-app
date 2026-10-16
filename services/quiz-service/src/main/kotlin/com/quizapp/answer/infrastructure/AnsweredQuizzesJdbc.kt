package com.quizapp.answer.infrastructure

import com.quizapp.answer.domain.StudyDay
import com.quizapp.quiz.domain.AnswerTally
import com.quizapp.quiz.domain.AnsweredQuizzes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * [AnsweredQuizzes] の実装。answer モジュールが自分のテーブルだけを読む。
 *
 * quiz モジュールはこのクラスを知らない。インターフェース越しに使う。
 */
@Component
class AnsweredQuizzesJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate, private val clock: Clock) :
    AnsweredQuizzes {

    override fun filterAnswered(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID> {
        if (candidateQuizIds.isEmpty()) return emptySet()

        return jdbcTemplate.query(
            """
            SELECT DISTINCT quiz_id FROM answer.answers
            WHERE user_id = :userId AND quiz_id IN (:quizIds)
            """,
            mapOf("userId" to userId, "quizIds" to candidateQuizIds),
        ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) }
            .toSet()
    }

    override fun filterMissed(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID> {
        if (candidateQuizIds.isEmpty()) return emptySet()

        // クイズごとに最後の回答だけを残し、それが不正解のものを返す
        return jdbcTemplate.query(
            """
            SELECT quiz_id FROM (
                SELECT DISTINCT ON (quiz_id) quiz_id, is_correct FROM answer.answers
                WHERE user_id = :userId AND quiz_id IN (:quizIds)
                ORDER BY quiz_id, answered_at DESC
            ) latest
            WHERE NOT is_correct
            """,
            mapOf("userId" to userId, "quizIds" to candidateQuizIds),
        ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) }
            .toSet()
    }

    override fun filterBookmarked(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID> {
        if (candidateQuizIds.isEmpty()) return emptySet()

        return jdbcTemplate.query(
            "SELECT quiz_id FROM answer.bookmarks WHERE user_id = :userId AND quiz_id IN (:quizIds)",
            mapOf("userId" to userId, "quizIds" to candidateQuizIds),
        ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) }
            .toSet()
    }

    override fun filterDue(userId: UUID, candidateQuizIds: List<UUID>): Set<UUID> {
        if (candidateQuizIds.isEmpty()) return emptySet()

        return jdbcTemplate.query(
            """
            SELECT quiz_id FROM answer.review_schedules
            WHERE user_id = :userId AND quiz_id IN (:quizIds) AND due_on <= :today
            """,
            mapOf("userId" to userId, "quizIds" to candidateQuizIds, "today" to StudyDay.today(clock)),
        ) { rs, _ -> rs.getObject("quiz_id", UUID::class.java) }
            .toSet()
    }

    override fun tally(quizIds: List<UUID>): Map<UUID, AnswerTally> {
        if (quizIds.isEmpty()) return emptyMap()

        // テナントでは絞らない。行レベルセキュリティが、いまのテナントの回答だけを返す
        return jdbcTemplate.query(
            """
            SELECT quiz_id, count(*) AS answered, count(*) FILTER (WHERE is_correct) AS correct
            FROM answer.answers
            WHERE quiz_id IN (:quizIds)
            GROUP BY quiz_id
            """,
            mapOf("quizIds" to quizIds),
        ) { rs, _ ->
            rs.getObject("quiz_id", UUID::class.java) to
                AnswerTally(answered = rs.getInt("answered"), correct = rs.getInt("correct"))
        }.toMap()
    }

    override fun countChoices(quizId: UUID): Map<UUID, Int> = // tally と同じく、テナントでは絞らない。行レベルセキュリティが、いまのテナントの回答だけを返す
        jdbcTemplate.query(
            "SELECT choice_id, count(*) AS answered FROM answer.answers WHERE quiz_id = :quizId GROUP BY choice_id",
            mapOf("quizId" to quizId),
        ) { rs, _ -> rs.getObject("choice_id", UUID::class.java) to rs.getInt("answered") }
            .toMap()
}
