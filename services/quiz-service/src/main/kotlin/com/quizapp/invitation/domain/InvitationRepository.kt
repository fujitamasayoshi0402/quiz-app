package com.quizapp.invitation.domain

import com.quizapp.auth.TenantRole
import java.time.Instant
import java.util.UUID

/**
 * 招待の永続化。
 *
 * `core.invitations` は行レベルセキュリティの対象外のため、**管理者の操作はテナントを引数で受けて絞る。**
 * 受け入れはトークンで引く。どのテナントの招待かは、引いてみるまで分からない。
 */
interface InvitationRepository {

    /** 未使用の招待（期限切れを含む）。新しい順 */
    fun findOpen(tenantId: UUID): List<Invitation>

    fun create(
        tenantId: UUID,
        email: String,
        role: TenantRole,
        tokenHash: String,
        invitedBy: UUID,
        expiresAt: Instant,
    ): Invitation

    /** 同じアドレスへの未使用の招待を取り消す。招待し直したとき、古いリンクを使えなくする */
    fun revokeOpenTo(tenantId: UUID, email: String)

    /** 未使用なら取り消す。取り消せたか。受け入れ済みのものは取り消せない */
    fun revoke(tenantId: UUID, id: UUID): Boolean

    /** トークンのハッシュで引く。削除されたテナントへの招待は返さない */
    fun findByTokenHash(tokenHash: String): ReceivedInvitation?

    /**
     * [findByTokenHash] と同じものを引き、トランザクションが終わるまで行をロックする。
     * 同じ招待を同時に受け入れても、受け入れの記録と所属の追加が 1 回ずつで済む
     */
    fun lockByTokenHash(tokenHash: String): ReceivedInvitation?

    fun markAccepted(id: UUID, userId: UUID)
}
