package com.quizapp.quiz.usecase

import com.quizapp.quiz.domain.PlayableCategory
import com.quizapp.quiz.domain.PlayableCategoryQuery
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service

/** 出題条件の選択肢を返す。 */
@Service
class PlayableCategoryUseCase(
    private val query: PlayableCategoryQuery,
    private val tenantTransaction: TenantTransaction,
) {
    fun list(): List<PlayableCategory> = tenantTransaction.execute { query.findAll() }
}
