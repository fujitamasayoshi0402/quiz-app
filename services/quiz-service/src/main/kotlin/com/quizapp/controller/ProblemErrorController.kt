package com.quizapp.controller

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * [ApiExceptionHandler] の外で起きたエラーも、RFC 9457 の Problem Details で返す（DEV-148）。
 *
 * 想定していない例外、フィルタの中の例外（テナントの解決で DB につながらないなど）、
 * 無いパス（404）や受け付けないメソッド（405）は、サーブレットのコンテナが `/error` へ回す。
 * Spring Boot の既定（`BasicErrorController`）は `application/json` の別の形で返し、
 * 画面は手前の API Gateway の応答（夜間の停止中の 500 / 503）と見分けられない。
 *
 * **パスは返さない。** 招待を受け入れる API のパスにはトークンが入る。
 */
@Hidden
@RestController
class ProblemErrorController : ErrorController {

    @RequestMapping("\${server.error.path:\${error.path:/error}}")
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
            ?.let { HttpStatus.resolve(it) }
            ?: HttpStatus.INTERNAL_SERVER_ERROR
        val problem = ProblemDetail.forStatus(status).apply { title = titleOf(status) }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    /** [ApiExceptionHandler] と同じ言い方にそろえる */
    private fun titleOf(status: HttpStatus): String = when {
        status.is5xxServerError -> "サーバー内部エラー"
        status == HttpStatus.NOT_FOUND -> "リソースが見つかりません"
        status == HttpStatus.METHOD_NOT_ALLOWED -> "この操作は受け付けていません"
        status == HttpStatus.UNAUTHORIZED -> "認証が必要です"
        status == HttpStatus.FORBIDDEN -> "権限がありません"
        else -> "リクエストを処理できません"
    }
}
