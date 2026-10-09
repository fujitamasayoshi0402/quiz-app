package com.quizapp.tenant.usecase

import com.quizapp.auth.Membership
import com.quizapp.auth.TenantAccessDeniedException
import com.quizapp.auth.TenantMemberships
import com.quizapp.auth.TenantRole
import com.quizapp.auth.UserContext
import com.quizapp.tenant.domain.PublicTenant
import com.quizapp.tenant.domain.PublicTenants
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 公開テナントを見つけて、招待なしで参加する（ADR-0025）。ログインした人なら誰でも呼べる。
 *
 * 参加すると、一般ユーザーとして所属する。招待で入った所属と区別しない
 */
@Service
class PublicTenantsUseCase(
    private val publicTenants: PublicTenants,
    private val memberships: TenantMemberships,
    private val transactionTemplate: TransactionTemplate,
) {
    fun list(): List<PublicTenant> = publicTenants.listFor(UserContext.require())

    /**
     * 参加する。**すでに所属していれば何も変えない。** 管理者が押しても、一般ユーザーには下がらない。
     *
     * 非公開・削除済み・存在しないテナントは、どれも「存在しない」（404）として扱う。非公開のテナントがあることを明かさない
     */
    fun join(slug: String): Membership = requireNotNull(
        transactionTemplate.execute {
            val userId = UserContext.require()
            val tenant = publicTenants.findJoinable(slug) ?: throw TenantAccessDeniedException()
            memberships.join(tenant.id, userId, TenantRole.MEMBER)
            val role = memberships.findRole(tenant.id, userId) ?: throw TenantAccessDeniedException()
            Membership(tenant.slug, tenant.name, role)
        },
    )
}
