package com.quizapp.tenant.usecase

import com.quizapp.auth.TenantMemberships
import com.quizapp.auth.UserContext
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import com.quizapp.tenant.domain.MemberAnswerHistory
import com.quizapp.tenant.domain.TenantLeaveNotAllowedException
import com.quizapp.tenant.domain.TenantLeavings
import org.springframework.stereotype.Service

/**
 * テナントから退会する（ADR-0030）。所属していれば、一般ユーザーも管理者も呼べる（パスの規約。`/api/t/{slug}/play/...`）。
 *
 * **退会すると、そのテナントでの回答の履歴を消す。** 所属を外すのと同じトランザクションで消し、片方だけが残らないようにする
 */
@Service
class TenantLeavingUseCase(
    private val leavings: TenantLeavings,
    private val memberships: TenantMemberships,
    private val answerHistory: MemberAnswerHistory,
    private val tenantTransaction: TenantTransaction,
) {
    fun leave() = tenantTransaction.executeWithoutResult {
        val tenantId = TenantContext.require()
        val userId = UserContext.require()
        leavings.blockFor(tenantId, userId)?.let { throw TenantLeaveNotAllowedException(it) }
        answerHistory.eraseOf(userId)
        leavings.leave(tenantId, userId)
        // メモリに持った所属を消す（ADR-0038）。消さないと、退会したあとも前のロールで読める
        memberships.forget(tenantId, userId)
    }
}
