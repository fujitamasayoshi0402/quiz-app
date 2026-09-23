package com.quizapp.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 利用者を特定して [UserContext] に載せる。**ここでは拒否しない。**
 *
 * 認証が必要かどうかはパスによって変わるため、判断は [TenantAccessInterceptor] に任せる。
 * フィルタで拒否すると、RFC 9457 の応答を組み立てる仕組み（`@RestControllerAdvice`）を通らない。
 */
@Component
@Order(AuthenticationFilter.ORDER)
class AuthenticationFilter(private val authenticator: Authenticator) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            authenticator.authenticate(request)?.let { UserContext.set(CurrentUser(it)) }
            filterChain.doFilter(request, response)
        } finally {
            // スレッドはプールで使い回されるため、必ず消す。
            // 残すと次のリクエストが前の利用者を引き継ぐ
            UserContext.clear()
        }
    }

    companion object {
        /** テナントの解決より後に動く。所属判定にテナントが要るため */
        const val ORDER = 20
    }
}
