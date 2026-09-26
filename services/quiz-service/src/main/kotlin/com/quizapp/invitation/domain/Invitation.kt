package com.quizapp.invitation.domain

import com.quizapp.auth.TenantRole
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

enum class InvitationStatus {
    /** 受け入れを待っている */
    PENDING,

    /** 期限が切れた。受け入れられない */
    EXPIRED,

    /** 受け入れられた */
    ACCEPTED,

    /** 管理者が取り消した。同じアドレスへ招待し直したときも、古いものはこれになる */
    REVOKED,
}

/**
 * テナントへの招待（ADR-0016）。管理者が作り、招待のリンクを相手に渡す。
 *
 * **招待したメールアドレスを持つ。** 受け入れるときに、ログインした人の確認済みのメールアドレスと比べる。
 * リンクが漏れても、別の人は所属できない。
 */
data class Invitation(
    val id: UUID,
    val tenantId: UUID,
    val email: String,
    val role: TenantRole,
    val expiresAt: Instant,
    val createdAt: Instant,
    val acceptedAt: Instant? = null,
    val acceptedBy: UUID? = null,
    val revokedAt: Instant? = null,
) {
    /** 受け入れと取り消しは、期限より優先する。期限が過ぎても「使った」「取り消した」の事実は変わらない */
    fun statusAt(now: Instant): InvitationStatus = when {
        acceptedAt != null -> InvitationStatus.ACCEPTED
        revokedAt != null -> InvitationStatus.REVOKED
        !now.isBefore(expiresAt) -> InvitationStatus.EXPIRED
        else -> InvitationStatus.PENDING
    }

    /** 大文字と小文字は区別しない。認証基盤もアドレスを区別しない。確認済みのアドレスが無ければ一致しない */
    fun isAddressedTo(email: String?): Boolean = email != null && this.email.equals(email, ignoreCase = true)

    companion object {
        /** 相手がすぐにサインアップできなくても間に合う長さ。漏れたリンクは、メールアドレスの照合で止まる */
        val VALIDITY: Duration = Duration.ofDays(7)
    }
}

/** 受け取った側から見た招待。招待先のテナントを添える */
data class ReceivedInvitation(val invitation: Invitation, val tenantSlug: String, val tenantName: String)

/**
 * 招待のリンクに入れるトークン。
 *
 * **DB にはハッシュだけを持つ。** トークンそのものは、作った直後の応答で 1 回だけ返す。
 * DB やバックアップが漏れても、そこからリンクは作れない。なくしたら、同じアドレスへ招待し直す。
 *
 * 256 ビットの乱数なので、ハッシュに塩やストレッチは要らない。
 * パスワードと違い、候補を絞って総当たりできる値ではない。
 */
object InvitationTokens {
    private const val BYTES = 32
    private val random = SecureRandom()

    /** URL にそのまま入る形（base64url、パディングなし） */
    fun generate(): String = ByteArray(BYTES)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
