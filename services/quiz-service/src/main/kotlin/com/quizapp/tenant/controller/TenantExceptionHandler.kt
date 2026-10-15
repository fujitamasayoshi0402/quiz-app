package com.quizapp.tenant.controller

import com.quizapp.tenant.domain.TenantCannotBePublicException
import com.quizapp.tenant.domain.TenantCreationBlock
import com.quizapp.tenant.domain.TenantCreationNotAllowedException
import com.quizapp.tenant.domain.TenantLeaveBlock
import com.quizapp.tenant.domain.TenantLeaveNotAllowedException
import com.quizapp.tenant.domain.TenantSlugTakenException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * テナントの作成と設定、退会のエラーを RFC 9457 の Problem Details で返す（ADR-0028、ADR-0030）。
 * 共通のもの（入力の誤り、認証、テナント）は [com.quizapp.controller.ApiExceptionHandler] が返す
 */
@RestControllerAdvice
class TenantExceptionHandler {

    /** **理由を `reason` で返す。** 画面が、共有のアカウントにはサインアップを、作った人には自分のテナントへの道を案内する */
    @ExceptionHandler(TenantCreationNotAllowedException::class)
    fun handleCreationNotAllowed(e: TenantCreationNotAllowedException): ProblemDetail {
        val detail = when (e.reason) {
            TenantCreationBlock.SHARED_ACCOUNT -> "共有のアカウントでは、テナントを作れません。自分のアカウントでログインしてください"
            TenantCreationBlock.ALREADY_CREATED -> "テナントは 1 人 1 つまで作れます。すでに作ったテナントがあります"
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail).apply {
            title = "テナントを作れません"
            setProperty("reason", e.reason.value)
        }
    }

    /** slug は URL に出るもので、重なることを返しても中身は見えない（ADR-0028） */
    @ExceptionHandler(TenantSlugTakenException::class)
    fun handleSlugTaken(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "この URL に使う名前は、ほかのテナントが使っています").apply {
            title = "URL に使う名前が重なっています"
        }

    /** **理由を `reason` で返す。** 画面が、最後の管理者と共有のアカウントとで案内を変える（ADR-0030） */
    @ExceptionHandler(TenantLeaveNotAllowedException::class)
    fun handleLeaveNotAllowed(e: TenantLeaveNotAllowedException): ProblemDetail {
        val detail = when (e.reason) {
            TenantLeaveBlock.LAST_ADMIN -> "最後の管理者は退会できません。ほかの人を管理者として招待してから退会してください"
            TenantLeaveBlock.SHARED_ACCOUNT -> "共有のアカウントは退会できません"
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail).apply {
            title = "退会できません"
            setProperty("reason", e.reason.value)
        }
    }

    @ExceptionHandler(TenantCannotBePublicException::class)
    fun handleCannotBePublic(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "利用者が作ったテナントは、公開できません",
    ).apply { title = "公開できないテナントです" }
}
