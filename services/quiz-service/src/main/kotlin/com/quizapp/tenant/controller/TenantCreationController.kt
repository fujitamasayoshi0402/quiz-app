package com.quizapp.tenant.controller

import com.quizapp.auth.MyTenantResponse
import com.quizapp.ratelimit.HeavyOperation
import com.quizapp.tenant.usecase.TenantCreationUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * ログインした人が、自分のテナントを作る（ADR-0028）。**テナントの外に置く。** 作る人は、まだそのテナントに所属していないため。
 *
 * 読み書きするのは行レベルセキュリティの対象外の `core` だけ。作ったテナントの中身は、所属してからテナント配下の API で触る。
 * 認証は [com.quizapp.auth.AuthenticationRequiredInterceptor] がパスで要求する
 */
@RestController
@RequestMapping("/api/me", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "テナントの作成", description = "自分のテナントを 1 つ作り、その管理者になる")
class TenantCreationController(private val useCase: TenantCreationUseCase) {

    @GetMapping("/tenant-creation")
    @Operation(operationId = "getTenantCreation", summary = "テナントを作れるかどうかと、作れない理由")
    fun status(): TenantCreationResponse = useCase.block().let { TenantCreationResponse(it == null, it?.value) }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createTenant", summary = "テナントを作り、作った人を管理者として所属させる")
    @HeavyOperation
    fun create(@Valid @RequestBody request: CreateTenantRequest): MyTenantResponse =
        MyTenantResponse.from(useCase.create(request.slug, request.name.trim()))
}

data class TenantCreationResponse(
    val allowed: Boolean,
    /** 作れない理由。`shared_account`（共有のアカウント）か `already_created`（すでに 1 つ作った）。作れるなら null */
    val reason: String?,
)

data class CreateTenantRequest(
    /** URL に入る識別子。core.tenants の制約と同じ形 */
    @field:Pattern(
        regexp = "^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$",
        message = "URL に使う名前は、英小文字・数字・ハイフンで 3〜32 文字にしてください（先頭と末尾はハイフン以外）",
    )
    val slug: String,
    @field:NotBlank(message = "テナントの名前を入力してください")
    @field:Size(max = 100, message = "テナントの名前は {max} 文字以内で入力してください")
    val name: String,
)
