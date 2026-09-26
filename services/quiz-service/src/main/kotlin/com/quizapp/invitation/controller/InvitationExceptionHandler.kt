package com.quizapp.invitation.controller

import com.quizapp.invitation.usecase.AlreadyMemberException
import com.quizapp.invitation.usecase.InvitationClosedException
import com.quizapp.invitation.usecase.InvitationNotAddressedToUserException
import com.quizapp.invitation.usecase.InvitationNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 招待のエラーを RFC 9457 の Problem Details で返す。
 * 共通のもの（入力の誤り、認証、テナント）は [com.quizapp.controller.ApiExceptionHandler] が返す。
 */
@RestControllerAdvice
class InvitationExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvitationNotFoundException::class)
    fun handleInvitationNotFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "指定された招待は存在しません").apply {
            title = "リソースが見つかりません"
        }

    /**
     * 招待のリンクを、別のメールアドレスでログインして開いた。
     *
     * **招待先のテナントも、招待したアドレスも返さない。** リンクが漏れた相手に、どこへ誰が招待されたかを教えない。
     * 本人がアカウントを間違えただけなら、ログインし直せば済む。
     */
    @ExceptionHandler(InvitationNotAddressedToUserException::class)
    fun handleInvitationNotAddressed(): ProblemDetail {
        log.warn("招待されたのとは別のメールアドレスで、招待を開こうとしました")
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "この招待は、ログインしているメールアドレス宛てではありません。招待されたメールアドレスでログインし直してください",
        ).apply { title = "この招待は受け入れられません" }
    }

    /**
     * 使い終わった、期限が切れた、または取り消された招待。
     *
     * **状態を `invitationStatus` で返す**（`status` は HTTP のステータスが使う）。
     * 期限切れと取り消しなら招待し直してもらう、と画面が案内を変える。
     */
    @ExceptionHandler(InvitationClosedException::class)
    fun handleInvitationClosed(e: InvitationClosedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "この招待は使えません").apply {
            title = "この招待は受け入れられません"
            setProperty("invitationStatus", e.status.name.lowercase())
        }

    @ExceptionHandler(AlreadyMemberException::class)
    fun handleAlreadyMember(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "このメールアドレスの利用者は、すでにテナントに所属しています",
    ).apply { title = "招待できません" }
}
