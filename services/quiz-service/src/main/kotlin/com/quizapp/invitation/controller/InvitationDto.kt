package com.quizapp.invitation.controller

import com.quizapp.invitation.domain.Invitation
import com.quizapp.invitation.domain.ReceivedInvitation
import com.quizapp.invitation.usecase.CreatedInvitation
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateInvitationRequest(
    @field:NotBlank(message = "メールアドレスを入力してください")
    @field:Email(message = "メールアドレスの形式が正しくありません")
    @field:Size(max = 254, message = "メールアドレスは {max} 文字以内で入力してください")
    val email: String,
    @field:Pattern(regexp = "admin|member", message = "ロールは admin か member を指定してください")
    val role: String = "member",
)

/** 管理者から見た招待。**トークンは含めない。** DB にはハッシュしかなく、作った直後にしか返せない */
data class InvitationResponse(
    val id: UUID,
    val email: String,
    val role: String,
    /** `pending`（受け入れ待ち）か `expired`（期限切れ）。使い終わったものと取り消したものは一覧に出ない */
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun from(invitation: Invitation) = InvitationResponse(
            id = invitation.id,
            email = invitation.email,
            role = invitation.role.name.lowercase(),
            status = invitation.statusAt(Instant.now()).name.lowercase(),
            expiresAt = invitation.expiresAt,
            createdAt = invitation.createdAt,
        )
    }
}

/**
 * 作った直後の招待。画面はトークンから招待のリンク（`/invitations/{token}`）を作って出す。
 * **リンクの URL はバックエンドでは組み立てない。** web のオリジンを知らないため。
 */
data class CreatedInvitationResponse(val invitation: InvitationResponse, val token: String) {
    companion object {
        fun from(created: CreatedInvitation) =
            CreatedInvitationResponse(InvitationResponse.from(created.invitation), created.token)
    }
}

/**
 * 招待された人から見た招待。**テナントの slug は返さない。** 入れるのは受け入れたあとで、そのときに返す。
 * 招待したアドレスも返さない。一致しなければここまで届かず、一致すれば本人が知っている
 */
data class ReceivedInvitationResponse(
    val tenantName: String,
    val role: String,
    /** `pending` / `expired` / `accepted` / `revoked` */
    val status: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(received: ReceivedInvitation) = ReceivedInvitationResponse(
            tenantName = received.tenantName,
            role = received.invitation.role.name.lowercase(),
            status = received.invitation.statusAt(Instant.now()).name.lowercase(),
            expiresAt = received.invitation.expiresAt,
        )
    }
}
