package com.quizapp.quiz.infrastructure

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.CategorySummary
import com.quizapp.tenant.TenantContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CategoryRepositoryImpl(
    private val jdbcRepository: CategoryJdbcRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : CategoryRepository {

    override fun findAll(): List<Category> = jdbcRepository.findAllActive().map { it.toDomain() }

    /** クイズの数も 1 本の SQL で数える。カテゴリごとに問い合わせると、カテゴリ数に比例して SQL が増える */
    override fun findAllSummaries(): List<CategorySummary> = jdbcTemplate.query(
        """
        SELECT c.id, c.name, c.description, c.sort_order,
               count(q.id) AS quiz_count,
               count(q.id) FILTER (WHERE q.status = 'published') AS published_quiz_count
        FROM quiz.categories c
        LEFT JOIN quiz.quizzes q ON q.category_id = c.id AND q.deleted_at IS NULL
        WHERE c.deleted_at IS NULL
        GROUP BY c.id
        ORDER BY c.sort_order, c.name
        """,
    ) { rs, _ ->
        CategorySummary(
            category = Category(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                description = rs.getString("description"),
                sortOrder = rs.getInt("sort_order"),
            ),
            quizCount = rs.getInt("quiz_count"),
            publishedQuizCount = rs.getInt("published_quiz_count"),
        )
    }

    override fun reorder(ids: List<UUID>) {
        ids.forEachIndexed { index, id -> jdbcRepository.updateSortOrder(id, index) }
    }

    override fun findById(id: UUID): Category? = jdbcRepository.findActiveById(id)?.toDomain()

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

    private fun CategoryEntity.toDomain() =
        Category(id = id, name = name, description = description, sortOrder = sortOrder)
}
