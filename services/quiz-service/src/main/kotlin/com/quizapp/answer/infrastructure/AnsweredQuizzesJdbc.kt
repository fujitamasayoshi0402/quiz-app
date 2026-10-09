package com.quizapp.answer.infrastructure

import com.quizapp.quiz.domain.AnsweredQuizzes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [AnsweredQuizzes] の実装。answer モジュールが自分のテーブルだけを読む。
 *
 * quiz モジュールはこのクラスを知らない。インターフェース越しに使う。
 */
@Component
class AnsweredQuizzesJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : AnsweredQuizzes {

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
}
