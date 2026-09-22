package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CategoryRepositoryImpl(
    private val jdbcRepository: CategoryJdbcRepository,
) : CategoryRepository {

    override fun findAll(): List<Category> = jdbcRepository.findAllActive().map { it.toDomain() }

    override fun findById(id: UUID): Category? = jdbcRepository.findActiveById(id)?.toDomain()

    override fun findAllIncludingDeleted(): List<Category> =
        jdbcRepository.findAllIncludingDeleted().map { it.toDomain() }

    override fun save(category: Category): Category {
        // テナントは呼び出し側から受け取らず、文脈から取る。
        // 引数で渡す形にすると、別テナントの ID を渡せる経路ができてしまう
        val tenantId = TenantContext.require()
        val entity = if (category.id == null) {
            CategoryEntity(
                tenantId = tenantId,
                name = category.name,
                description = category.description,
                sortOrder = category.sortOrder,
            )
        } else {
            val existing = jdbcRepository.findActiveById(category.id)
                ?: throw IllegalArgumentException("カテゴリが見つかりません: ${category.id}")
            existing.copy(
                name = category.name,
                description = category.description,
                sortOrder = category.sortOrder,
            )
        }
        return jdbcRepository.save(entity).toDomain()
    }

    override fun softDelete(id: UUID): Boolean = jdbcRepository.softDelete(id) > 0

    private fun CategoryEntity.toDomain() =
        Category(id = id, name = name, description = description, sortOrder = sortOrder)
}
