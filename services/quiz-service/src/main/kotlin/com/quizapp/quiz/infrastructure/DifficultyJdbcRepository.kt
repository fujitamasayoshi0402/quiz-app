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

    /** カテゴリの並び順が先。画面はカテゴリごとに分けて使う */
    @Query(
        """
        SELECT d.* FROM quiz.difficulties d
        JOIN quiz.categories c ON c.id = d.category_id AND c.deleted_at IS NULL
        WHERE d.deleted_at IS NULL
        ORDER BY c.sort_order, c.name, d.level, d.sort_order, d.name
        """,
    )
    fun findAllActive(): List<DifficultyEntity>

    @Query("SELECT * FROM quiz.difficulties WHERE id = :id AND deleted_at IS NULL")
    fun findActiveById(id: UUID): DifficultyEntity?

    @Modifying
    @Query("UPDATE quiz.difficulties SET sort_order = :sortOrder WHERE id = :id AND deleted_at IS NULL")
    fun updateSortOrder(id: UUID, sortOrder: Int): Int
}
