package com.quizapp.tenant.controller

import com.quizapp.tenant.usecase.TenantLeavingUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 自分の所属（ADR-0030）。**テナント配下の play に置く。** 退会できるのは所属している人だけで、
 * 一般ユーザーも管理者も play を呼べる。テナントの外（`/api/me`）に置くと、境界の外が広がる
 */
@RestController
@RequestMapping("/api/t/{slug}/play/membership")
@Tag(name = "所属", description = "テナントからの退会")
class TenantMembershipController(private val useCase: TenantLeavingUseCase) {

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "leaveTenant",
        summary = "テナントから退会する。そのテナントでの回答の履歴とランキングへの参加も消す。最後の管理者と共有のアカウントは退会できない",
    )
    fun leave() = useCase.leave()
}
