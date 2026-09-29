package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.Category
import com.quizapp.quiz.domain.CategoryRepository
import com.quizapp.quiz.domain.CategorySummary
import com.quizapp.quiz.domain.DeletionImpact
import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.Ordering
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * カテゴリの操作。
 *
 * DB アクセスは必ず [TenantTransaction] を通す。
 * テナントの設定を忘れると行レベルセキュリティによって「0 件が返るだけ」になり、
 * 例外も出ないまま静かに壊れるため、経路を 1 つに絞っている。
 *
 * 並び順は [reorder] だけで変える（[Ordering]）。作ったカテゴリは末尾に置く。
 */
@Service
class CategoryUseCase(
    private val repository: CategoryRepository,
    private val deletion: DeletionRepository,
    private val tenantTransaction: TenantTransaction,
) {
    fun list(): List<CategorySummary> = tenantTransaction.execute { repository.findAllSummaries() }

    fun get(id: UUID): Category = tenantTransaction.execute {
        repository.findById(id) ?: throw CategoryNotFoundException(id)
    }

    fun create(name: String, description: String?): Category = tenantTransaction.execute {
        val sortOrder = Ordering.next(repository.findAll().map { it.sortOrder })
        repository.save(Category(name = name, description = description, sortOrder = sortOrder))
    }

    fun update(id: UUID, name: String, description: String?): Category = tenantTransaction.execute {
        val current = repository.findById(id) ?: throw CategoryNotFoundException(id)
        repository.save(current.copy(name = name, description = description))
    }

    /** 今あるカテゴリの ID を、並べたい順にすべて受け取る。1 つでも過不足があれば受け付けない */
    fun reorder(ids: List<UUID>) = tenantTransaction.executeWithoutResult {
        Ordering.requireSameItems(ids, repository.findAll().mapNotNull { it.id })
        repository.reorder(ids)
    }

    /** 削除したときに巻き込む範囲。画面が確認を出すために先に引く。 */
    fun deletionImpact(id: UUID): DeletionImpact = tenantTransaction.execute {
        deletion.impactOfCategory(id) ?: throw CategoryNotFoundException(id)
    }

    /** 配下の難易度とクイズも一緒に削除する（ADR-0007）。 */
    fun delete(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!deletion.deleteCategory(id)) throw CategoryNotFoundException(id)
    }
}

class CategoryNotFoundException(val id: UUID) : RuntimeException("カテゴリが見つかりません: $id")
