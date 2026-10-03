package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.Choice
import com.quizapp.quiz.domain.Quiz
import com.quizapp.quiz.domain.QuizStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

/**
 * クイズを何件もまとめて読む。**選択肢は、クイズの数によらず 1 本の SQL で読む**（DEV-112）。
 *
 * Spring Data JDBC の `@Query` で集約（[QuizEntity]）を読むと、選択肢をクイズ 1 件ごとに問い合わせる。
 * 管理の一覧で 60 問なら 61 本になり、負荷試験で Aurora の CPU を使い切る一因になっていた。
 * 1 件だけを読むとき（[QuizJdbcRepository.findActiveById]）と保存は、集約のまま Spring Data JDBC に任せる。
 */
@Component
class QuizListJdbc(private val jdbcTemplate: NamedParameterJdbcTemplate) {

    /**
     * 条件を省略できる検索。
     *
     * **パラメータには明示的なキャストが必要**。`:param IS NULL` だけでは
     * PostgreSQL がプレースホルダの型を決められず、
     * `could not determine data type of parameter` で実行に失敗する。
     */
    fun search(categoryId: UUID?, difficultyId: UUID?, status: String?): List<Quiz> = query(
        """
        SELECT * FROM quiz.quizzes
        WHERE deleted_at IS NULL
          AND (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
          AND (CAST(:difficultyId AS uuid) IS NULL OR difficulty_id = CAST(:difficultyId AS uuid))
          AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
        ORDER BY created_at
        """,
        mapOf("categoryId" to categoryId, "difficultyId" to difficultyId, "status" to status),
    )

    /**
     * 出題の候補を返す。**公開済みのみが対象**。
     *
     * 難易度を結合しているのは、レベルによる絞り込みのため。
     * `level` は一意ではないので、レベル 2 を指定すると SAA / DVA / SOA がすべて対象になる。
     * カテゴリを指定しなければカテゴリ横断の出題になる。
     */
    fun findPublishedCandidates(categoryId: UUID?, difficultyId: UUID?, level: Int?): List<Quiz> = query(
        """
        SELECT q.* FROM quiz.quizzes q
        JOIN quiz.difficulties d ON d.id = q.difficulty_id AND d.deleted_at IS NULL
        WHERE q.deleted_at IS NULL
          AND q.status = 'published'
          AND (CAST(:categoryId AS uuid) IS NULL OR q.category_id = CAST(:categoryId AS uuid))
          AND (CAST(:difficultyId AS uuid) IS NULL OR q.difficulty_id = CAST(:difficultyId AS uuid))
          AND (CAST(:level AS integer) IS NULL OR d.level = CAST(:level AS integer))
        ORDER BY d.level, d.sort_order, q.created_at
        """,
        mapOf("categoryId" to categoryId, "difficultyId" to difficultyId, "level" to level),
    )

    fun findPublishedByIds(ids: Collection<UUID>): List<Quiz> {
        // IN 句に空のリストを渡すと SQL が壊れる
        if (ids.isEmpty()) return emptyList()
        return query(
            "SELECT * FROM quiz.quizzes WHERE id IN (:ids) AND deleted_at IS NULL AND status = 'published'",
            mapOf("ids" to ids),
        )
    }

    /** クイズを読み、その選択肢をまとめて読んで組み立てる。クイズの並びは、[sql] の並びのまま */
    private fun query(sql: String, params: Map<String, Any?>): List<Quiz> {
        val rows = jdbcTemplate.query(sql, params) { rs, _ -> rs.toRow() }
        if (rows.isEmpty()) return emptyList()

        val choices = jdbcTemplate.query(
            """
            SELECT quiz_id, id, body, is_correct FROM quiz.choices
            WHERE quiz_id IN (:ids)
            ORDER BY quiz_id, sort_order
            """,
            mapOf("ids" to rows.map { it.id }),
        ) { rs, _ ->
            rs.getObject("quiz_id", UUID::class.java) to
                Choice(
                    id = rs.getObject("id", UUID::class.java),
                    body = rs.getString("body"),
                    isCorrect = rs.getBoolean("is_correct"),
                )
        }.groupBy({ it.first }, { it.second })

        return rows.map { it.toDomain(choices[it.id].orEmpty()) }
    }

    private fun ResultSet.toRow() = Row(
        id = getObject("id", UUID::class.java),
        categoryId = getObject("category_id", UUID::class.java),
        difficultyId = getObject("difficulty_id", UUID::class.java),
        question = getString("question"),
        explanation = getString("explanation"),
        status = getString("status"),
    )

    private data class Row(
        val id: UUID,
        val categoryId: UUID,
        val difficultyId: UUID,
        val question: String,
        val explanation: String,
        val status: String,
    ) {
        fun toDomain(choices: List<Choice>) = Quiz(
            id = id,
            categoryId = categoryId,
            difficultyId = difficultyId,
            question = question,
            explanation = explanation,
            choices = choices,
            status = QuizStatus.from(status),
        )
    }
}
