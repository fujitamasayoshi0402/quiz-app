package com.quizapp.quiz.infrastructure

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
            SELECT question, explanation, deleted_at IS NOT NULL AS trashed FROM quiz.quizzes
            WHERE explanation LIKE '%figure:%'
            ORDER BY created_at, id
            """,
            emptyMap<String, Any>(),
        ) { rs, _ -> Triple(rs.getString("question"), rs.getString("explanation"), rs.getBoolean("trashed")) }

        val live = mutableMapOf<UUID, MutableList<String>>()
        val trashed = mutableMapOf<UUID, Int>()
        for ((question, explanation, inTrash) in rows) {
            for (id in FigureReferences.findIn(explanation)) {
                if (inTrash) trashed.merge(id, 1, Int::plus) else live.getOrPut(id) { mutableListOf() }.add(question)
            }
        }
        return (live.keys + trashed.keys).associateWith { FigureUsage(live[it].orEmpty(), trashed[it] ?: 0) }
    }
}
