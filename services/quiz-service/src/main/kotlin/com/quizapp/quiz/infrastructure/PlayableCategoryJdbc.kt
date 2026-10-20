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
 * 件数の集計と代表の図を 1 本の SQL で行う。カテゴリごとに問い合わせると、カテゴリ数に比例して SQL が増える。
 * 内部結合なので、公開済みのクイズがない難易度とカテゴリは結果に現れない。
 *
 * 代表の図は、解説の最初の `figure:<ID>` を見る。テナントにない図を指していれば（シードや手で流した SQL）、その問題は飛ばす
 */
@Component
class PlayableCategoryJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) : PlayableCategoryQuery {

    override fun findAll(): List<PlayableCategory> = jdbcTemplate.query(
        """
        WITH cover AS (
            -- 易しい難易度から登録順に見て、最初に図を指している公開済みの問題の、最初の図
            SELECT DISTINCT ON (q.category_id) q.category_id, f.id AS figure_id
            FROM quiz.quizzes q
            JOIN quiz.difficulties d ON d.id = q.difficulty_id AND d.deleted_at IS NULL
            JOIN quiz.figures f ON f.id = CAST(substring(q.explanation FROM '$FIGURE_REFERENCE') AS uuid)
            WHERE q.deleted_at IS NULL AND q.status = 'published'
            ORDER BY q.category_id, d.level, d.sort_order, q.created_at
        )
        SELECT c.id AS category_id, c.name AS category_name, c.description AS category_description,
               cover.figure_id AS cover_figure_id,
               d.id AS difficulty_id, d.name AS difficulty_name, d.level, d.description AS difficulty_description,
               count(q.id) AS quiz_count
        FROM quiz.categories c
        JOIN quiz.difficulties d ON d.category_id = c.id AND d.deleted_at IS NULL
        JOIN quiz.quizzes q ON q.difficulty_id = d.id AND q.deleted_at IS NULL AND q.status = 'published'
        LEFT JOIN cover ON cover.category_id = c.id
        WHERE c.deleted_at IS NULL
        GROUP BY c.id, d.id, cover.figure_id
        ORDER BY c.sort_order, c.name, d.level, d.sort_order, d.name
        """,
    ) { rs, _ ->
        Row(
            category = CategoryKey(
                id = rs.getObject("category_id", UUID::class.java),
                name = rs.getString("category_name"),
                description = rs.getString("category_description"),
                coverFigureId = rs.getObject("cover_figure_id", UUID::class.java),
            ),
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
        .groupBy { it.category }
        .map { (category, rows) ->
            PlayableCategory(
                id = category.id,
                name = category.name,
                description = category.description,
                difficulties = rows.map { it.difficulty },
                coverFigureId = category.coverFigureId,
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

    override fun findQuizIds(): Set<UUID> = jdbcTemplate.query(
        """
        SELECT q.id
        FROM quiz.quizzes q
        JOIN quiz.categories c ON c.id = q.category_id AND c.deleted_at IS NULL
        JOIN quiz.difficulties d ON d.id = q.difficulty_id AND d.deleted_at IS NULL
        WHERE q.deleted_at IS NULL AND q.status = 'published'
        """,
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        .toSet()

    private data class CategoryKey(val id: UUID, val name: String, val description: String?, val coverFigureId: UUID?)

    private data class Row(val category: CategoryKey, val difficulty: PlayableDifficulty)

    private companion object {
        /**
         * 解説の中の図の参照。[com.quizapp.quiz.domain.FigureReferences] と同じ形を、PostgreSQL の正規表現で書く（`(?i)` は大文字と小文字を区別しない）。
         * 形まで決めるのは、崩れたものに当てて uuid への変換で SQL ごと落ちないため
         */
        const val FIGURE_REFERENCE =
            "(?i)figure:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![0-9a-f-])"
    }
}
