package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.Difficulty
import com.quizapp.quiz.domain.DifficultyRepository
import com.quizapp.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DifficultyRepositoryImpl(
    private val jdbcRepository: DifficultyJdbcRepository,
) : DifficultyRepository {

    override fun findByCategoryId(categoryId: UUID): List<Difficulty> =
        jdbcRepository.findActiveByCategoryId(categoryId).map { it.toDomain() }

    override fun findById(id: UUID): Difficulty? = jdbcRepository.findActiveById(id)?.toDomain()

    override fun save(difficulty: Difficulty): Difficulty {
        val tenantId = TenantContext.require()
        val entity = if (difficulty.id == null) {
            DifficultyEntity(
                tenantId = tenantId,
                categoryId = difficulty.categoryId,
                name = difficulty.name,
                level = difficulty.level,
                sortOrder = difficulty.sortOrder,
                description = difficulty.description,
            )
        } else {
            val existing = jdbcRepository.findActiveById(difficulty.id)
                ?: throw IllegalArgumentException("難易度が見つかりません: ${difficulty.id}")
            existing.copy(
                name = difficulty.name,
                level = difficulty.level,
                sortOrder = difficulty.sortOrder,
                description = difficulty.description,
            )
        }
        return jdbcRepository.save(entity).toDomain()
    }

    override fun softDelete(id: UUID): Boolean = jdbcRepository.softDelete(id) > 0

    private fun DifficultyEntity.toDomain() = Difficulty(
        id = id,
        categoryId = categoryId,
        name = name,
        level = level,
        sortOrder = sortOrder,
        description = description,
    )
}
