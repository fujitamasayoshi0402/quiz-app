package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.DeletionRepository
import com.quizapp.quiz.domain.Trash
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 削除済みの一覧と復活。
 *
 * **削除済みを扱う操作をここに集めている。** 通常の取得は [CategoryUseCase] などが担い、
 * そちらからは削除済みが返らない（ADR-0009 の命名で区別する方針）。
 */
@Service
class TrashUseCase(private val deletion: DeletionRepository, private val tenantTransaction: TenantTransaction) {
    fun list(): Trash = tenantTransaction.execute { deletion.listDeleted() }

    fun restoreCategory(id: UUID) = tenantTransaction.executeWithoutResult { deletion.restoreCategory(id) }

    fun restoreDifficulty(id: UUID) = tenantTransaction.executeWithoutResult { deletion.restoreDifficulty(id) }

    fun restoreQuiz(id: UUID) = tenantTransaction.executeWithoutResult { deletion.restoreQuiz(id) }
}
