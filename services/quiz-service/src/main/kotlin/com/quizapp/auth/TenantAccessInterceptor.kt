package com.quizapp.auth

import com.quizapp.tenant.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * テナント配下のエンドポイントへのアクセスを検査する。
 *
 * | パス | 必要な条件 |
 * | --- | --- |
 * | `/api/t/{slug}/admin/` 配下 | 管理者として所属していること |
 * | `/api/t/{slug}/` 配下のその他 | 所属していること |
 *
 * **既定が「所属が必要」**になっている。テナント配下にエンドポイントを足したとき、
 * 書き忘れても公開されない。個々のコントローラに注釈を付ける方式だと、
 * 付け忘れがそのまま穴になる。
 *
 * フィルタではなくインターセプタなのは、ここで投げた例外を
 * [com.quizapp.controller.ApiExceptionHandler] が受け取れるようにするため。
 * フィルタで投げると RFC 9457 の応答にならない。
 */
@Component
class TenantAccessInterceptor(private val memberships: TenantMemberships) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val tenantId = TenantContext.get()
            // slug が実在しない。存在しないテナントとして扱う
            ?: throw IllegalStateException("テナントを解決できませんでした: ${request.requestURI}")

        val userId = UserContext.require()
        val role = memberships.findRole(tenantId, userId) ?: throw TenantAccessDeniedException()

        if (isAdminPath(request.requestURI) && role != TenantRole.ADMIN) {
            throw AdminRoleRequiredException()
        }

        // 以降のロール判定でもう一度 DB を引かずに済むよう、解決した結果を載せる
        UserContext.set(CurrentUser(userId, role))
        return true
    }

    private fun isAdminPath(uri: String): Boolean =
        uri.trim('/').split('/').let { segments ->
            val index = segments.indexOf("t")
            index >= 0 && index + 2 < segments.size && segments[index + 2] == "admin"
        }
}

@Component
class TenantAccessConfigurer(private val interceptor: TenantAccessInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns(TENANT_SCOPED)
    }

    private companion object {
        const val TENANT_SCOPED = "/api/t/*/**"
    }
}
