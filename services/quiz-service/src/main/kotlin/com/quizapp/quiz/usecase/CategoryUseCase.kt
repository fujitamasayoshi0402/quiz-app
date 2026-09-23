package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * カテゴリの操作。
 *
 * DB アクセスは必ず [TenantTransaction] を通す。
 * テナントの設定を忘れると行レベルセキュリティによって「0 件が返るだけ」になり、
 * 例外も出ないまま静かに壊れるため、経路を 1 つに絞っている。
 */
@Service
class CategoryUseCase(private val repository: CategoryRepository, private val tenantTransaction: TenantTransaction) {
    fun list(): List<Category> = tenantTransaction.execute { repository.findAll() }

    fun get(id: UUID): Category = tenantTransaction.execute {
        repository.findById(id) ?: throw CategoryNotFoundException(id)
    }

    fun create(name: String, description: String?, sortOrder: Int): Category = tenantTransaction.execute {
        repository.save(Category(name = name, description = description, sortOrder = sortOrder))
    }

    fun update(id: UUID, name: String, description: String?, sortOrder: Int): Category = tenantTransaction.execute {
        repository.findById(id) ?: throw CategoryNotFoundException(id)
        repository.save(
            Category(id = id, name = name, description = description, sortOrder = sortOrder),
        )
    }

    fun delete(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!repository.softDelete(id)) throw CategoryNotFoundException(id)
    }
}

class CategoryNotFoundException(val id: UUID) : RuntimeException("カテゴリが見つかりません: $id")
