package com.quizapp.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * `/api` 配下はすべて認証を要求する。
 *
 * テナント配下は [TenantAccessInterceptor] が所属まで確かめる。こちらはテナントの外（`/api/me`）も含めた既定で、
 * **テナントの外にエンドポイントを足したとき、認証の書き忘れで公開されない**ようにする。
 *
 * テナントの判定より先に動かす。後にすると、認証していない相手にも
 * 「その slug のテナントがあるか」が 401 と 404 の違いで分かってしまう。
 */
@Component
class AuthenticationRequiredInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        UserContext.require()
        return true
    }
}

/** インターセプタは登録した順に動く。認証 → テナントの所属とロール。 */
@Component
class ApiAccessConfigurer(
    private val authenticationRequired: AuthenticationRequiredInterceptor,
    private val tenantAccess: TenantAccessInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authenticationRequired).addPathPatterns(API)
        registry.addInterceptor(tenantAccess).addPathPatterns(TENANT_SCOPED)
    }

    private companion object {
        const val API = "/api/**"
        const val TENANT_SCOPED = "/api/t/*/**"
    }
}
