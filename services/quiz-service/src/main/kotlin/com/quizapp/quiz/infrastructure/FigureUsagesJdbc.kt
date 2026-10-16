package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.FigureQuiz
import com.quizapp.quiz.domain.FigureReferences
import com.quizapp.quiz.domain.FigureUsage
import com.quizapp.quiz.domain.FigureUsages
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [FigureUsages] の実装。図を指しうる解説だけを読み、指している図は [FigureReferences] で拾う。
 *
 * テナントの問題は多くても数百で、解説を読んで数える。図の数だけ問い合わせない
 */
@Component
class FigureUsagesJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : FigureUsages {

    override fun usages(): Map<UUID, FigureUsage> {
        val rows = jdbcTemplate.query(
            """
            SELECT id, question, explanation, deleted_at IS NOT NULL AS trashed FROM quiz.quizzes
            WHERE explanation LIKE '%figure:%'
            ORDER BY created_at, id
            """,
            emptyMap<String, Any>(),
        ) { rs, _ ->
            Row(
                FigureQuiz(rs.getObject("id", UUID::class.java), rs.getString("question")),
                rs.getString("explanation"),
                rs.getBoolean("trashed"),
            )
        }

        val live = mutableMapOf<UUID, MutableList<FigureQuiz>>()
        val trashed = mutableMapOf<UUID, MutableList<FigureQuiz>>()
        for (row in rows) {
            val target = if (row.trashed) trashed else live
            FigureReferences.findIn(row.explanation).forEach { target.getOrPut(it) { mutableListOf() }.add(row.quiz) }
        }
        return (live.keys + trashed.keys).associateWith { FigureUsage(live[it].orEmpty(), trashed[it].orEmpty()) }
    }

    private data class Row(val quiz: FigureQuiz, val explanation: String, val trashed: Boolean)
}
