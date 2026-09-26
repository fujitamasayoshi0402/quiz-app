package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableCategoryQuery
import com.quizapp.quiz.domain.PlayableDifficulty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [PlayableCategoryQuery] の実装。
 *
 * 件数の集計を 1 本の SQL で行う。カテゴリごとに問い合わせると、カテゴリ数に比例して SQL が増える。
 * 内部結合なので、公開済みのクイズがない難易度とカテゴリは結果に現れない。
 */
@Component
class PlayableCategoryJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : PlayableCategoryQuery {

    override fun findAll(): List<PlayableCategory> = jdbcTemplate.query(
        """
        SELECT c.id AS category_id, c.name AS category_name, c.description AS category_description,
               d.id AS difficulty_id, d.name AS difficulty_name, d.level, d.description AS difficulty_description,
               count(q.id) AS quiz_count
        FROM quiz.categories c
        JOIN quiz.difficulties d ON d.category_id = c.id AND d.deleted_at IS NULL
        JOIN quiz.quizzes q ON q.difficulty_id = d.id AND q.deleted_at IS NULL AND q.status = 'published'
        WHERE c.deleted_at IS NULL
        GROUP BY c.id, d.id
        ORDER BY c.sort_order, c.name, d.level, d.sort_order, d.name
        """,
    ) { rs, _ ->
        Row(
            categoryId = rs.getObject("category_id", UUID::class.java),
            categoryName = rs.getString("category_name"),
            categoryDescription = rs.getString("category_description"),
            difficulty = PlayableDifficulty(
                id = rs.getObject("difficulty_id", UUID::class.java),
                name = rs.getString("difficulty_name"),
                level = rs.getInt("level"),
                description = rs.getString("difficulty_description"),
                quizCount = rs.getInt("quiz_count"),
            ),
        )
    }
        // groupBy は最初に現れた順を保つので、SQL の並びがそのまま残る
        .groupBy { Triple(it.categoryId, it.categoryName, it.categoryDescription) }
        .map { (category, rows) ->
            PlayableCategory(
                id = category.first,
                name = category.second,
                description = category.third,
                difficulties = rows.map { it.difficulty },
            )
        }

    override fun findCategoryIds(quizIds: Collection<UUID>): Map<UUID, UUID> {
        // IN 句に空のリストを渡すと SQL が壊れる
        if (quizIds.isEmpty()) return emptyMap()

        return jdbcTemplate.query(
            """
            SELECT q.id, q.category_id
            FROM quiz.quizzes q
            JOIN quiz.categories c ON c.id = q.category_id AND c.deleted_at IS NULL
            JOIN quiz.difficulties d ON d.id = q.difficulty_id AND d.deleted_at IS NULL
            WHERE q.id IN (:quizIds) AND q.deleted_at IS NULL AND q.status = 'published'
            """,
            mapOf("quizIds" to quizIds),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("category_id", UUID::class.java) }
            .toMap()
    }

    private data class Row(
        val categoryId: UUID,
        val categoryName: String,
        val categoryDescription: String?,
        val difficulty: PlayableDifficulty,
    )
}
