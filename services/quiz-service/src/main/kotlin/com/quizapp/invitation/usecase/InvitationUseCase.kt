package com.quizapp.invitation.usecase

import com.quizapp.auth.Membership
import com.quizapp.auth.TenantMemberships
import com.quizapp.auth.TenantRole
import com.quizapp.auth.UserAccounts
import com.quizapp.auth.UserContext
import com.quizapp.invitation.domain.Invitation
import com.quizapp.invitation.domain.InvitationRepository
import com.quizapp.invitation.domain.InvitationStatus
import com.quizapp.invitation.domain.InvitationTokens
import com.quizapp.invitation.domain.ReceivedInvitation
import com.quizapp.tenant.TenantContext
import com.quizapp.tenant.TenantTransaction
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** 作った招待と、リンクに入れるトークン。トークンはこのときにしか分からない */
data class CreatedInvitation(val invitation: Invitation, val token: String)

/**
 * 招待を作る（管理者）と、受け入れる（招待された人）。
 *
 * 受け入れる人はまだ所属していないため、受け入れはテナントの外の操作になる。
 * どのテナントの招待かはトークンから決まり、[TenantTransaction] は使えない。
 * 触れるのは行レベルセキュリティの対象外の `core` だけで、範囲はトークンとメールアドレスで絞る。
 */
@Service
class InvitationUseCase(
    private val invitations: InvitationRepository,
    private val memberships: TenantMemberships,
    private val accounts: UserAccounts,
    private val tenantTransaction: TenantTransaction,
    private val transactionTemplate: TransactionTemplate,
) {
    fun list(): List<Invitation> = tenantTransaction.execute { invitations.findOpen(TenantContext.require()) }

    /**
     * 同じアドレスへの未使用の招待があれば、取り消してから作る。
     * トークンはハッシュしか残らないため、リンクをなくしたときは招待し直してもらう。
     */
    fun create(email: String, role: TenantRole): CreatedInvitation = tenantTransaction.execute {
        val tenantId = TenantContext.require()
        val address = email.trim()
        if (memberships.hasMemberWithEmail(tenantId, address)) throw AlreadyMemberException()

        invitations.revokeOpenTo(tenantId, address)
        val token = InvitationTokens.generate()
        val invitation = invitations.create(
            tenantId = tenantId,
            email = address,
            role = role,
            tokenHash = InvitationTokens.hash(token),
            invitedBy = UserContext.require(),
            expiresAt = Instant.now().plus(Invitation.VALIDITY),
        )
        CreatedInvitation(invitation, token)
    }

    fun revoke(id: UUID) = tenantTransaction.executeWithoutResult {
        if (!invitations.revoke(TenantContext.require(), id)) throw InvitationNotFoundException()
    }

    /** 受け入れる前に、どのテナントにどのロールで招待されたかを見せる */
    fun receive(token: String): ReceivedInvitation = requireNotNull(
        transactionTemplate.execute { addressedToMe(invitations.findByTokenHash(InvitationTokens.hash(token))) },
    )

    /**
     * 受け入れて、所属させる。**すでに所属していれば、ロールは変えない。**
     *
     * 同じ人がもう一度受け入れたとき（ボタンの二度押し、戻る操作）は、失敗にせず所属を返す。
     */
    fun accept(token: String): Membership = requireNotNull(
        transactionTemplate.execute {
            val received = addressedToMe(invitations.lockByTokenHash(InvitationTokens.hash(token)))
            val invitation = received.invitation
            val userId = UserContext.require()

            when (val status = invitation.statusAt(Instant.now())) {
                InvitationStatus.PENDING -> {
                    memberships.join(invitation.tenantId, userId, invitation.role)
                    invitations.markAccepted(invitation.id, userId)
                }

                InvitationStatus.ACCEPTED ->
                    if (invitation.acceptedBy != userId) throw InvitationClosedException(status)

                InvitationStatus.EXPIRED, InvitationStatus.REVOKED -> throw InvitationClosedException(status)
            }

            // 受け入れたあとに所属を外れていれば、もう一度は入れない
            val role = memberships.findRole(invitation.tenantId, userId)
                ?: throw InvitationClosedException(InvitationStatus.ACCEPTED)
            Membership(received.tenantSlug, received.tenantName, role)
        },
    )

    /**
     * 招待したアドレスと、ログインしている人の確認済みのアドレスが一致しなければ拒む。
     * **リンクを持っているだけでは受け入れられない。** 状態（期限切れなど）もこの後にしか教えない
     */
    private fun addressedToMe(received: ReceivedInvitation?): ReceivedInvitation {
        if (received == null) throw InvitationNotFoundException()
        if (!received.invitation.isAddressedTo(accounts.emailOf(UserContext.require()))) {
            throw InvitationNotAddressedToUserException()
        }
        return received
    }
}

class InvitationNotFoundException : RuntimeException("招待が見つかりません")

/** 招待のリンクを、招待されたのとは別のアドレスでログインして開いた */
class InvitationNotAddressedToUserException : RuntimeException("招待されたメールアドレスではありません")

/** 使い終わった、期限が切れた、または取り消された招待を受け入れようとした */
class InvitationClosedException(val status: InvitationStatus) : RuntimeException("この招待は受け入れられません: $status")

/** そのアドレスの利用者は、すでにテナントに所属している */
class AlreadyMemberException : RuntimeException("すでに所属しています")
