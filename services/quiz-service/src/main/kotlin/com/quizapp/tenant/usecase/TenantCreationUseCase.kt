package com.quizapp.tenant.usecase

import com.quizapp.auth.Membership
import com.quizapp.auth.TenantMemberships
import com.quizapp.auth.TenantRole
import com.quizapp.auth.UserContext
import com.quizapp.tenant.domain.TenantCreationBlock
import com.quizapp.tenant.domain.TenantCreationNotAllowedException
import com.quizapp.tenant.domain.TenantCreations
import com.quizapp.tenant.domain.TenantLimits
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * ログインした人が、自分のテナントを作る（ADR-0028）。作った人は、そのテナントの管理者として所属する。
 *
 * 作ったテナントは空で、非公開のまま。クイズと図の数に上限（[TenantLimits.SELF_SERVICE]）が付く
 */
@Service
class TenantCreationUseCase(
    private val creations: TenantCreations,
    private val memberships: TenantMemberships,
    private val transactionTemplate: TransactionTemplate,
) {
    /** 作れない理由。作れるなら null。画面が、入力させる前に示す */
    fun block(): TenantCreationBlock? = creations.blockFor(UserContext.require())

    /** テナントと、作った人の所属を、1 つのトランザクションで作る。所属だけが残ったり、管理者のいないテナントが残ったりしない */
    fun create(slug: String, name: String): Membership = requireNotNull(
        transactionTemplate.execute {
            val userId = UserContext.require()
            creations.blockFor(userId)?.let { throw TenantCreationNotAllowedException(it) }
            val tenantId = creations.create(slug, name, userId, TenantLimits.SELF_SERVICE)
            memberships.join(tenantId, userId, TenantRole.ADMIN)
            Membership(slug, name, TenantRole.ADMIN)
        },
    )
}
