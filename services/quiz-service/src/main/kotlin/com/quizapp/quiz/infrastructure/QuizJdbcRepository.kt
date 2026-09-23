package com.quizapp.quiz.infrastructure

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface QuizJdbcRepository : CrudRepository<QuizEntity, UUID> {

    /**
     * 条件を省略できる検索。
     *
     * **パラメータには明示的なキャストが必要**。`:param IS NULL` だけでは
     * PostgreSQL がプレースホルダの型を決められず、
     * `could not determine data type of parameter` で実行に失敗する。
     */
    @Query(
        """
        SELECT * FROM quiz.quizzes
        WHERE deleted_at IS NULL
          AND (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
          AND (CAST(:difficultyId AS uuid) IS NULL OR difficulty_id = CAST(:difficultyId AS uuid))
          AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
        ORDER BY created_at
        """,
    )
    fun search(categoryId: UUID?, difficultyId: UUID?, status: String?): List<QuizEntity>

    @Query("SELECT * FROM quiz.quizzes WHERE id = :id AND deleted_at IS NULL")
    fun findActiveById(id: UUID): QuizEntity?

    @Modifying
    @Query("UPDATE quiz.quizzes SET deleted_at = now() WHERE id = :id AND deleted_at IS NULL")
    fun softDelete(id: UUID): Int
}
