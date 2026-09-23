package com.quizapp.quiz.infrastructure

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Spring Data JDBC のリポジトリ。
 *
 * テナントによる絞り込みは書いていない。行レベルセキュリティが DB 側で効くため
 * （[ADR-0006](../../../../../../../docs/adr/0006-row-level-multi-tenancy.md)）。
 * 一方、**論理削除の条件は明示的に書く**。DB は削除済みを隠さない。
 */
@Repository
interface CategoryJdbcRepository : CrudRepository<CategoryEntity, UUID> {

    @Query("SELECT * FROM quiz.categories WHERE deleted_at IS NULL ORDER BY sort_order, name")
    fun findAllActive(): List<CategoryEntity>

    @Query("SELECT * FROM quiz.categories WHERE id = :id AND deleted_at IS NULL")
    fun findActiveById(id: UUID): CategoryEntity?
}
