package com.quizapp.tenant.controller

import com.quizapp.auth.MyTenantResponse
import com.quizapp.tenant.domain.PublicTenant
import com.quizapp.tenant.usecase.PublicTenantsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 公開テナントの一覧と参加（ADR-0025）。**テナントの外に置く。** まだ所属していない人が呼ぶため、`/api/t/{slug}/` の下には置けない。
 *
 * 読み書きするのは行レベルセキュリティの対象外の `core` だけで、カテゴリやクイズは返さない。
 * 公開テナントのクイズも、参加してから出題の API（`/api/t/{slug}/play/...`）で読む。
 * 認証は [com.quizapp.auth.AuthenticationRequiredInterceptor] がパスで要求する
 */
@RestController
@RequestMapping("/api/me/public-tenants", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "公開テナント", description = "公開テナントの一覧と、招待なしでの参加")
class PublicTenantsController(private val useCase: PublicTenantsUseCase) {

    @GetMapping
    @Operation(operationId = "listPublicTenants", summary = "公開テナントの一覧。参加済みかどうかも返す")
    fun list(): List<PublicTenantResponse> = useCase.list().map(PublicTenantResponse::from)

    @PostMapping("/{slug}/join")
    @Operation(
        operationId = "joinPublicTenant",
        summary = "公開テナントに一般ユーザーとして参加する。すでに所属していれば何も変えない",
    )
    fun join(@PathVariable slug: String): MyTenantResponse = MyTenantResponse.from(useCase.join(slug))
}

/** テナントの ID は返さない。画面が使うのは URL に入る slug だけ */
data class PublicTenantResponse(val slug: String, val name: String, val joined: Boolean) {
    companion object {
        fun from(tenant: PublicTenant) = PublicTenantResponse(tenant.slug, tenant.name, tenant.joined)
    }
}
