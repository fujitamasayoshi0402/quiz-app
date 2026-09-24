package com.quizapp.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ログインしている利用者自身の情報。**テナントの外に置く唯一の API。**
 *
 * テナントを選ぶ前に呼ぶため、`/api/t/{slug}/` の下には置けない。
 * 参照するのは行レベルセキュリティの対象外の `core` だけで、範囲は利用者自身に絞る。
 * 認証は [AuthenticationRequiredInterceptor] がパスで要求する。
 */
@RestController
@RequestMapping("/api/me", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "利用者", description = "ログインしている利用者自身の情報")
class MeController(private val memberships: TenantMemberships) {

    @GetMapping("/tenants")
    @Operation(operationId = "listMyTenants", summary = "所属しているテナントの一覧")
    fun tenants(): List<MyTenantResponse> = memberships.findTenantsOf(UserContext.require()).map(MyTenantResponse::from)
}

/** テナントの ID は返さない。画面が使うのは URL に入る slug だけ。 */
data class MyTenantResponse(val slug: String, val name: String, val role: String) {
    companion object {
        fun from(membership: Membership) = MyTenantResponse(
            slug = membership.slug,
            name = membership.name,
            role = membership.role.name.lowercase(),
        )
    }
}
