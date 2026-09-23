package com.quizapp.quiz.infrastructure

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DifficultyJdbcRepository : CrudRepository<DifficultyEntity, UUID> {

    /** レベルの昇順、同一レベル内は並び順で返す。 */
    @Query(
        """
        SELECT * FROM quiz.difficulties
        WHERE category_id = :categoryId AND deleted_at IS NULL
        ORDER BY level, sort_order, name
        """,
    )
    fun findActiveByCategoryId(categoryId: UUID): List<DifficultyEntity>

    @Query("SELECT * FROM quiz.difficulties WHERE id = :id AND deleted_at IS NULL")
    fun findActiveById(id: UUID): DifficultyEntity?
}
