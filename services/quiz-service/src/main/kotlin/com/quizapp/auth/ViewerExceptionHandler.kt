package com.quizapp.auth

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 見学者（ADR-0035）が、変えようとしたときと、見学者に見せない画面を読もうとしたときのエラー。
 * ほかの認可のエラー（所属していない、管理者でない）は [com.quizapp.controller.ApiExceptionHandler] が返す。
 *
 * 画面は、この文をそのまま出す（`ApiErrorAlert`）。管理を試すには自分のアカウントを作ればよいことを伝える
 */
@RestControllerAdvice
class ViewerExceptionHandler {

    @ExceptionHandler(ViewerNotAllowedException::class)
    fun handleViewerNotAllowed(e: ViewerNotAllowedException): ProblemDetail = if (e.reading) {
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "この画面は、見学では見られません。自分のアカウントを作ると、自分のテナントで使えます",
        ).apply { title = "見学では見られません" }
    } else {
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "見学では、見るだけで変更はできません。自分のアカウントを作ると、自分のテナントで試せます",
        ).apply { title = "見学では変更できません" }
    }
}
