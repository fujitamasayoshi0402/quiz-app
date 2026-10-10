package com.quizapp.quiz.controller

import com.quizapp.quiz.domain.LimitedResource
import com.quizapp.quiz.domain.TenantLimitReachedException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 利用者が作ったテナントの上限に達した（ADR-0028）。クイズの作成と取り込み、図の作成とアップロードで起きる。
 * 共通の [com.quizapp.controller.ApiExceptionHandler] と同じ形（RFC 9457）で返す
 */
@RestControllerAdvice
class TenantCapacityExceptionHandler {

    /** **何が上限に達したかを `resource` と `limit` で返す。** 画面が、消すか整理するかを案内する */
    @ExceptionHandler(TenantLimitReachedException::class)
    fun handleTenantLimitReached(e: TenantLimitReachedException): ProblemDetail {
        val detail = when (e.resource) {
            LimitedResource.QUIZZES -> "このテナントのクイズは ${e.limit} 問までです（ゴミ箱のものを含む）"
            LimitedResource.FIGURES -> "このテナントの図は ${e.limit} 個までです"
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail).apply {
            title = "テナントの上限に達しました"
            setProperty("resource", e.resource.name.lowercase())
            setProperty("limit", e.limit)
        }
    }
}
